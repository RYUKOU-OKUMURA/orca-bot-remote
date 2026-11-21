import assert from 'node:assert/strict';
import { once } from 'node:events';
import { copyFileSync, existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { get, type IncomingMessage } from 'node:http';
import { createConnection, type AddressInfo } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test, type TestContext } from 'node:test';
import { setTimeout as delay } from 'node:timers/promises';
import { tokenDigest } from '../src/config.ts';
import { createApi } from '../src/http.ts';
import { clearReadCache } from '../src/orca.ts';

const headers = { Authorization: 'Bearer events-test' };
const pollIntervalMs = 60;

async function until(condition: () => boolean) {
  const deadline = performance.now() + 3000;
  while (!condition()) {
    assert.ok(performance.now() < deadline, 'timed out waiting for SSE or fixture CLI');
    await delay(10);
  }
}

async function fixture(t: TestContext, requestTimeoutMs?: number) {
  const dir = mkdtempSync(join(tmpdir(), 'orca-events-'));
  const bin = join(dir, 'orca');
  copyFileSync(new URL('./fixtures/orca', import.meta.url), bin);
  writeFileSync(bin + '.calls', '');
  const server = createApi({ orcaBin: bin, digest: tokenDigest('events-test'), summaryId: 'daily',
    pollIntervalMs, keepaliveIntervalMs: 20 }, requestTimeoutMs);
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(async () => {
    if (server.listening) {
      const closed = once(server, 'close');
      server.close();
      server.closeAllConnections();
      await closed;
    }
    rmSync(dir, { recursive: true, force: true });
  });
  const url = `http://127.0.0.1:${(server.address() as AddressInfo).port}/api/events`;
  async function connect() {
    const [response] = await once(get(url, { headers }), 'response') as [IncomingMessage];
    assert.equal(response.statusCode, 200);
    const frames: string[] = [];
    let pending = '';
    response.setEncoding('utf8');
    response.on('data', chunk => {
      const parts = (pending + chunk).split('\n\n');
      pending = parts.pop()!;
      frames.push(...parts);
    });
    await until(() => frames.length > 0);
    assert.match(frames[0], /^event: ready\ndata: \{"at":\d+\}$/);
    return { response, frames };
  }
  return { bin, server, url, connect, calls: () => readFileSync(bin + '.calls', 'utf8') };
}

test('events authenticates before checking GET and runs no CLI without subscribers', async t => {
  const { url, calls } = await fixture(t);
  for (const options of [{}, { headers: { Authorization: 'Bearer wrong' } }]) {
    const response = await fetch(url, options);
    assert.equal(response.status, 401);
    assert.deepEqual(await response.json(), { error: 'unauthorized' });
  }
  const response = await fetch(url, { headers, method: 'POST' });
  assert.equal(response.status, 405);
  assert.equal(response.headers.get('allow'), 'GET');
  await delay(2 * pollIntervalMs);
  assert.equal(calls(), '');
});

test('ready and ping use SSE framing and outlive the ordinary request deadline', async t => {
  const { connect } = await fixture(t, 1);
  const { response, frames } = await connect();
  assert.equal(response.headers['content-type'], 'text/event-stream; charset=utf-8');
  assert.equal(response.headers['cache-control'], 'no-cache, no-transform');
  assert.equal(response.headers.connection, 'keep-alive');
  assert.equal(response.headers['x-accel-buffering'], 'no');
  await until(() => frames.includes(': ping'));
  assert.ok(frames.slice(1).every(frame => frame === ': ping'));
});

test('a six-bot poll uses at most three CLI slots and a foreground item read can still finish', async t => {
  const { connect, bin, calls, url } = await fixture(t);
  const bots = Array.from({ length: 6 }, (_, i) => ({ id: String(i), name: `bot${i}` }));
  writeFileSync(bin, `#!/bin/sh
printf '+ %s\\n' "$*" >> "$0.calls"
trap 'printf "%s\\n" "- $*" >> "$0.calls"' 0
case "$*" in
  'bot list --json') result='${JSON.stringify({ bots })}' ;;
  'worktree ps --json') result='{"worktrees":[]}' ;;
  *' --all '*) result='{"items":[{"uid":"item","number":7}]}' ;;
  'bot item show --id=item --json') result='{"item":{"number":7,"title":"fixture","owner":{"botId":"0"}}}' ;;
  *)
    while [ ! -f "$0.release" ]; do sleep 0.02; done
    case "$1 $2" in
      'bot item') result='{"items":[]}' ;;
      'bot chats') result='{"chats":[]}' ;;
      'automations runs') result='{"runs":[]}' ;;
      *) exit 1 ;;
    esac ;;
esac
printf '{"ok":true,"result":%s}\\n' "$result"
`);
  await connect();
  await until(() => calls().includes('+ bot item list'));
  const response = await fetch(new URL('/api/items/bot0/7', url), { headers, signal: AbortSignal.timeout(2000) });
  assert.equal(response.status, 200);
  assert.equal((await response.json()).title, 'fixture');
  writeFileSync(bin + '.release', '');
  await until(() => calls().split('+ automations runs').length > 2);

  const log = calls();
  for (const bot of bots) {
    assert.ok(log.includes(`- bot item list --bot=${bot.name} --limit 100 --json`));
    assert.ok(log.includes(`- bot chats --bot=${bot.name} --json`));
  }
  let active = 0;
  for (const line of log.trimEnd().split('\n')) {
    if (line.includes(' --all ') || line.includes('bot item show')) continue; // Foreground item read.
    active += line.startsWith('+') ? 1 : -1;
    assert.ok(active <= 3, `poll occupied ${active} CLI slots at ${line}`);
  }
});

