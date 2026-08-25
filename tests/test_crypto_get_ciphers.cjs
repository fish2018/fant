const assert = require('node:assert');
const crypto = require('crypto');

const first = crypto.getCiphers();
const second = crypto.getCiphers();

assert(Array.isArray(first), 'getCiphers should return an array');
assert(first.length > 0, 'getCiphers should return BoringSSL cipher names');
assert(first.includes('aes-128-cbc'), 'getCiphers should include aes-128-cbc');
assert(first.every((name) => typeof name === 'string'), 'cipher names should be strings');
assert.deepStrictEqual(first, [...first].sort(), 'cipher names should be sorted');
assert.strictEqual(new Set(first).size, first.length, 'cipher names should be unique');

first.push('__mutated__');
assert(!second.includes('__mutated__'), 'getCiphers should return a fresh array');

console.log('crypto:getCiphers:ok');
