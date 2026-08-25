const assert = require('node:assert');
const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'ant-cli-file-script-'));
const ant = path.resolve(process.execPath);

function run(args) {
  const result = spawnSync(ant, args, {
    cwd: tmp,
    encoding: 'utf8',
  });
  if (result.error) throw result.error;
  return result;
}

try {
  fs.writeFileSync(
    path.join(tmp, 'package.json'),
    JSON.stringify({
      scripts: {
        't.js': `${ant} -e "console.log('script-shadow')"`,
        start: `${ant} -e "console.log('start-script')"`,
      },
    })
  );
  fs.writeFileSync(path.join(tmp, 't.js'), "console.log('file-entry');\n");
  fs.writeFileSync(path.join(tmp, 'start.js'), "console.log('start-file');\n");

  const explicitFile = run(['--no-color', 't.js']);
  assert.strictEqual(
    explicitFile.status,
    0,
    `file command failed\nstdout:\n${explicitFile.stdout}\nstderr:\n${explicitFile.stderr}`
  );
  assert.match(explicitFile.stdout, /file-entry/);
  assert.doesNotMatch(explicitFile.stdout, /script-shadow/);

  const scriptShortcut = run(['--no-color', 'start']);
  assert.strictEqual(
    scriptShortcut.status,
    0,
    `script command failed\nstdout:\n${scriptShortcut.stdout}\nstderr:\n${scriptShortcut.stderr}`
  );
  assert.match(scriptShortcut.stdout, /start-script/);
  assert.doesNotMatch(scriptShortcut.stdout, /start-file/);

  console.log('cli file arg precedes package script ok');
} finally {
  fs.rmSync(tmp, { recursive: true, force: true });
}
