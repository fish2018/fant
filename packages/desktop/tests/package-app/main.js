import { app, BrowserWindow } from 'ant:desktop';

await app.ready();
const window = new BrowserWindow({ width: 480, height: 320, show: false });
try {
  await window.loadFile(new URL('page.html', import.meta.url).pathname);
} catch (error) {
  console.error(`desktop-packaged-browser-failed: ${error.stack || error}`);
  window.close();
  throw error;
}
console.log('desktop-packaged-browser-ok');
setTimeout(() => window.close(), 50);
