const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');

const file = path.join(os.tmpdir(), `ant-fs-stats-node-fields-${process.pid}.txt`);
fs.writeFileSync(file, 'x');

try {
  const stat = fs.statSync(file);
  const lstat = fs.lstatSync(file);

  for (const stats of [stat, lstat]) {
    for (const key of ['dev', 'ino', 'mode', 'nlink', 'uid', 'gid', 'rdev', 'size', 'blksize', 'blocks']) {
      assert.strictEqual(typeof stats[key], 'number', `${key} should be numeric`);
    }
  }
} finally {
  fs.unlinkSync(file);
}

console.log('fs stats node fields: ok');
