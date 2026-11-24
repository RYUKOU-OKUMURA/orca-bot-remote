import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, realpathSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { test, type TestContext } from 'node:test';
import { fileURLToPath } from 'node:url';

const darwin = { skip: process.platform !== 'darwin' };
const source = fileURLToPath(new URL('../', import.meta.url));
const install = join(source, 'launchd/install.sh');

function fixture(t: TestContext) {
  const home = mkdtempSync(join(realpathSync(tmpdir()), 'orca-bot-remote-launchd-test-'));
  t.after(() => rmSync(home, { recursive: true }));
  const env = {
    HOME: home, PATH: `${home}:/usr/bin:/bin`,
    ORCA_BIN: join(source, 'test/fixtures/orca')
  };
  const script = (path: string, body: string) => writeFileSync(path, '#!/bin/sh\n' + body + '\n', { mode: 0o700 });
  script(join(home, 'tailscale'), 'echo 100.113.115.59');
  const addNode = (path: string) => {
    mkdirSync(dirname(path), { recursive: true });
    symlinkSync(process.execPath, path);
    return path;
  };
  return { home, env, script, addNode, destination: join(home, 'Library/Application Support/orca-bot-remote/server') };
}

function parsePlist(plist: string) {
  return JSON.parse(execFileSync('/usr/bin/plutil', ['-convert', 'json', '-o', '-', '-'], {
    input: plist, encoding: 'utf8'
  }));
}

test('dry-run の ProgramArguments は候補順に解決した Node と main.ts の絶対パス', darwin, t => {
  const { home, env, addNode, destination } = fixture(t);
  const pathNode = addNode(join(home, 'node'));
  const run = (extra = {}) => {
    const plist = execFileSync(install, ['--dry-run'], { env: { ...env, ...extra }, cwd: home, encoding: 'utf8' });
    return parsePlist(plist).ProgramArguments;
  };
  const entry = join(destination, 'src/main.ts');
  assert.deepEqual(run(), [pathNode, entry]);
  const nvm = join(home, '.nvm/versions/node');
  addNode(join(nvm, 'v24.9.1/bin/node'));
  const latest = addNode(join(nvm, 'v24.19.0/bin/node'));
  assert.deepEqual(run(), [latest, entry]);
  const major = addNode(join(nvm, 'v25.0.0/bin/node'));
  assert.deepEqual(run(), [major, entry]);
  const explicit = addNode(join(home, 'custom node'));
  assert.deepEqual(run({ NODE_BIN: './custom node' }), [explicit, entry]);
});

test('明示した NODE_BIN が無効なら nvm と PATH に候補があっても失敗する', darwin, t => {
  const { home, env, script, addNode } = fixture(t);
  addNode(join(home, 'node'));
  addNode(join(home, '.nvm/versions/node/v25.0.0/bin/node'));
  const nonExecutable = join(home, 'not-executable');
  writeFileSync(nonExecutable, '', { mode: 0o600 });
  const older = join(home, 'old-node');
  script(older, 'echo 22');
  for (const value of ['', join(home, 'missing'), nonExecutable, older]) {
    const result = spawnSync(install, ['--dry-run'], { env: { ...env, NODE_BIN: value }, encoding: 'utf8' });
    assert.equal(result.status, 1);
    assert.match(result.stderr, /NODE_BIN.*Node.js 24 以上/);
    assert.equal(result.stdout, '');
  }
});

test('Node 24 未満しかない場合は分かりやすい理由で終了する', darwin, t => {
  const { home, env, script } = fixture(t);
  // Exercise the version probe with the actual Node runtime reporting an older version.
  writeFileSync(join(home, 'old.cjs'), 'Object.defineProperty(process.versions, "node", { value: "22.0.0" });');
  script(join(home, 'node'), 'exec "$TEST_NODE" --require "$HOME/old.cjs" "$@"');
  const result = spawnSync(install, ['--dry-run'], {
    env: { ...env, TEST_NODE: process.execPath }, encoding: 'utf8'
  });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /Node.js 24 以上/);
  assert.equal(result.stdout, '');
});

test('まとめ ID の明示指定時は Orca の自動化一覧を必要としない', darwin, t => {
  const { home, env } = fixture(t);
  const result = spawnSync(install, ['--dry-run'], {
    env: { ...env, NODE_BIN: process.execPath, ORCA_BIN: join(home, 'missing'), SUMMARY_AUTOMATION_ID: 'explicit' },
    encoding: 'utf8'
  });
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stderr, '');
  assert.equal(parsePlist(result.stdout).EnvironmentVariables.SUMMARY_AUTOMATION_ID, 'explicit');
});

