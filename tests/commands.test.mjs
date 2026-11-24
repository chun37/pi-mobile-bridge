import { test } from 'node:test';
import assert from 'node:assert/strict';
import { commandCatalog, contextUsage, runSlashCommand, RELOAD_COMMAND } from '../extension/commands.ts';

function fixture() {
  const calls = [];
  const models = [{ provider: 'local', id: 'fixture', name: 'Local', contextWindow: 100000 }];
  const pi = {
    getCommands: () => [
      { name: '日本語', description: '拡張', source: 'extension', path: '/private/path' },
      { name: 'prompt', source: 'prompt' }, { name: 'skill:local', source: 'skill' },
      { name: RELOAD_COMMAND, source: 'extension' }, { name: 'new', source: 'extension' },
    ],
    setModel: async m => { calls.push(['model', m]); return true; },
    setThinkingLevel: v => calls.push(['thinking', v]), getThinkingLevel: () => 'high',
    setSessionName: v => calls.push(['name', v]),
    sendUserMessage: (...v) => calls.push(['send', ...v]),
  };
  const ctx = { isIdle: () => true, model: models[0], modelRegistry: { getAvailable: () => models, refresh: async () => ({ aborted: false, errors: new Map() }) },
    sessionManager: { getSessionName: () => 'Fixture', getSessionId: () => 'fixture-session' },
    getContextUsage: () => ({ tokens: 12345, contextWindow: 100000, percent: 12.345 }),
    compact: opts => calls.push(['compact', opts]) };
  return { pi, ctx, calls };
}

test('catalog excludes private paths, internal reload and destructive /new', () => {
  const { pi } = fixture();
  const list = commandCatalog(pi);
  assert.equal(list.filter(c => c.name === 'model').length, 1);
  assert.ok(!list.some(c => c.name === 'new' || c.name === RELOAD_COMMAND || c.path));
  pi.getCommands = () => [{ name: 'model', source: 'extension' }];
  assert.deepEqual(commandCatalog(pi).find(c => c.name === 'model'), { name: 'model', description: '', source: 'extension' });
});
test('unknown/null/invalid usage is not zero; percentages above 100 remain real', () => {
  const { ctx } = fixture();
  assert.equal(contextUsage(ctx).percent, 12.345);
  ctx.getContextUsage = () => ({ tokens: null, percent: null, contextWindow: 1000 });
  assert.equal(contextUsage(ctx).percent, null);
  ctx.getContextUsage = () => ({ tokens: 1200, percent: 120, contextWindow: 1000 });
  assert.equal(contextUsage(ctx).percent, 120);
  ctx.getContextUsage = () => ({ tokens: NaN, percent: Infinity, contextWindow: 1000 });
  assert.equal(contextUsage(ctx).percent, null);
  ctx.getContextUsage = () => { throw new Error('unavailable'); };
  assert.equal(contextUsage(ctx), null);
});
test('registered extension, prompt and skill all opt into SDK expansion', async () => {
  const { pi, ctx, calls } = fixture();
  for (const text of ['/日本語 引数', '/prompt args', '/skill:local args']) await runSlashCommand(pi, ctx, text);
  assert.equal(calls.length, 3);
  for (const call of calls) assert.deepEqual(call[2], { expandPromptTemplates: true });
  for (const text of ['/new', '/quit', '/unknown', '/', '//path']) await assert.rejects(runSlashCommand(pi, ctx, text));
  assert.equal(calls.length, 3);
});
test('core commands use official SDK APIs without prompting a model', async () => {
  const { pi, ctx, calls } = fixture();
  await runSlashCommand(pi, ctx, '/model local/fixture');
  await runSlashCommand(pi, ctx, '/thinking high');
  await runSlashCommand(pi, ctx, '/name テスト');
  await runSlashCommand(pi, ctx, '/compact 日本語で要約');
  const result = await runSlashCommand(pi, ctx, '/session');
  assert.match(result.message, /12\.3%/);
  assert.deepEqual(calls.map(c => c[0]), ['model', 'thinking', 'name', 'compact']);
  assert.deepEqual(calls[3][1], { customInstructions: '日本語で要約' });
  for (const text of ['/model', '/model missing', '/thinking invalid', '/name', '/name bad\nname', '/reload args'])
    await assert.rejects(runSlashCommand(pi, ctx, text));
  ctx.isIdle = () => false;
  await assert.rejects(runSlashCommand(pi, ctx, '/compact'));
  assert.equal(calls.length, 4);
});
test('reload is deferred until after the RPC acknowledgement', async () => {
  const { pi, ctx, calls } = fixture();
  const result = await runSlashCommand(pi, ctx, '/reload');
  assert.equal(result.status, 'command');
  assert.equal(calls.length, 0);
  await new Promise(r => setImmediate(r));
  assert.deepEqual(calls, [['send', `/${RELOAD_COMMAND}`, { expandPromptTemplates: true }]]);
});
