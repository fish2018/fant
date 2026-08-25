const assert = require('assert');

const bytes = new Uint8Array([65, 0, 66, 67]);
assert.strictEqual(String.fromCharCode.apply(null, bytes), 'A\0BC');
assert.strictEqual(Reflect.apply(Math.max, null, new Uint8Array([3, 8, 2])), 8);
assert.strictEqual(Array.prototype.join.call(new Uint8Array([3, 8, 2]), ','), '3,8,2');

const words = new Uint16Array([0x41, 0x42, 0x43]);
assert.strictEqual(String.fromCharCode.apply(null, words.subarray(1)), 'BC');
