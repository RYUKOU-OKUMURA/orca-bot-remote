import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { chmodSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test, type TestContext } from 'node:test';
import {
  createOrcaWrite, messageParams, readDevice, withOrcaDevice, type Device
} from '../src/orca-write.ts';
import {
  createMobileE2EEV2Fixture, MOBILE_E2EE_V2_VECTOR
} from './fixtures/orca-protocol/mobile-e2ee-v2-fixtures.cjs';
import { createFixedKeyLegacyHandshakeForTest } from './fixtures/orca-protocol/paired-e2ee-fixed-key-test-fixture.cjs';
import { structuredAgentSessionPayloadFingerprint } from '../vendor/orca/shared/agent-session/lifecycle/operations/structured-agent-session-mutation.js';
import { pairedE2EEV2TokenAuth } from '../vendor/orca/shared/remote-runtime/e2ee/paired-e2ee-client-handshake.js';
import {
  encodeMobileE2EEV2Transcript, validateMobileE2EEV2Handshake
} from '../vendor/orca/shared/remote-runtime/e2ee/mobile-e2ee-v2-contract.js';
import {
  openMobileE2EEV2Frame, sealMobileE2EEV2Frame
} from '../vendor/orca/shared/remote-runtime/e2ee/mobile-e2ee-v2-framing.js';
import { NODE_PAIRED_E2EE_PRIMITIVES as crypto } from '../vendor/orca/shared/remote-runtime/e2ee/node/paired-e2ee-node-primitives.js';

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

const hostKey = crypto.generateKeyPair();
const device: Device = {
  endpoint: 'ws://127.0.0.1:6768',
  publicKeyB64: crypto.publicKeyToBase64(hostKey.publicKey),
  deviceToken: 'ab'.repeat(24)
};

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

type Rpc = { id: string; method: string; params?: Record<string, unknown> };
type Reply = { result: unknown } | { error: { code: string } };
const drop = Symbol('drop');

function fakeHost(t: TestContext, handle: (request: Rpc) => Reply | typeof drop, authorized = true) {
  const calls: Rpc[] = [];
  let connections = 0;
  let closes = 0;
  const original = globalThis.WebSocket;
  class Socket extends EventTarget {
    static OPEN = original.OPEN;
    static CLOSING = original.CLOSING;
    readyState = original.CONNECTING;
    keys: ReturnType<typeof crypto.deriveV2KeySchedule> | undefined;
    received = 0n;
    sent = 0n;

    constructor(endpoint: string) {
      super();
      assert.equal(endpoint, device.endpoint);
      connections++;
      queueMicrotask(() => {
        this.readyState = original.OPEN;
        this.dispatchEvent(new Event('open'));
      });
    }

    message(data: string) {
      queueMicrotask(() => this.dispatchEvent(new MessageEvent('message', { data })));
    }

    encrypt(payload: object) {
      const keys = this.keys!;
      return Buffer.from(sealMobileE2EEV2Frame({
        payload: Buffer.from(JSON.stringify(payload)), key: keys.desktopToMobileKey, sessionId: keys.sessionId,
        direction: 'desktop-to-mobile', payloadKind: 'text', counter: this.sent++
      })).toString('base64');
    }

    send(data: string) {
      if (!this.keys) {
        const hello = JSON.parse(data);
        const ready = {
          type: 'e2ee_ready', v: 2, desktopPublicKeyB64: device.publicKeyB64,
          clientNonceB64: hello.clientNonceB64, desktopNonceB64: Buffer.alloc(32, 7).toString('base64'),
          selection: { framing: 2, payloadKinds: ['text', 'binary'] }, context: hello.context
        };
        const handshake = validateMobileE2EEV2Handshake(hello, ready);
        assert.ok(handshake);
        this.keys = crypto.deriveV2KeySchedule({
          sharedSecret: crypto.deriveSharedKey(hostKey.secretKey, handshake.clientPublicKey),
          transcript: encodeMobileE2EEV2Transcript(handshake),
          clientNonce: handshake.clientNonce, desktopNonce: handshake.desktopNonce
        });
        this.message(JSON.stringify(ready));
        return;
      }
      const decoded = openMobileE2EEV2Frame({
        frame: Buffer.from(data, 'base64'), key: this.keys.mobileToDesktopKey, sessionId: this.keys.sessionId,
        direction: 'mobile-to-desktop', payloadKind: 'text', expectedCounter: this.received++
      });
      assert.ok(decoded);
      const body = JSON.parse(Buffer.from(decoded).toString('utf8'));
      const transcript = Buffer.from(this.keys.transcriptHash).toString('base64');
      if (body.type === 'e2ee_auth') {
        assert.deepEqual(body, {
          ...pairedE2EEV2TokenAuth(transcript, device.deviceToken),
          clientCapabilities: ['agent-session.structured.v1']
        });
        this.message(this.encrypt(authorized
          ? { type: 'e2ee_authenticated', v: 2, transcriptHashB64: transcript }
          : { type: 'e2ee_error', error: { code: 'unauthorized' } }));
        return;
      }
      calls.push(body);
      const reply = handle(body);
      if (reply === drop) queueMicrotask(() => this.close());
      else this.message(this.encrypt({ id: body.id, ok: 'result' in reply, ...reply }));
    }

    close() {
      if (this.readyState === original.CLOSED) return;
      this.readyState = original.CLOSED;
      closes++;
      this.dispatchEvent(new Event('close'));
    }
  }
  const descriptor = Object.getOwnPropertyDescriptor(globalThis, 'WebSocket')!;
  Object.defineProperty(globalThis, 'WebSocket', { configurable: true, value: Socket });
  t.after(() => Object.defineProperty(globalThis, 'WebSocket', descriptor));
  return { calls, connections: () => connections, closes: () => closes };
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

test('応答前の切断は読み取りなら orca_failed、書き込みなら unknown_result で再送しない', async t => {
  const host = fakeHost(t, () => drop);
  const signal = AbortSignal.timeout(1000);
  const write = createOrcaWrite(deviceFile(t));
  await assert.rejects(write.send('chat', '入力', signal), { code: 'orca_failed' });
  await assert.rejects(write.answer('uid-7', '回答', signal), { code: 'unknown_result' });
  assert.deepEqual(host.calls.map(call => [call.method, call.params]), [
    ['agentSession.history', { sessionId: 'chat', direction: 'tail', limit: 1 }],
    ['bots.items.owner.comment', { item: 'uid-7', text: '回答' }]
  ]);
  assert.equal(host.connections(), 2);
  assert.equal(host.closes(), 2);
});

test('認証済みホストからのトークン拒否では RPC を送らない', async t => {
  const host = fakeHost(t, () => { throw new Error('unexpected RPC'); }, false);
  const signal = AbortSignal.timeout(1000);
  await assert.rejects(withOrcaDevice(device, signal, call => call('status.get')), { code: 'write_unavailable' });
  assert.deepEqual(host.calls, []);
  assert.equal(host.closes(), 1);
});
