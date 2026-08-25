const { spawn, spawnSync } = require('node:child_process');

function assertEqual(actual, expected) {
  if (actual !== expected) {
    throw new Error(`expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
  }
}

const environment = { ANT_CHILD_PROCESS_OVERLOAD: 'overload-ok' };
const sync = spawnSync('printf "$ANT_CHILD_PROCESS_OVERLOAD"', {
  env: environment,
  shell: true
});

assertEqual(sync.status, 0);
assertEqual(String(sync.stdout), 'overload-ok');

const child = spawn('printf "$ANT_CHILD_PROCESS_OVERLOAD"', {
  env: environment,
  shell: true
});
let output = '';

child.stdout.on('data', chunk => {
  output += String(chunk);
});
child.on('error', error => {
  throw error;
});
child.on('close', code => {
  assertEqual(code, 0);
  assertEqual(output, 'overload-ok');
  console.log('child_process spawn options overload ok');
});
