'use strict';

const fs = require('node:fs');
const path = require('node:path');

const desktopRoot = path.resolve(__dirname, '../..');
const platforms = require(path.join(desktopRoot, 'platforms.json'));

function packagePlatformKey(nodePlatform = process.platform, nodeArch = process.arch) {
  const platform = platforms.names[nodePlatform];
  if (!platform) throw new Error(`unsupported host platform: ${nodePlatform}-${nodeArch}`);
  return `${platform}-${nodeArch}`;
}

function runtimeLockPath(nodePlatform = process.platform, nodeArch = process.arch) {
  return path.join(
    desktopRoot,
    'packaging',
    'npm',
    packagePlatformKey(nodePlatform, nodeArch),
    'runtime.lock.json'
  );
}

function requireString(lock, name) {
  if (typeof lock[name] !== 'string' || lock[name].length === 0) {
    throw new Error(`runtime lock field ${name} must be a non-empty string`);
  }
}

function readRuntimeLock(filename = runtimeLockPath()) {
  let lock;
  try {
    lock = JSON.parse(fs.readFileSync(filename, 'utf8'));
  } catch (error) {
    throw new Error(`cannot read runtime lock ${filename}: ${error.message}`);
  }

  if (lock.schema !== 1) {
    throw new Error(`unsupported runtime lock schema: ${lock.schema}`);
  }
  for (const name of [
    'adapter',
    'cefVersion',
    'chromiumVersion',
    'platform',
    'archive',
    'sha1'
  ]) {
    requireString(lock, name);
  }
  if (lock.adapter !== 'cef-bootstrap') {
    throw new Error(`unsupported browser adapter: ${lock.adapter}`);
  }
  if (path.basename(lock.archive) !== lock.archive ||
      !lock.archive.endsWith('.tar.bz2')) {
    throw new Error('runtime lock archive must be a .tar.bz2 filename');
  }
  if (!/^[a-f0-9]{40}$/.test(lock.sha1)) {
    throw new Error('runtime lock sha1 must be a lowercase SHA-1 digest');
  }
  if (!Number.isSafeInteger(lock.size) || lock.size <= 0) {
    throw new Error('runtime lock size must be a positive integer');
  }
  return Object.freeze(lock);
}

function hostPlatform(lock) {
  const platforms = {
    macosarm64: { nodePlatform: 'darwin', nodeArch: 'arm64', cmakeArch: 'arm64' },
    macosx64: { nodePlatform: 'darwin', nodeArch: 'x64', cmakeArch: 'x86_64' }
  };
  const target = platforms[lock.platform];
  if (!target) {
    throw new Error(`unsupported locked runtime platform: ${lock.platform}`);
  }
  return target;
}

function assertHostMatches(lock) {
  const target = hostPlatform(lock);
  if (process.platform !== target.nodePlatform || process.arch !== target.nodeArch) {
    throw new Error(
      `runtime ${lock.platform} cannot be built on ${process.platform}-${process.arch}`
    );
  }
  return target;
}

module.exports = {
  assertHostMatches,
  hostPlatform,
  packagePlatformKey,
  runtimeLockPath,
  readRuntimeLock
};
