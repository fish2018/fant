const assert = (condition, message) => {
  if (!condition) throw new Error(message);
};

globalThis.evalJitGlobal = 7;

function createHotEvalFunction() {
  let local = 1;
  const source = `
    (function evalEnvHot(mode) {
      if (mode === 0) return local + evalJitGlobal;
      if (mode === 1) {
        local = local + 1;
        return local;
      }
      if (mode === 2) return typeof evalJitMissing;
      return delete evalJitDelete;
    })
  `;
  return eval(source);
}

const hot = createHotEvalFunction();
let expectedLocal = 1;
for (let i = 0; i < 1000; i++) {
  const mode = i & 3;
  if (mode === 3) globalThis.evalJitDelete = i;

  const result = hot(mode);
  if (mode === 0)
    assert(result === expectedLocal + 7,
      'JIT eval lookup should read captured and global bindings');
  else if (mode === 1)
    assert(result === ++expectedLocal,
      'JIT eval assignment should update the captured binding');
  else if (mode === 2)
    assert(result === 'undefined',
      'JIT eval typeof lookup should allow a missing binding');
  else
    assert(result === true && !('evalJitDelete' in globalThis),
      'JIT eval deletion should delete the global property');
}

function createMissingReader() {
  const source = '(function evalEnvMissingRead() { return evalJitUnresolved; })';
  return eval(source);
}

const missingReader = createMissingReader();
for (let i = 0; i < 150; i++) {
  let threw = false;
  try {
    missingReader();
  } catch (error) {
    threw = error instanceof ReferenceError;
  }
  assert(threw, 'JIT eval lookup should preserve unresolved-name errors');
}

function createStrictWriter() {
  'use strict';
  const source = '(function evalEnvStrictWrite(value) { evalJitStrictMissing = value; })';
  return eval(source);
}

const strictWriter = createStrictWriter();
for (let i = 0; i < 150; i++) {
  let threw = false;
  try {
    strictWriter(i);
  } catch (error) {
    threw = error instanceof ReferenceError;
  }
  assert(threw, 'JIT strict eval assignment should reject unresolved names');
}

function createCaughtErrors() {
  'use strict';
  const locked = 1;
  const source = `
    (function evalEnvCaughtError(write) {
      try {
        if (write) locked = 2;
        return evalJitCaughtMissing;
      } catch (error) {
        return error.name;
      }
    })
  `;
  return eval(source);
}

const caughtErrors = createCaughtErrors();
for (let i = 0; i < 300; i++) {
  const expected = i & 1 ? 'TypeError' : 'ReferenceError';
  assert(caughtErrors(i & 1) === expected,
    'JIT eval helper errors should enter the function catch handler');
}

delete globalThis.evalJitGlobal;
delete globalThis.evalJitDelete;

console.log('direct eval JIT tests passed');
