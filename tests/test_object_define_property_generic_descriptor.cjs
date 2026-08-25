const assert = require('node:assert');

class AccessorHolder {
  constructor() {
    this.value = 'nodebuffer';
  }

  get binaryType() {
    return this.value;
  }

  set binaryType(value) {
    this.value = value;
  }
}

Object.defineProperty(AccessorHolder.prototype, 'binaryType', { enumerable: true });

const descriptor = Object.getOwnPropertyDescriptor(AccessorHolder.prototype, 'binaryType');
assert.strictEqual(typeof descriptor.get, 'function');
assert.strictEqual(typeof descriptor.set, 'function');
assert.strictEqual(descriptor.enumerable, true);

const holder = new AccessorHolder();
holder.binaryType = 'arraybuffer';
assert.strictEqual(holder.binaryType, 'arraybuffer');

const sym = Symbol('generic-symbol-descriptor');
const obj = {};
Object.defineProperty(obj, sym, { value: 1, configurable: true });
Object.defineProperty(obj, sym, { enumerable: true });

const symDescriptor = Object.getOwnPropertyDescriptor(obj, sym);
assert.strictEqual(symDescriptor.value, 1);
assert.strictEqual(symDescriptor.enumerable, true);
assert.strictEqual(symDescriptor.configurable, true);
assert.strictEqual(symDescriptor.writable, false);

const locked = {};
Object.defineProperty(locked, 'x', { value: 1, configurable: false });
Object.defineProperty(locked, 'x', { value: 1 });

const lockedSym = Symbol('locked');
Object.defineProperty(locked, lockedSym, { value: 1, configurable: false });
Object.defineProperty(locked, lockedSym, { value: 1 });

const lockedNaN = {};
Object.defineProperty(lockedNaN, 'x', { value: NaN, configurable: false });
Object.defineProperty(lockedNaN, 'x', { value: NaN });

const lockedNegativeZero = {};
Object.defineProperty(lockedNegativeZero, 'x', { value: -0, configurable: false });
Object.defineProperty(lockedNegativeZero, 'x', { value: -0 });
assert.strictEqual(Object.is(lockedNegativeZero.x, -0), true);

const zodLikeError = {};
const zodLikeState = { traits: [] };
Object.defineProperty(zodLikeError, '_zod', {
  value: zodLikeState,
  enumerable: false
});
Object.defineProperty(zodLikeError, '_zod', {
  value: zodLikeError._zod,
  enumerable: false
});
assert.strictEqual(zodLikeError._zod, zodLikeState);

function assertThrowsTypeError(fn) {
  try {
    fn();
  } catch (error) {
    assert.strictEqual(error instanceof TypeError, true);
    return;
  }
  assert.fail('expected TypeError');
}

assertThrowsTypeError(() => Object.defineProperty(locked, 'x', { configurable: true }));
assertThrowsTypeError(() => Object.defineProperty(locked, lockedSym, { configurable: true }));
assertThrowsTypeError(() => Object.defineProperty(locked, 'x', { value: 2 }));
assertThrowsTypeError(() => Object.defineProperty(locked, lockedSym, { value: 2 }));
assertThrowsTypeError(() => Object.defineProperty(lockedNegativeZero, 'x', { value: 0 }));

const accessorLocked = {};
function originalGetter() { return 1; }
function replacementGetter() { return 2; }
function originalSetter(value) { this.value = value; }
function replacementSetter(value) { this.value = value + 1; }

Object.defineProperty(accessorLocked, 'x', {
  get: originalGetter,
  set: originalSetter,
  configurable: false
});

Object.defineProperty(accessorLocked, 'x', { get: originalGetter });
Object.defineProperty(accessorLocked, 'x', { set: originalSetter });
assertThrowsTypeError(() => Object.defineProperty(accessorLocked, 'x', { get: replacementGetter }));
assertThrowsTypeError(() => Object.defineProperty(accessorLocked, 'x', { set: replacementSetter }));
assertThrowsTypeError(() => Object.defineProperty(accessorLocked, 'x', { value: 1 }));

const dataLocked = {};
Object.defineProperty(dataLocked, 'x', { value: 1, configurable: false });
assertThrowsTypeError(() => Object.defineProperty(dataLocked, 'x', { get: originalGetter }));

const accessorLockedSym = Symbol('locked-accessor');
Object.defineProperty(locked, accessorLockedSym, {
  get: originalGetter,
  configurable: false
});
assertThrowsTypeError(() => Object.defineProperty(locked, accessorLockedSym, { get: replacementGetter }));
assertThrowsTypeError(() => Object.defineProperty(locked, accessorLockedSym, { value: 1 }));

console.log('object-define-property-generic-descriptor:ok');
