import { timingSafeEqual } from 'node:crypto';
import { createServer, type IncomingMessage } from 'node:http';
import { tokenDigest } from './config.ts';
import { clearReadCache, createOrca, OrcaError } from './orca.ts';
import { createOrcaWrite, OrcaWriteError, type OrcaWrite } from './orca-write.ts';
import { BadInput, inlineJsonLimit, inlineUploads, jsonLimit, messageExtras, uuid, withRawUpload } from './attachments.ts';
import { parseCommand } from './commands.ts';
import { createAppRelease } from './app-release.ts';
import { createEvents } from './events.ts';

const BOTS = /^\/api\/bots$/;
const CHATS = /^\/api\/bots\/([^/]+)\/chats$/;
const CHAT_ACTION = /^\/api\/bots\/([^/]+)\/chats\/([^/]+)\/(messages|commands|command|attachments)$/;
const ITEMS = /^\/api\/items$/;
const ITEM = /^\/api\/items\/([^/]+)\/([^/]+)$/;
const ANSWER = /^\/api\/items\/([^/]+)\/([^/]+)\/answer$/;
const SUMMARY = /^\/api\/summary$/;

const notFound = { error: 'not_found', message: '対象が見つかりません。' };
const timedOut = { error: 'orca_failed', message: 'Orca の読み取りが制限時間内に終わりませんでした。' };
const writeErrors = {
  write_unavailable: 'Mac mini の書き込み設定が済んでいません。',
  unknown_result: '送れたか確認できませんでした。会話を更新して確かめてください。',
  orca_failed: 'Orca への書き込みに失敗しました。'
};

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

// The array must begin within the 64 KiB metadata budget. Skip strings, including escaped keys.
function hasInlineArray(prefix: string) {
  let depth = 0;
  for (const token of prefix.matchAll(/"(?:[^"\\]|\\.)*"|[{}\[\]]/g)) {
    if (token[0].startsWith('"')) {
      if (depth === 1 && /^\s*:\s*\[/.test(prefix.slice(token.index + token[0].length)) &&
          JSON.parse(token[0]) === 'inlineAttachments') return true;
    } else {
      depth += token[0] === '[' || token[0] === '{' ? 1 : -1;
    }
  }
  return false;
}

