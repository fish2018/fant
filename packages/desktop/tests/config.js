import assert from 'node:assert';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import { loadConfig, optionsFromConfig } from '../config.js';

const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ant-desktop-config-'));
try {
  const filename = path.join(root, 'desktop.json');
  fs.writeFileSync(filename, JSON.stringify({
    main: 'src/index.js',
    renderer: {
      watchDir: 'renderer/src',
      buildCommand: 'npm run build:renderer'
    },
    include: [
      'src/*.js',
      'renderer/dist/**',
      'assets/*'
    ],
    icon: 'assets/app.icns',
    output: 'release/My App.app',
    name: 'My App',
    identifier: 'com.example.my-app',
    version: '1.2.3'
  }));

  const config = loadConfig(filename);
  assert.equal(config.main, path.join(root, 'src/index.js'));
  assert.deepEqual(config.renderer, {
    watchDir: path.join(root, 'renderer/src'),
    buildCommand: 'npm run build:renderer',
    devServer: undefined
  });
  assert.equal(config.icon, path.join(root, 'assets/app.icns'));
  assert.equal(config.output, path.join(root, 'release/My App.app'));
  assert.deepEqual(config.include, [
    'src/*.js',
    'renderer/dist/**',
    'assets/*'
  ]);
  assert.deepEqual(optionsFromConfig(config, 'dev'), {
    appDir: root,
    include: config.include,
    identifier: 'com.example.my-app',
    name: 'My App',
    rendererDir: path.join(root, 'renderer/src'),
    rendererBuildCommand: 'npm run build:renderer',
    rendererDevServer: undefined,
    version: '1.2.3'
  });
  assert.deepEqual(optionsFromConfig(config, 'package'), {
    appDir: root,
    include: config.include,
    icon: path.join(root, 'assets/app.icns'),
    identifier: 'com.example.my-app',
    name: 'My App',
    out: path.join(root, 'release/My App.app'),
    rendererBuildCommand: 'npm run build:renderer',
    version: '1.2.3'
  });
  assert.equal(loadConfig(path.join(root, 'missing.json'), false), null);
  fs.writeFileSync(filename, JSON.stringify({ renderer: 'renderer' }));
  assert.throws(() => loadConfig(filename), /renderer must be an object/);
  fs.writeFileSync(filename, JSON.stringify({
    renderer: { watchDir: 'renderer', devServer: { command: 'vite', url: 'http://localhost:5173' } }
  }));
  assert.throws(() => loadConfig(filename), /watchDir and renderer\.devServer are alternatives/);
  let missingError;
  try {
    loadConfig(path.join(root, 'missing.json'));
  } catch (error) {
    missingError = error;
  }
  assert.match(missingError?.message, /Desktop config does not exist/);
} finally {
  fs.rmSync(root, { recursive: true, force: true });
}

console.log('desktop-config-ok');
