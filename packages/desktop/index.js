import fs from 'node:fs';
import path from 'node:path';

import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const packageRoot = path.dirname(fileURLToPath(import.meta.url));

const platforms = require('./platforms.json');
const supportedPlatforms = new Set(platforms.supported);

export function platformKey() {
  const platform = platforms.names[process.platform];
  const key = platform && `${platform}-${process.arch}`;

  if (!key || !supportedPlatforms.has(key))
    throw new Error(`Ant Desktop does not currently provide a binary for ${key || `${process.platform}-${process.arch}`}`);

  return key;
}

export function platformPackageName() {
  return `ant-desktop-${platformKey()}`;
}

function loadPlatform() {
  const configured = process.env.ANT_DESKTOP_PLATFORM_PACKAGE;
  if (configured) return require(path.resolve(configured));
  const name = platformPackageName();

  try {
    return require(name);
  } catch (error) {
    if (error && error.code !== 'MODULE_NOT_FOUND') throw error;
    const sourcePackage = path.join(packageRoot, 'packaging', 'npm', platformKey());
    if (fs.existsSync(path.join(sourcePackage, 'package.json'))) return require(sourcePackage);
    throw new Error(`The optional platform package ${name} is missing. Reinstall ant-desktop without --omit=optional.`, { cause: error });
  }
}

export function run(entry, args = [], options) {
  if (typeof entry !== 'string' || !entry) throw new TypeError('run(entry) requires an application entry file');
  return loadPlatform().run([entry, ...args], options);
}

export function runSync(entry, args = [], options) {
  if (typeof entry !== 'string' || !entry) throw new TypeError('runSync(entry) requires an application entry file');
  return loadPlatform().runSync([entry, ...args], options);
}

export function resolveRuntime() {
  return loadPlatform().resolveRuntime();
}

export function packageApp(entry, options) {
  if (typeof entry !== 'string' || !entry) {
    throw new TypeError('packageApp(entry) requires an application entry file');
  }

  const platform = loadPlatform();
  if (typeof platform.packageApp !== 'function') {
    throw new Error(`${platformPackageName()} does not support application packaging`);
  }

  return platform.packageApp(entry, options);
}

export function dev(entry, options) {
  if (typeof entry !== 'string' || !entry) {
    throw new TypeError('dev(entry) requires an application entry file');
  }

  const platform = loadPlatform();
  if (typeof platform.dev !== 'function') {
    throw new Error(`${platformPackageName()} does not support development mode`);
  }

  return platform.dev(entry, options);
}
