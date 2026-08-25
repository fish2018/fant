const iterations = Number(process.argv[2] || 10_000_000);
const rounds = Number(process.argv[3] || 7);

function loopOptionalSum(obj, key, n) {
  let sum = 0;
  for (let i = 0; i < n; i++) sum += obj?.[key];
  return sum;
}

function loopElemSum(obj, key, n) {
  let sum = 0;
  for (let i = 0; i < n; i++) sum += obj[key];
  return sum;
}

function loopOptionalUndefined(obj, key, n) {
  let count = 0;
  for (let i = 0; i < n; i++) {
    if (obj?.[key] === undefined) count++;
  }
  return count;
}

function loopOptionalString(obj, key, n) {
  let count = 0;
  for (let i = 0; i < n; i++) {
    if (obj?.[key] === "n") count++;
  }
  return count;
}

function loopElemString(obj, key, n) {
  let count = 0;
  for (let i = 0; i < n; i++) {
    if (obj[key] === "n") count++;
  }
  return count;
}

function median(values) {
  const sorted = values.slice().sort((a, b) => a - b);
  return sorted[Math.floor(sorted.length / 2)];
}

function bench(name, fn, expected) {
  for (let i = 0; i < 150; i++) {
    if (fn(100) !== expected(100)) throw new Error(name + " warmup mismatch");
  }

  const samples = [];
  for (let round = 0; round < rounds; round++) {
    const start = Date.now();
    const result = fn(iterations);
    const elapsed = Date.now() - start;
    if (result !== expected(iterations)) throw new Error(name + " result mismatch");
    samples.push(elapsed);
  }

  console.log(name + ": median=" + median(samples) + "ms samples=" + samples.join(","));
}

const object = { value: 7 };
const missing = {};
const array = [3, 7, 11];

console.log("GET_ELEM_OPT benchmark: " + iterations + " iterations x " + rounds + " rounds");
bench("optional object hit", n => loopOptionalSum(object, "value", n), n => n * 7);
bench("normal object hit", n => loopElemSum(object, "value", n), n => n * 7);
bench("optional array index", n => loopOptionalSum(array, 1, n), n => n * 7);
bench("normal array index", n => loopElemSum(array, 1, n), n => n * 7);
bench("optional string index", n => loopOptionalString("ant", 1, n), n => n);
bench("normal string index", n => loopElemString("ant", 1, n), n => n);
bench("optional missing", n => loopOptionalUndefined(missing, "value", n), n => n);
bench("optional null", n => loopOptionalUndefined(null, "value", n), n => n);
bench("optional undefined", n => loopOptionalUndefined(undefined, "value", n), n => n);
