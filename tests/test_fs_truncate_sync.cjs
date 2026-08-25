const assert = require('assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const file = path.join(os.tmpdir(), `ant-truncate-${process.pid}-${Date.now()}`);

fs.writeFileSync(file, 'abcdef');
fs.truncateSync(file, 3);
assert.strictEqual(fs.readFileSync(file, 'utf8'), 'abc');

const fd = fs.openSync(file, 'r+');
try {
  fs.ftruncateSync(fd, 1);
} finally {
  fs.closeSync(fd);
}

assert.strictEqual(fs.readFileSync(file, 'utf8'), 'a');
fs.unlinkSync(file);
