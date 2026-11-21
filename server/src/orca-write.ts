import { randomUUID } from 'node:crypto';
import { closeSync, constants, fstatSync, openSync, readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { attachmentKind, storePath, type MessageExtras, type Upload, type Uploaded } from './attachments.ts';
import { commandMenu, type Command, type CommandReport, type SessionOptions } from './commands.ts';
import { isTailscaleAddress } from './config.ts';
import { createPairedE2EEClientHandshake } from '../vendor/orca/shared/remote-runtime/e2ee/paired-e2ee-client-handshake.js';
import { NODE_PAIRED_E2EE_PRIMITIVES } from '../vendor/orca/shared/remote-runtime/e2ee/node/paired-e2ee-node-primitives.js';
import {
  createStructuredAgentSessionOperationId, structuredAgentSessionPayloadFingerprint
} from '../vendor/orca/shared/agent-session/lifecycle/operations/structured-agent-session-mutation.js';

export const DEVICE_FILE = join(homedir(), '.config/orca-bot-remote/orca-device.json');

export type Device = { endpoint: string; publicKeyB64: string; deviceToken: string };
export type OrcaWrite = {
  start: (botId: string, text: string, signal: AbortSignal, uploads?: Upload[]) => Promise<string>;
  send: (chatId: string, text: string, signal: AbortSignal, extras?: MessageExtras) => Promise<void>;
  commands: (chatId: string, signal: AbortSignal) => Promise<ReturnType<typeof commandMenu>>;
  command: (chatId: string, command: Command, signal: AbortSignal) => Promise<void>;
  upload: (chatId: string, upload: Upload, signal: AbortSignal) => Promise<Uploaded>;
  answer: (uid: string, text: string, signal: AbortSignal) => Promise<void>;
};

export class OrcaWriteError extends Error {
  readonly code: 'write_unavailable' | 'orca_failed' | 'unknown_result' | 'not_found';

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

function mutationEnvelope(method: string, sessionId: string, expectedRuntimeFence: number, fields: object) {
  return {
    sessionId, expectedRuntimeFence,
    clientOperationId: createStructuredAgentSessionOperationId(randomUUID),
    payloadFingerprint: structuredAgentSessionPayloadFingerprint({ method, sessionId, fields })
  };
}

export function messageParams(sessionId: string, text: string, fence: number, extras: MessageExtras = {}) {
  const attachments = extras.attachments ?? [];
  const fileRefs = attachments.filter(a => a.kind === 'file').map(({ path }) => {
    if (!/[\s@]/.test(path)) return '@' + path;
    if (path.includes('"') && !path.includes("'")) return "@'" + path + "'";
    return '@"' + path.replaceAll('"', '\\"') + '"';
  });
  const content = [text, ...fileRefs].filter(value => value.trim()).join('\n');
  const body = {
    kind: 'message', role: 'user',
    blocks: [
      ...(content ? [{ type: 'text', text: content }] : []),
      ...attachments.filter(a => a.kind === 'image').map(a => ({
        type: 'image-ref', path: a.path, ...(a.name ? { alt: a.name } : {})
      }))
    ],
    ...(extras.skills?.length ? { skills: extras.skills } : {})
  };
  return { envelope: mutationEnvelope('agentSession.send', sessionId, fence, { body }), body };
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
      if (code === 'not_found' && ['agentSession.commands', 'agentSession.options', 'agentSession.setOption',
        'agentSession.conversationCommand'].includes(method)) throw new OrcaWriteError('not_found');
      if ((method === 'bots.items.owner.comment' && code === 'not_found') ||
          (method === 'bots.session.start' && code === 'bot_not_found')) throw new OrcaWriteError('not_found');
      if ((method === 'bots.session.start' || method === 'bots.items.owner.comment') &&
          code === 'bots_disabled') throw new OrcaWriteError('orca_failed');
      if (method.startsWith('agentSession.') && code === 'runtime_error' &&
          reply.error.data?.refusal?.code === 'structured_agent_session_unsupported') throw new OrcaWriteError('orca_failed');
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

async function runtimeFence(call: Call, sessionId: string) {
  const history = await call<{ ok: boolean; page?: { fence: number } }>(
    'agentSession.history', { sessionId, direction: 'tail', limit: 1 }
  );
  const fence = history?.page?.fence;
  if (history?.ok !== true || typeof fence !== 'number' || !Number.isSafeInteger(fence) || fence < 1) {
    throw new OrcaWriteError('orca_failed');
  }
  return fence;
}

type MutationOutcome = { ok: boolean; refusal?: { code?: string; details?: { reason?: string } } };

function unsuccessful(result: MutationOutcome | undefined): never {
  const refusal = result?.refusal;
  const rejected = result?.ok === false && typeof refusal?.code === 'string' &&
    (refusal.code !== 'agent_session_operation_unknown' || refusal.details?.reason === 'rewindUnconfirmed');
  throw new OrcaWriteError(rejected ? 'orca_failed' : 'unknown_result');
}

async function sendMessage(call: Call, sessionId: string, text: string, extras?: MessageExtras) {
  const params = messageParams(sessionId, text, await runtimeFence(call, sessionId), extras);
  const result = await call<{
    ok: boolean;
    value?: { clientMessageId?: string; submission?: { reason?: string }; queued?: { messageId?: string } };
    refusal?: { code?: string; details?: { reason?: string } };
  }>('agentSession.send', params, true);
  const receipt = result?.value;
  if (result?.ok === true && receipt?.clientMessageId === params.envelope.clientOperationId &&
      ((receipt.submission && receipt.submission.reason !== 'durable_send_submission_missing') ||
        receipt.queued?.messageId)) return;
  unsuccessful(result);
}

async function uploadFile(device: Device, call: Call, sessionId: string, upload: Upload): Promise<Uploaded> {
  const { name, byteLength, mimeType, chunks } = upload;
  const start = await call<{ uploadId: string }>('agentSessionAttachment.uploadStart', { sessionId, name, byteLength }, true);
  if (typeof start?.uploadId !== 'string' || !start.uploadId) throw new OrcaWriteError('unknown_result');
  const { uploadId } = start;
  try {
    let offset = 0;
    let kind: Uploaded['kind'] = 'file';
    for await (const chunk of chunks) {
      if (offset === 0) kind = attachmentKind(name, mimeType, chunk);
      const reply = await call<{ receivedBytes: number }>('agentSessionAttachment.uploadAppend', {
        uploadId, offset, contentBase64: chunk.toString('base64')
      }, true);
      offset += chunk.length;
      if (reply?.receivedBytes !== offset) throw new OrcaWriteError('unknown_result');
    }
    if (offset !== byteLength) throw new OrcaWriteError('orca_failed');
    const result = await call<Uploaded>('agentSessionAttachment.uploadCommit', { uploadId }, true);
    if (!storePath(result?.path) || !result.path.endsWith('/' + uploadId + '/' + name) ||
        result.name !== name || result.byteLength !== byteLength) throw new OrcaWriteError('unknown_result');
    return { path: result.path, name, kind, byteLength };
  } catch (error) {
    // The original socket may be closed or have an unmatched reply after cancellation.
    await withOrcaDevice(device, AbortSignal.timeout(3000), cleanup =>
      cleanup('agentSessionAttachment.uploadAbort', { uploadId })).catch(() => {});
    throw error;
  }
}

export function createOrcaWrite(file = DEVICE_FILE): OrcaWrite {
  function withChat<T>(chatId: string, signal: AbortSignal, run: (call: Call, sessionId: string, device: Device) => Promise<T>) {
    const device = readDevice(file);
    return withOrcaDevice(device, signal, async call => {
      const result = await call<{ location: { kind: string; sessionId?: string } | null; lost?: string }>(
        'orchestration.partyLocation', { address: `orca_session_id:${chatId}` }
      );
      const location = result?.location;
      if (location?.kind !== 'chat' || typeof location.sessionId !== 'string' || !location.sessionId) {
        throw new OrcaWriteError(location === null && result.lost === 'chat' ? 'not_found' : 'orca_failed');
      }
      return run(call, location.sessionId, device);
    });
  }
  return {
    async start(botId, text, signal, uploads = []) {
      const device = readDevice(file);
      return withOrcaDevice(device, signal, async call => {
        const result = await call<{ sessionId: string }>('bots.session.start', { botId }, true);
        if (typeof result?.sessionId !== 'string' || !result.sessionId) throw new OrcaWriteError('unknown_result');
        const attachments = [];
        for (const upload of uploads) attachments.push(await uploadFile(device, call, result.sessionId, upload));
        await sendMessage(call, result.sessionId, text, { attachments });
        return result.sessionId;
      });
    },
    send(chatId, text, signal, extras) {
      return withChat(chatId, signal, (call, id) => sendMessage(call, id, text, extras));
    },
    upload(chatId, upload, signal) {
      return withChat(chatId, signal, (call, id, device) => uploadFile(device, call, id, upload));
    },
    commands(chatId, signal) {
      return withChat(chatId, signal, async (call, sessionId) => {
        const report = await call<CommandReport>('agentSession.commands', { sessionId });
        const options = await call<SessionOptions>('agentSession.options', { sessionId });
        return commandMenu(report, options);
      });
    },
    command(chatId, { name, argument }, signal) {
      return withChat(chatId, signal, async (call, sessionId) => {
        const option = name === 'model' || name === 'effort';
        const method = option ? 'agentSession.setOption' : 'agentSession.conversationCommand';
        const fields = option ? { key: name, value: argument } : { command: name, ...(argument ? { instructions: argument } : {}) };
        const envelope = mutationEnvelope(method, sessionId, await runtimeFence(call, sessionId), fields);
        const result = await call<MutationOutcome & {
          value?: { key?: string; value?: string; command?: string; state?: string; error?: string; operationId?: string }
        }>(method, { envelope, ...fields }, true);
        const receipt = result?.value;
        if (result?.ok !== true || !receipt) unsuccessful(result);
        if (option && receipt.key === name && receipt.value === argument) return;
        if (!option && receipt.command === name && receipt.state === 'completed' &&
            (receipt.operationId === undefined || receipt.operationId === envelope.clientOperationId)) {
          if (receipt.error !== undefined) throw new OrcaWriteError('orca_failed');
          return;
        }
        throw new OrcaWriteError('unknown_result');
      });
    },
    async answer(uid, text, signal) {
      await withOrcaDevice(readDevice(file), signal, async call => {
        const result = await call<{ eventId: string }>('bots.items.owner.comment', { item: uid, text }, true);
        if (typeof result?.eventId !== 'string' || !result.eventId) throw new OrcaWriteError('unknown_result');
      });
    }
  };
}
