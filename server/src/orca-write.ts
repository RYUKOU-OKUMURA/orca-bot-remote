import { randomUUID } from 'node:crypto';
import { closeSync, constants, fstatSync, openSync, readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { isTailscaleAddress } from './config.ts';
import { createPairedE2EEClientHandshake } from '../vendor/orca/shared/remote-runtime/e2ee/paired-e2ee-client-handshake.js';
import { NODE_PAIRED_E2EE_PRIMITIVES } from '../vendor/orca/shared/remote-runtime/e2ee/node/paired-e2ee-node-primitives.js';
import {
  createStructuredAgentSessionOperationId, structuredAgentSessionPayloadFingerprint
} from '../vendor/orca/shared/agent-session/lifecycle/operations/structured-agent-session-mutation.js';

export const DEVICE_FILE = join(homedir(), '.config/orca-bot-remote/orca-device.json');

export type Device = { endpoint: string; publicKeyB64: string; deviceToken: string };
export type OrcaWrite = {
  start: (botId: string, text: string, signal: AbortSignal) => Promise<string>;
  send: (chatId: string, text: string, signal: AbortSignal) => Promise<void>;
  answer: (uid: string, text: string, signal: AbortSignal) => Promise<void>;
};

export class OrcaWriteError extends Error {
  readonly code: 'write_unavailable' | 'orca_failed' | 'unknown_result';

  constructor(code: OrcaWriteError['code']) {
    super(code);
    this.code = code;
  }
}

export function validDevice(value: unknown): value is Device {
  if (!value || typeof value !== 'object') return false;
  const { endpoint, publicKeyB64, deviceToken } = value as Partial<Device>;
  if (typeof endpoint !== 'string' || typeof publicKeyB64 !== 'string' || typeof deviceToken !== 'string') return false;
  if (!/^[a-f0-9]{48,128}$/.test(deviceToken) || !/^[A-Za-z0-9+/]{43}=$/.test(publicKeyB64)) return false;
  if (Buffer.from(publicKeyB64, 'base64').toString('base64') !== publicKeyB64) return false;
  try {
    const url = new URL(endpoint);
    return url.protocol === 'ws:' && url.port !== '' &&
      (url.hostname === '127.0.0.1' || isTailscaleAddress(url.hostname)) &&
      !url.username && !url.password && url.pathname === '/' && !url.search && !url.hash;
  } catch {
    return false;
  }
}

export function readDevice(file = DEVICE_FILE): Device {
  try {
    const fd = openSync(file, constants.O_RDONLY | constants.O_NONBLOCK | constants.O_NOFOLLOW);
    try {
      const stat = fstatSync(fd);
      if (stat.isFile() && (stat.mode & 0o7777) === 0o600 && stat.uid === process.getuid?.() && stat.size <= 4096) {
        const device: unknown = JSON.parse(readFileSync(fd, 'utf8'));
        if (validDevice(device)) return device;
      }
    } finally {
      closeSync(fd);
    }
  } catch {
    // The file and parser errors may contain credentials.
  }
  throw new OrcaWriteError('write_unavailable');
}

const refusedRpcCodes = new Set(['method_not_found', 'method_not_supported', 'invalid_argument', 'forbidden']);
const maxFrameCharacters = 8 * 1024 * 1024 + 64;

export function messageParams(sessionId: string, text: string, expectedRuntimeFence: number) {
  const body = { kind: 'message', role: 'user', blocks: [{ type: 'text', text }] };
  return {
    envelope: {
      sessionId,
      clientOperationId: createStructuredAgentSessionOperationId(randomUUID),
      expectedRuntimeFence,
      payloadFingerprint: structuredAgentSessionPayloadFingerprint({
        method: 'agentSession.send', sessionId, fields: { body }
      })
    },
    body
  };
}

type Call = <T>(method: string, params?: object, mutates?: boolean) => Promise<T>;

export async function withOrcaDevice<T>(
  device: Device, signal: AbortSignal, run: (call: Call) => Promise<T>
): Promise<T> {
  const handshake = createPairedE2EEClientHandshake({
    primitives: NODE_PAIRED_E2EE_PRIMITIVES, hostPublicKeyB64: device.publicKeyB64,
    framing: 'v2', transport: 'direct', v2TokenCapabilities: true
  });
  signal = AbortSignal.any([signal, AbortSignal.timeout(45_000)]);
  let socket: WebSocket | undefined;
  try {
    signal.throwIfAborted();
    const ws = socket = new WebSocket(device.endpoint);

    // Install the listener before sending each frame. Only one RPC is in flight on this socket.
    function exchange(receive: 'open' | 'message', send?: () => void) {
      return new Promise<Event>((resolve, reject) => {
        const listeners = new AbortController();
        const opts = { once: true, signal: listeners.signal };
        function fail() {
          listeners.abort();
          reject(new OrcaWriteError('orca_failed'));
        }
        ws.addEventListener(receive, event => {
          listeners.abort();
          resolve(event);
        }, opts);
        ws.addEventListener('error', fail, opts);
        ws.addEventListener('close', fail, opts);
        signal.addEventListener('abort', fail, opts);
        if (signal.aborted || ws.readyState >= WebSocket.CLOSING) return fail();
        try {
          send?.();
        } catch {
          fail();
        }
      });
    }

    async function frame(outgoing: string, onSent?: () => void) {
      const event = await exchange('message', () => {
        if (ws.readyState !== WebSocket.OPEN) throw new OrcaWriteError('orca_failed');
        ws.send(outgoing);
        onSent?.();
      }) as MessageEvent;
      if (typeof event.data !== 'string' || event.data.length > maxFrameCharacters) {
        throw new OrcaWriteError('orca_failed');
      }
      return event.data as string;
    }

    await exchange('open');
    if (handshake.acceptReadyText(await frame(handshake.helloText())) !== 'accepted') {
      throw new OrcaWriteError('orca_failed');
    }
    const auth = await handshake.sealAuth({ kind: 'token', deviceToken: device.deviceToken }, {
      clientCapabilities: ['agent-session.structured.v1']
    });
    const authReply = handshake.classifyAuthReply(handshake.channel.openText(await frame(auth)) ?? '');
    if (authReply.kind !== 'authenticated') {
      const code = authReply.kind === 'rejected' ? authReply.errorCode : null;
      throw new OrcaWriteError(code === 'unauthorized' || code === 'device_revoked' ? 'write_unavailable' : 'orca_failed');
    }

    const call: Call = async (method, params, mutates = false) => {
      const id = randomUUID();
      const outgoing = handshake.channel.sealText(JSON.stringify({ id, method, params }));
      let sent = false;
      let reply;
      try {
        reply = JSON.parse(handshake.channel.openText(await frame(outgoing, () => { sent = true; })) ?? '');
        if (reply?.id !== id || typeof reply.ok !== 'boolean') throw new Error();
      } catch {
        throw new OrcaWriteError(sent && mutates ? 'unknown_result' : 'orca_failed');
      }
      if (reply.ok) return reply.result;
      const code = reply.error?.code;
      if (code === 'unauthorized' || code === 'device_revoked') throw new OrcaWriteError('write_unavailable');
      throw new OrcaWriteError(mutates && !refusedRpcCodes.has(code) ? 'unknown_result' : 'orca_failed');
    };
    return await run(call);
  } catch (error) {
    if (error instanceof OrcaWriteError) throw error;
    throw new OrcaWriteError('orca_failed');
  } finally {
    socket?.close();
  }
}

async function sendMessage(call: Call, sessionId: string, text: string) {
  const history = await call<{ ok: boolean; page?: { fence: number } }>(
    'agentSession.history', { sessionId, direction: 'tail', limit: 1 }
  );
  const fence = history?.page?.fence;
  if (history?.ok !== true || typeof fence !== 'number' || !Number.isSafeInteger(fence)) {
    throw new OrcaWriteError('orca_failed');
  }
  const params = messageParams(sessionId, text, fence);
  const result = await call<{
    ok: boolean;
    value?: { clientMessageId?: string; submission?: { reason?: string }; queued?: { messageId?: string } };
    refusal?: { code?: string; details?: { reason?: string } };
  }>('agentSession.send', params, true);
  const receipt = result?.value;
  if (result?.ok === true && receipt?.clientMessageId === params.envelope.clientOperationId &&
      ((receipt.submission && receipt.submission.reason !== 'durable_send_submission_missing') ||
        receipt.queued?.messageId)) return;
  const refusal = result?.refusal;
  const rejected = result?.ok === false && typeof refusal?.code === 'string' &&
    (refusal.code !== 'agent_session_operation_unknown' || refusal.details?.reason === 'rewindUnconfirmed');
  throw new OrcaWriteError(rejected ? 'orca_failed' : 'unknown_result');
}

export function createOrcaWrite(file = DEVICE_FILE): OrcaWrite {
  return {
    async start(botId, text, signal) {
      return withOrcaDevice(readDevice(file), signal, async call => {
        const result = await call<{ sessionId: string }>('bots.session.start', { botId }, true);
        if (typeof result?.sessionId !== 'string' || !result.sessionId) throw new OrcaWriteError('unknown_result');
        await sendMessage(call, result.sessionId, text);
        return result.sessionId;
      });
    },
    async send(chatId, text, signal) {
      await withOrcaDevice(readDevice(file), signal, call => sendMessage(call, chatId, text));
    },
    async answer(uid, text, signal) {
      await withOrcaDevice(readDevice(file), signal, async call => {
        const result = await call<{ eventId: string }>('bots.items.owner.comment', { item: uid, text }, true);
        if (typeof result?.eventId !== 'string' || !result.eventId) throw new OrcaWriteError('unknown_result');
      });
    }
  };
}
