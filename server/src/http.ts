import { timingSafeEqual } from 'node:crypto';
import { createServer, type IncomingMessage } from 'node:http';
import { tokenDigest } from './config.ts';
import { createOrca, OrcaError } from './orca.ts';
import { createOrcaWrite, OrcaWriteError, type OrcaWrite } from './orca-write.ts';

const BOTS = /^\/api\/bots$/;
const CHATS = /^\/api\/bots\/([^/]+)\/chats$/;
const MESSAGES = /^\/api\/bots\/([^/]+)\/chats\/([^/]+)\/messages$/;
const ITEMS = /^\/api\/items$/;
const ITEM = /^\/api\/items\/([^/]+)\/([^/]+)$/;
const ANSWER = /^\/api\/items\/([^/]+)\/([^/]+)\/answer$/;
const SUMMARY = /^\/api\/summary$/;
const UUID = /^[\da-f]{8}(?:-[\da-f]{4}){3}-[\da-f]{12}$/i;

const notFound = { error: 'not_found', message: '対象が見つかりません。' };
const timedOut = { error: 'orca_failed', message: 'Orca の読み取りが制限時間内に終わりませんでした。' };
const writeErrors = {
  write_unavailable: 'Mac mini の書き込み設定が済んでいません。',
  unknown_result: '送れたか確認できませんでした。会話を更新して確かめてください。',
  orca_failed: 'Orca への書き込みに失敗しました。'
};
const maxBodyBytes = 64 * 1024;
// Orca 1.4.266 counts the full comment in UTF-16 code units.
const maxAnswerLength = 8000;

function decode(segment: string) {
  try {
    return decodeURIComponent(segment);
  } catch {
    return '';
  }
}

function messageLimit(query: string) {
  const value = new URLSearchParams(query).get('limit');
  return value !== null && /^[+-]?\d+$/.test(value) ? Math.max(1, Math.min(100, Number(value))) : 100;
}

function readText(request: IncomingMessage, signal: AbortSignal) {
  return new Promise<string | null>((resolve, reject) => {
    const contentType = request.headers['content-type']?.split(';')[0].trim().toLowerCase();
    if (contentType !== 'application/json' || Number(request.headers['content-length']) > maxBodyBytes) {
      return resolve(null);
    }

    const chunks: Buffer[] = [];
    let size = 0;
    function cleanup() {
      request.off('data', onData);
      request.off('end', onEnd);
      request.off('close', onAbort);
      request.off('error', onAbort);
      signal.removeEventListener('abort', onAbort);
    }
    function onAbort() {
      cleanup();
      reject(new OrcaWriteError('orca_failed'));
    }
    function onData(chunk: Buffer) {
      size += chunk.length;
      if (size > maxBodyBytes) {
        cleanup();
        request.pause();
        resolve(null);
      } else {
        chunks.push(chunk);
      }
    }
    function onEnd() {
      cleanup();
      try {
        const value = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks)));
        const text = value?.text;
        const length = typeof text === 'string' ? [...text.trim()].length : 0;
        resolve(length >= 1 && length <= 10_000 ? text : null);
      } catch {
        resolve(null);
      }
    }
    if (signal.aborted) return onAbort();
    request.on('data', onData);
    request.once('end', onEnd);
    request.once('close', onAbort);
    request.once('error', onAbort);
    signal.addEventListener('abort', onAbort, { once: true });
  });
}

