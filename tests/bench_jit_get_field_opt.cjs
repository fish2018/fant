const iterations = Number(process.argv[2] || 10_000_000);
const rounds = Number(process.argv[3] || 7);

function readOptional(obj) {
  return obj?.value;
}

function readField(obj) {
  return obj.value;
}

function loopOptionalPresent(obj, n) {
  let sum = 0;
  for (let i = 0; i < n; i++) sum += readOptional(obj);
  return sum;
}

function loopOptionalNullish(obj, n) {
  let count = 0;
  for (let i = 0; i < n; i++) {
    if (readOptional(obj) === undefined) count++;
  }
  return count;
}

function loopField(obj, n) {
  let sum = 0;
  for (let i = 0; i < n; i++) sum += readField(obj);
  return sum;
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

console.log("GET_FIELD_OPT benchmark: " + iterations + " iterations x " + rounds + " rounds");
bench("optional present", n => loopOptionalPresent(object, n), n => n * 7);
bench("optional missing", n => loopOptionalNullish(missing, n), n => n);
bench("optional null", n => loopOptionalNullish(null, n), n => n);
bench("optional undefined", n => loopOptionalNullish(undefined, n), n => n);
bench("normal field", n => loopField(object, n), n => n * 7);
