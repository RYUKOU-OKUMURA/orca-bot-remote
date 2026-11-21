import { execFileSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { linkSync, lstatSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { connect } from 'node:net';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';
import { DEFAULT_ORCA_BIN, isTailscaleAddress } from './config.ts';
import { DEVICE_FILE, validDevice, withOrcaDevice } from './orca-write.ts';

function reachesOrca(host: string) {
  return new Promise<boolean>(resolve => {
    const socket = connect(6768, host);
    function finish(reachable: boolean) {
      socket.destroy();
      resolve(reachable);
    }
    socket.once('connect', () => finish(true));
    socket.once('error', () => finish(false));
    socket.setTimeout(2000, () => finish(false));
  });
}

async function pairingAddress() {
  if (await reachesOrca('127.0.0.1')) return '127.0.0.1';
  const ip = execFileSync('tailscale', ['ip', '-4'], {
    encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 5000
  }).trim();
  if (!isTailscaleAddress(ip) || !await reachesOrca(ip)) throw new Error();
  return ip;
}

async function pair() {
  const dir = dirname(DEVICE_FILE);
  if (lstatSync(DEVICE_FILE, { throwIfNoEntry: false })) {
    console.log('Orca の端末資格情報は保存済みです。');
    return;
  }
  mkdirSync(dir, { recursive: true, mode: 0o700 });
  const lock = join(dir, '.orca-device-pairing');
  mkdirSync(lock, { mode: 0o700 });
  let stage = '接続先を確認';
  try {
    if (lstatSync(DEVICE_FILE, { throwIfNoEntry: false })) return;
    const address = await pairingAddress();
    stage = '招待を発行';
    const reply = JSON.parse(execFileSync(DEFAULT_ORCA_BIN, [
      'pair', '--mobile', '--address', address, '--json'
    ], {
      encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 20_000,
      env: { HOME: homedir(), PATH: '/usr/bin:/bin' }
    }));
    if (reply.ok !== true || reply.result?.available !== true || reply.result.scope !== 'mobile') throw new Error();
    stage = '招待を確認';
    const url = new URL(reply.result.pairingUrl);
    const code = url.searchParams.get('code');
    if (url.protocol !== 'orca:' || url.hostname !== 'pair' || !code) throw new Error();
    const invite = JSON.parse(Buffer.from(code, 'base64url').toString('utf8'));
    if (invite?.v !== 2 || invite.scope !== 'mobile' || invite.relay || !validDevice(invite) ||
        new URL(invite.endpoint).origin !== `ws://${address}:6768`) throw new Error();
    stage = '端末トークンに交換';
    const deviceToken = randomBytes(24).toString('hex');
    const signal = AbortSignal.timeout(15_000);
    const exchanged = await withOrcaDevice(invite, signal, call =>
      call<{ deviceId: string }>('pairing.exchangeTicket', { deviceToken }, true)
    );
    if (typeof exchanged?.deviceId !== 'string' || !exchanged.deviceId) throw new Error();
    const device = { endpoint: invite.endpoint, publicKeyB64: invite.publicKeyB64, deviceToken };

    stage = '端末資格情報を保存';
    const temporary = mkdtempSync(join(lock, 'save-'));
    const file = join(temporary, 'device.json');
    writeFileSync(file, JSON.stringify(device) + '\n', { flag: 'wx', mode: 0o600 });
    linkSync(file, DEVICE_FILE);

    // First sign-in with the replacement retires the invitation.
    stage = '交換後の端末トークンで認証';
    await withOrcaDevice(device, AbortSignal.timeout(15_000), call => call('status.get'));
    console.log(`Orca の端末資格情報を保存しました（${address === '127.0.0.1' ? 'loopback' : 'Tailscale'}）。`);
  } catch {
    throw new Error(stage);
  } finally {
    rmSync(lock, { recursive: true, force: true });
  }
}

try {
  await pair();
} catch (error) {
  // Use only stage names; subprocess errors and JSON parse errors can include secrets.
  const stages = ['接続先を確認', '招待を発行', '招待を確認', '端末トークンに交換',
    '端末資格情報を保存', '交換後の端末トークンで認証'];
  const stage = error instanceof Error && stages.includes(error.message) ? `（${error.message}）` : '';
  console.error(`Orca のペアリングに失敗しました${stage}。`);
  process.exitCode = 1;
}
