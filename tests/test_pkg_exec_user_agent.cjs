const assert = require('node:assert');
const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

if (process.platform === 'win32') {
  console.log('skipping ant x user agent test on win32');
  process.exit(0);
}

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'ant-x-user-agent-'));
const ant = path.resolve(process.execPath);
const binDir = path.join(tmp, 'node_modules', '.bin');
const binPath = path.join(binDir, 'print-user-agent');

try {
  fs.mkdirSync(binDir, { recursive: true });
  fs.writeFileSync(binPath, '#!/bin/sh\nprintf "%s\\n" "$npm_config_user_agent"\n');
  fs.chmodSync(binPath, 0o755);

  const result = spawnSync(ant, ['x', 'print-user-agent'], {
    cwd: tmp,
    encoding: 'utf8',
    env: {
      ...process.env,
      npm_config_user_agent: 'npm/10.0.0',
    },
  });

  assert.strictEqual(
    result.status,
    0,
    `ant x failed\nstdout:\n${result.stdout}\nstderr:\n${result.stderr}`
  );
  assert.match(result.stdout.trim(), /^ant\//);

  console.log('ant x sets npm_config_user_agent');
} finally {
  fs.rmSync(tmp, { recursive: true, force: true });
}
