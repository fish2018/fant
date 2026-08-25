#!/usr/bin/env node
'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { Readable } = require('node:stream');
const { pipeline } = require('node:stream/promises');

const { execute } = require('./lib/command.cjs');
const {
  assertHostMatches,
  readRuntimeLock
} = require('./lib/runtime-lock.cjs');

const desktopRoot = path.resolve(__dirname, '..');

function digest(filename) {
  const hash = crypto.createHash('sha1');
  hash.update(fs.readFileSync(filename));
  return hash.digest('hex');
}

async function fetchBrowserRuntime() {
  const lock = readRuntimeLock();
  assertHostMatches(lock);
  const cache = path.join(desktopRoot, '.cache');
  const download = path.join(cache, lock.archive);
  const source = path.join(cache, lock.archive.replace(/\.tar\.bz2$/, ''));
  fs.mkdirSync(cache, { recursive: true });

  if (!fs.existsSync(download)) {
    const temporary = `${download}.download-${process.pid}`;
    process.stdout.write(`Downloading ${lock.archive}\n`);
    const response = await fetch(
      `https://cef-builds.spotifycdn.com/${encodeURIComponent(lock.archive)}`,
      { redirect: 'follow' }
    );
    if (!response.ok || !response.body) {
      throw new Error(`CEF download failed with HTTP ${response.status}`);
    }
    try {
      await pipeline(Readable.fromWeb(response.body), fs.createWriteStream(temporary));
      fs.renameSync(temporary, download);
    } catch (error) {
      fs.rmSync(temporary, { force: true });
      throw error;
    }
  }

  const actualSize = fs.statSync(download).size;
  if (actualSize !== lock.size) {
    throw new Error(
      `CEF size mismatch: expected ${lock.size}, got ${actualSize}`
    );
  }
  const actualSha1 = digest(download);
  if (actualSha1 !== lock.sha1) {
    throw new Error(
      `CEF checksum mismatch: expected ${lock.sha1}, got ${actualSha1}`
    );
  }
  if (!fs.existsSync(source)) {
    execute('tar', ['-xjf', download, '-C', cache]);
  }
  return source;
}

if (require.main === module) {
  fetchBrowserRuntime()
    .then(source => process.stdout.write(`${source}\n`))
    .catch(error => {
      process.stderr.write(`${error.message}\n`);
      process.exitCode = 1;
    });
}

module.exports = { fetchBrowserRuntime };
