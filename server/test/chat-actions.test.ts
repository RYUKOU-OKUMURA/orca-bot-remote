import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { once } from 'node:events';
import { mkdir, mkdtemp, realpath, rm, symlink, writeFile } from 'node:fs/promises';
import type { AddressInfo } from 'node:net';
import { homedir, tmpdir } from 'node:os';
import { join } from 'node:path';
import { test, type TestContext } from 'node:test';
import { fileURLToPath } from 'node:url';
import { attachmentStore, maxUploadBytes, inlineUploads } from '../src/attachments.ts';
import { commandMenu, dedupeModels, modelOptions } from '../src/commands.ts';
import { tokenDigest } from '../src/config.ts';
import { createApi } from '../src/http.ts';
import { createOrcaWrite, type messageParams } from '../src/orca-write.ts';
import { device, drop, hold, installFakeHost } from './fixtures/orca-protocol/fake-host.ts';
import { structuredAgentSessionPayloadFingerprint as fingerprint } from '../vendor/orca/shared/agent-session/lifecycle/operations/structured-agent-session-mutation.js';

type Envelope = ReturnType<typeof messageParams>['envelope'];

const chat = 'abcdef00-abcd-1234-abcd-123456789abc';
const chats = '/api/bots/' + encodeURIComponent('専門Bot') + '/chats';
const route = chats + '/' + chat;
const closed = '/api/bots/' + encodeURIComponent('メインBot') + '/chats/12345678-abcd-1234-abcd-123456789abc';
const sessionId = 'resolved-session';
const methods = (calls: { method: string }[]) => calls.map(call => call.method);
const baseHeaders = { Authorization: 'Bearer test', 'Content-Type': 'application/json' };

