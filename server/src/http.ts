import { timingSafeEqual } from 'node:crypto';
import { createServer } from 'node:http';
import { tokenDigest } from './config.ts';
import { createOrca, OrcaError } from './orca.ts';

const BOTS = /^\/api\/bots$/;
const CHATS = /^\/api\/bots\/([^/]+)\/chats$/;
const MESSAGES = /^\/api\/chats\/([^/]+)\/messages$/;
const ITEMS = /^\/api\/items$/;
const ITEM = /^\/api\/items\/([^/]+)\/([^/]+)$/;
const SUMMARY = /^\/api\/summary$/;
const UUID = /^[\da-f]{8}(?:-[\da-f]{4}){3}-[\da-f]{12}$/i;

const notFound = { error: 'not_found', message: '対象が見つかりません。' };

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
}) {
  const orca = createOrca(orcaBin, summaryId);

  return createServer(async (request, response) => {
    const started = performance.now();
    const url = request.url ?? '';
    const queryAt = url.indexOf('?');
    const path = queryAt === -1 ? url : url.slice(0, queryAt);
    const query = queryAt === -1 ? '' : url.slice(queryAt + 1);
    response.once('finish', () => {
      console.log(`${JSON.stringify(path)} ${response.statusCode} ${Math.round(performance.now() - started)}ms`);
    });
    function send(status: number, body: object) {
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
    if (!chats && !messages && !item && !bots && !items && !summary) return send(404, notFound);
    if (request.method !== 'GET') {
      response.setHeader('Allow', 'GET');
      return send(405, { error: 'bad_request', message: 'GET のみ使用できます。' });
    }

    try {
      if (bots) return send(200, { bots: await orca.bots() });
      if (items) return send(200, { items: await orca.items() });
      if (summary) return send(200, { summary: await orca.summary() });

      if (messages) {
        const chatId = decode(messages[1]);
        if (!UUID.test(chatId)) return send(404, notFound);
        return send(200, await orca.messages(chatId, messageLimit(query)));
      }

      const numberText = item ? decode(item[2]) : '';
      const number = Number(numberText);
      if (item && (!/^\d+$/.test(numberText) || !Number.isSafeInteger(number) || number < 1)) {
        return send(404, notFound);
      }
      const name = decode((chats ?? item)![1]);
      const bot = name && (await orca.listBots()).find(bot => bot.name === name);
      if (!bot) return send(404, notFound);
      if (chats) return send(200, { chats: await orca.chats(bot) });

      const detail = await orca.item(bot, number);
      return detail ? send(200, detail) : send(404, notFound);
    } catch (error) {
      if (error instanceof OrcaError && (
        (error.code === 'not_found' && (chats || item || messages)) ||
        (error.code === 'agent_unknown' && messages)
      )) {
        return send(404, notFound);
      }
      return send(502, { error: 'orca_failed', message: 'Orca の読み取りに失敗しました。' });
    }
  });
}
