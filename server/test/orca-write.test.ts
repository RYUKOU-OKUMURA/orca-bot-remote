import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { chmodSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test, type TestContext } from 'node:test';
import {
  createOrcaWrite, messageParams, readDevice, withOrcaDevice
} from '../src/orca-write.ts';
import {
  createMobileE2EEV2Fixture, MOBILE_E2EE_V2_VECTOR
} from './fixtures/orca-protocol/mobile-e2ee-v2-fixtures.cjs';
import { createFixedKeyLegacyHandshakeForTest } from './fixtures/orca-protocol/paired-e2ee-fixed-key-test-fixture.cjs';
import { structuredAgentSessionPayloadFingerprint } from '../vendor/orca/shared/agent-session/lifecycle/operations/structured-agent-session-mutation.js';
import {
  encodeMobileE2EEV2Transcript, validateMobileE2EEV2Handshake
} from '../vendor/orca/shared/remote-runtime/e2ee/mobile-e2ee-v2-contract.js';
import { NODE_PAIRED_E2EE_PRIMITIVES as crypto } from '../vendor/orca/shared/remote-runtime/e2ee/node/paired-e2ee-node-primitives.js';
import { device, drop, installFakeHost } from './fixtures/orca-protocol/fake-host.ts';

test('Orca の E2EE v2 フィクスチャと鍵導出が一致する', () => {
  const { hello, ready, sharedSecret } = createMobileE2EEV2Fixture();
  const handshake = validateMobileE2EEV2Handshake(hello, ready);
  assert.ok(handshake);
  const transcript = encodeMobileE2EEV2Transcript(handshake);
  const keys = crypto.deriveV2KeySchedule({
    sharedSecret, transcript, clientNonce: handshake.clientNonce, desktopNonce: handshake.desktopNonce
  });
  const hex = (value: Uint8Array) => Buffer.from(value).toString('hex');
  assert.deepEqual({
    transcriptLength: transcript.length,
    transcriptHashHex: hex(keys.transcriptHash),
    mobileToDesktopKeyHex: hex(keys.mobileToDesktopKey),
    desktopToMobileKeyHex: hex(keys.desktopToMobileKey),
    sessionIdHex: hex(keys.sessionId)
  }, MOBILE_E2EE_V2_VECTOR);
});

test('Orca の固定鍵フィクスチャで暗号化フレームを相互に開ける', () => {
  const key = new Uint8Array(32).fill(3);
  const { channel } = createFixedKeyLegacyHandshakeForTest(key);
  assert.equal(crypto.openLegacyText(channel.sealText('入力'), key), '入力');
  assert.equal(channel.openText(crypto.sealLegacyText('返信', key)), '返信');
  assert.equal(channel.openText(crypto.sealLegacyText('別の鍵', new Uint8Array(32))), null);
});

test('操作 ID の形式と payloadFingerprint が Orca と一致する', () => {
  const sessionId = 'a0b0';
  const text = '入力😀\n"引用"';
  const params = messageParams(sessionId, text, 7);
  const second = messageParams(sessionId, text, 7);
  assert.match(params.envelope.clientOperationId, /^\d{13}-[0-9a-f]{32}$/);
  assert.notEqual(params.envelope.clientOperationId, second.envelope.clientOperationId);
  assert.equal(params.envelope.expectedRuntimeFence, 7);
  const canonical = `{"fields":{"body":{"blocks":[{"text":${JSON.stringify(text)},"type":"text"}],"kind":"message","role":"user"}},"method":"agentSession.send","sessionId":"a0b0"}`;
  assert.equal(params.envelope.payloadFingerprint, createHash('sha256').update(canonical).digest('hex'));
  assert.equal(params.envelope.payloadFingerprint, structuredAgentSessionPayloadFingerprint({
    method: 'agentSession.send', sessionId, fields: { body: params.body }
  }));
});

function deviceFile(t: TestContext) {
  const dir = mkdtempSync(join(tmpdir(), 'orca-write-test-'));
  t.after(() => rmSync(dir, { recursive: true }));
  const file = join(dir, 'device.json');
  writeFileSync(file, JSON.stringify(device), { mode: 0o600 });
  return file;
}

