import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { once } from 'node:events';
import { mkdtemp, rename, rm, symlink, truncate, writeFile } from 'node:fs/promises';
import type { AddressInfo } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test, type TestContext } from 'node:test';
import { tokenDigest } from '../src/config.ts';
import { createApi } from '../src/http.ts';

const release = { versionCode: 42, versionName: '0.2.42' };
const apk = Buffer.from([0, 1, 2, 255]);

async function fixture(t: TestContext, timeout?: number) {
  const dir = await mkdtemp(join(tmpdir(), 'orca-app-release-'));
  t.after(() => rm(dir, { recursive: true }));
  const server = createApi({ orcaBin: '/nonexistent', digest: tokenDigest('test-token'), appDir: dir }, timeout);
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(async () => {
    server.close();
    server.closeAllConnections();
    await once(server, 'close');
  });
  const request = (path = '/api/app', method = 'GET', authenticated = true) => fetch(
    `http://127.0.0.1:${(server.address() as AddressInfo).port}${path}`,
    { method, headers: authenticated ? { Authorization: 'Bearer test-token' } : {} }
  );
  return {
    request,
    apkPath: join(dir, 'orca-bot-remote.apk'),
    metadataPath: join(dir, 'release.json'),
    metadata: async () => {
      const response = await request();
      assert.equal(response.status, 200);
      return response.json();
    }
  };
}

test('アプリのメタデータには開いた APK のサイズと SHA-256 を含め、差し替え後は再計算する', async t => {
  const { apkPath, metadata, metadataPath } = await fixture(t);
  await writeFile(metadataPath, JSON.stringify({ ...release, sha256: 'ignored' }));
  for (const bytes of [apk, Buffer.from([1, 2, 3, 4])]) {
    await writeFile(apkPath + '.new', bytes);
    await rename(apkPath + '.new', apkPath);
    assert.deepEqual(await metadata(), {
      app: { ...release, sha256: createHash('sha256').update(bytes).digest('hex'), size: bytes.length }
    });
  }
});

test('未配置、壊れたメタデータ、空または過大な APK は配信対象にしない', async t => {
  const { request, apkPath, metadata, metadataPath } = await fixture(t);
  assert.deepEqual(await metadata(), { app: null });
  assert.equal((await request('/api/app/apk')).status, 404);
  await writeFile(apkPath, apk);
  for (const invalid of ['{', JSON.stringify({ ...release, versionCode: 0 }),
    JSON.stringify({ ...release, versionCode: 2 ** 53 }), JSON.stringify({ ...release, versionName: 'v1\n' }),
    JSON.stringify({ ...release, versionName: 'a'.repeat(65) })]) {
    await writeFile(metadataPath, invalid);
    assert.deepEqual(await metadata(), { app: null });
  }
  await writeFile(metadataPath, JSON.stringify(release));
  await writeFile(apkPath, '');
  assert.deepEqual(await metadata(), { app: null });
  assert.equal((await request('/api/app/apk')).status, 404);
  await truncate(apkPath, 200 * 1024 * 1024 + 1);
  assert.deepEqual(await metadata(), { app: null });
  assert.equal((await request('/api/app/apk')).status, 404);
});

test('メタデータと APK のシンボリックリンクを拒否する', async t => {
  const { request, apkPath, metadata, metadataPath } = await fixture(t);
  await writeFile(apkPath, apk);
  await writeFile(metadataPath + '.real', JSON.stringify(release));
  await symlink(metadataPath + '.real', metadataPath);
  assert.deepEqual(await metadata(), { app: null });
  await rm(metadataPath);
  await writeFile(metadataPath, JSON.stringify(release));
  await rename(apkPath, apkPath + '.real');
  await symlink(apkPath + '.real', apkPath);
  assert.deepEqual(await metadata(), { app: null });
  assert.equal((await request('/api/app/apk')).status, 404);
});

test('APK は読み取り期限を適用せず、固定の Content-Type とバイト数でそのまま返す', async t => {
  const { request, apkPath } = await fixture(t, 0);
  await writeFile(apkPath, apk);
  const response = await request('/api/app/apk');
  assert.equal(response.status, 200);
  assert.equal(response.headers.get('content-type'), 'application/vnd.android.package-archive');
  assert.equal(response.headers.get('content-length'), String(apk.length));
  assert.deepEqual(Buffer.from(await response.arrayBuffer()), apk);
});

test('更新の両ルートとも Bearer 認証済みの GET に限る', async t => {
  const { request } = await fixture(t);
  for (const path of ['/api/app', '/api/app/apk']) {
    assert.equal((await request(path, 'GET', false)).status, 401);
    const post = await request(path, 'POST');
    assert.equal(post.status, 405);
    assert.equal(post.headers.get('allow'), 'GET');
  }
});
