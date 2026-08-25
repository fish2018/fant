'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const {
  hostPlatform,
  packagePlatformKey,
  readRuntimeLock
} = require('../scripts/lib/runtime-lock.cjs');

const lock = readRuntimeLock();
assert.equal(packagePlatformKey(), 'darwin-arm64');
assert.equal(packagePlatformKey('win32', 'x64'), 'windows-x64');
assert.equal(packagePlatformKey('linux', 'arm64'), 'linux-arm64');
assert.equal(lock.archive, `cef_binary_${lock.cefVersion}_${lock.platform}.tar.bz2`);
assert.equal(hostPlatform(lock).cmakeArch, 'arm64');

const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'ant-runtime-lock-'));
try {
  const invalid = path.join(temporary, 'runtime.lock.json');
  fs.writeFileSync(invalid, JSON.stringify({ ...lock, size: 0 }));
  assert.throws(
    () => readRuntimeLock(invalid),
    /size must be a positive integer/
  );
} finally {
  fs.rmSync(temporary, { recursive: true, force: true });
}

console.log('desktop-runtime-lock-ok');
