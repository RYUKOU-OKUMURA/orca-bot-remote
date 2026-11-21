import assert from 'node:assert/strict';
import type { Device } from '../../../src/orca-write.ts';
import { pairedE2EEV2TokenAuth } from '../../../vendor/orca/shared/remote-runtime/e2ee/paired-e2ee-client-handshake.js';
import {
  encodeMobileE2EEV2Transcript, validateMobileE2EEV2Handshake
} from '../../../vendor/orca/shared/remote-runtime/e2ee/mobile-e2ee-v2-contract.js';
import {
  openMobileE2EEV2Frame, sealMobileE2EEV2Frame
} from '../../../vendor/orca/shared/remote-runtime/e2ee/mobile-e2ee-v2-framing.js';
import { NODE_PAIRED_E2EE_PRIMITIVES as crypto } from '../../../vendor/orca/shared/remote-runtime/e2ee/node/paired-e2ee-node-primitives.js';

const hostKey = crypto.generateKeyPair();
export const device: Device = {
  endpoint: 'ws://127.0.0.1:6768',
  publicKeyB64: crypto.publicKeyToBase64(hostKey.publicKey),
  deviceToken: 'ab'.repeat(24)
};

type Rpc = { id: string; method: string; params?: Record<string, unknown> };
type Reply = { result: unknown } | { error: { code: string; data?: unknown } };
export const drop = Symbol('drop');
export const hold = Symbol('hold');

export function installFakeHost(handle: (request: Rpc) => Reply | typeof drop | typeof hold, authorized = true) {
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
      else if (reply !== hold) this.message(this.encrypt({ id: body.id, ok: 'result' in reply, ...reply }));
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
  return {
    calls, connections: () => connections, closes: () => closes,
    restore: () => Object.defineProperty(globalThis, 'WebSocket', descriptor)
  };
}
