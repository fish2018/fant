import { app, BrowserWindow, ipcMain, versions } from 'ant:desktop';

if (typeof BrowserWindow !== 'function') {
  throw new Error('BrowserWindow export is unavailable');
}

const firstReady = app.ready();
if (firstReady !== app.ready()) {
  throw new Error('app.ready() did not return the shared readiness promise');
}
if ('_readyPromise' in app) {
  throw new Error('native readiness state leaked onto the app object');
}
if (typeof app.resourcesPath !== 'string' || !app.resourcesPath) {
  throw new Error('app.resourcesPath is unavailable');
}
for (const name of ['home', 'temp', 'appData', 'userData', 'desktop', 'documents', 'downloads', 'resources', 'exe']) {
  const value = app.getPath(name);
  if (typeof value !== 'string' || !value) throw new Error(`app.getPath('${name}') is unavailable`);
}
try {
  app.getPath('missing');
  throw new Error('app.getPath accepted an unknown path');
} catch (error) {
  if (!String(error).includes('unknown app path')) throw error;
}
for (const name of ['ant', 'desktop', 'chrome']) {
  if (typeof versions[name] !== 'string' || !versions[name]) {
    throw new Error(`versions.${name} is unavailable`);
  }
}
if (process.versions['ant-desktop'] !== versions.desktop ||
    process.versions.chrome !== versions.chrome) {
  throw new Error('desktop component versions are missing from process.versions');
}
if (app.versions !== versions) {
  throw new Error('app.versions does not reference the exported version set');
}
for (const name of [
  '__antDesktopWindowObjects',
  '__antDesktopIpcHandlers',
  '__antDesktopIpcListeners',
  '__antDesktopEncode',
  '__antDesktopDispatch',
  '__antNativeIpcReply'
]) {
  if (name in globalThis) {
    throw new Error(`native desktop bridge leaked as globalThis.${name}`);
  }
}
await firstReady;
ipcMain.handle('smoke:ping', value => value);
ipcMain.on('smoke:event', () => {});
ipcMain.removeHandler('smoke:ping');
console.log('desktop-smoke-ok');
setTimeout(() => app.quit(), 10);
