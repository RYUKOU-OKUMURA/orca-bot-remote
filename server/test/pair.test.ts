import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test, type TestContext } from 'node:test';
import { fileURLToPath } from 'node:url';

function fixture(t: TestContext) {
  const home = mkdtempSync(join(tmpdir(), 'orca-pair-test-'));
  t.after(() => rmSync(home, { recursive: true }));
  const dir = join(home, '.config/orca-bot-remote');
  mkdirSync(dir, { recursive: true });
  const file = join(dir, 'orca-device.json');
  const previous = 'old-test-credential';
  const fakeHost = new URL('./fixtures/orca-protocol/fake-host.ts', import.meta.url);
  const preload = `
    import assert from 'node:assert/strict';
    import cp from 'node:child_process';
    import net from 'node:net';
    import { existsSync, readFileSync } from 'node:fs';
    import { syncBuiltinESMExports } from 'node:module';
    import { device, installFakeHost } from ${JSON.stringify(fakeHost.href)};
    const invite = { ...device, v: 2, scope: 'mobile' };
    const code = Buffer.from(JSON.stringify(invite)).toString('base64url');
    net.connect = () => {
      const socket = new net.Socket();
      queueMicrotask(() => socket.emit('connect'));
      return socket;
    };
    cp.execFileSync = () => {
      if (process.env.SCENARIO === 'skip') throw new Error(invite.deviceToken);
      return JSON.stringify({ ok: true, result: {
        available: true, scope: 'mobile', pairingUrl: 'orca://pair?code=' + code
      } });
    };
    syncBuiltinESMExports();
    installFakeHost(({ method, params }) => {
      if (method === 'pairing.exchangeTicket') {
        device.deviceToken = params.deviceToken;
        return { result: { deviceId: 'new-device' } };
      }
      assert.equal(method, 'status.get');
      if (process.env.SCENARIO === 'first') assert.ok(!existsSync(${JSON.stringify(file)}));
      else assert.equal(readFileSync(${JSON.stringify(file)}, 'utf8'), ${JSON.stringify(previous)});
      return process.env.SCENARIO === 'fail'
        ? { error: { code: 'forbidden', data: device.deviceToken } }
        : { result: {} };
    });
  `;
  const run = (scenario: string, ...args: string[]) => spawnSync(process.execPath, [
    '--import', `data:text/javascript,${encodeURIComponent(preload)}`,
    fileURLToPath(new URL('../src/pair.ts', import.meta.url)), ...args
  ], { env: { HOME: home, SCENARIO: scenario }, encoding: 'utf8', timeout: 5000 });
  return { run, file, previous, dir };
}

test('pair は引数なしの初回実行で検証済みの資格情報を権限 600 で保存する', t => {
  const { run, file } = fixture(t);
  const result = run('first');
  assert.equal(result.status, 0, result.stderr);
  const { deviceToken } = JSON.parse(readFileSync(file, 'utf8'));
  assert.match(deviceToken, /^[0-9a-f]{48}$/);
  assert.equal(statSync(file).mode & 0o777, 0o600);
});

test('pair は未知の引数や余分な引数を終了コード 1 で拒否する', t => {
  const { run, file } = fixture(t);
  for (const args of [['--unknown'], ['--force', 'extra']]) {
    const result = run('skip', ...args);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /使い方/);
    assert.ok(!existsSync(file));
  }
});

test('pair --force は新しい資格情報を検証してから置換し、失敗時は旧資格情報を維持する', t => {
  const { run, file, previous, dir } = fixture(t);
  writeFileSync(file, previous, { mode: 0o600 });
  assert.equal(run('skip').status, 0);
  const failed = run('fail', '--force');
  assert.equal(failed.status, 1);
  assert.equal(failed.stdout, '');
  assert.equal(failed.stderr.trim(), 'Orca のペアリングに失敗しました（交換後の端末トークンで認証）。');
  assert.equal(readFileSync(file, 'utf8'), previous);
  assert.ok(!existsSync(join(dir, '.orca-device-pairing')));

  const paired = run('success', '--force');
  assert.equal(paired.status, 0, paired.stderr);
  const { deviceToken } = JSON.parse(readFileSync(file, 'utf8'));
  assert.match(deviceToken, /^[0-9a-f]{48}$/);
  assert.ok(!paired.stdout.includes(deviceToken));
  assert.equal(statSync(file).mode & 0o777, 0o600);
  assert.ok(!existsSync(join(dir, '.orca-device-pairing')));
});