async function fixture(t: TestContext, handler: Parameters<typeof installFakeHost>[0], paired = true) {
  const home = await realpath(await mkdtemp(join(tmpdir(), 'orca-chat-api-')));
  const previousHome = process.env.HOME;
  process.env.HOME = home;
  t.after(async () => {
    if (previousHome === undefined) delete process.env.HOME;
    else process.env.HOME = previousHome;
    await rm(home, { recursive: true, force: true });
  });
  const file = join(home, 'device.json');
  if (paired) await writeFile(file, JSON.stringify(device), { mode: 0o600 });
  const host = installFakeHost(request => {
    if (request.method === 'orchestration.partyLocation') {
      assert.deepEqual(request.params, { address: 'orca_session_id:' + chat });
      return { result: { location: { kind: 'chat', sessionId } } };
    }
    if (request.method === 'agentSession.history') {
      assert.deepEqual(request.params, { sessionId, direction: 'tail', limit: 1 });
      return { result: { ok: true, page: { fence: 7 } } };
    }
    return handler(request);
  });
  t.after(host.restore);
  const server = createApi({
    digest: tokenDigest('test'), orcaBin: fileURLToPath(new URL('./fixtures/orca', import.meta.url)), write: createOrcaWrite(file),
    tasksFile: join(home, 'tasks.json')
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(async () => {
    const closed = once(server, 'close');
    server.close();
    server.closeAllConnections();
    await closed;
  });
  const base = 'http://127.0.0.1:' + (server.address() as AddressInfo).port;
  const request = (path: string, init?: RequestInit) => fetch(base + path, { headers: baseHeaders, ...init });
  const post = (path: string, body: object) => request(path, { method: 'POST', body: JSON.stringify(body) });
  const upload = (bytes: Buffer, name?: string, path = route) => request(path + '/attachments', {
    method: 'POST', body: bytes, headers: { ...baseHeaders, 'Content-Type': 'application/octet-stream',
      ...(name !== undefined ? { 'X-File-Name': encodeURIComponent(name) } : {}) }
  });
  return { ...host, request, post, upload, base };
}

test('chat actions enforce authorization, method, bot membership, closed state and pairing', async t => {
  const f = await fixture(t, () => assert.fail(), false);
  for (const [action, method] of [['commands', 'GET'], ['command', 'POST'], ['attachments', 'POST']]) {
    assert.equal((await f.request(route + '/' + action, { headers: {}, method })).status, 401);
    const wrong = await f.request(route + '/' + action, { method: 'PUT' });
    assert.equal(wrong.status, 405);
    assert.equal(wrong.headers.get('allow'), method);
  }
  for (const path of [route.replace('abcdef00', 'abcdffff'), route.replace(encodeURIComponent('専門Bot'), 'other')]) {
    assert.equal((await f.request(path + '/commands')).status, 404);
  }
  const noPair = await f.request(route + '/commands');
  assert.equal(noPair.status, 503);
  assert.equal((await noPair.json()).error, 'write_unavailable');
  const conflict = await f.post(closed + '/command', { name: 'clear' });
  assert.equal(conflict.status, 409);
  assert.deepEqual(await conflict.json(), { error: 'conflict', message: 'このチャットは閉じています。' });
  assert.equal((await f.upload(Buffer.from('x'), 'x', closed)).status, 409);
  assert.deepEqual(f.calls, []);
});

test('command menu uses reported commands, skills and the current model’s efforts', async t => {
  let supported = true;
  const f = await fixture(t, ({ method, params }) => {
    assert.deepEqual(params, { sessionId });
    if (method === 'agentSession.commands') return { result: { commands: [
      { name: 'compact', kind: 'command' }, { name: 'help', description: 'Help' },
      { name: 'notes', kind: 'skill', path: '/skills/n', argumentHint: '[topic]' }, { name: 'review', path: '/skills/r' }
    ] } };
    assert.equal(method, 'agentSession.options');
    return { result: { current: { model: 'second', effort: 'high' }, models: supported ? [
      { id: 'first', label: 'First', efforts: [] }, { id: 'second', label: 'Second', efforts: [{ value: 'high', label: 'High' }] }
    ] : [], conversationCommands: supported ? ['compact'] : [], compactInstructions: true } };
  });
  const response = await f.request(route + '/commands');
  assert.equal(response.status, 200);
  const menu = await response.json();
  assert.deepEqual(menu.commands.map((c: { name: string; kind: string; argumentHint: string }) => [c.name, c.kind, c.argumentHint]),
    [['compact', 'conversation', '[指示]'], ['model', 'option', '<モデル>'], ['effort', 'option', '<強さ>'], ['help', 'text', null]]);
  assert.deepEqual(menu.options, { model: { current: 'second', choices: [{ value: 'first', label: 'First' }, { value: 'second', label: 'Second' }] },
    effort: { current: 'high', choices: [{ value: 'high', label: 'High' }] } });
  assert.deepEqual(menu.skills, [
    { name: 'notes', path: '/skills/n', description: '', argumentHint: '[topic]' },
    { name: 'review', path: '/skills/r', description: '', argumentHint: null }
  ]);
  supported = false;
  assert.deepEqual((await (await f.request(route + '/commands')).json()).options, {});
});

test('placeholder choices do not become selectable models or efforts', () => {
  const placeholder = { id: '', label: 'Model not reported', efforts: [], isDefault: true };
  const model = { id: 'm', label: 'Model', isDefault: true, efforts: [
    { value: '', label: 'Effort not reported' }, { value: 'high', label: 'High' }
  ] };
  const current = { model: '', effort: '' };
  assert.deepEqual(commandMenu({}, { current, models: [placeholder, model] }).options, {
    model: { current: null, choices: [{ value: 'm', label: 'Model' }] },
    effort: { current: null, choices: [{ value: 'high', label: 'High' }] }
  });
  const empty = commandMenu({}, { current, models: [placeholder] });
  assert.deepEqual(empty.options, {});
  assert.deepEqual(empty.commands, []);
});

test('short model aliases with the same label are dropped from /model and new-chat choices', () => {
  const efforts = [{ value: 'high', label: 'High' }];
  const models = [
    { id: 'claude-opus-5-5', resolvedModel: 'claude-opus-5-5', label: 'Opus 5.5', isDefault: true, efforts, defaultEffort: 'high' },
    { id: 'claude-fable-5-1', resolvedModel: 'claude-fable-5-1', label: 'Fable 5.1', efforts },
    { id: 'opus', resolvedModel: 'claude-opus-5-5', label: 'Opus 5.5', efforts },
    { id: 'fable', resolvedModel: 'claude-fable-5-1', label: 'Fable 5.1', efforts },
    // An alias whose target is not listed is the only way to pick that model.
    { id: 'mythos', resolvedModel: 'claude-mythos-9', label: 'Mythos', efforts },
    { id: 'claude-opus-5-5', label: 'Opus 5.5 again', efforts }
  ];
  const ids = (list: { id: string }[]) => list.map(m => m.id);
  assert.deepEqual(ids(dedupeModels(models).models), ['claude-opus-5-5', 'claude-fable-5-1', 'mythos']);
  // A session running the alias reports it as the full id.
  const menu = commandMenu({}, { current: { model: 'opus', effort: 'high' }, models });
  assert.deepEqual(menu.options.model, { current: 'claude-opus-5-5', choices: [
    { value: 'claude-opus-5-5', label: 'Opus 5.5' }, { value: 'claude-fable-5-1', label: 'Fable 5.1' }, { value: 'mythos', label: 'Mythos' }
  ] });
  assert.deepEqual(menu.options.effort, { current: 'high', choices: efforts });
  assert.deepEqual(modelOptions({ models }).defaultEffort, 'high');
  assert.equal(modelOptions({ models, current: { model: 'fable', effort: 'low' } }).defaultModel, 'claude-fable-5-1');

  // Without resolvedModel: a later shorter id with the same label and efforts is an alias,
  // unless it is the default, the current model, or has different efforts.
  const bare = [
    { id: 'claude-sonnet-5-5', label: 'Sonnet 5.5', efforts },
    { id: 'sonnet', label: 'Sonnet 5.5', efforts },
    { id: 'haiku-long-id', label: 'Haiku', efforts },
    { id: 'haiku', label: 'Haiku', efforts: [] },
    { id: 'gpt-x', label: 'GPT', efforts },
    { id: 'gpt', label: 'GPT', efforts, isDefault: true }
  ];
  assert.deepEqual(ids(dedupeModels(bare).models), ['claude-sonnet-5-5', 'haiku-long-id', 'haiku', 'gpt-x', 'gpt']);
  assert.deepEqual(ids(dedupeModels(bare, 'sonnet').models), ids(bare));
  // A dropped default alias moves the default flag to its target.
  const promoted = dedupeModels([
    { id: 'claude-opus-5-5', resolvedModel: 'claude-opus-5-5', label: 'Opus 5.5', efforts },
    { id: 'opus', resolvedModel: 'claude-opus-5-5', label: 'Opus 5.5', isDefault: true, efforts }
  ]).models;
  assert.deepEqual(promoted.map(m => [m.id, m.isDefault]), [['claude-opus-5-5', true]]);
});

test('only the newest Claude model of each family is offered, keeping current and default', () => {
  const efforts = [{ value: 'high', label: 'High' }];
  const row = (id: string, extra = {}) => ({ id, resolvedModel: id, label: id, efforts, ...extra });
  const alias = (id: string, to: string) => ({ id, resolvedModel: to, label: to, efforts });
  const real = [
    row('claude-opus-5-5', { isDefault: true }), row('claude-opus-5'), row('claude-opus-4-8'), row('claude-opus-4-7'), row('claude-opus-4-6'),
    row('claude-fable-5-2'), row('claude-fable-5-1'), row('claude-fable-5'),
    row('claude-sonnet-5-5'), row('claude-sonnet-5'), row('claude-sonnet-4-6'),
    row('claude-haiku-5-5'), row('claude-haiku-4-5-20251001'),
    row('claude-mythos-5-2'), row('claude-mythos-5-1'),
    alias('opus', 'claude-opus-5-5'), alias('sonnet', 'claude-sonnet-5-5'), alias('haiku', 'claude-haiku-5-5'), alias('fable', 'claude-fable-5-2')
  ];
  assert.equal(real.length, 19);
  const latest = ['claude-opus-5-5', 'claude-fable-5-2', 'claude-sonnet-5-5', 'claude-haiku-5-5', 'claude-mythos-5-2'];
  const ids = (list: { id: string }[]) => list.map(m => m.id);
  assert.deepEqual(ids(modelOptions({ models: real }).models), latest);
  assert.deepEqual(commandMenu({}, { models: real, current: { model: 'opus' } }).options.model?.choices.map(c => c.value), latest);

  // An older current or default model stays, in its original place.
  assert.deepEqual(ids(modelOptions({ models: real, current: { model: 'claude-opus-4-7' } }).models),
    ['claude-opus-5-5', 'claude-opus-4-7', ...latest.slice(1)]);
  assert.deepEqual(commandMenu({}, { models: real, current: { model: 'claude-fable-5' } }).options.model,
    { current: 'claude-fable-5', choices: ['claude-opus-5-5', 'claude-fable-5-2', 'claude-fable-5', ...latest.slice(2)].map(value => ({ value, label: value })) });
  const oldDefault = real.map(m => ({ ...m, isDefault: m.id === 'claude-sonnet-4-6' }));
  const options = modelOptions({ models: oldDefault });
  assert.deepEqual(ids(options.models), [...latest.slice(0, 3), 'claude-sonnet-4-6', ...latest.slice(3)]);
  assert.equal(options.defaultModel, 'claude-sonnet-4-6');

  // Versions compare as numbers; an undated id beats a dated one, a newer date beats an older one.
  const latestOf = (...list: string[]) => ids(dedupeModels(list.map(id => row(id))).models);
  assert.deepEqual(latestOf('claude-opus-5', 'claude-opus-5-5'), ['claude-opus-5-5']);
  assert.deepEqual(latestOf('claude-opus-4-10', 'claude-opus-4-8'), ['claude-opus-4-10']);
  assert.deepEqual(latestOf('claude-opus-9', 'claude-opus-10'), ['claude-opus-10']);
  assert.deepEqual(latestOf('claude-haiku-4-5-20251001', 'claude-haiku-4-5'), ['claude-haiku-4-5']);
  assert.deepEqual(latestOf('claude-haiku-4-5-20251001', 'claude-haiku-4-5-20260101'), ['claude-haiku-4-5-20260101']);
  assert.deepEqual(latestOf('claude-3-5-sonnet-20241022', 'claude-3-7-sonnet-20250219', 'claude-sonnet-4-6'), ['claude-sonnet-4-6']);

  // Other providers' models and other families are untouched.
  const others = ['gpt-6.2-sol', 'gpt-6.1', 'gemini-4-neon', 'gemini-3', 'claude-opus-5-5', 'claude-sonnet-5-5', 'claude-code'];
  assert.deepEqual(latestOf(...others), others);
});

test('command mutations use current fence, operation ID and exact fingerprint fields', async t => {
  const expected = [
    { command: 'clear' }, { command: 'compact' }, { command: 'compact', instructions: 'keep context' },
    { key: 'model', value: 'm' }, { key: 'effort', value: 'high' }
  ];
  let index = 0;
  const f = await fixture(t, ({ method, params }) => {
    const { envelope, ...fields } = params as { envelope: Envelope };
    const field = expected[index++];
    assert.deepEqual(fields, field);
    assert.equal(method, field.key ? 'agentSession.setOption' : 'agentSession.conversationCommand');
    assert.deepEqual(envelope, {
      sessionId, clientOperationId: envelope.clientOperationId, expectedRuntimeFence: 7,
      payloadFingerprint: fingerprint({ method, sessionId, fields: field })
    });
    assert.match(envelope.clientOperationId, /^\d{13}-[a-f0-9]{32}$/);
    return { result: { ok: true, value: field.key ? { ...field, options: {} } : { command: field.command, state: 'completed' } } };
  });
  for (const field of expected) {
    const response = await f.post(route + '/command', { name: field.command ?? field.key, argument: field.instructions ?? field.value });
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { ok: true });
  }
  assert.equal(index, 5);
  for (const body of [{ name: 'clear', argument: ' ' }, { name: 'compact', argument: 'x'.repeat(10001) },
    { name: 'model' }, { name: 'effort', argument: 'x'.repeat(513) }, { name: 'clear', argument: null }, { name: 'help' }]) {
    assert.equal((await f.post(route + '/command', body)).status, 400);
  }
  assert.equal(f.calls.length, 15);
});

test('a dropped, ambiguous, or refused command never gets replayed; error details are private', async t => {
  const replies = [drop, { result: { ok: true, value: { command: 'compact', state: 'unknown', error: 'secret' } } },
    { result: { ok: true, value: { command: 'compact', state: 'completed', error: 'secret' } } }, { result: { ok: true } }] as const;
  let index = 0;
  const f = await fixture(t, () => replies[index++]);
  for (const code of ['unknown_result', 'unknown_result', 'orca_failed', 'unknown_result']) {
    const res = await f.post(route + '/command', { name: 'compact' });
    assert.equal(res.status, 502);
    const err = await res.json();
    assert.equal(err.error, code);
    assert.doesNotMatch(err.message, /secret/);
  }
  assert.equal(index, 4);
});

test('messages bind stored files, images and skills in the fingerprint and reject invalid paths', async t => {
  let body: unknown;
  const f = await fixture(t, ({ method, params }) => {
    if (method === 'agentSession.commands') {
      assert.deepEqual(params, { sessionId });
      return { result: { commands: [{ kind: 'skill', name: 'note', path: '/skills/note' }] } };
    }
    assert.equal(method, 'agentSession.send');
    body = params!.body;
    const envelope = params!.envelope as Envelope;
    assert.equal(envelope.payloadFingerprint, fingerprint({ method, sessionId, fields: { body } }));
    return { result: { ok: true, value: { clientMessageId: envelope.clientOperationId, submission: { reason: null } } } };
  });
  const dir = join(homedir(), 'Library/Application Support/orca/agent-session-attachments', randomUUID());
  await mkdir(dir, { recursive: true });
  const file = join(dir, 'my notes.txt'), image = join(dir, '写真.jpg');
  await writeFile(file, 'notes');
  await writeFile(image, 'image');
  const attachments = [{ kind: 'file', path: file }, { kind: 'image', path: image, name: '画像' }];
  const skills = [{ name: 'note', path: '/skills/note' }];
  for (const text of ['message', undefined]) {
    assert.equal((await f.post(route + '/messages', { text, skills, attachments })).status, 202);
    assert.deepEqual(body, { kind: 'message', role: 'user', skills, blocks: [
      { type: 'text', text: (text ? text + '\n' : '') + '@"' + file + '"' }, { type: 'image-ref', path: image, alt: '画像' }
    ] });
  }
  const link = join(dir, 'link');
  await symlink(process.execPath, link);
  for (const path of [process.execPath, file + '/../../outside', '/tmp/agent-session-attachments/' + randomUUID() + '/fake',
    join(dir, '.upload.part'), file + '\n', link]) {
    assert.equal((await f.post(route + '/messages', { attachments: [{ path, kind: 'file' }] })).status, 400);
  }
  for (const bad of [{ skills: null }, { skills: Array(17).fill(skills[0]) }, { skills: [{ name: 'x', path: '\nabc' }] },
    { skills: [{ name: ' ', path: 'p' }] }, { attachments: Array(11).fill(attachments[0]) }, { attachments: [{ path: image, kind: 'audio' }] }]) {
    assert.equal((await f.post(route + '/messages', { text: 'text', ...bad })).status, 400);
  }
  assert.equal(methods(f.calls).filter(m => m === 'agentSession.send').length, 2);
});

test('skill sends require every name and path to be reported by the current session', async t => {
  const skill = { name: 'note', path: '/skills/note' };
  const replies = [
    { result: { commands: [skill] } }, { result: { commands: [skill] } },
    { error: { code: 'method_not_supported' } }, { result: { commandsPending: true, commands: [] } }, { result: {} }
  ];
  const f = await fixture(t, ({ method, params }) => {
    assert.equal(method, 'agentSession.commands');
    assert.deepEqual(params, { sessionId });
    return replies.shift()!;
  });
  for (const [skills, status] of [
    [[skill, { ...skill, path: '/unlisted' }], 400], [[{ ...skill, name: 'other' }], 400],
    [[skill], 502], [[skill], 502], [[skill], 502]
  ] as const) {
    const res = await f.post(route + '/messages', { text: 'hi', skills });
    assert.equal(res.status, status);
    assert.equal((await res.json()).error, status === 400 ? 'bad_request' : 'orca_failed');
  }
  assert.deepEqual(methods(f.calls), Array(5).fill(['orchestration.partyLocation', 'agentSession.commands']).flat());
});

test('after session creation an upload or first message failure makes the creation request uncertain', async t => {
  let failStage = 'upload';
  const f = await fixture(t, ({ method }) => {
    if (method === 'bots.session.start') return { result: { sessionId } };
    assert.equal(method, failStage === 'upload' ? 'agentSessionAttachment.uploadStart' : 'agentSession.send');
    return { error: { code: failStage === 'auth' ? 'device_revoked' : 'forbidden' } };
  });
  for (const stage of ['upload', 'send', 'auth']) {
    failStage = stage;
    const body = stage === 'upload' ? { inlineAttachments: [{ name: 'x', contentBase64: 'WA==' }] } : { text: 'hi' };
    const res = await f.post(chats, body);
    assert.equal(res.status, stage === 'auth' ? 503 : 502);
    assert.equal((await res.json()).error, stage === 'auth' ? 'write_unavailable' : 'unknown_result');
  }
  assert.deepEqual(methods(f.calls), [
    'bots.session.start', 'agentSessionAttachment.uploadStart', 'bots.session.start', 'agentSession.history',
    'agentSession.send', 'bots.session.start', 'agentSession.history', 'agentSession.send'
  ]);
});

test('raw and inline uploads use ordered 384 KiB chunks, verify commit, and send only after upload', async t => {
  const id = randomUUID();
  const bytes = Buffer.alloc(384 * 1024 + 13, 7);
  Buffer.from('GIF89a').copy(bytes);
  let offset = 0;
  const name = '仕事';
  const f = await fixture(t, ({ method, params }) => {
    switch (method) {
      case 'bots.session.start':
        assert.deepEqual(params, { botId: 'other' });
        return { result: { sessionId } };
      case 'agentSessionAttachment.uploadStart':
        assert.deepEqual(params, { sessionId, name, byteLength: bytes.length });
        offset = 0;
        return { result: { uploadId: id } };
      case 'agentSessionAttachment.uploadAppend': {
        const chunk = bytes.subarray(offset, offset + 384 * 1024);
        assert.deepEqual(params, { uploadId: id, offset, contentBase64: chunk.toString('base64') });
        offset += chunk.length;
        return { result: { receivedBytes: offset } };
      }
      case 'agentSessionAttachment.uploadCommit':
        assert.deepEqual(params, { uploadId: id });
        assert.equal(offset, bytes.length);
        return { result: { path: join(homedir(), 'Library/Application Support/orca/agent-session-attachments', id, name), name, byteLength: offset } };
      case 'agentSession.send':
        assert.deepEqual(params!.body, { kind: 'message', role: 'user', blocks: [
          { type: 'image-ref', path: join(attachmentStore(), id, name), alt: name }
        ] });
        return { result: { ok: true, value: { clientMessageId: (params!.envelope as Envelope).clientOperationId, submission: {} } } };
      default: assert.fail('unexpected RPC ' + method);
    }
  });
  const result = await f.upload(bytes, '../' + name);
  assert.equal(result.status, 201);
  assert.deepEqual(await result.json(), { path: join(attachmentStore(), id, name), name, kind: 'image', byteLength: bytes.length });
  const created = await f.post(chats, { inlineAttachments: [{ name, contentBase64: bytes.toString('base64') }] });
  assert.equal(created.status, 201);
  assert.deepEqual(await created.json(), { chatId: sessionId });
  const sequence = ['uploadStart', 'uploadAppend', 'uploadAppend', 'uploadCommit'].map(m => 'agentSessionAttachment.' + m);
  assert.deepEqual(methods(f.calls), ['orchestration.partyLocation', ...sequence, 'bots.session.start', ...sequence,
    'agentSession.history', 'agentSession.send']);
});

test('raw uploads reject a missing name, zero bytes and oversized fixed or chunked bodies', async t => {
  const f = await fixture(t, () => assert.fail());
  for (const [bytes, name] of [[Buffer.alloc(0), 'x'], [Buffer.from('x'), undefined], [Buffer.from('x'), 'x'.repeat(257)]] as const) {
    assert.equal((await f.upload(bytes, name)).status, 400);
  }
  const bytes = Buffer.alloc(maxUploadBytes + 1);
  for (const body of [bytes, new ReadableStream({ start(c) { c.enqueue(bytes); c.close(); } })]) {
    const res = await f.request(route + '/attachments', { method: 'POST', headers: { ...baseHeaders, 'X-File-Name': 'x' },
      body, duplex: 'half' } as RequestInit);
    assert.equal(res.status, 400);
    assert.equal(res.headers.get('connection'), 'close');
  }
  assert.deepEqual(f.calls, []);
});

test('failed chunk and client cancellation trigger uploadAbort on a fresh connection', { timeout: 8000 }, async t => {
  const uploadId = randomUUID();
  let cancel = false;
  const pending = Promise.withResolvers<void>();
  const cleaned = Promise.withResolvers<void>();
  const f = await fixture(t, ({ method, params }) => {
    switch (method) {
      case 'agentSessionAttachment.uploadStart': return { result: { uploadId } };
      case 'agentSessionAttachment.uploadAppend':
        if (cancel) pending.resolve();
        return cancel ? hold : { error: { code: 'forbidden' } };
      case 'agentSessionAttachment.uploadAbort':
        assert.deepEqual(params, { uploadId });
        if (cancel) cleaned.resolve();
        return { result: { aborted: true } };
      default: assert.fail();
    }
  });
  assert.equal((await f.upload(Buffer.from('private bytes'), 'x')).status, 502);
  cancel = true;
  const controller = new AbortController();
  const response = f.request(route + '/attachments', { method: 'POST', body: 'x', headers: { ...baseHeaders, 'X-File-Name': 'x' },
    signal: controller.signal });
  await pending.promise;
  controller.abort();
  await assert.rejects(response, { name: 'AbortError' });
  await cleaned.promise;
  assert.equal(methods(f.calls).filter(m => m.endsWith('uploadAbort')).length, 2);
  assert.ok(!methods(f.calls).some(m => m.endsWith('uploadCommit')));
});

test('invalid inline JSON is refused before a session starts, preserving the normal metadata budget', async t => {
  const f = await fixture(t, () => assert.fail());
  for (const inlineAttachments of [null, [], [{ name: 'x', contentBase64: 'xxx' }], [{ name: 'x', contentBase64: 'AA=A' }],
    Array(6).fill({ name: 'x', contentBase64: 'WA==' })]) {
    assert.equal((await f.post(chats, { inlineAttachments })).status, 400);
  }
  const file = { name: 'x', contentBase64: 'WA==' };
  assert.equal((await f.post(chats, { inlineAttachments: [file], padding: ' '.repeat(65536) })).status, 400);
  const big = { name: 'x', contentBase64: Buffer.alloc(12 * 1024 * 1024).toString('base64') };
  assert.throws(() => inlineUploads([big, big], 0));
  assert.throws(() => inlineUploads([{ ...big, contentBase64: big.contentBase64 + big.contentBase64 }], 0));
  assert.deepEqual(f.calls, []);
});
