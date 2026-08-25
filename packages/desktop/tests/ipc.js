import { app, BrowserWindow, ipcMain } from 'ant:desktop';

await app.ready();

let window;
ipcMain.handle('test:echo', (event, value) => {
  if (event.sender !== window.webContents) throw new Error('wrong IPC sender');
  if (value.cyclic !== value.cyclic.self || value.bytes[0] !== 7) {
    throw new Error('structured renderer payload was corrupted');
  }
  return value;
});

let resolveCompleted;
let rejectCompleted;
const completed = new Promise((resolve, reject) => {
  resolveCompleted = resolve;
  rejectCompleted = reject;
});

ipcMain.on('test:ready', (event, value) => {
  if (event.sender !== window.webContents || value.ready !== true) {
    rejectCompleted(new Error('invalid renderer ready event'));
    return;
  }
  window.webContents.send('test:event', {
    kind: 'main-event',
    bytes: new Uint8Array([99])
  });
});
ipcMain.on('test:done', (_event, value) => {
  if (value.ok === true) resolveCompleted();
  else rejectCompleted(new Error('invalid renderer completion event'));
});
ipcMain.on('test:error', (_event, value) => {
  rejectCompleted(new Error(value.message));
});

window = new BrowserWindow({
  width: 640,
  height: 400,
  show: false,
  webPreferences: {
    capabilities: [
      { channel: 'test:echo', access: ['invoke'] },
      { channel: 'test:ready', access: ['send'] },
      { channel: 'test:done', access: ['send'] },
      { channel: 'test:error', access: ['send'] },
      { channel: 'test:event', access: ['receive'] }
    ]
  }
});
await window.loadFile(new URL('fixtures/ipc-page.html', import.meta.url).pathname);

await Promise.race([completed, new Promise((_, reject) => setTimeout(() => reject(new Error('desktop IPC smoke timed out')), 5000))]);
console.log('desktop-ipc-smoke-ok');
window.close();