test('資格情報は権限600の通常ファイルと、限定した接続先だけを受け付ける', t => {
  const file = deviceFile(t);
  assert.deepEqual(readDevice(file), device);
  chmodSync(file, 0o644);
  assert.throws(() => readDevice(file), { code: 'write_unavailable' });
  chmodSync(file, 0o600);
  symlinkSync(file, file + '.link');
  assert.throws(() => readDevice(file + '.link'), { code: 'write_unavailable' });
  writeFileSync(file, JSON.stringify({ ...device, endpoint: 'ws://example.invalid:6768' }));
  assert.throws(() => readDevice(file), { code: 'write_unavailable' });
});

function fakeHost(t: TestContext, ...args: Parameters<typeof installFakeHost>) {
  const host = installFakeHost(...args);
  t.after(host.restore);
  return host;
}

test('新規チャットは同じ認証済み接続で履歴の fence を取得して1回だけ送る', async t => {
  const chatId = randomUUID();
  const host = fakeHost(t, request => {
    switch (request.method) {
      case 'bots.session.start':
        assert.deepEqual(request.params, { botId: 'main' });
        return { result: { sessionId: chatId } };
      case 'agentSession.history':
        assert.deepEqual(request.params, { sessionId: chatId, direction: 'tail', limit: 1 });
        return { result: { ok: true, page: { fence: 15 } } };
      case 'agentSession.send': {
        const { body, envelope } = request.params as ReturnType<typeof messageParams>;
        assert.deepEqual(body, { kind: 'message', role: 'user', blocks: [{ type: 'text', text: '入力' }] });
        assert.equal(envelope.sessionId, chatId);
        assert.equal(envelope.expectedRuntimeFence, 15);
        assert.equal(envelope.payloadFingerprint, structuredAgentSessionPayloadFingerprint({
          method: request.method, sessionId: chatId, fields: { body }
        }));
        return { result: { ok: true, value: {
          clientMessageId: envelope.clientOperationId, submission: { reason: null }
        } } };
      }
      default: throw new Error('unexpected RPC');
    }
  });
  assert.equal(await createOrcaWrite(deviceFile(t)).start('main', '入力', AbortSignal.timeout(1000)), chatId);
  assert.deepEqual(host.calls.map(call => call.method), ['bots.session.start', 'agentSession.history', 'agentSession.send']);
  assert.equal(host.connections(), 1);
  assert.equal(host.closes(), 1);
});

test('既存チャットはルート ID を解決して後継の fence と ID で送る', async t => {
  const root = randomUUID();
  const successor = randomUUID();
  const host = fakeHost(t, ({ method, params }) => {
    switch (method) {
      case 'orchestration.partyLocation':
        assert.deepEqual(params, { address: `orca_session_id:${root}` });
        return { result: { location: { kind: 'chat', sessionId: successor } } };
      case 'agentSession.history':
        assert.deepEqual(params, { sessionId: successor, direction: 'tail', limit: 1 });
        return { result: { ok: true, page: { fence: 21 } } };
      case 'agentSession.send': {
        const { envelope } = params as ReturnType<typeof messageParams>;
        assert.equal(envelope.sessionId, successor);
        assert.equal(envelope.expectedRuntimeFence, 21);
        return { result: { ok: true, value: {
          clientMessageId: envelope.clientOperationId, submission: { reason: null }
        } } };
      }
      default: throw new Error('unexpected RPC');
    }
  });
  await createOrcaWrite(deviceFile(t)).send(root, '入力', AbortSignal.timeout(1000));
  assert.deepEqual(host.calls.map(call => call.method), [
    'orchestration.partyLocation', 'agentSession.history', 'agentSession.send'
  ]);
  assert.equal(host.connections(), 1);
  assert.equal(host.closes(), 1);
});

test('応答前の切断は読み取りなら orca_failed、書き込みなら unknown_result で再送しない', async t => {
  const host = fakeHost(t, () => drop);
  const signal = AbortSignal.timeout(1000);
  const write = createOrcaWrite(deviceFile(t));
  await assert.rejects(write.send('chat', '入力', signal), { code: 'orca_failed' });
  await assert.rejects(write.answer('uid-7', '回答', signal), { code: 'unknown_result' });
  assert.deepEqual(host.calls.map(call => [call.method, call.params]), [
    ['orchestration.partyLocation', { address: 'orca_session_id:chat' }],
    ['bots.items.owner.comment', { item: 'uid-7', text: '回答' }]
  ]);
  assert.equal(host.connections(), 2);
  assert.equal(host.closes(), 2);
});

