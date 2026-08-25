function assertEq(actual, expected, msg) {
  if (actual !== expected) {
    throw new Error(`${msg}: expected ${expected}, got ${actual}`);
  }
}

function readValue(obj) {
  return obj?.value;
}

function readNested(obj) {
  return obj?.child?.value;
}

function readLength(obj) {
  return obj?.length;
}

function readViaInline(obj) {
  return readValue(obj);
}

const object = { value: 41, child: { value: 42 } };
const missing = { child: {} };

for (let i = 0; i < 500; i++) {
  assertEq(readValue(object), 41, "warm object optional field");
  assertEq(readNested(object), 42, "warm nested optional field");
  assertEq(readLength("abc"), 3, "warm primitive optional field");
  assertEq(readViaInline(object), 41, "warm inline optional field");
}

assertEq(readValue(object), 41, "hot object optional field");
assertEq(readValue({}), undefined, "hot missing optional field");
assertEq(readValue(null), undefined, "hot null optional field");
assertEq(readValue(undefined), undefined, "hot undefined optional field");
assertEq(readNested(object), 42, "hot nested optional field");
assertEq(readNested(missing), undefined, "hot nested missing optional field");
assertEq(readNested(null), undefined, "hot nested null optional field");
assertEq(readLength("abcd"), 4, "hot primitive optional field");
assertEq(readViaInline({ value: 7 }), 7, "hot inline object optional field");
assertEq(readViaInline(null), undefined, "hot inline null optional field");

console.log("OK: test_jit_get_field_opt");
