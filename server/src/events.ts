import type { ServerResponse } from 'node:http';
import { createOrca } from './orca.ts';
import type { MemoTask } from './tasks.ts';

type Snapshot = { items?: string; summary?: number | null; bots?: string; board?: string; memoTasks?: string };

// A different CLI listing order alone is not a change.
function digest(rows: unknown[][]) {
  return rows.map(row => JSON.stringify(row)).sort().join('\n');
}

function event(name: string, data: object) {
  return `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`;
}

function writeFrame(client: ServerResponse, frame: string) {
  if (!client.write(frame)) client.destroy(); // Bound queued data for a stalled reader.
}

function memoDigest(tasks: MemoTask[]) {
  return digest(tasks.map(task => [task.id, task.done, task.title, task.note, task.due, task.updatedAt]));
}

export function createEvents(orcaBin: string, summaryId: string | undefined, readTimeoutMs: number,
  listTasks: () => Promise<MemoTask[]>, pollIntervalMs = 10_000, keepaliveIntervalMs = 25_000) {
  const clients = new Set<ServerResponse>();
  let last: Snapshot = {};
  let check: AbortController | undefined;
  let timer: NodeJS.Timeout | undefined;
  let ping: NodeJS.Timeout | undefined;

  function stop() {
    clearTimeout(timer);
    clearInterval(ping);
    check?.abort();
    timer = undefined;
    ping = undefined;
    check = undefined;
    last = {};
  }

  function broadcast(frame: string) {
    for (const client of clients) writeFrame(client, frame);
  }

  function change(changes: Record<string, boolean>) {
    broadcast(event('change', { items: false, summary: false, bots: false, board: false, memoTasks: false, ...changes, at: Date.now() }));
  }

  async function poll() {
    const controller = new AbortController();
    check = controller;
    const orca = createOrca(orcaBin, summaryId, controller.signal, performance.now() + readTimeoutMs);
    const [rows, summary, memoTasks] = await Promise.all([
      orca.notificationRows().catch(() => undefined),
      orca.summary().then(value => value?.runAt ?? null).catch(() => undefined),
      listTasks().then(memoDigest, () => undefined)
    ]);
    if (controller.signal.aborted) return;
    controller.abort(); // Cancel sibling CLI reads left running after a failed page.
    const previous = last;
    const next: Snapshot = {
      memoTasks: memoTasks ?? previous.memoTasks,
      board: rows ? digest(rows.board) : previous.board,
      items: rows ? digest(rows.items) : previous.items,
      bots: rows?.bots ? digest(rows.bots) : previous.bots,
      summary: summary === undefined ? previous.summary : summary
    };
    const changes = {
      board: previous.board !== undefined && previous.board !== next.board,
      items: previous.items !== undefined && previous.items !== next.items,
      summary: previous.summary !== undefined && previous.summary !== next.summary,
      bots: previous.bots !== undefined && previous.bots !== next.bots,
      memoTasks: previous.memoTasks !== undefined && previous.memoTasks !== next.memoTasks
    };
    // The board response carries memoTasks, so clients that only watch board still refresh it.
    if (changes.memoTasks) changes.board = true;
    if (changes.items || changes.summary || changes.bots || changes.board) change(changes);
    last = next;
    timer = setTimeout(poll, pollIntervalMs);
  }

  return {
    subscribe(response: ServerResponse) {
      if (clients.size >= 8) return false;
      response.setTimeout(0);
      response.writeHead(200, {
        'Content-Type': 'text/event-stream; charset=utf-8',
        'Cache-Control': 'no-cache, no-transform',
        'Connection': 'keep-alive',
        'X-Accel-Buffering': 'no'
      });
      writeFrame(response, event('ready', { at: Date.now() }));
      if (response.destroyed) return true;
      clients.add(response);
      response.once('close', () => {
        clients.delete(response);
        if (!clients.size) stop();
      });
      if (clients.size === 1) {
        ping = setInterval(broadcast, keepaliveIntervalMs, ': ping\n\n');
        void poll();
      }
      return true;
    },
    // Called after a task write so connected apps refresh without waiting for the next poll.
    async tasksChanged() {
      if (!clients.size || last.memoTasks === undefined) return;
      const memoTasks = await listTasks().then(memoDigest, () => undefined);
      if (memoTasks === undefined || last.memoTasks === undefined || memoTasks === last.memoTasks) return;
      last = { ...last, memoTasks };
      change({ board: true, memoTasks: true });
    },
    close() {
      stop();
      for (const client of clients) {
        const socket = client.socket;
        client.end();
        socket?.destroySoon();
      }
    }
  };
}
