import { spawn } from 'node:child_process';
import { readdir, readFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { join } from 'node:path';

const maxOutputBytes = 4 * 1024 * 1024;
const activeStops = new Set<() => Promise<void>>();
let shuttingDown = false;

export function stopAll() {
  // In-flight requests must not start another CLI while the server is closing.
  shuttingDown = true;
  return Promise.all(Array.from(activeStops, stop => stop()));
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

const newestFirst = (a: { lastActivityAt?: number }, b: { lastActivityAt?: number }) =>
  (b.lastActivityAt ?? -Infinity) - (a.lastActivityAt ?? -Infinity);

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
    signal.throwIfAborted();
    if (shuttingDown || performance.now() >= deadline) throw new OrcaError();
    return new Promise((resolve, reject) => {
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
      const timer = setTimeout(stop, 20_000);
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
    });
  }

  async function listBots() {
    return (await read<{ bots: Bot[] }>('bot', 'list')).bots;
  }

  async function listChats(bot: Bot, ...filters: string[]) {
    return (await read<{ chats: Chat[] }>('bot', 'chats', `--bot=${bot.name}`, ...filters)).chats;
  }

  function openChats(chats: Chat[]) {
    return chats.filter(chat => !chat.closed).sort(newestFirst);
  }

  async function latestChat(bot: Bot) {
    const chats = await listChats(bot).catch(error => {
      signal.throwIfAborted();
      if (!(error instanceof OrcaError)) throw error;
      return null;
    });
    if (!Array.isArray(chats) || !chats.every(chat =>
      chat && typeof chat === 'object' && !Array.isArray(chat) &&
      (chat.closed == null || typeof chat.closed === 'boolean') &&
      (chat.lastActivityAt == null || Number.isSafeInteger(chat.lastActivityAt)) &&
      (chat.lastSaid == null || typeof chat.lastSaid === 'string')
    )) return null;
    return openChats(chats)[0];
  }

  async function findChat(bot: Bot, chatId: string) {
    return (await listChats(bot, '--all')).find(chat => chat.id === chatId);
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
    async bots() {
      const bots = await listBots();
      const [previews, avatars] = await Promise.all([
        Promise.all(bots.map(bot => Promise.all([needsYou(bot), latestChat(bot)]))),
        readMascots(signal)
      ]);
      signal.throwIfAborted();
      return bots.map((bot, index) => {
        const [items, chat] = previews[index];
        return {
          id: bot.id, name: bot.name, specialty: bot.specialty ?? null, paused: bot.paused,
          needsYou: items.length, avatar: avatars.get(bot.id) ?? null,
          lastActivityAt: chat?.lastActivityAt ?? null, lastSaid: chat?.lastSaid ?? null
        };
      });
    },
    async chats(bot: Bot) {
      return openChats(await listChats(bot)).map(chat => ({
        id: chat.id, title: chat.title ?? null, home: chat.home, lastActivityAt: chat.lastActivityAt ?? null,
        lastAsked: chat.lastAsked ?? null, lastSaid: chat.lastSaid ?? null
      }));
    },
    async messages(bot: Bot, chatId: string, limit: number) {
      if (!await findChat(bot, chatId)) return null;

      const { state, entries } = await read<{ state?: string; entries: Entry[] }>(
        'agent', 'read', `orca_session_id:${chatId}`, '--limit', String(limit)
      );
      return {
        state: state ?? null,
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
