const { spawnSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'ant-pkg-lifecycle-'));
const packageRoot = path.join(tmpRoot, 'node_modules', 'dual-lifecycle');
const outPath = path.join(tmpRoot, 'lifecycle.log');
const markerPath = path.join(packageRoot, '.postinstall');
const antPath = path.resolve(__dirname, '..', 'build', 'ant');

fs.mkdirSync(packageRoot, { recursive: true });
fs.writeFileSync(
  path.join(tmpRoot, 'package.json'),
  JSON.stringify({ dependencies: { 'dual-lifecycle': '1.0.0' } }, null, 2)
);
fs.writeFileSync(
  path.join(packageRoot, 'package.json'),
  JSON.stringify(
    {
      name: 'dual-lifecycle',
      version: '1.0.0',
      scripts: {
        install: 'printf install > "$ANT_TEST_LIFECYCLE_OUT"',
        postinstall: 'printf ",postinstall" >> "$ANT_TEST_LIFECYCLE_OUT"'
      }
    },
    null,
    2
  )
);

fs.writeFileSync(markerPath, '');

function runTrust() {
  return spawnSync(antPath, ['trust', 'dual-lifecycle'], {
    cwd: tmpRoot,
    env: { ...process.env, ANT_TEST_LIFECYCLE_OUT: outPath },
    encoding: 'utf8'
  });
}

let result = runTrust();
if (result.error) throw result.error;
assert(
  result.status === 0,
  `expected ant trust to pass, got ${result.status}\nstdout:\n${result.stdout}\nstderr:\n${result.stderr}`
);
assert(
  fs.readFileSync(outPath, 'utf8') === 'install,postinstall',
  `expected install then postinstall, got ${JSON.stringify(fs.readFileSync(outPath, 'utf8'))}`
);
assert(
  fs.readFileSync(markerPath, 'utf8') === 'ant lifecycle v1\ninstall\npostinstall\n',
  'expected lifecycle marker to record both commands'
);

result = runTrust();
if (result.error) throw result.error;
assert(
  result.status === 0,
  `expected second ant trust to pass, got ${result.status}\nstdout:\n${result.stdout}\nstderr:\n${result.stderr}`
);
assert(
  fs.readFileSync(outPath, 'utf8') === 'install,postinstall',
  'expected completed lifecycle marker to prevent rerun'
);

const quietRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'ant-pkg-lifecycle-quiet-'));
const quietPackageRoot = path.join(quietRoot, 'node_modules', 'quiet-lifecycle');
fs.mkdirSync(quietPackageRoot, { recursive: true });
fs.writeFileSync(
  path.join(quietRoot, 'package.json'),
  JSON.stringify({ dependencies: { 'quiet-lifecycle': '1.0.0' } }, null, 2)
);
fs.writeFileSync(
  path.join(quietPackageRoot, 'package.json'),
  JSON.stringify(
    {
      name: 'quiet-lifecycle',
      version: '1.0.0',
      scripts: {
        install: 'echo quiet lifecycle stdout; echo quiet lifecycle stderr >&2'
      }
    },
    null,
    2
  )
);

result = spawnSync(antPath, ['trust', 'quiet-lifecycle'], {
  cwd: quietRoot,
  encoding: 'utf8'
});
if (result.error) throw result.error;
assert(
  result.status === 0,
  `expected noisy successful lifecycle to pass, got ${result.status}\nstdout:\n${result.stdout}\nstderr:\n${result.stderr}`
);
assert(
  !result.stdout.includes('quiet lifecycle stdout') && !result.stderr.includes('quiet lifecycle stdout'),
  `expected successful lifecycle stdout to stay quiet\nstdout:\n${result.stdout}\nstderr:\n${result.stderr}`
);
assert(
  !result.stdout.includes('quiet lifecycle stderr') && !result.stderr.includes('quiet lifecycle stderr'),
  `expected successful lifecycle stderr to stay quiet\nstdout:\n${result.stdout}\nstderr:\n${result.stderr}`
);

const failRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'ant-pkg-lifecycle-fail-'));
const failPackageRoot = path.join(failRoot, 'node_modules', 'fail-lifecycle');
fs.mkdirSync(failPackageRoot, { recursive: true });
fs.writeFileSync(
  path.join(failRoot, 'package.json'),
  JSON.stringify({ dependencies: { 'fail-lifecycle': '1.0.0' } }, null, 2)
);
fs.writeFileSync(
  path.join(failPackageRoot, 'package.json'),
  JSON.stringify(
    {
      name: 'fail-lifecycle',
      version: '1.0.0',
      scripts: {
        install: 'echo lifecycle failed stdout; echo lifecycle failed >&2; exit 7'
      }
    },
    null,
    2
  )
);

result = spawnSync(antPath, ['trust', 'fail-lifecycle'], {
  cwd: failRoot,
  encoding: 'utf8'
});
if (result.error) throw result.error;
assert(result.status !== 0, 'expected failing lifecycle script to make ant trust fail');
assert(
  /lifecycle failed stdout/.test(result.stderr),
  `expected lifecycle stdout to be replayed on failure, got ${JSON.stringify(result.stderr)}`
);
assert(
  /lifecycle failed/.test(result.stderr),
  `expected lifecycle stderr to be replayed on failure, got ${JSON.stringify(result.stderr)}`
);
assert(
  /Lifecycle script 'install' failed for fail-lifecycle/.test(result.stderr),
  `expected lifecycle failure in stderr, got ${JSON.stringify(result.stderr)}`
);
assert(
  !fs.existsSync(path.join(failPackageRoot, '.postinstall')),
  'expected failed lifecycle script not to write completion marker'
);

const gypRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'ant-pkg-lifecycle-gyp-'));
const gypPackageRoot = path.join(gypRoot, 'node_modules', 'gyp-lifecycle');
const fakeNodeRoot = path.join(gypRoot, 'fake-node');
const fakeNodeBin = path.join(fakeNodeRoot, 'bin');
const fakeGypJs = path.join(
  fakeNodeRoot,
  'lib',
  'node_modules',
  'npm',
  'node_modules',
  'node-gyp',
  'bin',
  'node-gyp.js'
);
const gypOutPath = path.join(gypRoot, 'gyp.log');

fs.mkdirSync(gypPackageRoot, { recursive: true });
fs.mkdirSync(path.dirname(fakeGypJs), { recursive: true });
fs.mkdirSync(fakeNodeBin, { recursive: true });
fs.writeFileSync(
  path.join(gypRoot, 'package.json'),
  JSON.stringify({ dependencies: { 'gyp-lifecycle': '1.0.0' } }, null, 2)
);
fs.writeFileSync(
  path.join(gypPackageRoot, 'package.json'),
  JSON.stringify(
    {
      name: 'gyp-lifecycle',
      version: '1.0.0',
      scripts: {
        install: 'node-gyp rebuild'
      }
    },
    null,
    2
  )
);
fs.writeFileSync(fakeGypJs, '// fake node-gyp entrypoint\n');
fs.writeFileSync(
  path.join(fakeNodeBin, 'node'),
  `#!/bin/sh
if [ "$1" = "$ANT_TEST_GYP_JS" ]; then
  echo "fake-gyp $2" >> "$ANT_TEST_LIFECYCLE_OUT"
  exit 0
fi
echo "unexpected node $@" >> "$ANT_TEST_LIFECYCLE_OUT"
exit 31
`
);
fs.writeFileSync(path.join(fakeNodeBin, 'npm'), '#!/bin/sh\nexit 0\n');
fs.chmodSync(path.join(fakeNodeBin, 'node'), 0o755);
fs.chmodSync(path.join(fakeNodeBin, 'npm'), 0o755);

