import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { BrowserWindow } from 'ant:desktop';

const isMac = process.platform === 'darwin';
const filePath = path.dirname(fileURLToPath(import.meta.url));

const macConfig = {
  titleBarStyle: 'hiddenInset',
  trafficLightPosition: { x: 16, y: 15 }
};

export const preloadEntry = path.join(filePath, 'preload.ts');
export const rendererEntry = path.join(filePath, '../', 'dist', 'index.html');

export type WindowBounds = {
  x: number;
  y: number;
  width: number;
  height: number;
};

export function createMainWindow(bounds: Partial<WindowBounds> = {}) {
  const window = new BrowserWindow({
    title: 'Ant Desktop',

    ...bounds,
    ...(isMac ? macConfig : { titleBarStyle: 'hidden' }),

    webPreferences: {
      preload: preloadEntry,
      sandbox: false,
      nodeIntegration: true,
      contextIsolation: false,
      capabilities: [
        { channel: 'app:get-runtime-info', access: ['invoke'] },
        { channel: 'app:toggle-theme', access: ['invoke'] },
        { channel: 'page:ready', access: ['send'] }
      ]
    }
  });

  for (const event of ['navigation-start', 'navigation-commit', 'ready', 'renderer-crash'])
    window.on(event, value => console.log(`window:${value.type}`, value.detail ?? ''));

  return window;
}
