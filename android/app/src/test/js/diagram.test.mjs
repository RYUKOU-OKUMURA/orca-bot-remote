import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

test('a superseded render cannot report a failure or replace the newest diagram', async () => {
  const started = [];
  const sizes = [];
  const container = { querySelector: () => ({ getBoundingClientRect: () => ({ height: 95 }) }) };
  let finishFirst;
  const first = new Promise(resolve => { finishFirst = resolve; });
  const page = vm.createContext({
    document: { getElementById: () => container },
    DiagramSize: { report: (id, height) => sizes.push([id, height]) },
    ResizeObserver: class { observe() {} disconnect() {} },
    mermaid: {
      mermaidAPI: { defaultConfig: { secure: [] } },
      initialize() {},
      async render(id, source) {
        started.push(source);
        if (source === 'first') {
          await first;
          throw new Error('old diagram failed');
        }
        return { svg: source };
      },
    },
  });
  const script = new URL('../../main/assets/mermaid/diagram.js', import.meta.url);
  vm.runInContext(await readFile(script, 'utf8'), page);
  page.renderDiagram('first', 'default', 1);
  await Promise.resolve();
  page.renderDiagram('intermediate', 'dark', 2);
  page.renderDiagram('latest', 'default', 3);
  finishFirst();
  await vm.runInContext('rendering', page);

  assert.deepEqual(started, ['first', 'latest']);
  assert.deepEqual(sizes, [[3, 95]]);
  assert.equal(container.innerHTML, 'latest');
});
