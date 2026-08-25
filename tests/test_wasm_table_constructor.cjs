const assert = require('node:assert');

function assertThrowsName(fn, name) {
  let thrown;
  try {
    fn();
  } catch (error) {
    thrown = error;
  }
  assert(thrown, `expected ${name}`);
  assert.strictEqual(thrown.name, name);
}

const table = new WebAssembly.Table({ element: 'externref', initial: 2, maximum: 4 });
assert(table instanceof WebAssembly.Table);
assert.strictEqual(table.length, 2);
assert.strictEqual(table.get(0), null);

const value = { ok: true };
table.set(0, value);
assert.strictEqual(table.get(0), value);

assert.strictEqual(table.grow(1, 'fill'), 2);
assert.strictEqual(table.length, 3);
assert.strictEqual(table.get(2), 'fill');

assert.strictEqual(table.grow(1), 3);
assert.strictEqual(table.get(3), null);
assertThrowsName(() => table.grow(1), 'RangeError');
assertThrowsName(() => table.get(4), 'RangeError');
assertThrowsName(() => table.set(4, null), 'RangeError');

const anyfunc = new WebAssembly.Table({ element: 'anyfunc', initial: 1 });
assert.strictEqual(anyfunc.length, 1);
assert.strictEqual(anyfunc.get(0), null);
assertThrowsName(() => anyfunc.set(0, () => {}), 'TypeError');

assertThrowsName(() => WebAssembly.Table({ element: 'externref', initial: 0 }), 'TypeError');
assertThrowsName(() => new WebAssembly.Table(), 'TypeError');
assertThrowsName(() => new WebAssembly.Table({ element: 'nope', initial: 0 }), 'TypeError');
assertThrowsName(() => new WebAssembly.Table({ element: 'externref', initial: 2, maximum: 1 }), 'RangeError');

console.log('wasm:table-constructor:ok');
