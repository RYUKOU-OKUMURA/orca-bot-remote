import { randomUUID } from 'node:crypto';
import { mkdir, readFile, rename, rm, writeFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';
import { BadInput } from './attachments.ts';

export const DEFAULT_TASKS_FILE = join(homedir(), '.orca', 'bots', 'bot', 'memory', 'tasks.json');
export const maxTasks = 500;

export type MemoTask = {
  id: string; title: string; note: string | null; due: string | null; done: boolean;
  createdAt: number; updatedAt: number; doneAt: number | null;
};
type Stored = { tasks: Record<string, unknown>[]; [key: string]: unknown };

export class TasksError extends Error {}

const fields = ['title', 'note', 'due', 'done'];

function title(value: unknown) {
  if (typeof value !== 'string') throw new BadInput('title は文字列で指定してください。');
  const line = value.replace(/\p{Cc}+/gu, ' ').trim();
  const length = [...line].length;
  if (length < 1 || length > 200) throw new BadInput('title は前後の空白を除いて 1〜200 文字で指定してください。');
  return line;
}

function note(value: unknown) {
  if (value === null) return null;
  if (typeof value !== 'string') throw new BadInput('note は文字列か null で指定してください。');
  const text = value.trim();
  if ([...text].length > 2000) throw new BadInput('note は 2000 文字以内で指定してください。');
  return text || null;
}

function isDate(value: unknown): value is string {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const date = new Date(value + 'T00:00:00Z');
  return !Number.isNaN(date.getTime()) && date.toISOString().startsWith(value);
}

function due(value: unknown) {
  if (value === null) return null;
  if (!isDate(value)) throw new BadInput('due は実在する YYYY-MM-DD か null で指定してください。');
  return value;
}

function done(value: unknown) {
  if (typeof value !== 'boolean') throw new BadInput('done は true か false で指定してください。');
  return value;
}

function parse(body: Record<string, unknown>, patch: boolean) {
  const keys = Object.keys(body);
  if (keys.some(key => !fields.includes(key) || (!patch && key === 'done')) || (patch && !keys.length) ||
      (!patch && !keys.includes('title'))) {
    throw new BadInput(patch ? 'title、note、due、done のいずれかを指定してください。' : 'title と任意の note、due を指定してください。');
  }
  return {
    ...('title' in body ? { title: title(body.title) } : {}),
    ...('note' in body ? { note: note(body.note) } : {}),
    ...('due' in body ? { due: due(body.due) } : {}),
    ...('done' in body ? { done: done(body.done) } : {})
  };
}

// Entries written by the bot are read leniently; ones without an id and title are kept in the file but not shown.
function view(raw: Record<string, unknown>): MemoTask | null {
  if (!raw || typeof raw !== 'object' || typeof raw.id !== 'string' || !raw.id || typeof raw.title !== 'string') return null;
  const time = (value: unknown) => Number.isSafeInteger(value) ? value as number : null;
  const createdAt = time(raw.createdAt) ?? 0;
  const isDone = raw.done === true;
  return {
    id: raw.id, title: raw.title,
    note: typeof raw.note === 'string' && raw.note.trim() ? raw.note : null,
    due: isDate(raw.due) ? raw.due : null,
    done: isDone, createdAt, updatedAt: time(raw.updatedAt) ?? createdAt,
    doneAt: isDone ? time(raw.doneAt) : null
  };
}

export function taskOrder(a: MemoTask, b: MemoTask) {
  if (a.done !== b.done) return a.done ? 1 : -1;
  if (a.done) return (b.doneAt ?? b.updatedAt) - (a.doneAt ?? a.updatedAt);
  if (a.due !== b.due) {
    if (a.due === null || b.due === null) return a.due === null ? 1 : -1;
    return a.due < b.due ? -1 : 1;
  }
  return b.createdAt - a.createdAt;
}

export function createTasks(file = process.env.ORCA_TASKS_FILE || DEFAULT_TASKS_FILE) {
  // ponytail: in-process queue only; a bot editing the file at the same moment can still lose an update.
  let queue: Promise<unknown> = Promise.resolve();
  function serial<T>(run: () => Promise<T>) {
    const next = queue.then(run, run);
    queue = next.catch(() => {});
    return next;
  }

  async function load(): Promise<Stored> {
    let text;
    try {
      text = await readFile(file, 'utf8');
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === 'ENOENT') return { version: 1, tasks: [] };
      throw new TasksError('read');
    }
    try {
      const data = JSON.parse(text);
      if (data && typeof data === 'object' && !Array.isArray(data) && Array.isArray(data.tasks)) return data;
    } catch { /* A broken file is reported, never overwritten. */ }
    throw new TasksError('parse');
  }

  async function save(data: Stored) {
    const dir = dirname(file);
    const temp = join(dir, `.tasks-${randomUUID()}.tmp`);
    try {
      await mkdir(dir, { recursive: true, mode: 0o700 });
      await writeFile(temp, JSON.stringify(data, null, 2) + '\n', { mode: 0o600, flag: 'wx' });
      await rename(temp, file);
    } catch {
      await rm(temp, { force: true }).catch(() => {});
      throw new TasksError('write');
    }
  }

  function find(data: Stored, id: string) {
    const index = data.tasks.findIndex(raw => view(raw)?.id === id);
    return index === -1 ? null : { index, task: view(data.tasks[index])! };
  }

  return {
    async list() {
      return (await load()).tasks.map(view).filter(task => task !== null).sort(taskOrder);
    },
    async create(body: Record<string, unknown>) {
      const input = parse(body, false);
      return serial(async () => {
        const data = await load();
        if (data.tasks.length >= maxTasks) throw new BadInput(`タスクは ${maxTasks} 件までです。完了したタスクを削除してください。`);
        const now = Date.now();
        const task: MemoTask = {
          id: randomUUID(), title: input.title!, note: input.note ?? null, due: input.due ?? null,
          done: false, createdAt: now, updatedAt: now, doneAt: null
        };
        data.tasks.push(task);
        await save(data);
        return task;
      });
    },
    async update(id: string, body: Record<string, unknown>) {
      const input = parse(body, true);
      return serial(async () => {
        const data = await load();
        const found = find(data, id);
        if (!found) return null;
        const now = Date.now();
        const task: MemoTask = { ...found.task, ...input, updatedAt: now };
        if (input.done !== undefined && input.done !== found.task.done) task.doneAt = input.done ? now : null;
        data.tasks[found.index] = { ...data.tasks[found.index], ...task };
        await save(data);
        return task;
      });
    },
    remove(id: string) {
      return serial(async () => {
        const data = await load();
        const found = find(data, id);
        if (!found) return false;
        data.tasks.splice(found.index, 1);
        await save(data);
        return true;
      });
    }
  };
}

export type Tasks = ReturnType<typeof createTasks>;