test('one poller broadcasts only changed fields and retains good values across CLI failures', async t => {
  const { connect, bin, calls } = await fixture(t);
  const first = await connect();
  const second = await connect();
  const polls = () => calls().split('automations runs').length - 1;
  await until(() => polls() >= 3);
  const changes = (frames: string[]) => frames.filter(frame => frame !== ': ping').slice(1);
  let count = 0;
  async function expectChange(items: boolean, summary: boolean, bots: boolean, board = items) {
    count++;
    for (const { frames } of [first, second]) {
      await until(() => changes(frames).length >= count);
      const frame = changes(frames)[count - 1];
      assert.match(frame, /^event: change\ndata: /);
      const { at, ...flags } = JSON.parse(frame.split('\ndata: ')[1]);
      assert.ok(Number.isSafeInteger(at));
      assert.deepEqual(flags, { items, summary, bots, board });
    }
  }
  assert.equal(changes(first.frames).length, 0);
  assert.equal(changes(second.frames).length, 0);
  assert.equal(calls().split('worktree ps').length - 1, 1);
  writeFileSync(bin + '.items-result', JSON.stringify({ items: [{ number: 2, status: 'needs_you', lastActivityAt: 201 }] }));
  await expectChange(true, false, false);

  writeFileSync(bin + '.fail-chats', '');
  writeFileSync(bin + '.runs-result', 'invalid JSON');
  clearReadCache();
  const before = polls();
  await until(() => polls() >= before + 3);
  assert.equal(changes(first.frames).length, count);
  rmSync(bin + '.fail-chats');
  writeFileSync(bin + '.runs-result', JSON.stringify({ runs: [
    { trigger: 'scheduled', status: 'completed', scheduledFor: 301, outputSnapshot: { content: '更新' } }
  ] }));
  await expectChange(false, true, false);

  writeFileSync(bin + '.chats-result', JSON.stringify({ chats: [{ lastActivityAt: 300, lastSaid: '新しい返答' }] }));
  clearReadCache();
  await expectChange(false, false, true);
  writeFileSync(bin + '.items-result', '{"items":[]}');
  await expectChange(true, false, true);
  for (const [status, title] of [['planned', '準備'], ['in_progress', '準備'], ['in_progress', '実装']]) {
    writeFileSync(bin + '.items-result', JSON.stringify({ items: [{ number: 3, status, title }] }));
    await expectChange(false, false, false, true);
  }
  writeFileSync(bin + '.ps-result', JSON.stringify({ worktrees: [{ bot: 'メインBot', agents: [{ state: 'working' }] }] }));
  clearReadCache();
  await expectChange(false, false, true);
  assert.deepEqual(changes(first.frames), changes(second.frames));
});

test('eight clients share the poller and a disconnected slot can be reused', async t => {
  const { connect, bin, url, calls, server } = await fixture(t);
  writeFileSync(bin + '.slow-ps', '');
  const released = new Promise<void>(resolve => server.once('request', (_, res) => res.once('close', resolve)));
  const first = await connect();
  await Promise.all(Array.from({ length: 7 }, connect));
  const response = await fetch(url, { headers });
  assert.equal(response.status, 503);
  assert.deepEqual(await response.json(), { error: 'too_many_connections',
    message: 'リアルタイム通知の接続数が上限に達しています。' });
  first.response.destroy();
  await released;
  await connect();
  await until(() => existsSync(bin + '.ps-pid'));
  assert.equal(calls().split('automations runs').length - 1, 1);
});

for (const end of ['disconnect', 'server.close'] as const) {
  test(`${end} stops active CLI reads and all further polling`, { timeout: 5000 }, async t => {
    const { connect, bin, server, calls } = await fixture(t);
    writeFileSync(bin + '.slow-ps', '');
    const { response } = await connect();
    await until(() => existsSync(bin + '.ps-pid'));
    const pid = Number(readFileSync(bin + '.ps-pid', 'utf8'));
    const closed = once(response, 'close');
    if (end === 'disconnect') response.destroy();
    else await new Promise<void>(resolve => server.close(() => resolve()));
    await closed;
    await until(() => {
      try { process.kill(pid, 0); return false; }
      catch (error) { return (error as NodeJS.ErrnoException).code === 'ESRCH'; }
    });
    const stopped = calls();
    await delay(3 * pollIntervalMs);
    assert.equal(calls(), stopped);
    if (end === 'disconnect') await new Promise<void>(resolve => server.close(() => resolve()));
  });
}

test('server.close drains SSE to a half-open peer and logs only the route', { timeout: 3000 }, async t => {
  const { server } = await fixture(t);
  const log = t.mock.method(console, 'log', () => {});
  const socket = createConnection({ host: '127.0.0.1', port: (server.address() as AddressInfo).port, allowHalfOpen: true });
  t.after(() => socket.destroy());
  socket.write('GET /api/events?private-query HTTP/1.1\r\nHost: localhost\r\n' +
    `Authorization: ${headers.Authorization}\r\n\r\n`);
  let body = '';
  socket.on('data', chunk => { body += chunk; });
  await until(() => body.includes('event: ready'));
  const ended = once(socket, 'end');
  await new Promise<void>(resolve => server.close(() => resolve()));
  await ended;
  assert.ok(body.endsWith('0\r\n\r\n'));
  assert.equal(log.mock.callCount(), 1);
  assert.match(log.mock.calls[0].arguments[0], /^"\/api\/events" 200 \d+ms$/);
});
