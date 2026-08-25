import fs from 'node:fs';
import path from 'node:path';

export const CONFIG_NAME = 'ant-desktop.json';

function optionalString(config, key, filename) {
  const value = config[key];
  if (value === undefined) return undefined;
  if (typeof value !== 'string' || !value.trim()) {
    throw new Error(`${filename}: ${key} must be a non-empty string`);
  }
  return value;
}

function resolveFrom(root, value) {
  return value === undefined ? undefined : path.resolve(root, value);
}

function renderer(config, root, filename) {
  const value = config.renderer;
  if (value === undefined) return undefined;
  if (!value || Array.isArray(value) || typeof value !== 'object') {
    throw new Error(`${filename}: renderer must be an object`);
  }
  const devServer = value.devServer;
  if (devServer !== undefined && (!devServer || Array.isArray(devServer) || typeof devServer !== 'object')) {
    throw new Error(`${filename}: renderer.devServer must be an object`);
  }
  const result = {
    watchDir: resolveFrom(root, optionalString(value, 'watchDir', `${filename}: renderer`)),
    buildCommand: optionalString(value, 'buildCommand', `${filename}: renderer`),
    devServer:
      devServer === undefined
        ? undefined
        : {
            command: optionalString(devServer, 'command', `${filename}: renderer.devServer`),
            url: optionalString(devServer, 'url', `${filename}: renderer.devServer`)
          }
  };
  if (result.devServer && (!result.devServer.command || !result.devServer.url)) {
    throw new Error(`${filename}: renderer.devServer requires command and url`);
  }
  if (result.watchDir && result.devServer) {
    throw new Error(`${filename}: renderer.watchDir and renderer.devServer are alternatives`);
  }
  return result;
}

function include(config, filename) {
  const values = config.include;
  if (values === undefined) return [];
  if (!Array.isArray(values)) {
    throw new Error(`${filename}: include must be an array`);
  }
  return values.map((value, index) => {
    if (typeof value !== 'string' || !value.trim()) {
      throw new Error(`${filename}: include[${index}] must be a non-empty glob string`);
    }
    return value;
  });
}

export function loadConfig(configuredPath, required = Boolean(configuredPath)) {
  const filename = path.resolve(configuredPath || CONFIG_NAME);
  if (!fs.existsSync(filename)) {
    if (required) throw new Error(`Desktop config does not exist: ${filename}`);
    return null;
  }

  let config;
  try {
    config = JSON.parse(fs.readFileSync(filename, 'utf8'));
  } catch (error) {
    throw new Error(`Could not read ${filename}: ${error.message}`, { cause: error });
  }

  if (!config || Array.isArray(config) || typeof config !== 'object') {
    throw new Error(`${filename}: root must be an object`);
  }

  const root = path.dirname(filename);
  const main = optionalString(config, 'main', filename) || 'index.js';

  return {
    filename,
    root,
    main: resolveFrom(root, main),
    renderer: renderer(config, root, filename),
    icon: resolveFrom(root, optionalString(config, 'icon', filename)),
    output: resolveFrom(root, optionalString(config, 'output', filename)),
    name: optionalString(config, 'name', filename),
    identifier: optionalString(config, 'identifier', filename),
    version: optionalString(config, 'version', filename),
    include: include(config, filename)
  };
}

export function optionsFromConfig(config, command) {
  if (!config) return {};

  const options = {
    appDir: config.root,
    include: config.include,
    identifier: config.identifier,
    name: config.name,
    version: config.version
  };

  if (command === 'dev') {
    options.rendererDir = config.renderer?.watchDir;
    options.rendererBuildCommand = config.renderer?.buildCommand;
    options.rendererDevServer = config.renderer?.devServer;
  }

  if (command === 'package') {
    options.icon = config.icon;
    options.out = config.output;
    options.rendererBuildCommand = config.renderer?.buildCommand;
  }

  return options;
}
