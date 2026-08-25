const assert = require('assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

assert.strictEqual(typeof fs.fsync, 'function');
assert.strictEqual(typeof fs.fsyncSync, 'function');

let missingCallbackThrew = false;
try {
  fs.fsync(1);
} catch (error) {
  missingCallbackThrew = error instanceof TypeError || error.name === 'TypeError';
}
assert.strictEqual(missingCallbackThrew, true);

const file = path.join(os.tmpdir(), `ant-fsync-${process.pid}-${Date.now()}`);
const fd = fs.openSync(file, 'w');

function cleanup() {
  try {
    fs.closeSync(fd);
  } catch {}
  try {
    fs.unlinkSync(file);
  } catch {}
}

function fail(error) {
  cleanup();
  console.error(error);
  process.exit(1);
}

fs.writeSync(fd, 'sync');
assert.strictEqual(fs.fsyncSync(fd), undefined);

const ret = fs.fsync(fd, (err) => {
  if (err) fail(err);
  cleanup();
});
assert.strictEqual(ret, undefined);
