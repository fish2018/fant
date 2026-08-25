const assert = (condition, message) => {
  if (!condition) throw new Error(message);
};

const callable = new Proxy(function target() { return 42; }, {});
assert(typeof callable === 'function', 'callable proxy should have function typeof');
assert(callable() === 42, 'callable proxy should remain callable');
const mapped = [1, 2].map(new Proxy(value => value + 1, {}));
assert(mapped.join(',') === '2,3', 'callable proxy should pass shared callable checks');

const nested = new Proxy(callable, {});
assert(typeof nested === 'function', 'nested callable proxy should have function typeof');
assert(nested() === 42, 'nested callable proxy should remain callable');

function typeOfValue(value) {
  return typeof value;
}
for (let i = 0; i < 10000; i++) {
  assert(typeOfValue(callable) === 'function', 'hot callable proxy typeof should stay function');
}

const object = new Proxy({}, {});
assert(typeof object === 'object', 'non-callable proxy should have object typeof');

const revocableFunction = Proxy.revocable(() => 7, {});
revocableFunction.revoke();
assert(typeof revocableFunction.proxy === 'function', 'revoked callable proxy should retain function typeof');

const revocableObject = Proxy.revocable({}, {});
revocableObject.revoke();
assert(typeof revocableObject.proxy === 'object', 'revoked non-callable proxy should retain object typeof');

const patches = new WeakMap();
const parent = { method() { return 'original'; } };

function patch(name) {
  const original = parent[name];
  assert(typeof original === 'function', 'an already proxied function should accept another patch');
  if (!patches.has(original)) {
    const replacement = new Proxy(original, {});
    patches.set(replacement, true);
    parent[name] = replacement;
  }
}

patch('method');
patch('method');
assert(parent.method() === 'original', 'stacked patch proxy should remain callable');

console.log('callable proxy typeof tests passed');
