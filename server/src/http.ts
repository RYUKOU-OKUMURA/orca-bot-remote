import { timingSafeEqual } from 'node:crypto';
import { createServer } from 'node:http';
import { tokenDigest } from './config.ts';
import { createOrca, OrcaError } from './orca.ts';

const BOTS = /^\/api\/bots$/;
const CHATS = /^\/api\/bots\/([^/]+)\/chats$/;
const MESSAGES = /^\/api\/bots\/([^/]+)\/chats\/([^/]+)\/messages$/;
const ITEMS = /^\/api\/items$/;
const ITEM = /^\/api\/items\/([^/]+)\/([^/]+)$/;
const SUMMARY = /^\/api\/summary$/;
const UUID = /^[\da-f]{8}(?:-[\da-f]{4}){3}-[\da-f]{12}$/i;

const notFound = { error: 'not_found', message: '対象が見つかりません。' };
const timedOut = { error: 'orca_failed', message: 'Orca の読み取りが制限時間内に終わりませんでした。' };

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

export function createApi({ orcaBin, digest, summaryId }: {
  orcaBin: string; digest: Buffer; summaryId?: string;
}, requestTimeoutMs = 55_000) {
  return createServer(async (request, response) => {
    const started = performance.now();
    const deadline = started + requestTimeoutMs;
    const url = request.url ?? '';
    const queryAt = url.indexOf('?');
    const path = queryAt === -1 ? url : url.slice(0, queryAt);
    const query = queryAt === -1 ? '' : url.slice(queryAt + 1);
    let logPath = 'unauthenticated';
    response.once('finish', () => {
      console.log(`${JSON.stringify(logPath)} ${response.statusCode} ${Math.round(performance.now() - started)}ms`);
    });
    function send(status: number, body: object) {
      if (response.destroyed || response.writableEnded) return;
      if (performance.now() >= deadline) {
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
    const bots = BOTS.test(path);
    const items = ITEMS.test(path);
    const summary = SUMMARY.test(path);
    if (bots) logPath = '/api/bots';
    else if (chats) logPath = '/api/bots/:bot/chats';
    else if (messages) logPath = '/api/bots/:bot/chats/:chat/messages';
    else if (items) logPath = '/api/items';
    else if (item) logPath = '/api/items/:bot/:number';
    else if (summary) logPath = '/api/summary';
    else {
      logPath = 'unknown';
      return send(404, notFound);
    }
    if (request.method !== 'GET') {
      response.setHeader('Allow', 'GET');
      return send(405, { error: 'bad_request', message: 'GET のみ使用できます。' });
    }

    const controller = new AbortController();
    function stop() {
      clearTimeout(timer);
      controller.abort();
    }
    const timer = setTimeout(() => {
      stop();
      send(502, timedOut);
    }, Math.max(0, Math.ceil(deadline - performance.now())));
    response.once('close', () => {
      if (!response.writableEnded) stop();
    });
    const orca = createOrca(orcaBin, summaryId, controller.signal, deadline);
    try {
      if (bots) return send(200, { bots: await orca.bots() });
      if (items) return send(200, { items: await orca.items() });
      if (summary) return send(200, { summary: await orca.summary() });

      const chatId = messages ? decode(messages[2]) : '';
      if (messages && !UUID.test(chatId)) return send(404, notFound);
      const numberText = item ? decode(item[2]) : '';
      const number = Number(numberText);
      if (item && (!/^\d+$/.test(numberText) || !Number.isSafeInteger(number) || number < 1)) {
        return send(404, notFound);
      }
      const name = decode((chats ?? messages ?? item)![1]);
      const bot = name && (await orca.listBots()).find(bot => bot.name === name);
      if (!bot) return send(404, notFound);
      if (chats) return send(200, { chats: await orca.chats(bot) });

      const result = messages
        ? await orca.messages(bot, chatId, messageLimit(query))
        : await orca.item(bot, number);
      return result ? send(200, result) : send(404, notFound);
    } catch (error) {
      if (error instanceof OrcaError && (
        (error.code === 'not_found' && (chats || item || messages)) ||
        (error.code === 'agent_unknown' && messages)
      )) {
        return send(404, notFound);
      }
      return send(502, { error: 'orca_failed', message: 'Orca の読み取りに失敗しました。' });
    } finally {
      stop();
    }
  });
}