function readJson(request: IncomingMessage, signal: AbortSignal, maxBodyBytes: number) {
  return new Promise<{ body: Record<string, unknown>; size: number } | null>((resolve, reject) => {
    const contentType = request.headers['content-type']?.split(';')[0].trim().toLowerCase();
    if (contentType !== 'application/json' || Number(request.headers['content-length']) > maxBodyBytes) {
      return resolve(null);
    }

    const chunks: Buffer[] = [];
    let size = 0;
    let limit = jsonLimit;
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
      // Only an inline file can legitimately push a chats request past the normal JSON budget.
      if (size > limit && limit < maxBodyBytes) {
        const prefix = Buffer.concat([...chunks, chunk], jsonLimit).toString('utf8');
        try {
          if (hasInlineArray(prefix)) limit = maxBodyBytes;
        } catch { /* Invalid JSON stays under the normal limit. */ }
      }
      if (size > limit) {
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
        resolve(value && typeof value === 'object' && !Array.isArray(value) ? { body: value, size } : null);
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

export function createApi({ orcaBin, digest, summaryId, write = createOrcaWrite(), shutdownSignal, appDir,
  pollIntervalMs, keepaliveIntervalMs }: {
  orcaBin: string; digest: Buffer; summaryId?: string; write?: OrcaWrite; shutdownSignal?: AbortSignal; appDir?: string;
  pollIntervalMs?: number; keepaliveIntervalMs?: number;
}, requestTimeoutMs = 55_000) {
  const appRelease = createAppRelease(appDir);
  const events = createEvents(orcaBin, summaryId, requestTimeoutMs, pollIntervalMs, keepaliveIntervalMs);
  const pendingAnswers = new Map<string, Promise<'unknown_result' | undefined>>();
  const server = createServer({ keepAliveTimeout: 65_000, headersTimeout: 66_000 }, async (request, response) => {
    const started = performance.now();
    const deadline = started + requestTimeoutMs;
    const url = request.url ?? '';
    const queryAt = url.indexOf('?');
    const path = queryAt === -1 ? url : url.slice(0, queryAt);
    const query = queryAt === -1 ? '' : url.slice(queryAt + 1);
    const posting = request.method === 'POST';
    const app = path === '/api/app';
    const apk = path === '/api/app/apk';
    const streaming = path === '/api/events';
    let logPath = 'unauthenticated';
    response.once(streaming ? 'close' : 'finish', () => {
      console.log(`${JSON.stringify(logPath)} ${response.statusCode} ${Math.round(performance.now() - started)}ms`);
    });
    function send(status: number, body: object) {
      if (response.destroyed || response.writableEnded) return;
      if (shutdownSignal?.aborted) response.setHeader('Connection', 'close');
      if (!posting && !app && !apk && !streaming && performance.now() >= deadline) {
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
    const action = CHAT_ACTION.exec(path);
    const route = action?.[3];
    const messages = route === 'messages';
    const upload = route === 'attachments';
    const command = route === 'command';
    const menu = route === 'commands';
    const item = ITEM.exec(path);
    const answer = ANSWER.exec(path);
    const bots = BOTS.test(path);
    const items = ITEMS.test(path);
    const summary = SUMMARY.test(path);
    const health = path === '/api/health';
    if (health) logPath = '/api/health';
    else if (app || apk || streaming) logPath = path;
    else if (bots) logPath = '/api/bots';
    else if (chats) logPath = '/api/bots/:bot/chats';
    else if (action) logPath = '/api/bots/:bot/chats/:chat/' + route;
    else if (items) logPath = '/api/items';
    else if (item) logPath = '/api/items/:bot/:number';
    else if (answer) logPath = '/api/items/:bot/:number/answer';
    else if (summary) logPath = '/api/summary';
    else {
      logPath = 'unknown';
      return send(404, notFound);
    }
    const allowed = answer || command || upload ? 'POST' : chats || messages ? 'GET, POST' : 'GET';
    if (!allowed.split(', ').includes(request.method ?? '')) {
      response.setHeader('Allow', allowed);
      return send(405, { error: 'bad_request', message: `${allowed} のみ使用できます。` });
    }

    if (health) return send(200, { ok: true });
    if (streaming) {
      if (!server.listening || shutdownSignal?.aborted) return response.destroy();
      if (!events.subscribe(response)) {
        return send(503, { error: 'too_many_connections', message: 'リアルタイム通知の接続数が上限に達しています。' });
      }
      return;
    }
    if (app) return send(200, { app: await appRelease.metadata() });
    if (apk) {
      if (!await appRelease.download(response)) send(404, notFound);
      return;
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

      const chatId = action ? decode(action[2]) : '';
      if (action && !uuid.test(chatId)) return send(404, notFound);
      const itemRoute = item ?? answer;
      const numberText = itemRoute ? decode(itemRoute[2]) : '';
      const number = Number(numberText);
      if (itemRoute && (!/^\d+$/.test(numberText) || !Number.isSafeInteger(number) || number < 1)) {
        return send(404, notFound);
      }
      const json = posting && !upload ? await readJson(request, signal, chats ? inlineJsonLimit : jsonLimit) : null;
      const invalidText = '本文は最大 64 KiB の JSON で、text は前後の空白を除いて 1〜10000 文字で指定してください。';
      if (posting && !upload && !json) throw new BadInput(command ? undefined : invalidText);
      const body = json?.body ?? {};
      const extras = posting && messages ? await messageExtras(body) : undefined;
      const uploads = posting && chats ? inlineUploads(body.inlineAttachments, json!.size) : undefined;
      let text: string | null = null;
      if (posting && !command && !upload) {
        const hasFiles = !!(extras?.attachments?.length || uploads?.length);
        const input = body.text === undefined && hasFiles ? '' : body.text;
        if (typeof input !== 'string' || (!hasFiles && !input.trim()) || [...input.trim()].length > 10_000) throw new BadInput(invalidText);
        text = input;
      }
      if (answer && text !== null && text.length > maxAnswerLength) {
        return send(400, { error: 'bad_request', message: '判断待ちへの回答は前後の空白を含め、8000 UTF-16 コード単位以内で指定してください。' });
      }
      const name = decode((chats ?? action ?? itemRoute)![1]);
      const bot = name && (await orca.listBots(!posting)).find(bot => bot.name === name);
      if (!bot) return send(404, notFound);
      if (action && (posting || menu)) {
        const chat = await orca.findChat(bot, chatId, !posting);
        if (!chat) return send(404, notFound);
        if (posting && chat.closed) return send(409, { error: 'conflict', message: 'このチャットは閉じています。' });
        signal.throwIfAborted();
        if (menu) return send(200, await write.commands(chatId, signal));
        if (command) {
          await write.command(chatId, parseCommand(body), signal);
          return send(200, { ok: true });
        }
        if (upload) {
          const result = await withRawUpload(request, signal, file => write.upload(chatId, file, signal));
          return send(201, result);
        }
        await write.send(chatId, text!, signal, extras);
        return send(202, { ok: true });
      }
      if (text !== null) {
        signal.throwIfAborted();
        if (chats) return send(201, { chatId: await write.start(bot.id, text, signal, uploads) });
        const uid = await orca.findItemUid(bot, number);
        if (!uid) return send(404, notFound);
        const previous = pendingAnswers.get(uid);
        const waiting = Promise.withResolvers<never>();
        const onAbort = () => waiting.reject(new OrcaWriteError('orca_failed'));
        if (previous) {
          if (signal.aborted) onAbort();
          else signal.addEventListener('abort', onAbort, { once: true });
        }
        const result = (async () => {
          const previousOutcome = await previous;
          signal.removeEventListener('abort', onAbort);
          if (previousOutcome === 'unknown_result') throw new OrcaWriteError(previousOutcome);
          signal.throwIfAborted();
          const target = await orca.showItem(bot, uid);
          if (!target) return send(404, notFound);
          if (target.page.item.status !== 'needs_you') {
            return send(409, { error: 'conflict', message: 'この項目は判断待ちではありません。' });
          }
          signal.throwIfAborted();
          if (performance.now() >= deadline) throw new OrcaWriteError('orca_failed');
          await write.answer(uid, text, signal);
          return send(200, { ok: true });
        })();
        // A waiting response may end early, but its queue slot must still follow the preceding answer.
        const pending = result.then(
          () => undefined,
          error => error instanceof OrcaWriteError && error.code === 'unknown_result' ? error.code : undefined
        ).finally(() => {
          if (pendingAnswers.get(uid) === pending) pendingAnswers.delete(uid);
        });
        pendingAnswers.set(uid, pending);
        return await (previous ? Promise.race([result, waiting.promise]) : result);
      }
      if (chats) return send(200, { chats: await orca.chats(bot) });

      const result = messages
        ? await orca.messages(bot, chatId, messageLimit(query))
        : await orca.item(bot, number);
      return result ? send(200, result) : send(404, notFound);
    } catch (error) {
      if (error instanceof OrcaError && (
        (error.code === 'not_found' && (chats || item || action || answer)) ||
        (error.code === 'agent_unknown' && action)
      )) {
        return send(404, notFound);
      }
      if (posting || menu) {
        if (!request.readableEnded || (upload && error instanceof BadInput)) response.setHeader('Connection', 'close');
        if (error instanceof BadInput) return send(400, { error: 'bad_request', message: error.message || '本文の形式、値、またはサイズが無効です。' });
        const code = error instanceof OrcaWriteError ? error.code : 'orca_failed';
        if (code === 'not_found') return send(404, notFound);
        return send(code === 'write_unavailable' ? 503 : 502, { error: code, message: writeErrors[code] });
      }
      return send(502, { error: 'orca_failed', message: 'Orca の読み取りに失敗しました。' });
    } finally {
      if (posting) clearReadCache();
      stop();
    }
  });
  shutdownSignal?.addEventListener('abort', events.close, { once: true });
  // Node emits 'close' only after all active responses have ended.
  const close = server.close.bind(server);
  server.close = callback => {
    shutdownSignal?.removeEventListener('abort', events.close);
    events.close();
    return close(callback);
  };
  return server;
}
