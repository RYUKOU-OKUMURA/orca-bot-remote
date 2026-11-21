import { randomUUID } from 'node:crypto';
import { open, realpath, stat, unlink } from 'node:fs/promises';
import type { IncomingMessage } from 'node:http';
import { homedir, tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  AGENT_SESSION_ATTACHMENT_CHUNK_BYTES as chunkSize, AGENT_SESSION_ATTACHMENTS_DIR_NAME,
  agentSessionAttachmentAppends, base64DecodedByteLength, sanitizeAgentSessionAttachmentName
} from '../vendor/orca/shared/agent-session/agent-session-attachments.js';

export const maxUploadBytes = 20 * 1024 * 1024;
export const jsonLimit = 64 * 1024;
export const inlineJsonLimit = jsonLimit + 4 * Math.ceil(maxUploadBytes / 3) + 16;
export const uuid = /^[\da-f]{8}(?:-[\da-f]{4}){3}-[\da-f]{12}$/i;
const controls = /\p{Cc}/u;

export class BadInput extends Error {}
export type Attachment = { path: string; name?: string; kind: 'image' | 'file' };
export type Skill = { name: string; path: string };
export type MessageExtras = { skills?: Skill[]; attachments?: Attachment[] };
export type Upload = {
  name: string; mimeType?: string; byteLength: number; chunks: Iterable<Buffer> | AsyncIterable<Buffer>;
};
export type Uploaded = Required<Attachment> & { byteLength: number };

function limitedString(value: unknown, max: number): value is string {
  return typeof value === 'string' && value.length <= max && !!value.trim() && !controls.test(value);
}

export function uploadName(value: unknown) {
  if (!limitedString(value, 256)) throw new BadInput();
  return (sanitizeAgentSessionAttachmentName(value) as string).replace(/\.{2,}/g, '_');
}

export function attachmentStore() {
  return join(homedir(), 'Library', 'Application Support', 'orca', AGENT_SESSION_ATTACHMENTS_DIR_NAME);
}

export function storePath(path: unknown): path is string {
  if (!limitedString(path, 4096) || path.includes('..') || !path.startsWith(attachmentStore() + '/') || path.includes('\\')) return false;
  const parts = path.slice(attachmentStore().length + 1).split('/');
  return parts.length === 2 && uuid.test(parts[0]) && !!parts[1] && !parts[1].startsWith('.') &&
    sanitizeAgentSessionAttachmentName(parts[1]) === parts[1];
}

export async function messageExtras(body: Record<string, unknown>): Promise<MessageExtras> {
  const extras: MessageExtras = {};
  if (body.skills !== undefined) {
    if (!Array.isArray(body.skills) || body.skills.length > 16) throw new BadInput();
    extras.skills = body.skills.map(skill => {
      if (!limitedString(skill?.name, 512) || !limitedString(skill.path, 4096)) throw new BadInput();
      return { name: skill.name, path: skill.path };
    });
  }
  if (body.attachments !== undefined) {
    if (!Array.isArray(body.attachments) || body.attachments.length > 10) throw new BadInput();
    extras.attachments = [];
    for (const entry of body.attachments) {
      if (!storePath(entry?.path) || !['image', 'file'].includes(entry.kind) ||
          (entry.name !== undefined && !limitedString(entry.name, 512))) throw new BadInput();
      // A text mention may be opened by the agent, outside Orca's image-reader checks.
      try {
        if (await realpath(entry.path) !== entry.path || !(await stat(entry.path)).isFile()) throw new BadInput();
      } catch {
        throw new BadInput();
      }
      extras.attachments.push({ path: entry.path, kind: entry.kind, ...(entry.name ? { name: entry.name } : {}) });
    }
  }
  return extras;
}

export function inlineUploads(entries: unknown, jsonBytes: number): Upload[] {
  if (entries === undefined) entries = [];
  if (!Array.isArray(entries) || entries.length > 5) throw new BadInput();
  let total = 0;
  const uploads = entries.map(entry => {
    const name = uploadName(entry?.name);
    const { contentBase64, mimeType } = entry;
    if (mimeType !== undefined && !limitedString(mimeType, 256)) throw new BadInput();
    if (typeof contentBase64 !== 'string' || !contentBase64.length || contentBase64.length % 4 !== 0 ||
        !/^[A-Za-z0-9+/]*={0,2}$/.test(contentBase64) ||
        Buffer.from(contentBase64.slice(-4), 'base64').toString('base64') !== contentBase64.slice(-4)) throw new BadInput();
    const byteLength = base64DecodedByteLength(contentBase64);
    total += byteLength;
    if (byteLength > 15 * 1024 * 1024 || total > maxUploadBytes) throw new BadInput();
    jsonBytes -= contentBase64.length;
    function* chunks() {
      for (const part of agentSessionAttachmentAppends(contentBase64)) yield Buffer.from(part.contentBase64, 'base64');
    }
    return { name, mimeType, byteLength, chunks: chunks() };
  });
  if (jsonBytes > jsonLimit) throw new BadInput();
  return uploads;
}

export function attachmentKind(name: string, mime: string | undefined, first: Buffer): Attachment['kind'] {
  if (/\.(png|jpe?g|gif|webp|hei[cf]|avif|bmp)$/i.test(name) ||
      /^image\/(?:png|jpeg|gif|webp|hei[cf]|avif|(?:x-ms-)?bmp)$/i.test(mime?.split(';')[0].trim() ?? '')) return 'image';
  const header = first.toString('latin1', 0, 12);
  return header.startsWith('\x89PNG\r\n\x1a\n') || header.startsWith('\xff\xd8\xff') ||
    /^GIF8[79]a/.test(header) || header.startsWith('BM') ||
    (header.startsWith('RIFF') && header.slice(8) === 'WEBP') ||
    /^ftyp(?:hei[cf]|heix|hevc|hevx|mif1|msf1|avif|avis)$/.test(header.slice(4)) ? 'image' : 'file';
}

// uploadStart requires a length even when HTTP uses chunked transfer. An unlinked private file bounds RAM.
export async function withRawUpload<T>(request: IncomingMessage, signal: AbortSignal, run: (upload: Upload) => Promise<T>) {
  const header = request.headers['x-file-name'];
  if (typeof header !== 'string' || Number(request.headers['content-length']) > maxUploadBytes) throw new BadInput();
  if (/^multipart\//i.test(request.headers['content-type'] ?? '')) throw new BadInput();
  let decoded;
  try { decoded = decodeURIComponent(header); } catch { throw new BadInput(); }
  const name = uploadName(decoded);
  const path = join(tmpdir(), 'orca-upload-' + randomUUID());
  const file = await open(path, 'wx+', 0o600);
  const stop = () => request.destroy();
  try {
    await unlink(path);
    signal.throwIfAborted();
    signal.addEventListener('abort', stop, { once: true });
    let byteLength = 0;
    for await (const chunk of request.iterator({ destroyOnReturn: false })) {
      byteLength += chunk.length;
      if (byteLength > maxUploadBytes) throw new BadInput();
      await file.writeFile(chunk);
    }
    signal.removeEventListener('abort', stop);
    if (!byteLength) throw new BadInput();
    const chunks = file.createReadStream({ start: 0, highWaterMark: chunkSize, autoClose: false });
    try {
      return await run({ name, mimeType: request.headers['content-type'], byteLength, chunks });
    } finally {
      chunks.destroy();
    }
  } finally {
    signal.removeEventListener('abort', stop);
    await file.close();
  }
}