result = spawnSync(antPath, ['trust', 'gyp-lifecycle'], {
  cwd: gypRoot,
  env: {
    ...process.env,
    ANT_TEST_GYP_JS: fakeGypJs,
    ANT_TEST_LIFECYCLE_OUT: gypOutPath,
    PATH: `${fakeNodeBin}:/usr/bin:/bin`
  },
  encoding: 'utf8'
});
if (result.error) throw result.error;
assert(
  result.status === 0,
  `expected node-gyp lifecycle to pass, got ${result.status}\nstdout:\n${result.stdout}\nstderr:\n${result.stderr}`
);
assert(
  fs.readFileSync(gypOutPath, 'utf8') === 'fake-gyp rebuild\n',
  `expected lifecycle node-gyp shim to run bundled gyp, got ${JSON.stringify(fs.readFileSync(gypOutPath, 'utf8'))}`
);

const cachedGypRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'ant-pkg-lifecycle-cached-gyp-'));
const cachedGypPackageRoot = path.join(cachedGypRoot, 'node_modules', 'cached-gyp-lifecycle');
const cachedFakeNodeBin = path.join(cachedGypRoot, 'fake-node', 'bin');
const cachedXdg = path.join(cachedGypRoot, 'xdg-cache');
const cachedToolBin = path.join(
  cachedXdg,
  'ant',
  'pkg',
  'tools',
  'node-gyp',
  '12.2.0',
  'node_modules',
  '.bin'
);
const cachedGypOutPath = path.join(cachedGypRoot, 'cached-gyp.log');

fs.mkdirSync(cachedGypPackageRoot, { recursive: true });
fs.mkdirSync(cachedFakeNodeBin, { recursive: true });
fs.mkdirSync(cachedToolBin, { recursive: true });
fs.writeFileSync(
  path.join(cachedGypRoot, 'package.json'),
  JSON.stringify({ dependencies: { 'cached-gyp-lifecycle': '1.0.0' } }, null, 2)
);
fs.writeFileSync(
  path.join(cachedGypPackageRoot, 'package.json'),
  JSON.stringify(
    {
      name: 'cached-gyp-lifecycle',
      version: '1.0.0',
      scripts: {
        install: 'node-gyp rebuild'
      }
    },
    null,
    2
  )
);
fs.writeFileSync(path.join(cachedFakeNodeBin, 'node'), '#!/bin/sh\nexit 0\n');
fs.writeFileSync(
  path.join(cachedToolBin, 'node-gyp'),
  `#!/bin/sh
echo "cached-gyp $1" >> "$ANT_TEST_LIFECYCLE_OUT"
`
);
fs.chmodSync(path.join(cachedFakeNodeBin, 'node'), 0o755);
fs.chmodSync(path.join(cachedToolBin, 'node-gyp'), 0o755);

result = spawnSync(antPath, ['trust', 'cached-gyp-lifecycle'], {
  cwd: cachedGypRoot,
  env: {
    ...process.env,
    ANT_TEST_LIFECYCLE_OUT: cachedGypOutPath,
    HOME: path.join(cachedGypRoot, 'home'),
    PATH: `${cachedFakeNodeBin}:/bin`,
    XDG_CACHE_HOME: cachedXdg
  },
  encoding: 'utf8'
});
if (result.error) throw result.error;
assert(
  result.status === 0,
  `expected cached node-gyp lifecycle to pass, got ${result.status}\nstdout:\n${result.stdout}\nstderr:\n${result.stderr}`
);
assert(
  fs.readFileSync(cachedGypOutPath, 'utf8') === 'cached-gyp rebuild\n',
  `expected lifecycle node-gyp fallback to use Ant tool cache, got ${JSON.stringify(fs.readFileSync(cachedGypOutPath, 'utf8'))}`
);

console.log('package lifecycle install/postinstall order and node-gyp fallbacks work');
