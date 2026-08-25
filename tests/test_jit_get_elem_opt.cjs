function assertEq(actual, expected, message) {
  if (actual !== expected) {
    throw new Error(`${message}: expected ${expected}, got ${actual}`);
  }
}

function readComputed(obj, key) {
  return obj?.[key];
}

function readNested(obj, first, second) {
  return obj?.[first]?.[second];
}

function readCaught(obj, key) {
  try {
    return obj?.[key];
  } catch (error) {
    return error.message;
  }
}

const symbol = Symbol("computed");
const object = {
  value: 42,
  nested: { value: 43 },
  [symbol]: 44,
};
const array = [10, 20, 30];

for (let i = 0; i < 500; i++) {
  assertEq(readComputed(object, "value"), 42, "warm object key");
  assertEq(readComputed(array, 1), 20, "warm array index");
  assertEq(readComputed("ant", 1), "n", "warm string index");
  assertEq(readNested(object, "nested", "value"), 43, "warm nested key");
  assertEq(readCaught(object, "value"), 42, "warm caught path");
}

assertEq(readComputed(object, "value"), 42, "hot object key");
assertEq(readComputed(object, "missing"), undefined, "hot missing key");
assertEq(readComputed(object, symbol), 44, "hot symbol key");
assertEq(readComputed(array, 2), 30, "hot array index");
assertEq(readComputed(array, 9), undefined, "hot array out of bounds");
assertEq(readComputed("ant", 2), "t", "hot string index");
assertEq(readComputed(null, "value"), undefined, "hot null base");
assertEq(readComputed(undefined, "value"), undefined, "hot undefined base");
assertEq(readNested(object, "nested", "value"), 43, "hot nested key");
assertEq(readNested(null, "nested", "value"), undefined, "hot nested null base");

let coercions = 0;
const coercingKey = {
  toString() {
    coercions++;
    return "value";
  },
};
assertEq(readComputed(object, coercingKey), 42, "hot coerced key");
assertEq(coercions, 1, "non-null base coerces key");
assertEq(readComputed(null, coercingKey), undefined, "hot null base with object key");
assertEq(coercions, 1, "null base does not coerce key");

const throwing = {};
Object.defineProperty(throwing, "value", {
  get() {
    throw new TypeError("computed getter failed");
  },
});
assertEq(readCaught(throwing, "value"), "computed getter failed", "hot getter error");
assertEq(readCaught(null, "value"), undefined, "hot caught null base");

console.log("OK: test_jit_get_elem_opt");
