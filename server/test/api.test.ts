import assert from 'node:assert/strict';
import childProcess, { fork, ChildProcess } from 'node:child_process';
import { once } from 'node:events';
import { copyFileSync, existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { request } from 'node:http';
import { syncBuiltinESMExports } from 'node:module';
import type { AddressInfo } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { PassThrough } from 'node:stream';
import { after, before, test, type TestContext } from 'node:test';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';
import { readConfig } from '../src/config.ts';
import { createApi } from '../src/http.ts';
import { createOrca, OrcaError } from '../src/orca.ts';

const orcaBin = fileURLToPath(new URL('./fixtures/orca', import.meta.url));
const token = 'test-token';
const bot = encodeURIComponent('メインBot');
const other = encodeURIComponent('専門Bot');
const chat = '12345678-abcd-1234-abcd-123456789abc';
const unrelatedChat = '00000000-0000-0000-0000-000000000000';
const messages = `/api/bots/${bot}/chats/${chat}/messages`;

let tokenDir: string;
before(() => {
  tokenDir = mkdtempSync(join(tmpdir(), 'orca-bot-remote-api-test-'));
  writeFileSync(join(tokenDir, 'token'), token, { mode: 0o600 });
});
after(() => rmSync(tokenDir, { recursive: true }));

function recordOrcaCalls(t: TestContext) {
  const dir = mkdtempSync(join(tokenDir, 'orca-'));
  const bin = join(dir, 'orca');
  copyFileSync(orcaBin, bin);
  writeFileSync(`${bin}.calls`, '');
  t.after(() => rmSync(dir, { recursive: true }));
  return { bin, calls: () => readFileSync(`${bin}.calls`, 'utf8').trim().split('\n') };
}

async function listenApi(t: TestContext, summaryId?: string, bin = orcaBin, requestTimeoutMs?: number) {
  const server = createApi(readConfig({
    HOST: '100.113.115.59', TOKEN_FILE: join(tokenDir, 'token'),
    ORCA_BIN: bin, SUMMARY_AUTOMATION_ID: summaryId
  }), requestTimeoutMs);
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(async () => {
    server.close();
    server.closeAllConnections();
    await once(server, 'close');
  });
  return server;
}

async function openApi(t: TestContext, summaryId?: string, bin = orcaBin, requestTimeoutMs?: number) {
  const server = await listenApi(t, summaryId, bin, requestTimeoutMs);
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  return async (path: string, status = 200, authorization: string | null = `Bearer ${token}`, method = 'GET') => {
    const response = await fetch(base + path, {
      method, headers: authorization === null ? {} : { Authorization: authorization }
    });
    assert.equal(response.status, status, path);
    assert.equal(response.headers.get('content-type'), 'application/json; charset=utf-8');
    if (status === 405) assert.equal(response.headers.get('allow'), 'GET');
    return response.json();
  };
}

const slowItemCalls = [
  'bot list --json',
  'bot item list --bot=メインBot --all --limit 100 --json',
  'bot item list --bot=メインBot --all --limit 100 --cursor 400:1 --json'
];

function readPids(bin: string) {
  const pids = readFileSync(`${bin}.pids`, 'utf8').trim().split(' ').map(Number);
  assert.equal(pids.length, 2);
  assert.ok(pids.every(pid => Number.isInteger(pid) && pid > 1));
  return pids;
}

async function waitForPids(bin: string) {
  const deadline = performance.now() + 2000;
  while (!existsSync(`${bin}.pids`)) {
    assert.ok(performance.now() < deadline, 'CLI の親と子が起動しませんでした');
    await delay(10);
  }
  return readPids(bin);
}

async function assertProcessesStopped(pids: number[]) {
  for (const pid of pids) {
    const deadline = performance.now() + 500;
    while (true) {
      try {
        process.kill(pid, 0);
      } catch (error) {
        assert.equal((error as NodeJS.ErrnoException).code, 'ESRCH');
        break;
      }
      assert.ok(performance.now() < deadline, `CLI の PID ${pid} が残っています`);
      await delay(10);
    }
  }
}

test('Bot 一覧は判断待ちの全ページを集計する', async t => {
  const get = await openApi(t);
  assert.deepEqual(await get('/api/bots'), { bots: [
    { id: 'main', name: 'メインBot', specialty: '総合', paused: false, needsYou: 2 },
    { id: 'other', name: '専門Bot', specialty: null, paused: true, needsYou: 1 }
  ] });
});

test('チャット一覧は閉じたチャットを除き新しい順で返す', async t => {
  const get = await openApi(t);
  assert.deepEqual(await get(`/api/bots/${bot}/chats`), { chats: [
    { id: 'new', title: '会話', home: true, lastActivityAt: 300, lastAsked: '質問', lastSaid: '回答' },
    { id: 'old', title: null, home: false, lastActivityAt: 100, lastAsked: null, lastSaid: null },
    { id: 'no-time', title: null, home: false, lastActivityAt: null, lastAsked: null, lastSaid: null }
  ] });
});

test('閉じたチャットのメッセージとツールも元の順序と契約の項目で返す', async t => {
  const get = await openApi(t);
  assert.deepEqual(await get(messages), { state: 'idle', entries: [
    { kind: 'message', role: 'user', text: '質問' },
    { kind: 'tool', name: 'Read', state: 'done' },
    { kind: 'message', role: 'assistant', text: '回答' },
    { kind: 'tool', name: 'List', state: null }
  ] });
});

test('limit は 1〜100 に丸め、整数以外は既定値を使う', async t => {
  const get = await openApi(t);
  for (const limit of ['-10', '0', '1']) {
    assert.deepEqual(await get(`${messages}?limit=${limit}`), {
      state: null, entries: [{ kind: 'message', role: 'assistant', text: '回答' }]
    });
  }
  for (const limit of ['100', '1000', '誤り']) {
    assert.equal((await get(`${messages}?limit=${encodeURIComponent(limit)}`)).entries.length, 4);
  }
});

test('判断待ちは全 Bot を集約して新しい順で返す', async t => {
  const get = await openApi(t);
  assert.deepEqual(await get('/api/items'), { items: [
    { bot: '専門Bot', number: 2, title: '確認', status: 'needs_you', chatId: null, lastActivityAt: 200 },
    { bot: 'メインBot', number: 7, title: '判断待ち', status: 'needs_you', chatId: 'chat-7', lastActivityAt: 100 },
    { bot: 'メインBot', number: 6, title: 'メモ', status: 'needs_you', chatId: null, lastActivityAt: null }
  ] });
});

test('判断待ちの詳細は現在の質問イベントから本文と選択肢を取る', async t => {
  const get = await openApi(t);
  assert.deepEqual(await get(`/api/items/${bot}/7`), {
    bot: 'メインBot', number: 7, title: '判断待ち', status: 'needs_you', chatId: 'chat-7',
    question: { text: 'どちらにしますか？', options: ['進める', '保留'] }
  });
  assert.deepEqual(await get(`/api/items/${bot}/1`), {
    bot: 'メインBot', number: 1, title: '完了済み', status: 'done', chatId: null, question: null
  });
});

test('まとめは直近の完了済み定期実行を返す', async t => {
  const get = await openApi(t, 'daily');
  assert.deepEqual(await get('/api/summary'), {
    summary: { bot: 'メインBot', runAt: 300, content: '状況まとめ', chatId: 'home' }
  });
});

test('まとめの Bot 名が一覧に無ければ自動化定義の Bot ID を返す', async t => {
  const get = await openApi(t, 'missing-bot');
  assert.deepEqual(await get('/api/summary'), {
    summary: { bot: 'deleted-bot', runAt: 300, content: '状況まとめ', chatId: 'home' }
  });
});

test('未設定または履歴なしならまとめは null', async t => {
  for (const id of [undefined, 'empty']) {
    const get = await openApi(t, id);
    assert.deepEqual(await get('/api/summary'), { summary: null });
  }
});

test('トークン無し・同じ長さの誤り・異なる長さの誤りは 401', async t => {
  const get = await openApi(t);
  for (const auth of [null, 'Bearer test-t0ken', 'Bearer short', `Basic ${token}`]) {
    assert.deepEqual(await get('/api/bots', 401, auth), { error: 'unauthorized' });
  }
});

test('未知のパス・Bot・チャット・項目は 404', async t => {
  const get = await openApi(t);
  for (const path of [
    '/api/unknown', '/api/bots/', '/api/bots/unknown/chats', '/api/bots/%zz/chats',
    `/api/bots/${bot}/chats/not-a-uuid/messages`,
    '/api/items/unknown/7', `/api/items/${other}/7`, `/api/items/${bot}/0`, `/api/items/${bot}/-1`,
    `/api/items/${bot}/7.1`, `/api/items/${bot}/9007199254740992`, `/api/items/${bot}/3`
  ]) {
    const result = await get(path, 404);
    assert.equal(result.error, 'not_found');
    assert.equal(typeof result.message, 'string');
  }
});

test('履歴の旧パス、未知の Bot、別の Bot のチャット、Bot 外のセッションには 404 を返す', async t => {
  const { bin, calls } = recordOrcaCalls(t);
  const get = await openApi(t, undefined, bin);
  for (const path of [
    `/api/chats/${chat}/messages`, `/api/bots/unknown/chats/${chat}/messages`,
    `/api/bots/${other}/chats/${chat}/messages`, `/api/bots/${bot}/chats/${unrelatedChat}/messages`
  ]) {
    assert.equal((await get(path, 404)).error, 'not_found');
  }
  assert.deepEqual(calls(), [
    'bot list --json', 'bot list --json', 'bot chats --bot=専門Bot --all --json',
    'bot list --json', 'bot chats --bot=メインBot --all --json'
  ]);
});

test('リクエスト全体の期限で実行中の CLI を停止し、残りを呼ばず 502 を返す', { timeout: 5000 }, async t => {
  const { bin, calls } = recordOrcaCalls(t);
  writeFileSync(`${bin}.slow`, '');
  const get = await openApi(t, undefined, bin, 1200);
  const started = performance.now();
  assert.deepEqual(await get(`/api/items/${bot}/7`, 502), {
    error: 'orca_failed', message: 'Orca の読み取りが制限時間内に終わりませんでした。'
  });
  const elapsed = performance.now() - started;
  assert.ok(elapsed <= 1700, `期限は1200msですが応答まで${elapsed}msかかりました`);
  await assertProcessesStopped(readPids(bin));
  assert.deepEqual(calls(), slowItemCalls);
});

test('期限タイマーが発火する前でも期限に達したら次の CLI を起動しない', async t => {
  const { bin, calls } = recordOrcaCalls(t);
  let now = 0;
  t.mock.method(performance, 'now', () => now);
  const { signal } = new AbortController();
  const orca = createOrca(bin, undefined, signal, 100);
  const [bot] = await orca.listBots();
  now = 100;
  await assert.rejects(orca.chats(bot), OrcaError);
  assert.deepEqual(calls(), ['bot list --json']);
});

test('個別の CLI の期限でも親と子を停止して 502 を返す', { timeout: 5000 }, async t => {
  const { bin, calls } = recordOrcaCalls(t);
  writeFileSync(`${bin}.slow`, '');
  const realSetTimeout = setTimeout;
  t.mock.method(globalThis, 'setTimeout', (callback, ms, ...args) =>
    realSetTimeout(callback, ms === 20_000 ? 1000 : ms, ...args));
  const get = await openApi(t, undefined, bin);
  assert.deepEqual(await get(`/api/items/${bot}/7`, 502), {
    error: 'orca_failed', message: 'Orca の読み取りに失敗しました。'
  });
  await assertProcessesStopped(readPids(bin));
  assert.deepEqual(calls(), slowItemCalls);
});

test('サーバーは SIGTERM で実行中の CLI とその子を停止して終了する', { timeout: 5000 }, async t => {
  const { bin } = recordOrcaCalls(t);
  writeFileSync(`${bin}.slow`, '');
  // Run the actual entry point on an ephemeral loopback port without requiring Tailscale.
  const preload = `
    import { Server } from 'node:http';
    const listen = Server.prototype.listen;
    Server.prototype.listen = function () {
      return listen.call(this, 0, '127.0.0.1', () => process.send(this.address().port));
    };
  `;
  const server = fork(fileURLToPath(new URL('../src/main.ts', import.meta.url)), {
    env: { HOST: '100.113.115.59', TOKEN_FILE: join(tokenDir, 'token'), ORCA_BIN: bin },
    execArgv: ['--import', `data:text/javascript,${encodeURIComponent(preload)}`],
    silent: true
  });
  t.after(() => { server.kill('SIGKILL'); });
  const exited = once(server, 'exit', { signal: t.signal });
  const [port] = await Promise.race([once(server, 'message', { signal: t.signal }), exited]);
  assert.ok(typeof port === 'number' && port > 0, 'サーバーが待ち受けを開始しませんでした');
  const client = request({
    host: '127.0.0.1', port, path: `/api/items/${bot}/7`, headers: { Authorization: `Bearer ${token}` }
  }, response => response.resume());
  client.on('error', () => {});
  t.after(() => { client.destroy(); });
  client.end();
  const pids = await waitForPids(bin);
  assert.ok(server.kill('SIGTERM'));
  assert.deepEqual(await exited, [0, null]);
  await assertProcessesStopped(pids);
});

test('CLI のリーダーが終了済みなら中止時にグループへシグナルを送らない', async t => {
  const child = Object.assign(new ChildProcess(), { pid: 123, stdout: new PassThrough(), stderr: new PassThrough() });
  const spawn = t.mock.method(childProcess, 'spawn', () => child);
  syncBuiltinESMExports();
  t.after(() => {
    spawn.mock.restore();
    syncBuiltinESMExports();
  });
  const kill = t.mock.method(process, 'kill', () => true);
  const controller = new AbortController();
  const orca = createOrca(orcaBin, undefined, controller.signal, Infinity);
  const result = orca.listBots();
  child.emit('exit', 0, null);
  controller.abort();
  child.emit('close', 0, null);
  await assert.rejects(result, OrcaError);
  assert.equal(kill.mock.callCount(), 0);
  assert.ok(child.stdout.destroyed && child.stderr.destroyed);
});

test('クライアント切断時は CLI を停止し、後続の CLI 起動と応答書き込みを行わない', { timeout: 5000 }, async t => {
  const { bin, calls } = recordOrcaCalls(t);
  writeFileSync(`${bin}.slow`, '');
  const timers = t.mock.method(globalThis, 'setTimeout');
  const realClearTimeout = clearTimeout;
  let cliTimer: ReturnType<typeof setTimeout> | undefined;
  const cliClosed = Promise.withResolvers<void>();
  const cleared = t.mock.method(globalThis, 'clearTimeout', timer => {
    realClearTimeout(timer);
    if (cliTimer && timer === cliTimer) cliClosed.resolve();
  });
  const server = await listenApi(t, undefined, bin, 4990);
  const received = once(server, 'request');
  const client = request({
    host: '127.0.0.1', port: (server.address() as AddressInfo).port,
    path: `/api/items/${bot}/7`, headers: { Authorization: `Bearer ${token}` }
  });
  client.on('error', () => {});
  t.after(() => { client.destroy(); });
  client.end();
  const [, response] = await received;
  const writeHead = t.mock.method(response, 'writeHead');
  const closed = once(response, 'close');
  const pids = await waitForPids(bin);
  const requestTimer = timers.mock.calls.find(({ arguments: [, ms] }) => ms > 4900 && ms <= 4990)?.result;
  cliTimer = timers.mock.calls.findLast(({ arguments: [, ms] }) => ms === 20_000)?.result;
  assert.ok(requestTimer && cliTimer);
  client.destroy();
  await closed;
  assert.ok(cleared.mock.calls.some(({ arguments: [timer] }) => timer === requestTimer));
  await assertProcessesStopped(pids);
  await cliClosed.promise;
  assert.deepEqual(calls(), slowItemCalls);
  assert.equal(writeHead.mock.callCount(), 0);
});

test('標準出力と標準エラー出力はそれぞれ4 MiBを超えると 502 を返す', async t => {
  for (const id of ['overflow-stdout', 'overflow-stderr']) {
    const get = await openApi(t, id);
    assert.deepEqual(await get('/api/summary', 502), {
      error: 'orca_failed', message: 'Orca の読み取りに失敗しました。'
    });
  }
});

test('既知パスの GET 以外は 405', async t => {
  const get = await openApi(t);
  assert.equal((await get('/api/bots', 405, undefined, 'POST')).error, 'bad_request');
});

test('アクセスログにはルートテンプレートか固定値を記録する', async t => {
  const log = t.mock.method(console, 'log', () => {});
  const get = await openApi(t);
  await get('/api/path-canary', 401, null);
  await get('/api/path-canary', 404);
  await get(`${messages}?limit=1&secret=query-canary`);
  await get(`/api/items/${bot}/7`, 405, undefined, 'POST');
  const lines = log.mock.calls.map(call => call.arguments[0].replace(/ \d+ms$/, ''));
  assert.deepEqual(lines, [
    '"unauthenticated" 401', '"unknown" 404',
    '"/api/bots/:bot/chats/:chat/messages" 200', '"/api/items/:bot/:number" 405'
  ]);
});

test('Orca CLI の失敗時は CLI の出力をレスポンス本文にもログにも含めず 502 を返す', async t => {
  const log = t.mock.method(console, 'log', () => {});
  const error = t.mock.method(console, 'error', () => {});
  const get = await openApi(t, 'failed');
  assert.deepEqual(await get('/api/summary', 502), {
    error: 'orca_failed', message: 'Orca の読み取りに失敗しました。'
  });
  assert.equal(log.mock.callCount(), 1);
  for (const call of [...log.mock.calls, ...error.mock.calls]) {
    assert.doesNotMatch(call.arguments.map(String).join(' '), /LEAK-CANARY/);
  }
});
