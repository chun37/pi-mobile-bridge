import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { ModelRuntime, ModelRegistry } from '@earendil-works/pi-coding-agent';
import { refreshModels, cancelModelRefresh } from '../extension/model-refresh.ts';
import { modelCatalog, runSlashCommand } from '../extension/commands.ts';

const success = () => ({ aborted: false, errors: new Map() });
const model = { provider: 'fixture-live', id: 'new-model', name: 'New', contextWindow: 8192 };
function context(registry) {
  return { modelRegistry: registry, isIdle: () => true, sessionManager: { getSessionId: () => 'same-session' } };
}
test('newly authenticated models are read after refresh, not before it', async () => {
  let models = [];
  const registry = { getAvailable: () => models, refresh: async options => {
    assert.equal(options.allowNetwork, false); assert.ok(options.signal instanceof AbortSignal);
    models = [model]; return success();
  } };
  assert.deepEqual((await modelCatalog(context(registry))).models, [{ value: 'fixture-live/new-model', name: 'New', contextWindow: 8192 }]);
});
test('concurrent lookups share one refresh but subsequent opens refresh again', async () => {
  let calls = 0, finish;
  const registry = { refresh: () => { calls++; return new Promise(resolve => { finish = resolve; }); } };
  const a = refreshModels(registry), b = refreshModels(registry);
  assert.equal(a, b); await Promise.resolve(); assert.equal(calls, 1); finish(success()); await a;
  const c = refreshModels(registry); await Promise.resolve(); assert.equal(calls, 2); finish(success()); await c;
});
test('timeout bounds a provider which ignores abort and permits a later retry', async () => {
  const registry = { refresh: () => new Promise(() => {}) };
  await assert.rejects(refreshModels(registry, 20), /タイムアウト/);
  registry.refresh = async () => success(); await refreshModels(registry, 100);
});
test('provider errors and config errors never disclose credentials', async () => {
  await assert.rejects(refreshModels({ refresh: async () => { throw Error('SECRET_TOKEN'); } }), error => {
    assert.ok(!error.message.includes('SECRET')); return true;
  });
  await assert.rejects(refreshModels({ refresh: async () => success(), getError: () => 'SECRET_CONFIG' }), /更新できません/);
});
test('partial provider failures retain available candidates with a safe warning', async () => {
  const result = await modelCatalog(context({ getAvailable: () => [model], refresh: async () => ({ aborted: false, errors: new Map([['fixture', Error('SECRET')]]) }) }));
  assert.equal(result.models.length, 1); assert.ok(result.modelRefreshWarning); assert.ok(!JSON.stringify(result).includes('SECRET'));
});
test('shutdown cancels refresh without cancelling the conversation', async () => {
  const registry = { refresh: () => new Promise(() => {}) };
  const pending = refreshModels(registry); cancelModelRefresh(registry);
  await assert.rejects(pending, /更新できません/);
});
test('a directly typed new model refreshes before selection and never sends a prompt', async () => {
  let models = [], selected;
  const registry = { getAvailable: () => models, refresh: async () => { models = [model]; return success(); } };
  const pi = { getCommands: () => [], setModel: async m => { selected = m; return true; }, sendUserMessage: () => assert.fail('must not prompt') };
  await runSlashCommand(pi, context(registry), '/model fixture-live/new-model');
  assert.equal(selected, model);
});
test('a run starting during refresh prevents model mutation', async () => {
  let idle = true;
  const registry = { getAvailable: () => [model], refresh: async () => { idle = false; return success(); } };
  const ctx = { ...context(registry), isIdle: () => idle };
  await assert.rejects(runSlashCommand({ getCommands: () => [], setModel: () => assert.fail('busy') }, ctx, '/model fixture-live/new-model'), /完了してから/);
});
test('a session generation change during refresh prevents model mutation', async () => {
  const registry = { getAvailable: () => [model], refresh: async () => success() };
  await assert.rejects(runSlashCommand({ getCommands: () => [], setModel: () => assert.fail('stale') }, context(registry), '/model fixture-live/new-model', () => false), /session_changed/);
});
test('real SDK sees credential and models.json additions and removals in the same registry', async t => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pi-bridge-model-refresh-')); t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const authPath = path.join(dir, 'auth.json'), modelsPath = path.join(dir, 'models.json');
  const config = ids => ({ providers: { 'fixture-live': { baseUrl: 'http://127.0.0.1:9/v1', api: 'openai-completions', models: ids.map(id => ({ id, contextWindow: 8192, maxTokens: 1024 })) } } });
  fs.writeFileSync(authPath, '{}'); fs.writeFileSync(modelsPath, JSON.stringify(config(['new-model'])));
  const runtime = await ModelRuntime.create({ authPath, modelsPath, modelsStorePath: path.join(dir,'models-store.json'), allowModelNetwork: false });
  const registry = new ModelRegistry(runtime), ctx = context(registry);
  const ownModels = () => registry.getAvailable().filter(m => m.provider === 'fixture-live');
  assert.equal(ownModels().length, 0);
  fs.writeFileSync(authPath, JSON.stringify({ 'fixture-live': { type: 'api_key', key: 'test-only-not-a-real-token' } }));
  assert.equal(ownModels().length, 0, 'reproduce the stale synchronous snapshot');
  assert.ok((await modelCatalog(ctx)).models.some(m => m.value === 'fixture-live/new-model'));
  fs.writeFileSync(modelsPath, JSON.stringify(config(['new-model','added-later'])));
  assert.ok((await modelCatalog(ctx)).models.some(m => m.value === 'fixture-live/added-later'));
  fs.writeFileSync(authPath, '{}'); await modelCatalog(ctx); assert.equal(ownModels().length, 0);
});