test('偽ホストの確定拒否と結果不明の応答を区別して再送しない', async t => {
  const cases = [
    ['not_found', 'not_found'],
    ['invalid_argument', 'orca_failed'],
    ['forbidden', 'orca_failed'],
    ['bots_disabled', 'orca_failed'],
    ['operation_unknown', 'unknown_result'],
    ['runtime_error', 'unknown_result']
  ];
  let code = '';
  const host = fakeHost(t, ({ method }) => {
    if (method === 'orchestration.partyLocation') return { result: { location: { kind: 'chat', sessionId: 'session' } } };
    if (method === 'agentSession.history') return { result: { ok: true, page: { fence: 1 } } };
    if (method === 'agentSession.send') {
      return { error: { code: 'runtime_error', data: { refusal: { code: 'structured_agent_session_unsupported' } } } };
    }
    return { error: { code } };
  });
  const write = createOrcaWrite(deviceFile(t));
  for (const [reason, expected] of cases) {
    code = reason;
    await assert.rejects(write.answer('uid-7', '回答', AbortSignal.timeout(1000)), { code: expected });
  }
  code = 'bot_not_found';
  await assert.rejects(write.start('main', '入力', AbortSignal.timeout(1000)), { code: 'not_found' });
  code = 'bots_disabled';
  await assert.rejects(write.start('main', '入力', AbortSignal.timeout(1000)), { code: 'orca_failed' });
  await assert.rejects(write.send('chat', '入力', AbortSignal.timeout(1000)), { code: 'orca_failed' });
  assert.deepEqual(host.calls.map(call => call.method), [
    ...cases.map(() => 'bots.items.owner.comment'), 'bots.session.start', 'bots.session.start',
    'orchestration.partyLocation', 'agentSession.history', 'agentSession.send'
  ]);
  assert.equal(host.connections(), cases.length + 3);
  assert.equal(host.closes(), host.connections());
});

test('認証済みホストからのトークン拒否では RPC を送らない', async t => {
  const host = fakeHost(t, () => { throw new Error('unexpected RPC'); }, false);
  const signal = AbortSignal.timeout(1000);
  await assert.rejects(withOrcaDevice(device, signal, call => call('status.get')), { code: 'write_unavailable' });
  assert.deepEqual(host.calls, []);
  assert.equal(host.closes(), 1);
});

test('新規チャットは開始後に選択肢を確かめて model と effort を設定してから送る', async t => {
  const chatId = randomUUID();
  let refuse = false;
  const host = fakeHost(t, ({ method, params }) => {
    switch (method) {
      case 'bots.session.start':
        assert.deepEqual(params, { botId: 'main' });
        return { result: { sessionId: chatId } };
      case 'agentSession.options':
        return { result: { current: {}, models: [
          { id: 'opus', label: 'Opus', isDefault: true, efforts: [{ value: 'high', label: 'High' }] },
          { id: 'fable', label: 'Fable', efforts: [{ value: 'max', label: 'Max' }] }
        ] } };
      case 'agentSession.history':
        return { result: { ok: true, page: { fence: 3 } } };
      case 'agentSession.setOption': {
        const { envelope, key, value } = params as { envelope: { sessionId: string; payloadFingerprint: string }; key: string; value: string };
        assert.equal(envelope.sessionId, chatId);
        assert.equal(envelope.payloadFingerprint, structuredAgentSessionPayloadFingerprint({
          method, sessionId: chatId, fields: { key, value }
        }));
        if (refuse) return { result: { ok: false, refusal: { code: 'invalid_option' } } };
        return { result: { ok: true, value: { key, value } } };
      }
      case 'agentSession.send': {
        const { envelope } = params as ReturnType<typeof messageParams>;
        return { result: { ok: true, value: { clientMessageId: envelope.clientOperationId, submission: { reason: null } } } };
      }
      default: throw new Error('unexpected RPC');
    }
  });
  const write = createOrcaWrite(deviceFile(t));
  const signal = () => AbortSignal.timeout(1000);
  assert.equal(await write.start('main', '入力', signal(), [], { model: 'fable', effort: 'max' }), chatId);
  assert.deepEqual(host.calls.map(call => [call.method, (call.params as { key?: string }).key]), [
    ['bots.session.start', undefined], ['agentSession.options', undefined],
    ['agentSession.history', undefined], ['agentSession.setOption', 'model'],
    ['agentSession.history', undefined], ['agentSession.setOption', 'effort'],
    ['agentSession.history', undefined], ['agentSession.send', undefined]
  ]);

  // effort alone is checked against the session's current (or default) model.
  host.calls.length = 0;
  await write.start('main', '入力', signal(), [], { effort: 'high' });
  assert.deepEqual(host.calls.filter(call => call.method === 'agentSession.setOption').map(call => call.params!.value), ['high']);

  for (const choice of [{ model: 'missing' }, { effort: 'max' }, { model: 'opus', effort: 'max' }]) {
    host.calls.length = 0;
    await assert.rejects(write.start('main', '入力', signal(), [], choice), { message: 'モデルまたは推論の強さを選択肢から選び直してください。' });
    assert.deepEqual(host.calls.map(call => call.method), ['bots.session.start', 'agentSession.options']);
  }

  // Orca refusing the option after the chat exists must not invite a retry that opens another chat.
  refuse = true;
  host.calls.length = 0;
  await assert.rejects(write.start('main', '入力', signal(), [], { model: 'fable' }), { code: 'unknown_result' });
  assert.ok(!host.calls.some(call => call.method === 'agentSession.send'));
});

