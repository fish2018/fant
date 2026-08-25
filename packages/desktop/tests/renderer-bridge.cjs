'use strict';

const assert = require('node:assert/strict');
const vm = require('node:vm');

const { rendererBridgeSource } = require('../scripts/embed-renderer-bridge.cjs');

const context = vm.createContext({
  atob,
  btoa,
  DOMException
});
const factory = vm.runInContext(rendererBridgeSource().toString('utf8'), context);
assert.equal(typeof factory, 'function');

const replies = [];
const bridge = factory({
  handlers: {
    echo(_event, value) {
      return value;
    }
  },
  listeners: {},
  reply(...args) {
    replies.push(args);
  },
  windows: {}
});

assert.equal(typeof bridge.dispatch, 'function');
assert.equal(typeof bridge.encode, 'function');
bridge.dispatch(1, 1, 2, 'echo', bridge.encode({ value: 42 })).then(() => {
  assert.equal(replies.length, 1);
  assert.equal(replies[0][0], 1);
  assert.equal(replies[0][1], 2);
  assert.equal(replies[0][2], true);

  const messages = [];
  const renderer = factory({
    capabilityManifest: 'send:test',
    nativeIpc(...args) {
      messages.push(args);
    }
  });
  assert.equal(context.ant, undefined);
  assert.equal(typeof context.Ant.ipc.send, 'function');
  assert.equal(typeof context.Ant.expose, 'function');
  assert.equal(renderer.ipcRenderer, context.Ant.ipc);
  renderer.contextBridge.exposeInMainWorld('exampleApi', { ready: true });
  assert.equal(context.exampleApi.ready, true);
  assert.equal(typeof context.Ant.versions.desktop, 'string');
  assert.equal(typeof context.Ant.versions.ant, 'string');
  assert.equal(context.Ant.versions.chrome, '150.0.7871.115');
  assert.ok(Object.isFrozen(context.Ant.versions));
  context.Ant.ipc.send('test', { value: 42 });
  assert.equal(messages.length, 1);
  console.log('desktop-renderer-bridge-ok');
});
