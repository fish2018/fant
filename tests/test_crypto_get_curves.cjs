const assert = require('node:assert');
const crypto = require('crypto');
const nodeCrypto = require('node:crypto');

const first = crypto.getCurves();
const second = crypto.getCurves();

assert(Array.isArray(first), 'getCurves should return an array');
assert(first.length > 0, 'getCurves should return BoringSSL curve names');
assert(first.includes('prime256v1'), 'getCurves should include prime256v1');
assert(first.includes('secp384r1'), 'getCurves should include secp384r1');
assert(first.includes('secp521r1'), 'getCurves should include secp521r1');
assert(first.every((name) => typeof name === 'string'), 'curve names should be strings');
assert.deepStrictEqual(first, [...first].sort(), 'curve names should be sorted');
assert.strictEqual(new Set(first).size, first.length, 'curve names should be unique');

first.push('__mutated__');
assert(!second.includes('__mutated__'), 'getCurves should return a fresh array');
assert.deepStrictEqual(nodeCrypto.getCurves(), second, 'node:crypto should expose getCurves');

console.log('crypto:getCurves:ok');
