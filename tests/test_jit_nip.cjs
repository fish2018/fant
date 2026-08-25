function assertEq(actual, expected, message) {
  if (actual !== expected) {
    throw new Error(`${message}: expected ${expected}, got ${actual}`);
  }
}

let initializations = 0;

function fallback(value) {
  initializations++;
  return value;
}

function initPublic(obj, value) {
  return (obj.value ??= fallback(value));
}

class PrivateCache {
  #value;

  getOrInit(value) {
    return (this.#value ??= fallback(value));
  }

  clear() {
    this.#value = undefined;
  }
}

const publicCache = {};
const privateCache = new PrivateCache();

for (let i = 0; i < 500; i++) {
  publicCache.value = undefined;
  assertEq(initPublic(publicCache, i), i, "warm public initialize");
  assertEq(initPublic(publicCache, i + 1), i, "warm public preserve");

  privateCache.clear();
  assertEq(privateCache.getOrInit(i), i, "warm private initialize");
  assertEq(privateCache.getOrInit(i + 1), i, "warm private preserve");
}

assertEq(initializations, 1_000, "warm fallback count");

publicCache.value = undefined;
assertEq(initPublic(publicCache, 41), 41, "hot public initialize");
assertEq(initPublic(publicCache, 42), 41, "hot public preserve");
publicCache.value = 0;
assertEq(initPublic(publicCache, 42), 0, "hot public preserve zero");
publicCache.value = false;
assertEq(initPublic(publicCache, 42), false, "hot public preserve false");

privateCache.clear();
assertEq(privateCache.getOrInit(41), 41, "hot private initialize");
assertEq(privateCache.getOrInit(42), 41, "hot private preserve");
assertEq(initializations, 1_002, "hot fallback count");

console.log("OK: test_jit_nip");
