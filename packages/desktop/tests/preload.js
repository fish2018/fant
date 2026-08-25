import { fileURLToPath } from 'node:url';
import { app, BrowserWindow, ipcMain } from 'ant:desktop';

await app.ready();

ipcMain.handle('preload:echo', (_event, value) => value);

let resolveCompleted;
let rejectCompleted;
const completed = new Promise((resolve, reject) => {
  resolveCompleted = resolve;
  rejectCompleted = reject;
});

ipcMain.on('preload:done', (_event, value) => {
  if (value.exposedBeforePage && value.isolated && value.sandboxed) {
    resolveCompleted();
  } else {
    rejectCompleted(new Error(`invalid preload result: ${JSON.stringify(value)}`));
  }
});
ipcMain.on('preload:error', (_event, value) => {
  rejectCompleted(new Error(value.message));
});

const window = new BrowserWindow({
  show: false,
  webPreferences: {
    preload: fileURLToPath(new URL('fixtures/preload.js', import.meta.url)),
    capabilities: [
      { channel: 'preload:echo', access: ['invoke'] },
      { channel: 'preload:done', access: ['send'] },
      { channel: 'preload:error', access: ['send'] }
    ]
  }
});

await window.loadFile(fileURLToPath(new URL('fixtures/preload-page.html', import.meta.url)));
await Promise.race([
  completed,
  new Promise((_, reject) => setTimeout(
    () => reject(new Error('desktop preload smoke timed out')),
    5000
  ))
]);
console.log('desktop-preload-smoke-ok');
window.close();
