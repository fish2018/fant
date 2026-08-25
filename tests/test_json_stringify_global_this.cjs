const { spawnSync } = require('child_process');

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

const result = spawnSync(process.execPath, [
  '-e',
  `
    console.log(Object.getOwnPropertyDescriptor(globalThis, 'global').enumerable);
    console.log(Object.getOwnPropertyDescriptor(localStorage, 'length').enumerable);
    try {
      JSON.stringify(globalThis);
    } catch (error) {
      console.log(error.name + ': ' + error.message);
    }
  `,
]);

if (result.error) throw result.error;

const stdout = String(result.stdout);
const stderr = String(result.stderr);

assert(result.status === 0, `child exited ${result.status}\nstdout:\n${stdout}\nstderr:\n${stderr}`);
assert(stdout.includes('true\nfalse\n'), `unexpected descriptors:\n${stdout}`);
assert(
  stdout.includes('TypeError: Converting circular structure to JSON'),
  `expected circular JSON TypeError, got:\n${stdout}`
);
assert(
  stdout.includes("--> starting at object with constructor 'global'"),
  `expected circular root detail, got:\n${stdout}`
);
assert(
  stdout.includes("--- property 'global' closes the circle"),
  `expected circular property detail, got:\n${stdout}`
);
assert(
  !stdout.includes('--localstorage-file') && !stderr.includes('--localstorage-file'),
  `localStorage warning leaked into stringify output\nstdout:\n${stdout}\nstderr:\n${stderr}`
);

console.log('JSON.stringify(globalThis) reports circular global');
