import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

test('dry-run の ProgramArguments は Node と main.ts の2要素', {
  skip: process.platform !== 'darwin'
}, t => {
  const home = mkdtempSync(join(tmpdir(), 'orca-bot-remote-launchd-test-'));
  t.after(() => rmSync(home, { recursive: true }));
  const node = join(home, '.nvm/versions/node/v24.19.0/bin/node');
  mkdirSync(dirname(node), { recursive: true });
  symlinkSync(process.execPath, node);
  writeFileSync(join(home, 'tailscale'), '#!/bin/sh\nprintf "100.113.115.59\\n"\n', { mode: 0o700 });
  const plist = execFileSync(fileURLToPath(new URL('../launchd/install.sh', import.meta.url)), ['--dry-run'], {
    env: {
      HOME: home, PATH: `${home}:/usr/bin:/bin`,
      ORCA_BIN: fileURLToPath(new URL('./fixtures/orca', import.meta.url))
    }
  });
  const args = execFileSync('/usr/bin/plutil', ['-extract', 'ProgramArguments', 'json', '-o', '-', '-'], {
    input: plist, encoding: 'utf8'
  });
  assert.deepEqual(JSON.parse(args), [
    node, join(home, 'Library/Application Support/orca-bot-remote/server/src/main.ts')
  ]);
});
