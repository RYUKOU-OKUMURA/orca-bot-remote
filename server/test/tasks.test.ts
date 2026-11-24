import assert from 'node:assert/strict';
import { once } from 'node:events';
import { mkdtempSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import type { AddressInfo } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test, type TestContext } from 'node:test';
import { fileURLToPath } from 'node:url';
import { tokenDigest } from '../src/config.ts';
import { createApi } from '../src/http.ts';
import { BadInput } from '../src/attachments.ts';
import { createTasks, maxTasks } from '../src/tasks.ts';

function tempFile(t: TestContext) {
  const dir = mkdtempSync(join(tmpdir(), 'orca-tasks-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  return join(dir, 'memory', 'tasks.json');
}

test('missing file reads as empty and the first write creates private dir and file', async t => {
  const file = tempFile(t);
  const tasks = createTasks(file);
  assert.deepEqual(await tasks.list(), []);
  const task = await tasks.create({ title: '  一行\nタイトル\t ', note: '  ', due: null });
  assert.equal(task.title, '一行 タイトル');
  assert.equal(task.note, null);
  assert.match(task.id, /^[0-9a-f-]{36}$/);
  assert.equal(statSync(join(file, '..')).mode & 0o777, 0o700);
  assert.equal(statSync(file).mode & 0o777, 0o600);
  assert.deepEqual(JSON.parse(readFileSync(file, 'utf8')), { version: 1, tasks: [task] });
  assert.deepEqual(readdirSync(join(file, '..')), ['tasks.json']);
});

test('validation rejects bad titles, notes, dates, flags and unknown fields', async t => {
  const tasks = createTasks(tempFile(t));
  for (const body of [
    {}, { title: '' }, { title: ' \n ' }, { title: 'あ'.repeat(201) }, { title: 1 }, { title: 'x', note: 1 },
    { title: 'x', note: 'n'.repeat(2001) }, { title: 'x', due: '2026-02-30' }, { title: 'x', due: '2026-2-3' },
    { title: 'x', due: '' }, { title: 'x', done: true }, { title: 'x', extra: 1 }
  ]) await assert.rejects(tasks.create(body), BadInput, JSON.stringify(body));
  assert.equal((await tasks.create({ title: '😀'.repeat(200), due: '2028-02-29' })).due, '2028-02-29');
  const { id } = (await tasks.list())[0];
  for (const body of [{}, { done: 'yes' }, { id: 'x' }]) await assert.rejects(tasks.update(id, body), BadInput);
});

test('bot is an optional free-form name: blank means none, control characters become spaces', async t => {
  const file = tempFile(t);
  const tasks = createTasks(file);
  for (const body of [{ title: 'x', bot: 1 }, { title: 'x', bot: 'あ'.repeat(101) }, { title: 'x', bot: ['a'] }]) {
    await assert.rejects(tasks.create(body), BadInput, JSON.stringify(body));
  }
  const plain = await tasks.create({ title: 'x' });
  assert.equal(plain.bot, null);
  assert.equal((await tasks.create({ title: 'x', bot: '  ' })).bot, null);
  assert.equal((await tasks.create({ title: 'x', bot: null })).bot, null);
  assert.equal((await tasks.create({ title: 'x', bot: ' まだ無い\nBot ' })).bot, 'まだ無い Bot');
  assert.equal((await tasks.create({ title: 'x', bot: '😀'.repeat(100) })).bot, '😀'.repeat(100));
  assert.equal((await tasks.update(plain.id, { bot: 'メインBot' }))!.bot, 'メインBot');
  assert.equal((await tasks.update(plain.id, { bot: '' }))!.bot, null);
  await assert.rejects(tasks.update(plain.id, { bot: 2 }), BadInput);
  // Rows written before the field existed, or by the bot with a non-string value, read as none.
  writeFileSync(file, JSON.stringify({ version: 1, tasks: [{ id: 'a', title: '古い行' }, { id: 'b', title: 'b', bot: 3 }] }));
  assert.deepEqual((await tasks.list()).map(task => task.bot), [null, null]);
});

test('done toggles doneAt, updates keep unknown fields, and sorting follows the spec', async t => {
  const file = tempFile(t);
  const tasks = createTasks(file);
  const a = await tasks.create({ title: 'a 期限なし古い' });
  await new Promise(resolve => setTimeout(resolve, 2));
  const b = await tasks.create({ title: 'b 期限なし新しい' });
  const c = await tasks.create({ title: 'c 期限遅い', due: '2026-12-01' });
  const d = await tasks.create({ title: 'd 期限早い', due: '2026-11-30' });
  const doneTask = await tasks.update(a.id, { done: true });
  assert.ok(doneTask!.doneAt! >= doneTask!.createdAt);
  assert.deepEqual((await tasks.list()).map(task => task.id), [d.id, c.id, b.id, a.id]);
  const reopened = await tasks.update(a.id, { done: false, note: '  メモ  ' });
  assert.equal(reopened!.doneAt, null);
  assert.equal(reopened!.note, 'メモ');

  const data = JSON.parse(readFileSync(file, 'utf8'));
  data.tasks[0].source = 'bot';
  data.tasks.push({ note: 'id も title も無い行' });
  writeFileSync(file, JSON.stringify(data));
  await tasks.update(a.id, { title: '更新' });
  const saved = JSON.parse(readFileSync(file, 'utf8'));
  assert.equal(saved.tasks[0].source, 'bot');
  assert.deepEqual(saved.tasks.at(-1), { note: 'id も title も無い行' });
  assert.equal((await tasks.list()).length, 4);
  assert.equal(await tasks.update('missing', { done: true }), null);
  assert.equal(await tasks.remove('missing'), false);
  assert.equal(await tasks.remove(b.id), true);
  assert.equal((await tasks.list()).length, 3);
});

test('concurrent writes are serialized without lost updates', async t => {
  const tasks = createTasks(tempFile(t));
  await Promise.all(Array.from({ length: 20 }, (_, i) => tasks.create({ title: `t${i}` })));
  const list = await tasks.list();
  assert.equal(list.length, 20);
  await Promise.all(list.map(task => tasks.update(task.id, { done: true })));
  assert.ok((await tasks.list()).every(task => task.done));
});

test('a broken file is reported and never overwritten; the task cap holds', async t => {
  const file = tempFile(t);
  const tasks = createTasks(file);
  await tasks.create({ title: 'x' });
  writeFileSync(file, '{broken');
  await assert.rejects(tasks.list(), { name: 'Error', message: 'parse' });
  await assert.rejects(tasks.create({ title: 'y' }), { message: 'parse' });
  assert.equal(readFileSync(file, 'utf8'), '{broken');
  writeFileSync(file, JSON.stringify({ version: 1, tasks: Array.from({ length: maxTasks }, (_, i) => ({ id: `${i}`, title: 't' })) }));
  await assert.rejects(tasks.create({ title: 'over' }), BadInput);
});

test('REST endpoints create, list, patch and delete tasks and board shows open ones', async t => {
  const file = tempFile(t);
  const server = createApi({
    orcaBin: fileURLToPath(new URL('./fixtures/orca', import.meta.url)), digest: tokenDigest('tasks-test'), tasksFile: file
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(async () => {
    const closed = once(server, 'close');
    server.close();
    server.closeAllConnections();
    await closed;
  });
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  const call = async (path: string, method = 'GET', body?: unknown, status = 200) => {
    const response = await fetch(base + path, {
      method, body: body === undefined ? undefined : JSON.stringify(body),
      headers: { Authorization: 'Bearer tasks-test', 'Content-Type': 'application/json' }
    });
    assert.equal(response.status, status, `${method} ${path}`);
    return response.json();
  };
  assert.equal((await fetch(base + '/api/tasks')).status, 401);
  assert.deepEqual(await call('/api/tasks'), { tasks: [] });
  const { task } = await call('/api/tasks', 'POST', { title: '牛乳', due: '2026-11-23', bot: 'メインBot' }, 201);
  assert.equal(task.title, '牛乳');
  assert.equal(task.bot, 'メインBot');
  assert.equal((await call('/api/tasks', 'POST', { title: 'x', bot: 1 }, 400)).error, 'bad_request');
  assert.equal((await call('/api/tasks', 'POST', { title: '' }, 400)).error, 'bad_request');
  assert.equal((await call('/api/tasks', 'POST', [1], 400)).error, 'bad_request');
  assert.equal((await call('/api/tasks/' + task.id, 'PATCH', {}, 400)).error, 'bad_request');
  assert.equal((await call('/api/tasks/nope', 'PATCH', { done: true }, 404)).error, 'not_found');
  assert.equal((await call('/api/tasks/' + task.id, 'GET', undefined, 405)).error, 'bad_request');
  assert.equal((await call('/api/tasks', 'DELETE', undefined, 405)).error, 'bad_request');

  const board = await call('/api/board');
  assert.deepEqual(board.memoTasks, [task]);
  assert.ok(Array.isArray(board.bots) && Array.isArray(board.tasks) && Array.isArray(board.sessions));
  const reassigned = (await call('/api/tasks/' + task.id, 'PATCH', { bot: null })).task;
  assert.equal(reassigned.bot, null);
  assert.deepEqual((await call('/api/board')).memoTasks, [reassigned]);
  const moved = (await call('/api/tasks/' + task.id, 'PATCH', { due: '2026-12-05' })).task;
  assert.equal(moved.due, '2026-12-05');
  assert.equal(moved.title, '牛乳');
  assert.equal((await call('/api/tasks')).tasks[0].due, '2026-12-05');
  for (const due of ['2026-02-30', '2026-2-3', '', 20261205]) {
    assert.equal((await call('/api/tasks/' + task.id, 'PATCH', { due }, 400)).error, 'bad_request');
  }
  assert.equal((await call('/api/tasks')).tasks[0].due, '2026-12-05');
  const cleared = (await call('/api/tasks/' + task.id, 'PATCH', { due: null })).task;
  assert.equal(cleared.due, null);
  assert.deepEqual((await call('/api/board')).memoTasks, [cleared]);
  const patched = (await call('/api/tasks/' + task.id, 'PATCH', { done: true })).task;
  assert.equal(patched.done, true);
  assert.deepEqual((await call('/api/board')).memoTasks, []);
  assert.deepEqual(await call('/api/tasks/' + encodeURIComponent(task.id), 'DELETE'), { ok: true, id: task.id });
  assert.equal((await call('/api/tasks/' + task.id, 'DELETE', undefined, 404)).error, 'not_found');

  writeFileSync(file, '{broken');
  const error = t.mock.method(console, 'error', () => {});
  assert.deepEqual(await call('/api/tasks', 'GET', undefined, 500), {
    error: 'tasks_failed', message: 'タスクリストを読み書きできませんでした。'
  });
  const fallback = await call('/api/board');
  assert.equal(fallback.memoTasks, null);
  assert.ok(fallback.bots.length > 0);
  assert.ok(error.mock.callCount() >= 2);
});
