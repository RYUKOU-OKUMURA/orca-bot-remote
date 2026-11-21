import assert from 'node:assert/strict';
import { once } from 'node:events';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import type { AddressInfo } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, before, test, type TestContext } from 'node:test';
import { fileURLToPath } from 'node:url';
import { readConfig } from '../src/config.ts';
import { createApi } from '../src/http.ts';

const orcaBin = fileURLToPath(new URL('./fixtures/orca', import.meta.url));
const token = 'test-token';
const bot = encodeURIComponent('メインBot');
const other = encodeURIComponent('専門Bot');
const chat = '12345678-abcd-1234-abcd-123456789abc';
const messages = `/api/chats/${chat}/messages`;

let tokenDir: string;
before(() => {
  tokenDir = mkdtempSync(join(tmpdir(), 'orca-bot-remote-api-test-'));
  writeFileSync(join(tokenDir, 'token'), token, { mode: 0o600 });
});
after(() => rmSync(tokenDir, { recursive: true }));

async function openApi(t: TestContext, summaryId?: string) {
  const server = createApi(readConfig({
    HOST: '100.113.115.59', TOKEN_FILE: join(tokenDir, 'token'),
    ORCA_BIN: orcaBin, SUMMARY_AUTOMATION_ID: summaryId
  }));
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(async () => {
    server.close();
    server.closeAllConnections();
    await once(server, 'close');
  });
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

test('メッセージとツールを元の順序と契約の項目で返す', async t => {
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
    summary: { runAt: 300, content: '状況まとめ', chatId: 'home' }
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
    '/api/chats/not-a-uuid/messages', '/api/chats/00000000-0000-0000-0000-000000000000/messages',
    '/api/items/unknown/7', `/api/items/${other}/7`, `/api/items/${bot}/0`, `/api/items/${bot}/-1`,
    `/api/items/${bot}/7.1`, `/api/items/${bot}/9007199254740992`, `/api/items/${bot}/3`
  ]) {
    const result = await get(path, 404);
    assert.equal(result.error, 'not_found');
    assert.equal(typeof result.message, 'string');
  }
});

test('既知パスの GET 以外は 405', async t => {
  const get = await openApi(t);
  assert.equal((await get('/api/bots', 405, undefined, 'POST')).error, 'bad_request');
});

test('Orca CLI が失敗したら本文を漏らさず 502', async t => {
  const get = await openApi(t, 'failed');
  assert.deepEqual(await get('/api/summary', 502), {
    error: 'orca_failed', message: 'Orca の読み取りに失敗しました。'
  });
});