test('エージェント指定の新規チャットは agent を渡し、model と effort が無ければ選択肢を読まずに送る', async t => {
  const chatId = randomUUID();
  const host = fakeHost(t, ({ method, params }) => {
    switch (method) {
      case 'bots.session.start':
        assert.deepEqual(params, { botId: 'main', agent: 'hermes' });
        return { result: { sessionId: chatId } };
      case 'agentSession.history':
        return { result: { ok: true, page: { fence: 1 } } };
      case 'agentSession.send': {
        const { envelope } = params as ReturnType<typeof messageParams>;
        return { result: { ok: true, value: { clientMessageId: envelope.clientOperationId, submission: { reason: null } } } };
      }
      default: throw new Error('unexpected RPC');
    }
  });
  assert.equal(await createOrcaWrite(deviceFile(t)).start('main', '入力', AbortSignal.timeout(1000), [], { agent: 'hermes' }), chatId);
  assert.deepEqual(host.calls.map(call => call.method), ['bots.session.start', 'agentSession.history', 'agentSession.send']);
});

test('エージェント一覧とモデル一覧は Orca の RPC から読む', async t => {
  const catalog = { origin: 'probe', models: [{ id: 'gpt', label: 'GPT', efforts: [] }] };
  let agents: unknown = ['claude', 'codex'];
  fakeHost(t, ({ method, params }) => {
    if (method === 'bots.agentChoices') {
      assert.deepEqual(params, {});
      return { result: { agents } };
    }
    assert.equal(method, 'agentSession.modelCatalog');
    assert.deepEqual(params, { agent: 'codex', waitForListing: true });
    return { result: catalog };
  });
  const write = createOrcaWrite(deviceFile(t));
  assert.deepEqual(await write.agentChoices(AbortSignal.timeout(1000)), ['claude', 'codex']);
  assert.deepEqual(await write.modelCatalog('codex', AbortSignal.timeout(1000)), catalog);
  agents = ['claude', 1];
  await assert.rejects(write.agentChoices(AbortSignal.timeout(1000)), { code: 'orca_failed' });
});

test('モデル選択肢はチャットの現在のセッションから読む', async t => {
  const options = { current: { model: 'm' }, models: [{ id: 'm', label: 'M', efforts: [] }] };
  fakeHost(t, ({ method, params }) => {
    if (method === 'orchestration.partyLocation') return { result: { location: { kind: 'chat', sessionId: 'successor' } } };
    assert.equal(method, 'agentSession.options');
    assert.deepEqual(params, { sessionId: 'successor' });
    return { result: options };
  });
  assert.deepEqual(await createOrcaWrite(deviceFile(t)).options('root', AbortSignal.timeout(1000)), options);
});
