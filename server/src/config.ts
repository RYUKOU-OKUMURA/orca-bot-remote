import { createHash } from 'node:crypto';
import { closeSync, constants, fstatSync, openSync, readFileSync } from 'node:fs';
import { isIP } from 'node:net';
import { homedir } from 'node:os';
import { isAbsolute, join } from 'node:path';

export const DEFAULT_ORCA_BIN = '/Applications/Orca.app/Contents/Resources/bin/orca';

export function isTailscaleAddress(host: string): boolean {
  if (isIP(host) === 4) {
    const [first, second] = host.split('.').map(Number);
    return first === 100 && second >= 64 && second <= 127;
  }
  return isIP(host) === 6 && /^fd7a:115c:a1e0:/i.test(host) && !host.includes('%');
}

export function tokenDigest(token: string): Buffer {
  return createHash('sha256').update(token).digest();
}

export function readConfig(env = process.env) {
  const host = env.HOST;
  if (!host || !isTailscaleAddress(host)) {
    throw new Error('HOST に Tailscale の IP アドレスを指定してください。');
  }

  const portText = env.PORT ?? '8787';
  const port = Number(portText);
  if (!/^\d+$/.test(portText) || port < 1 || port > 65535) {
    throw new Error('PORT は 1〜65535 の整数を指定してください。');
  }

  const orcaBin = env.ORCA_BIN ?? DEFAULT_ORCA_BIN;
  if (!isAbsolute(orcaBin)) {
    throw new Error('ORCA_BIN は絶対パスを指定してください。');
  }

  const tokenFile = env.TOKEN_FILE ?? join(homedir(), '.config/orca-bot-remote/token');
  const fd = openSync(tokenFile, constants.O_RDONLY | constants.O_NONBLOCK | constants.O_NOFOLLOW);
  try {
    const stat = fstatSync(fd);
    if (!stat.isFile() || (stat.mode & 0o7777) !== 0o600) {
      throw new Error('トークンは権限 600 の通常ファイルに保存してください。');
    }
    const token = readFileSync(fd, 'utf8').trim();
    if (!/^[A-Za-z0-9._~+/-]+=*$/.test(token)) {
      throw new Error('トークンファイルに有効な Bearer トークンを保存してください。');
    }
    return { host, port, orcaBin, digest: tokenDigest(token), summaryId: env.SUMMARY_AUTOMATION_ID };
  } finally {
    closeSync(fd);
  }
}