export function createApi({ orcaBin, digest, summaryId, write = createOrcaWrite(), shutdownSignal }: {
  orcaBin: string; digest: Buffer; summaryId?: string; write?: OrcaWrite; shutdownSignal?: AbortSignal;
}, requestTimeoutMs = 55_000) {
  const pendingAnswers = new Map<string, Promise<void>>();
  return createServer(async (request, response) => {
    const started = performance.now();
    const deadline = started + requestTimeoutMs;
    const url = request.url ?? '';
    const queryAt = url.indexOf('?');
    const path = queryAt === -1 ? url : url.slice(0, queryAt);
    const query = queryAt === -1 ? '' : url.slice(queryAt + 1);
    const posting = request.method === 'POST';
    let logPath = 'unauthenticated';
    response.once('finish', () => {
      console.log(`${JSON.stringify(logPath)} ${response.statusCode} ${Math.round(performance.now() - started)}ms`);
    });
    function send(status: number, body: object) {
      if (response.destroyed || response.writableEnded) return;
      if (shutdownSignal?.aborted) response.setHeader('Connection', 'close');
      if (!posting && performance.now() >= deadline) {
        status = 502;
        body = timedOut;
      }
      response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
      response.end(JSON.stringify(body));
    }

    const token = /^Bearer ([^\s]+)$/i.exec(request.headers.authorization ?? '')?.[1];
    if (!token || !timingSafeEqual(tokenDigest(token), digest)) {
      return send(401, { error: 'unauthorized' });
    }

    const chats = CHATS.exec(path);
    const messages = MESSAGES.exec(path);
    const item = ITEM.exec(path);
    const answer = ANSWER.exec(path);
    const bots = BOTS.test(path);
    const items = ITEMS.test(path);
    const summary = SUMMARY.test(path);
    if (bots) logPath = '/api/bots';
    else if (chats) logPath = '/api/bots/:bot/chats';
    else if (messages) logPath = '/api/bots/:bot/chats/:chat/messages';
    else if (items) logPath = '/api/items';
    else if (item) logPath = '/api/items/:bot/:number';
    else if (answer) logPath = '/api/items/:bot/:number/answer';
    else if (summary) logPath = '/api/summary';
    else {
      logPath = 'unknown';
      return send(404, notFound);
    }
    const allowed = answer ? 'POST' : chats || messages ? 'GET, POST' : 'GET';
    if (!(posting ? answer || chats || messages : request.method === 'GET' && !answer)) {
      response.setHeader('Allow', allowed);
      return send(405, { error: 'bad_request', message: `${allowed} のみ使用できます。` });
    }

    const controller = new AbortController();
    const signal = shutdownSignal ? AbortSignal.any([controller.signal, shutdownSignal]) : controller.signal;
    function stop() {
      clearTimeout(timer);
      controller.abort();
    }
    const timer = setTimeout(() => {
      stop();
      if (!posting) send(502, timedOut);
    }, Math.max(0, Math.ceil(deadline - performance.now())));
    response.once('close', () => {
      if (!response.writableEnded) stop();
    });
    const orca = createOrca(orcaBin, summaryId, signal, deadline);
    try {
      if (bots) return send(200, { bots: await orca.bots() });
      if (items) return send(200, { items: await orca.items() });
      if (summary) return send(200, { summary: await orca.summary() });

      const chatId = messages ? decode(messages[2]) : '';
      if (messages && !UUID.test(chatId)) return send(404, notFound);
      const itemRoute = item ?? answer;
      const numberText = itemRoute ? decode(itemRoute[2]) : '';
      const number = Number(numberText);
      if (itemRoute && (!/^\d+$/.test(numberText) || !Number.isSafeInteger(number) || number < 1)) {
        return send(404, notFound);
      }
      const text = posting ? await readText(request, signal) : null;
      if (posting && text === null) {
        if (!request.readableEnded) response.setHeader('Connection', 'close');
        return send(400, { error: 'bad_request', message: '本文は最大 64 KiB の JSON で、text は前後の空白を除いて 1〜10000 文字で指定してください。' });
      }
      if (answer && text !== null && text.length > maxAnswerLength) {
        return send(400, { error: 'bad_request', message: '判断待ちへの回答は前後の空白を含め、8000 UTF-16 コード単位以内で指定してください。' });
      }
      const name = decode((chats ?? messages ?? itemRoute)![1]);
      const bot = name && (await orca.listBots()).find(bot => bot.name === name);
      if (!bot) return send(404, notFound);
      if (text !== null) {
        signal.throwIfAborted();
        if (chats) return send(201, { chatId: await write.start(bot.id, text, signal) });
        if (messages) {
          const chat = await orca.findChat(bot, chatId);
          if (!chat) return send(404, notFound);
          if (chat.closed) return send(409, { error: 'conflict', message: 'このチャットは閉じています。' });
          signal.throwIfAborted();
          await write.send(chatId, text, signal);
          return send(202, { ok: true });
        }
        const uid = await orca.findItemUid(bot, number);
        if (!uid) return send(404, notFound);
        const previous = pendingAnswers.get(uid);
        const result = (async () => {
          await previous;
          const target = await orca.showItem(bot, uid);
          if (!target) return send(404, notFound);
          if (target.page.item.status !== 'needs_you') {
            return send(409, { error: 'conflict', message: 'この項目は判断待ちではありません。' });
          }
          signal.throwIfAborted();
          await write.answer(uid, text, signal);
          return send(200, { ok: true });
        })();
        const pending = result.catch(() => {});
        pendingAnswers.set(uid, pending);
        try {
          return await result;
        } finally {
          if (pendingAnswers.get(uid) === pending) pendingAnswers.delete(uid);
        }
      }
      if (chats) return send(200, { chats: await orca.chats(bot) });

      const result = messages
        ? await orca.messages(bot, chatId, messageLimit(query))
        : await orca.item(bot, number);
      return result ? send(200, result) : send(404, notFound);
    } catch (error) {
      if (error instanceof OrcaError && (
        (error.code === 'not_found' && (chats || item || messages || answer)) ||
        (error.code === 'agent_unknown' && messages)
      )) {
        return send(404, notFound);
      }
      if (posting) {
        if (!request.readableEnded) response.setHeader('Connection', 'close');
        const code = error instanceof OrcaWriteError ? error.code : 'orca_failed';
        if (code === 'not_found') return send(404, notFound);
        return send(code === 'write_unavailable' ? 503 : 502, { error: code, message: writeErrors[code] });
      }
      return send(502, { error: 'orca_failed', message: 'Orca の読み取りに失敗しました。' });
    } finally {
      stop();
    }
  });
}