test('まとめ ID は新名、旧名、有効な部分一致の順で検出し、候補なしなら警告する', darwin, t => {
  const { home, env, script } = fixture(t);
  const bin = join(home, 'orca');
  script(bin, 'cat "$HOME/automations.json"');
  const candidates = [
    { id: 'invalid-name', name: null },
    { id: 'unrelated', name: '予定' },
    { id: 'disabled', name: '週次まとめ', enabled: false },
    { id: 'first', name: '朝のまとめ' },
    { id: 'second', name: '夕方まとめ', enabled: true },
    { id: 'old', name: '専門Botの状況まとめ（毎日12:00）' },
    { id: 'new', name: '秘書まとめ（朝8:00・昼12:00・夕18:00＋放置リマインド）' }
  ];
  for (const [count, expected] of [[7, 'new'], [6, 'old'], [5, 'first'], [3, ''], [0, '']] as const) {
    writeFileSync(join(home, 'automations.json'), JSON.stringify({ ok: true, result: { automations: candidates.slice(0, count) } }));
    const result = spawnSync(install, ['--dry-run'], {
      env: { ...env, NODE_BIN: process.execPath, ORCA_BIN: bin, SUMMARY_AUTOMATION_ID: '' }, encoding: 'utf8'
    });
    assert.equal(result.status, 0, result.stderr);
    if (expected) assert.equal(result.stderr, '');
    else assert.match(result.stderr, /警告.*まとめ用の自動化が見つかりません/);
    assert.equal(parsePlist(result.stdout).EnvironmentVariables.SUMMARY_AUTOMATION_ID, expected);
  }
});

function installedFixture(t: TestContext) {
  const { home, env, script, destination } = fixture(t);
  const checkout = join(home, 'checkout');
  mkdirSync(checkout);
  for (const file of ['package.json', 'package-lock.json', 'src', 'vendor', 'launchd']) {
    cpSync(join(source, file), join(checkout, file), { recursive: true });
  }
  mkdirSync(join(checkout, 'test'));
  writeFileSync(join(checkout, 'debug.log'), 'test');
  mkdirSync(destination, { recursive: true });
  writeFileSync(join(destination, 'obsolete.ts'), 'test');

  // Redirect the two fixed external programs in the sandbox copy.
  // print succeeds while the job is loaded; bootout unloads it, a successful bootstrap reloads it.
  const launchctl = (bootstrap = 'true') => script(join(home, 'launchctl'), `echo "$1" >> "$HOME/launchctl.calls"
    case "$1" in
      print) [ ! -e "$HOME/unloaded" ];;
      bootout) touch "$HOME/unloaded";;
      bootstrap) ${bootstrap} && rm -f "$HOME/unloaded";;
    esac`);
  launchctl();
  const isolatedInstall = join(checkout, 'launchd/install.sh');
  writeFileSync(isolatedInstall, readFileSync(install, 'utf8')
    .replaceAll('/bin/launchctl', '"$HOME/launchctl"')
    .replace('orca_bin=/Applications/Orca.app/Contents/Resources/bin/orca', 'orca_bin="$ORCA_BIN"'));
  const node = join(home, 'node');
  symlinkSync(process.execPath, node);
  script(join(home, 'npm'), 'set -eu\n[ "$1" = ci ] && [ "$2" = --prefix ] || exit 1\nmkdir -p "$3/node_modules/tweetnacl"');
  const plist = join(home, 'Library/LaunchAgents/dev.okumura.orca-bot-remote.plist');
  mkdirSync(dirname(plist), { recursive: true });
  writeFileSync(plist, 'old plist');
  const run = () => spawnSync(isolatedInstall, { env: { ...env, NODE_BIN: node }, encoding: 'utf8' });
  const calls = () => readFileSync(join(home, 'launchctl.calls'), 'utf8').trim().split('\n');
  return { home, script, launchctl, destination, plist, run, calls };
}

test('更新は稼働用ファイルだけを配置し、旧版のファイルを除く', darwin, t => {
  const { destination, run, calls } = installedFixture(t);
  const result = run();
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(readdirSync(destination).sort(), ['node_modules', 'package-lock.json', 'package.json', 'src', 'vendor']);
  assert.ok(existsSync(join(destination, 'src/main.ts')));
  assert.ok(existsSync(join(destination, 'node_modules/tweetnacl')));
  assert.deepEqual(readdirSync(dirname(destination)), ['server']);
  assert.deepEqual(calls(), ['print', 'bootout', 'print', 'bootstrap']);
});

for (const failure of ['mv', 'bootstrap']) {
  test(`更新の ${failure} 失敗時に旧版と plist を復元して再登録する`, darwin, t => {
    const { home, script, launchctl, destination, plist, run, calls } = installedFixture(t);
    if (failure === 'mv') {
      script(join(home, 'mv'), 'case "$1" in */new) exit 1;; esac\nexec /bin/mv "$@"');
    } else {
      launchctl(`[ "$(cat "$3")" = 'old plist' ]`);
    }
    const result = run();
    assert.equal(result.status, 1);
    assert.deepEqual(readdirSync(destination), ['obsolete.ts']);
    assert.equal(readFileSync(plist, 'utf8'), 'old plist');
    assert.deepEqual(readdirSync(dirname(destination)), ['server']);
    assert.deepEqual(calls(), failure === 'mv'
      ? ['print', 'bootout', 'print', 'print', 'bootstrap']
      : ['print', 'bootout', 'print', 'bootstrap', 'print', 'bootstrap']);
  });
}
