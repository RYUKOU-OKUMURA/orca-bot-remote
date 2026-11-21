import { spawn } from 'node:child_process';
import { readdir, readFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { join } from 'node:path';

const maxOutputBytes = 4 * 1024 * 1024;
const activeStops = new Set<() => Promise<void>>();
const maxConcurrentCli = 8;
let occupiedSlots = 0;
const waiting = new Set<(granted: boolean) => void>();
let shuttingDown = false;

let readCache = new Map<string, { value: unknown[]; expires: number }>();

export function clearReadCache() {
  readCache = new Map();
}

export function stopAll() {
  // In-flight requests must not start another CLI while the server is closing.
  shuttingDown = true;
  for (const finish of waiting) finish(false);
  return Promise.all(Array.from(activeStops, stop => stop()));
}

async function withCliSlot<T>(signal: AbortSignal, deadline: number, run: () => Promise<T>): Promise<T> {
  function checkRequest() {
    if (shuttingDown || signal.aborted || performance.now() >= deadline) throw new OrcaError();
  }
  checkRequest();
  if (occupiedSlots < maxConcurrentCli) {
    occupiedSlots++;
  } else {
    await new Promise<void>((resolve, reject) => {
      function finish(granted: boolean) {
        waiting.delete(finish);
        clearTimeout(timer);
        signal.removeEventListener('abort', cancel);
        if (granted) resolve();
        else reject(new OrcaError());
      }
      const cancel = () => finish(false);
      const timer = Number.isFinite(deadline)
        ? setTimeout(cancel, Math.ceil(deadline - performance.now())) : undefined;
      waiting.add(finish);
      signal.addEventListener('abort', cancel, { once: true });
    });
  }
  try {
    // Cancellation may race the handoff from the preceding CLI.
    checkRequest();
    return await run();
  } finally {
    const next = waiting.values().next().value;
    if (next) next(true);
    else occupiedSlots--;
  }
}

export class OrcaError extends Error {
  code: string | undefined;

  constructor(code?: string) {
    super('Orca の読み取りに失敗しました。');
    this.code = code;
  }
}

function parseResult(stdout: string): {
  ok?: boolean;
  result?: object;
  error?: { code?: string };
} {
  try {
    return JSON.parse(stdout) ?? {};
  } catch {
    return {};
  }
}

type Bot = { id: string; name: string; specialty?: string; paused: boolean };
type Chat = {
  id: string; title?: string; home: boolean; closed?: boolean; lastActivityAt?: number;
  lastAsked?: string; lastSaid?: string;
};
type Worktree = {
  bot?: unknown; botHome?: unknown; displayName?: unknown;
  agents: {
    state?: unknown; mainAgent?: { state?: unknown }; orcaSessionId?: unknown;
    taskTitle?: unknown; conversationName?: unknown; toolName?: unknown;
  }[];
};
type WorkLabel = { title: string; tool: string | null };
type ActiveWork = { chats: Map<string, WorkLabel>; workers: WorkLabel[] };
type Entry = { kind: 'message'; role: string; text: string } | { kind: 'tool'; name: string; state?: string | null };
type Item = {
  uid: string; number: number; title: string; status: string; owner: { botId: string };
  chat?: { id: string }; lastActivityAt?: number; question?: { eventId: string };
};
type Event = {
  id: string;
  data: { text?: string; ask?: { questions: { options: { label: string }[] }[] } };
};
type Run = {
  trigger: string; status: string; scheduledFor: number; chatSessionId?: string;
  outputSnapshot?: { content?: string };
};

function validChats(chats: Chat[] | null): chats is Chat[] {
  return Array.isArray(chats) && chats.every(chat =>
    chat && typeof chat === 'object' && !Array.isArray(chat) &&
    (chat.closed == null || typeof chat.closed === 'boolean') &&
    (chat.lastActivityAt == null || Number.isSafeInteger(chat.lastActivityAt)) &&
    (chat.lastSaid == null || typeof chat.lastSaid === 'string')
  );
}

const newestFirst = (a: { lastActivityAt?: number | null }, b: { lastActivityAt?: number | null }) =>
  (b.lastActivityAt ?? -Infinity) - (a.lastActivityAt ?? -Infinity);

const boardStatuses = ['needs_you', 'in_progress', 'planned'];

function label(value: unknown, limit = 80) {
  if (typeof value !== 'string') return '';
  const line = value.replace(/[\s\p{Cc}]+/gu, ' ').trimStart();
  return Array.from(line).slice(0, limit).join('').trimEnd();
}

function activeWorkTitle(work?: ActiveWork) {
  return work?.workers[0]?.title ?? (work?.chats.size ? '応答を作成中' : null);
}

async function readMascots(signal: AbortSignal) {
  const root = join(homedir(), '.orca', 'bots');
  const entries = await readdir(root, { withFileTypes: true }).catch(() => []);
  const avatars = new Map<string, string>();
  for (const entry of entries) {
    signal.throwIfAborted();
    if (!entry.isDirectory()) continue;
    try {
      const { id, avatar } = JSON.parse(await readFile(join(root, entry.name, 'bot.json'), { encoding: 'utf8', signal }));
      if (typeof id === 'string' && typeof avatar === 'string' && /^mascot:[A-Z][0-9]$/.test(avatar)) {
        avatars.set(id, avatar);
      }
    } catch {
      signal.throwIfAborted();
      // Missing or unreadable metadata should not hide a bot from the list.
    }
  }
  return avatars;
}

export function createOrca(orcaBin: string, summaryId: string | undefined, signal: AbortSignal, deadline: number) {
  async function read<T>(...args: string[]): Promise<T> {
    // Optional worktree status gets four seconds including the wait for a CLI slot.
    const statusDeadline = args[0] === 'worktree' && args[1] === 'ps' ? performance.now() + 4_000 : Infinity;
    return withCliSlot(signal, Math.min(deadline, statusDeadline), () => new Promise<T>((resolve, reject) => {
      const child = spawn(orcaBin, [...args, '--json'], {
        env: { HOME: homedir(), PATH: '/usr/bin:/bin' },
        // Give the CLI and its descendants a process group of their own.
        detached: true,
        stdio: ['ignore', 'pipe', 'pipe']
      });
      const output: Buffer[] = [];
      let stdoutBytes = 0;
      let stderrBytes = 0;
      let stopped = false;
      let exited = false;
      const closed = Promise.withResolvers<void>();
      function stop() {
        if (stopped) return closed.promise;
        stopped = true;
        if (!exited && child.pid !== undefined) {
          try {
            process.kill(-child.pid, 'SIGKILL');
          } catch (error) {
            if ((error as NodeJS.ErrnoException).code !== 'ESRCH') child.kill('SIGKILL');
          }
        }
        child.stdout.destroy();
        child.stderr.destroy();
        return closed.promise;
      }

      activeStops.add(stop);
      const timer = setTimeout(stop, Math.min(20_000, Math.ceil(statusDeadline - performance.now())));
      signal.addEventListener('abort', stop, { once: true });
      child.once('exit', () => { exited = true; });
      child.on('error', stop);
      child.stdout.on('error', stop);
      child.stderr.on('error', stop);
      child.stdout.on('data', (chunk: Buffer) => {
        stdoutBytes += chunk.length;
        if (stdoutBytes > maxOutputBytes) return stop();
        output.push(chunk);
      });
      child.stderr.on('data', (chunk: Buffer) => {
        stderrBytes += chunk.length;
        if (stderrBytes > maxOutputBytes) stop();
      });
      child.once('close', code => {
        activeStops.delete(stop);
        clearTimeout(timer);
        signal.removeEventListener('abort', stop);
        closed.resolve();
        if (stopped) return reject(new OrcaError());

        const reply = parseResult(Buffer.concat(output).toString('utf8'));
        if (code !== 0 || reply.ok !== true || !reply.result) {
          reject(new OrcaError(reply.error?.code));
        } else {
          resolve(reply.result as T);
        }
      });
    }));
  }

  async function readList<T>(field: string, ttl: number, valid: (value: T[]) => boolean, matches: (value: T[]) => boolean, ...args: string[]): Promise<T[]> {
    if (shuttingDown || signal.aborted || performance.now() >= deadline) throw new OrcaError();
    const cache = readCache;
    const key = JSON.stringify([orcaBin, ...args]);
    const cached = ttl ? cache.get(key) : undefined;
    if (cached && cached.expires > performance.now() && matches(cached.value as T[])) {
      return cached.value as T[];
    }

    const value = (await read<Record<string, T[]>>(...args))[field];
    if (ttl && Array.isArray(value) && valid(value) && cache === readCache) {
      // Bound the key count and prevent reads started before a POST from refilling the cleared cache.
      if (cache.size >= 256 && !cache.has(key)) cache.delete(cache.keys().next().value!);
      cache.set(key, { value, expires: performance.now() + ttl });
    }
    return value;
  }

  function listBots(cached = true) {
    return readList<Bot>('bots', cached ? 10_000 : 0, bots => bots.every(bot => typeof bot?.id === 'string' && typeof bot.name === 'string'),
      () => true, 'bot', 'list');
  }

  function listChats(bot: Bot) {
    return readList<Chat>('chats', 3_000, validChats, () => true, 'bot', 'chats', `--bot=${bot.name}`);
  }

  async function activeWorkByBot(required = false) {
    const byBot = new Map<string, ActiveWork>();
    const working = (state: unknown) => state === 'working' || state === 'running';
    const valid = (trees: Worktree[]) => trees.every(tree => Array.isArray(tree?.agents) &&
      tree.agents.every(agent => agent && typeof agent === 'object' && !Array.isArray(agent)));
    const worktrees = await readList<Worktree>('worktrees', 3_000, valid, () => true, 'worktree', 'ps').catch(() => null);
    signal.throwIfAborted();
    if (!Array.isArray(worktrees) || !valid(worktrees)) {
      if (required) throw new OrcaError();
      return byBot;
    }
    for (const wt of worktrees) {
      if (typeof wt.bot !== 'string' || !wt.bot) continue;
      for (const agent of wt.agents) {
        if (!working(agent.state) && !working(agent.mainAgent?.state)) continue;
        let work = byBot.get(wt.bot);
        if (!work) {
          work = { chats: new Map(), workers: [] };
          byBot.set(wt.bot, work);
        }
        const title = label(agent.taskTitle) || label(agent.conversationName);
        const tool = label(agent.toolName) || null;
        if (wt.botHome === true) {
          if (typeof agent.orcaSessionId === 'string' && agent.orcaSessionId) {
            work.chats.set(agent.orcaSessionId, { title: title || '応答を作成中', tool });
          }
        } else {
          work.workers.push({ title: title || label(wt.displayName) || 'ワーカー作業中', tool });
        }
      }
    }
    return byBot;
  }

  function openChats(chats: Chat[]) {
    return chats.filter(chat => !chat.closed).sort(newestFirst);
  }

  async function latestChat(bot: Bot, required = false) {
    const chats = await listChats(bot).catch(error => {
      signal.throwIfAborted();
      if (required || !(error instanceof OrcaError)) throw error;
      return null;
    });
    if (!validChats(chats)) {
      if (required) throw new OrcaError();
      return null;
    }
    return openChats(chats)[0];
  }

  async function findChat(bot: Bot, chatId: string, cached: boolean) {
    const matches = (chat: Chat) => chat.id === chatId;
    const chats = await readList<Chat>('chats', cached ? 10_000 : 0, validChats, chats => chats.some(matches),
      'bot', 'chats', `--bot=${bot.name}`, '--all');
    return chats.find(matches);
  }

  async function* itemPages(bot: Bot, ...filters: string[]) {
    let cursor: string | undefined;
    do {
      const page: { items: Item[]; nextCursor?: string } = await read(
        'bot', 'item', 'list', `--bot=${bot.name}`, ...filters, '--limit', '100',
        ...(cursor ? ['--cursor', cursor] : [])
      );
      yield page.items;
      cursor = page.nextCursor;
    } while (cursor);
  }

  async function needsYou(bot: Bot) {
    const items: Item[] = [];
    for await (const page of itemPages(bot, '--status', 'needs-you')) items.push(...page);
    return items;
  }

  async function openItems(bot: Bot) {
    const items: Item[] = [];
    for await (const page of itemPages(bot)) items.push(...page.filter(item => boardStatuses.includes(item.status)));
    return items;
  }

  async function boardSessions(bots: Bot[], activity: Map<string, ActiveWork>, avatars: Map<string, string>) {
    const sessions = bots.flatMap(bot => {
      const work = activity.get(bot.name);
      if (!work) return [];
      const owner = { bot: bot.name, avatar: avatars.get(bot.id) ?? null };
      return [
        ...work.workers.map(worker => ({ ...owner, kind: 'worker', ...worker, chatId: null })),
        ...Array.from(work.chats, ([chatId, chat]) => ({ ...owner, kind: 'chat', ...chat, chatId }))
      ];
    }).slice(0, 20);
    await Promise.all(bots.map(async bot => {
      const active = sessions.filter(session => session.bot === bot.name && session.chatId !== null);
      if (!active.length) return;
      const chats = await listChats(bot).catch(() => null);
      if (!validChats(chats)) return;
      for (const session of active) {
        session.title = label(chats.find(chat => chat.id === session.chatId)?.title) || session.title;
      }
    }));
    return sessions;
  }

  async function findItemUid(bot: Bot, number: number) {
    // Orca's item show takes a UID or an unscoped number.
    for await (const page of itemPages(bot, '--all')) {
      const uid = page.find(item => item.number === number)?.uid;
      if (uid) return uid;
    }
    return null;
  }

  async function showItem(bot: Bot, uid: string) {
    const show = (after?: string) => read<{ item: Item; events: Event[]; nextCursor?: string }>(
      'bot', 'item', 'show', `--id=${uid}`, ...(after ? ['--after', after] : [])
    );
    const page = await show();
    return page.item.owner.botId === bot.id ? { page, show } : null;
  }

  return {
    listBots,
    findChat,
    findItemUid,
    showItem,
    async notificationRows() {
      const bots = await listBots();
      // Two serial reads across the bots leave room for foreground requests even while summary uses a third slot.
      async function readEachBot<T>(readBot: (bot: Bot) => Promise<T>) {
        const results: T[] = [];
        for (const bot of bots) results.push(await readBot(bot));
        return results;
      }
      async function detailsByBot() {
        const activity = await activeWorkByBot(true);
        const chats = await readEachBot(bot => latestChat(bot, true));
        return { activity, chats };
      }
      const [lists, details] = await Promise.all([readEachBot(openItems), detailsByBot().catch(() => null)]);
      const waiting = lists.map(items => items.filter(item => item.status === 'needs_you'));
      return {
        board: lists.flatMap((items, i) => items.map(item =>
          [bots[i].name, item.number, item.status, item.lastActivityAt ?? null, item.title, item.chat?.id ?? null])),
        items: waiting.flatMap((items, i) => items.map(item =>
          [bots[i].name, item.number, item.status, item.lastActivityAt ?? null])),
        bots: details && bots.map((bot, i) => {
          const chat = details.chats[i];
          const work = details.activity.get(bot.name);
          return [bot.id, chat?.lastActivityAt ?? null, chat?.lastSaid ?? null, waiting[i].length,
            activeWorkTitle(work) !== null];
        })
      };
    },
    async board() {
      const bots = await listBots();
      const [activity, avatars, lists] = await Promise.all([
        activeWorkByBot(), readMascots(signal), Promise.all(bots.map(openItems))
      ]);
      const sessions = await boardSessions(bots, activity, avatars);
      signal.throwIfAborted();
      const tasks = lists.flatMap((items, i) => items.map(item => ({
        bot: bots[i].name, avatar: avatars.get(bots[i].id) ?? null, number: item.number,
        title: label(item.title, 120), status: item.status, chatId: item.chat?.id ?? null,
        lastActivityAt: item.lastActivityAt ?? null
      })));
      tasks.sort((a, b) => boardStatuses.indexOf(a.status) - boardStatuses.indexOf(b.status) || newestFirst(a, b));
      return {
        updatedAt: Date.now(),
        bots: bots.map((bot, i) => {
          const activeWork = activeWorkTitle(activity.get(bot.name));
          return { id: bot.id, name: bot.name, avatar: avatars.get(bot.id) ?? null, paused: bot.paused,
            working: activeWork !== null, activeWork, needsYou: lists[i].filter(item => item.status === 'needs_you').length };
        }),
        sessions, tasks: tasks.slice(0, 30)
      };
    },
    async bots() {
      const bots = await listBots();
      const [previews, avatars, activity] = await Promise.all([
        Promise.all(bots.map(bot => Promise.all([needsYou(bot), latestChat(bot)]))),
        readMascots(signal), activeWorkByBot()
      ]);
      signal.throwIfAborted();
      return bots.map((bot, index) => {
        const [items, chat] = previews[index];
        const work = activity.get(bot.name);
        const activeWork = activeWorkTitle(work);
        return {
          id: bot.id, name: bot.name, specialty: bot.specialty ?? null, paused: bot.paused,
          working: activeWork !== null, activeWork,
          needsYou: items.length, avatar: avatars.get(bot.id) ?? null,
          lastActivityAt: chat?.lastActivityAt ?? null, lastSaid: chat?.lastSaid ?? null
        };
      });
    },
    async chats(bot: Bot) {
      const [chats, activity] = await Promise.all([listChats(bot), activeWorkByBot()]);
      const activeChats = activity.get(bot.name)?.chats;
      return openChats(chats).map(chat => ({
        id: chat.id, title: chat.title ?? null, home: chat.home, lastActivityAt: chat.lastActivityAt ?? null,
        working: activeChats?.has(chat.id) ?? false,
        lastAsked: chat.lastAsked ?? null, lastSaid: chat.lastSaid ?? null
      }));
    },
    async messages(bot: Bot, chatId: string, limit: number) {
      if (!await findChat(bot, chatId, true)) return null;

      const [{ state, entries }, activity] = await Promise.all([
        read<{ state?: string; entries: Entry[] }>('agent', 'read', `orca_session_id:${chatId}`, '--limit', String(limit)),
        activeWorkByBot()
      ]);
      const workers: WorkLabel[] = [];
      for (const worker of activity.get(bot.name)?.workers ?? []) {
        if (!workers.some(existing => existing.title === worker.title)) workers.push(worker);
        if (workers.length === 10) break;
      }
      return {
        state: state ?? null,
        activeWorkers: workers,
        entries: entries.flatMap((entry): Entry[] => {
          if (entry.kind === 'message') return [{ kind: entry.kind, role: entry.role, text: entry.text }];
          if (entry.kind === 'tool') return [{ kind: entry.kind, name: entry.name, state: entry.state ?? null }];
          return [];
        })
      };
    },
    async items() {
      const lists = await Promise.all((await listBots()).map(async bot =>
        (await needsYou(bot)).map(item => ({
          bot: bot.name, number: item.number, title: item.title, status: item.status,
          chatId: item.chat?.id ?? null, lastActivityAt: item.lastActivityAt ?? null
        }))
      ));
      return lists.flat().sort(newestFirst);
    },
    async item(bot: Bot, number: number) {
      const uid = await findItemUid(bot, number);
      const resolved = uid && await showItem(bot, uid);
      if (!resolved) return null;
      const { show } = resolved;
      let { page } = resolved;
      const { item } = page;

      let question: { text: string; options: string[] } | null = null;
      if (item.question) {
        const eventId = item.question.eventId;
        while (true) {
          const event = page.events.find(event => event.id === eventId);
          if (event) {
            if (typeof event.data.text === 'string') {
              question = {
                text: event.data.text,
                options: event.data.ask?.questions.flatMap(q => q.options.map(option => option.label)) ?? []
              };
            }
            break;
          }
          if (!page.nextCursor) break;
          page = await show(page.nextCursor);
        }
      }
      return {
        bot: bot.name, number: item.number, title: item.title, status: item.status,
        chatId: item.chat?.id ?? null, question
      };
    },
    async summary() {
      if (!summaryId) return null;
      const { runs } = await read<{ runs: Run[] }>('automations', 'runs', '--id', summaryId, '--limit', '20');
      const run = runs.find(run => run.trigger === 'scheduled' && run.status === 'completed');
      if (!run || typeof run.outputSnapshot?.content !== 'string') return null;
      const { automation } = await read<{ automation: { bot?: { botId: string } } }>(
        'automations', 'show', '--id', summaryId
      );
      const botId = automation.bot?.botId;
      if (typeof botId !== 'string' || !botId) throw new OrcaError();
      const bot = (await listBots()).find(bot => bot.id === botId)?.name ?? botId;
      return { bot, runAt: run.scheduledFor, content: run.outputSnapshot.content, chatId: run.chatSessionId ?? null };
    }
  };
}
