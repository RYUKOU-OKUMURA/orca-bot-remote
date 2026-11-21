import assert from 'node:assert/strict';
import childProcess, { fork, ChildProcess } from 'node:child_process';
import { once } from 'node:events';
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
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
import { createOrcaWrite, OrcaWriteError, type OrcaWrite } from '../src/orca-write.ts';

const orcaBin = fileURLToPath(new URL('./fixtures/orca', import.meta.url));
const token = 'test-token';
const bot = encodeURIComponent('メインBot');
const other = encodeURIComponent('専門Bot');
const chat = '12345678-abcd-1234-abcd-123456789abc';
const unrelatedChat = '00000000-0000-0000-0000-000000000000';
const messages = `/api/bots/${bot}/chats/${chat}/messages`;
const chats = `/api/bots/${bot}/chats`;
const openChat = 'abcdef00-abcd-1234-abcd-123456789abc';
const openMessages = `/api/bots/${other}/chats/${openChat}/messages`;
const answer = `/api/items/${bot}/7/answer`;

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

async function listenApi(t: TestContext, summaryId?: string, bin = orcaBin, requestTimeoutMs?: number, write?: OrcaWrite) {
  const config = readConfig({
    HOST: '100.113.115.59', TOKEN_FILE: join(tokenDir, 'token'),
    ORCA_BIN: bin, SUMMARY_AUTOMATION_ID: summaryId
  });
  const server = createApi({ ...config, write }, requestTimeoutMs);
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

test('Bot 一覧は判断待ちの全ページ、ID が一致するマスコット、最新の開いたチャットを返す', async t => {
  const testHome = mkdtempSync(join(tokenDir, 'home-'));
  const home = process.env.HOME;
  process.env.HOME = testHome;
  t.after(() => {
    if (home === undefined) delete process.env.HOME;
    else process.env.HOME = home;
  });
  const get = await openApi(t);
  const expected = { bots: [
    { id: 'main', name: 'メインBot', specialty: '総合', paused: false, needsYou: 2,
      avatar: null as string | null, lastActivityAt: 300, lastSaid: '回答' },
    { id: 'other', name: '専門Bot', specialty: null, paused: true, needsYou: 1,
      avatar: null, lastActivityAt: null, lastSaid: null }
  ] };
  assert.deepEqual(await get('/api/bots'), expected);
  for (const [dir, metadata] of [
    ['unrelated-folder', '{"id":"main","avatar":"mascot:A1"}'],
    ['main', '{"id":"other","avatar":"upload:private-path"}'],
    ['broken', '{']
  ]) {
    const path = join(testHome, '.orca', 'bots', dir);
    mkdirSync(path, { recursive: true });
    writeFileSync(join(path, 'bot.json'), metadata);
  }
  expected.bots[0].avatar = 'mascot:A1';
  assert.deepEqual(await get('/api/bots'), expected);
});

test('最新のチャットを取得できなくても Bot と判断待ちの件数は返す', async t => {
  const { bin } = recordOrcaCalls(t);
  writeFileSync(`${bin}.fail-chats`, '');
  const get = await openApi(t, undefined, bin);
  const { bots: [main] } = await get('/api/bots');
  assert.equal(main.id, 'main');
  assert.equal(main.needsYou, 2);
  assert.equal(main.lastActivityAt, null);
  assert.equal(main.lastSaid, null);
  await get(chats, 502);
});

test('最新チャットの応答形式が不正なら Bot 一覧のプレビューを空にする', async t => {
  const { bin } = recordOrcaCalls(t);
  const get = await openApi(t, undefined, bin);
  for (const result of [
    {},
    { chats: {} },
    { chats: [null] },
    { chats: [{ id: 'new', home: true, lastActivityAt: '300', lastSaid: '回答' }] },
    { chats: [{ id: 'new', home: true, lastActivityAt: 300, lastSaid: 42 }] }
  ]) {
    writeFileSync(`${bin}.chats-result`, JSON.stringify(result));
    const { bots: [main] } = await get('/api/bots');
    assert.equal(main.id, 'main');
    assert.equal(main.needsYou, 2);
    assert.equal(main.lastActivityAt, null);
    assert.equal(main.lastSaid, null);
  }
  assert.equal((await get(chats)).chats[0].lastSaid, 42);
  writeFileSync(`${bin}.chats-result`, '{}');
  await get(chats, 502);
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

async function forkServer(t: TestContext, bin: string, extraPreload = '', home?: string) {
  // Run the actual entry point on an ephemeral loopback port without requiring Tailscale.
  const preload = `
    import { Server } from 'node:http';
    const listen = Server.prototype.listen;
    Server.prototype.listen = function () {
      return listen.call(this, 0, '127.0.0.1', () => process.send(this.address().port));
    };
    ${extraPreload}
  `;
  const server = fork(fileURLToPath(new URL('../src/main.ts', import.meta.url)), {
    env: { HOST: '100.113.115.59', TOKEN_FILE: join(tokenDir, 'token'), ORCA_BIN: bin, HOME: home },
    execArgv: ['--import', `data:text/javascript,${encodeURIComponent(preload)}`],
    silent: true
  });
  t.after(() => { server.kill('SIGKILL'); });
  const exited = once(server, 'exit', { signal: t.signal });
  const [port] = await Promise.race([once(server, 'message', { signal: t.signal }), exited]);
  assert.ok(typeof port === 'number' && port > 0, 'サーバーが待ち受けを開始しませんでした');
  return { server, exited, port };
}

test('サーバーは SIGTERM で実行中の CLI とその子を停止して終了する', { timeout: 5000 }, async t => {
  const { bin } = recordOrcaCalls(t);
  writeFileSync(`${bin}.slow`, '');
  const { server, exited, port } = await forkServer(t, bin);
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

test('SIGTERM は書き込み前後に応じた HTTP エラーを返してから終了する', { timeout: 5000 }, async t => {
  const hostFixture = new URL('./fixtures/orca-protocol/fake-host.ts', import.meta.url).href;
  const preload = `
    import { mkdirSync, writeFileSync } from 'node:fs';
    import { device, installFakeHost, hold } from ${JSON.stringify(hostFixture)};
    const dir = process.env.HOME + '/.config/orca-bot-remote';
    mkdirSync(dir, { recursive: true });
    writeFileSync(dir + '/orca-device.json', JSON.stringify(device), { mode: 0o600 });
    installFakeHost(({ method }) => {
      if (method === 'orchestration.partyLocation') {
        return { result: { location: { kind: 'chat', sessionId: 'successor' } } };
      }
      if (method === 'agentSession.history' && process.env.HOME.endsWith('/unknown_result')) {
        return { result: { ok: true, page: { fence: 1 } } };
      }
      process.send(method);
      return hold;
    });
  `;
  for (const [code, method] of [['orca_failed', 'agentSession.history'], ['unknown_result', 'agentSession.send']]) {
    const { server, exited, port } = await forkServer(t, orcaBin, preload, join(tokenDir, code));
    const waiting = once(server, 'message', { signal: t.signal });
    const response = fetch(`http://127.0.0.1:${port}${openMessages}`, {
      method: 'POST', body: '{"text":"入力"}', signal: t.signal,
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
    });
    const [received] = await Promise.race([waiting, exited]);
    assert.equal(received, method);
    assert.ok(server.kill('SIGTERM'));
    const reply = await response;
    assert.equal(reply.status, 502);
    assert.equal((await reply.json()).error, code);
    assert.deepEqual(await exited, [0, null]);
    await assertProcessesStopped([server.pid!]);
  }
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

function fakeWrite(calls: string[][], failure?: OrcaWriteError['code']): OrcaWrite {
  async function record(method: string, id: string, text: string) {
    calls.push([method, id, text]);
    if (failure) throw new OrcaWriteError(failure);
  }
  return {
    async start(id, text) {
      await record('start', id, text);
      return openChat;
    },
    send: (id, text) => record('send', id, text),
    answer: (uid, text) => record('answer', uid, text)
  };
}

async function writeApi(t: TestContext, write: OrcaWrite, bin = orcaBin, timeout?: number) {
  const server = await listenApi(t, undefined, bin, timeout, write);
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  return (path: string, body: BodyInit | null = '{"text":"進める"}', method = 'POST', contentType = 'application/json', authenticated = true) =>
    fetch(base + path, {
      method, body, headers: {
        'Content-Type': contentType, ...(authenticated ? { Authorization: `Bearer ${token}` } : {})
      }
    });
}

test('書き込みは Bot ID、開いているチャット ID、対象項目の UID で実行する', async t => {
  const calls: string[][] = [];
  const log = t.mock.method(console, 'log', () => {});
  const { bin, calls: cliCalls } = recordOrcaCalls(t);
  const post = await writeApi(t, fakeWrite(calls), bin);
  const created = await post(chats, '{"text":"書き込み本文-canary"}');
  const sent = await post(openMessages, '{"text":"  つづき  "}');
  const answered = await post(answer);
  assert.deepEqual([created.status, sent.status, answered.status], [201, 202, 200]);
  assert.deepEqual(await created.json(), { chatId: openChat });
  assert.deepEqual(await sent.json(), { ok: true });
  assert.deepEqual(await answered.json(), { ok: true });
  assert.deepEqual(calls, [
    ['start', 'main', '書き込み本文-canary'], ['send', openChat, '  つづき  '], ['answer', 'uid-7', '進める']
  ]);
  assert.deepEqual(cliCalls().slice(-4), [
    'bot list --json', 'bot item list --bot=メインBot --all --limit 100 --json',
    'bot item list --bot=メインBot --all --limit 100 --cursor 400:1 --json', 'bot item show --id=uid-7 --json'
  ]);
  assert.deepEqual(log.mock.calls.map(call => call.arguments[0].replace(/ \d+ms$/, '')), [
    '"/api/bots/:bot/chats" 201', '"/api/bots/:bot/chats/:chat/messages" 202', '"/api/items/:bot/:number/answer" 200'
  ]);
});

test('書き込み本文は JSON で、最大 64 KiB、空白を除いて 1〜10000 文字', async t => {
  const calls: string[][] = [];
  const post = await writeApi(t, fakeWrite(calls));
  for (const body of [
    'invalid', 'null', '{}', '{"text":1}', '{"text":"  "}', JSON.stringify({ text: '界'.repeat(10_001) }),
    JSON.stringify({ text: 'x', padding: ' '.repeat(64 * 1024) }),
    new Blob([new Uint8Array([123, 0xff, 125])])
  ]) {
    const response = await post(chats, body);
    assert.equal(response.status, 400);
    assert.equal((await response.json()).error, 'bad_request');
  }
  const wrongType = await post(chats, '{"text":"入力"}', 'POST', 'text/plain');
  assert.equal(wrongType.status, 400);
  const text = '😀'.repeat(10_000);
  const prefix = JSON.stringify({ text });
  const response = await post(chats, prefix + ' '.repeat(65536 - Buffer.byteLength(prefix)), 'POST', 'application/json; charset=utf-8');
  assert.equal(response.status, 201);
  assert.deepEqual(calls, [['start', 'main', text]]);
});

test('chunked の本文も 64 KiB を超えた時点で 400 を返す', async t => {
  const calls: string[][] = [];
  const server = await listenApi(t, undefined, orcaBin, undefined, fakeWrite(calls));
  const status = await new Promise<number | undefined>((resolve, reject) => {
    const client = request({
      hostname: '127.0.0.1', port: (server.address() as AddressInfo).port,
      method: 'POST', path: chats, headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
    }, response => {
      response.resume();
      resolve(response.statusCode);
    });
    client.on('error', reject);
    client.write(' '.repeat(32768));
    client.write(' '.repeat(32769));
    client.end();
  });
  assert.equal(status, 400);
  assert.deepEqual(calls, []);
});

test('判断待ちへの回答は前後の空白を含めて最大8000 UTF-16コード単位', async t => {
  const calls: string[][] = [];
  const post = await writeApi(t, fakeWrite(calls));
  const text = '😀'.repeat(4000);
  const response = await post(answer, JSON.stringify({ text: ` ${text}` }));
  assert.equal(response.status, 400);
  assert.equal((await response.json()).error, 'bad_request');
  assert.equal((await post(answer, JSON.stringify({ text }))).status, 200);
  assert.deepEqual(calls, [['answer', 'uid-7', text]]);
});

async function waitForAnswerLookups(t: TestContext, cliCalls: () => string[], count: number) {
  while (cliCalls().filter(call => call === slowItemCalls[2]).length < count) {
    await delay(10, undefined, { signal: t.signal });
  }
  await delay(50, undefined, { signal: t.signal });
}

test('同じ UID の同時回答は最初の回答後に状態を読み直して 409 を返す', { timeout: 5000 }, async t => {
  const { bin, calls: cliCalls } = recordOrcaCalls(t);
  const calls: string[][] = [];
  const entered = Promise.withResolvers<void>();
  const release = Promise.withResolvers<void>();
  t.after(release.resolve);
  const post = await writeApi(t, {
    ...fakeWrite(calls),
    async answer(uid, text) {
      calls.push(['answer', uid, text]);
      entered.resolve();
      await release.promise;
      writeFileSync(`${bin}.answered`, '');
    }
  }, bin);
  const first = post(answer);
  await entered.promise;
  const second = post(answer, '{"text":"保留"}');
  await waitForAnswerLookups(t, cliCalls, 2);
  assert.equal(cliCalls().filter(call => call === 'bot item show --id=uid-7 --json').length, 1);
  release.resolve();
  const [accepted, conflict] = await Promise.all([first, second]);
  assert.equal(accepted.status, 200);
  assert.equal(conflict.status, 409);
  assert.equal((await conflict.json()).error, 'conflict');
  assert.deepEqual(calls, [['answer', 'uid-7', '進める']]);
});

test('同じ UID の待機中の回答は先行が結果不明なら見送り、確定拒否なら再確認して送る', { timeout: 5000 }, async t => {
  for (const code of ['unknown_result', 'orca_failed'] as const) {
    const { bin, calls: cliCalls } = recordOrcaCalls(t);
    const calls: string[][] = [];
    const entered = Promise.withResolvers<void>();
    const release = Promise.withResolvers<void>();
    t.after(release.resolve);
    const post = await writeApi(t, {
      ...fakeWrite(calls),
      async answer(uid, text) {
        calls.push(['answer', uid, text]);
        if (calls.length === 1) {
          entered.resolve();
          await release.promise;
          throw new OrcaWriteError(code);
        }
      }
    }, bin);

    const first = post(answer);
    await entered.promise;
    const second = post(answer, '{"text":"保留"}');
    await waitForAnswerLookups(t, cliCalls, 2);
    release.resolve();

    const [failed, waiting] = await Promise.all([first, second]);
    assert.equal(failed.status, 502);
    assert.equal((await failed.json()).error, code);
    if (code === 'unknown_result') {
      assert.equal(waiting.status, 502);
      assert.equal((await waiting.json()).error, code);
      assert.deepEqual(calls, [['answer', 'uid-7', '進める']]);
    } else {
      assert.equal(waiting.status, 200);
      assert.deepEqual(calls, [['answer', 'uid-7', '進める'], ['answer', 'uid-7', '保留']]);
    }
    assert.equal(cliCalls().filter(call => call === 'bot item show --id=uid-7 --json').length,
      code === 'unknown_result' ? 1 : 2);
  }
});

test('同じ UID の待機中に期限切れの回答は先に 502 を返し、後続の順序を保つ', { timeout: 5000 }, async t => {
  const { bin, calls: cliCalls } = recordOrcaCalls(t);
  const calls: string[][] = [];
  const entered = Promise.withResolvers<void>();
  const release = Promise.withResolvers<void>();
  t.after(release.resolve);
  const post = await writeApi(t, {
    ...fakeWrite(calls),
    async answer(uid, text) {
      calls.push(['answer', uid, text]);
      if (calls.length === 1) {
        entered.resolve();
        await release.promise;
        throw new OrcaWriteError('orca_failed');
      }
    }
  }, bin, 1200);

  const first = post(answer);
  await entered.promise;
  const expired = await post(answer, '{"text":"期限切れ"}');
  assert.equal(expired.status, 502);
  assert.equal((await expired.json()).error, 'orca_failed');
  const third = post(answer, '{"text":"保留"}');
  await waitForAnswerLookups(t, cliCalls, 3);
  assert.equal(cliCalls().filter(call => call === 'bot item show --id=uid-7 --json').length, 1);
  release.resolve();

  const [failed, accepted] = await Promise.all([first, third]);
  assert.equal(failed.status, 502);
  assert.equal(accepted.status, 200);
  assert.deepEqual(calls, [['answer', 'uid-7', '進める'], ['answer', 'uid-7', '保留']]);
});

test('書き込みも認証を先に確認し、未知の対象、閉じたチャット、回答済み項目を拒否する', async t => {
  const calls: string[][] = [];
  const post = await writeApi(t, fakeWrite(calls));
  assert.equal((await post(chats, null, 'POST', 'application/json', false)).status, 401);
  for (const path of ['/api/bots/unknown/chats', messages.replace(bot, other), `/api/items/${other}/7/answer`,
    `/api/items/${bot}/0/answer`]) {
    const response = await post(path);
    assert.equal(response.status, 404);
    assert.equal((await response.json()).error, 'not_found');
  }
  for (const path of [messages, `/api/items/${bot}/1/answer`]) {
    const response = await post(path);
    assert.equal(response.status, 409);
    assert.equal((await response.json()).error, 'conflict');
  }
  assert.deepEqual(calls, []);
});

test('書き込みを追加したルートの 405 は使用できるメソッドを返す', async t => {
  const calls: string[][] = [];
  const post = await writeApi(t, fakeWrite(calls));
  for (const [path, method, allow] of [
    [answer, 'GET', 'POST'], [chats, 'PATCH', 'GET, POST'], [openMessages, 'PUT', 'GET, POST'],
    ['/api/summary', 'POST', 'GET']
  ]) {
    const response = await post(path, null, method);
    assert.equal(response.status, 405);
    assert.equal(response.headers.get('allow'), allow);
  }
  assert.deepEqual(calls, []);
});

test('ペアリング資格情報がない環境でも読み取れ、書き込みは 503', async t => {
  const post = await writeApi(t, createOrcaWrite(join(tokenDir, 'missing-device')));
  assert.equal((await post(chats, null, 'GET')).status, 200);
  const response = await post(chats);
  assert.equal(response.status, 503);
  assert.deepEqual(await response.json(), {
    error: 'write_unavailable', message: 'Mac mini の書き込み設定が済んでいません。'
  });
});

test('書き込みの拒否と送信結果が不明な場合を区別して再送しない', async t => {
  for (const code of ['orca_failed', 'unknown_result'] as const) {
    const calls: string[][] = [];
    const post = await writeApi(t, fakeWrite(calls, code));
    const response = await post(openMessages);
    assert.equal(response.status, 502);
    const body = await response.json();
    assert.equal(body.error, code);
    if (code === 'unknown_result') {
      assert.equal(body.message, '送れたか確認できませんでした。会話を更新して確かめてください。');
    }
    assert.deepEqual(calls, [['send', openChat, '進める']]);
  }
});

test('書き込み前に Orca から項目の消失が返ると HTTP 404 を返す', async t => {
  const calls: string[][] = [];
  const post = await writeApi(t, fakeWrite(calls, 'not_found'));
  const response = await post(answer);
  assert.equal(response.status, 404);
  assert.equal((await response.json()).error, 'not_found');
  assert.deepEqual(calls, [['answer', 'uid-7', '進める']]);
});

test('HTTP の期限で送信後の結果不明を読み取りタイムアウトに置き換えない', async t => {
  const calls: string[][] = [];
  const write: OrcaWrite = {
    ...fakeWrite(calls),
    async start(id, text, signal) {
      calls.push(['start', id, text]);
      if (!signal.aborted) await once(signal, 'abort');
      throw new OrcaWriteError('unknown_result');
    }
  };
  const post = await writeApi(t, write, orcaBin, 250);
  const response = await post(chats);
  assert.equal(response.status, 502);
  assert.equal((await response.json()).error, 'unknown_result');
  assert.deepEqual(calls, [['start', 'main', '進める']]);
});
