import assert from 'node:assert/strict';
import { chmodSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test, type TestContext } from 'node:test';
import { readConfig } from '../src/config.ts';

function tokenFile(t: TestContext) {
  const dir = mkdtempSync(join(tmpdir(), 'orca-bot-remote-test-'));
  t.after(() => rmSync(dir, { recursive: true }));
  const file = join(dir, 'token');
  writeFileSync(file, 'test-token\n', { mode: 0o600 });
  return file;
}

test('設定は Tailscale の IPv4 と IPv6 を受け付ける', t => {
  const file = tokenFile(t);
  for (const host of ['100.64.0.0', '100.113.115.59', '100.127.255.255', 'fd7a:115c:a1e0::abcd', 'FD7A:115C:A1E0:1:2:3:4:5']) {
    const config = readConfig({ HOST: host, TOKEN_FILE: file });
    assert.equal(config.host, host);
    assert.equal(config.port, 8787);
  }
});

test('HOST が空か Tailscale の範囲外なら拒否する', t => {
  const file = tokenFile(t);
  for (const host of [undefined, '', '0.0.0.0', '127.0.0.1', '192.168.1.1', '::', '::1', '100.63.255.255',
    '100.128.0.1', '::ffff:100.113.115.59', 'fd7a:115c:a1e1::1', 'fd7a:115c:a1e0::1%en0', 'mac-mini']) {
    assert.throws(() => readConfig({ HOST: host, TOKEN_FILE: file }), /HOST/);
  }
});

test('トークンファイルの権限は厳密に 600 を要求する', t => {
  const file = tokenFile(t);
  for (const mode of [0o644, 0o400, 0o700]) {
    chmodSync(file, mode);
    assert.throws(() => readConfig({ HOST: '100.113.115.59', TOKEN_FILE: file }), /権限 600/);
  }
  chmodSync(file, 0o600);
  assert.doesNotThrow(() => readConfig({ HOST: '100.113.115.59', TOKEN_FILE: file }));
});

test('権限 600 の通常ファイルへのシンボリックリンクを拒否する', t => {
  const file = tokenFile(t);
  const link = `${file}.link`;
  symlinkSync(file, link);
  assert.throws(() => readConfig({ HOST: '100.113.115.59', TOKEN_FILE: link }), { code: 'ELOOP' });
});
