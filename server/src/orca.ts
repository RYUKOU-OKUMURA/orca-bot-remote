import { execFile } from 'node:child_process';
import { homedir } from 'node:os';

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

export function createOrca(orcaBin: string, summaryId: string | undefined, signal: AbortSignal, deadline: number) {
  async function read<T>(...args: string[]): Promise<T> {
    signal.throwIfAborted();
    if (performance.now() >= deadline) throw new OrcaError();
    return new Promise((resolve, reject) => {
      execFile(orcaBin, [...args, '--json'], {
        env: { HOME: homedir(), PATH: '/usr/bin:/bin' },
        signal,
        timeout: 20_000,
        killSignal: 'SIGKILL',
        maxBuffer: 4 * 1024 * 1024
      }, (error, stdout) => {
        const reply = parseResult(stdout);
        if (error || reply.ok !== true || !reply.result) {
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

  return {
    listBots,
    async bots() {
      return Promise.all((await listBots()).map(async bot => ({
        id: bot.id, name: bot.name, specialty: bot.specialty ?? null, paused: bot.paused,
        needsYou: (await needsYou(bot)).length
      })));
    },
    async chats(bot: Bot) {
      const chats = await listChats(bot);
      return chats.filter(chat => !chat.closed).sort(newestFirst).map(chat => ({
        id: chat.id, title: chat.title ?? null, home: chat.home, lastActivityAt: chat.lastActivityAt ?? null,
        lastAsked: chat.lastAsked ?? null, lastSaid: chat.lastSaid ?? null
      }));
    },
    async messages(bot: Bot, chatId: string, limit: number) {
      const chats = await listChats(bot, '--all');
      if (!chats.some(chat => chat.id === chatId)) return null;

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
      // Orca's item show takes a UID or an unscoped number, so resolve the UID within this Bot first.
      let uid: string | undefined;
      for await (const page of itemPages(bot, '--all')) {
        uid = page.find(item => item.number === number)?.uid;
        if (uid) break;
      }
      if (!uid) return null;

      const show = (after?: string) => read<{ item: Item; events: Event[]; nextCursor?: string }>(
        'bot', 'item', 'show', `--id=${uid}`, ...(after ? ['--after', after] : [])
      );
      let page = await show();
      const { item } = page;
      if (item.owner.botId !== bot.id) return null;

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
