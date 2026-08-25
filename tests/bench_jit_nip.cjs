const iterations = Number(process.argv[2] || 2_000_000);
const rounds = Number(process.argv[3] || 7);

class PrivateBench {
  #value = 7;

  read(n) {
    this.#value = 7;
    let sum = 0;
    for (let i = 0; i < n; i++) sum += this.#value;
    return sum;
  }

  preserve(n) {
    this.#value = 7;
    let sum = 0;
    for (let i = 0; i < n; i++) sum += (this.#value ??= 3);
    return sum;
  }

  alternate(n) {
    this.#value = undefined;
    let sum = 0;
    for (let i = 0; i < n; i++) {
      if ((i & 1) === 0) this.#value = undefined;
      sum += (this.#value ??= 7);
    }
    return sum;
  }
}

class PublicBench {
  value = 7;

  read(n) {
    this.value = 7;
    let sum = 0;
    for (let i = 0; i < n; i++) sum += this.value;
    return sum;
  }

  preserve(n) {
    this.value = 7;
    let sum = 0;
    for (let i = 0; i < n; i++) sum += (this.value ??= 3);
    return sum;
  }

  alternate(n) {
    this.value = undefined;
    let sum = 0;
    for (let i = 0; i < n; i++) {
      if ((i & 1) === 0) this.value = undefined;
      sum += (this.value ??= 7);
    }
    return sum;
  }
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

const privateBench = new PrivateBench();
const publicBench = new PublicBench();

console.log("NIP JIT benchmark: " + iterations + " iterations x " + rounds + " rounds");
bench("public field read", n => publicBench.read(n), n => n * 7);
bench("public ??= preserve", n => publicBench.preserve(n), n => n * 7);
bench("public ??= alternating", n => publicBench.alternate(n), n => n * 7);
bench("private field read", n => privateBench.read(n), n => n * 7);
bench("private ??= preserve", n => privateBench.preserve(n), n => n * 7);
bench("private ??= alternating", n => privateBench.alternate(n), n => n * 7);
