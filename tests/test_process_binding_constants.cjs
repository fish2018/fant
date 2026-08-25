const assert = require('node:assert');
const fs = require('node:fs');

assert.strictEqual(typeof process.binding, 'function');

const constants = process.binding('constants');
assert.strictEqual(typeof constants, 'object');
assert.strictEqual(typeof constants.fs, 'object');

for (const name of [
  'O_APPEND',
  'O_CREAT',
  'O_EXCL',
  'O_NOCTTY',
  'O_RDONLY',
  'O_RDWR',
  'O_SYNC',
  'O_TRUNC',
  'O_WRONLY',
]) {
  assert.strictEqual(typeof constants.fs[name], 'number', name);
  assert.strictEqual(constants.fs[name], fs.constants[name], name);
}

console.log('process:binding-constants:ok');
