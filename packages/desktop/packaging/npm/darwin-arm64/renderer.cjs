'use strict';

const { spawnSync } = require('node:child_process');

function buildRenderer(command, cwd) {
  if (!command) return;
  const result = spawnSync(command, {
    cwd,
    env: process.env,
    shell: true,
    stdio: 'inherit'
  });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`Renderer build failed (${result.signal || result.status})`);
  }
}

module.exports = { buildRenderer };
