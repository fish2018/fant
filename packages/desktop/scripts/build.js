import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { buildApp } = require('./build-app.cjs');
const { buildBrowserHost } = require('./build-browser-host.cjs');

await buildBrowserHost();
buildApp();
