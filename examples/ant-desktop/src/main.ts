import fs from 'node:fs';
import path from 'node:path';
import { app } from 'ant:desktop';

import { registerIpc } from './ipc.js';
import { createApplicationMenu } from './menu/index.js';
import { createMainWindow, rendererEntry, type WindowBounds } from './window.js';

await app.ready();

const userData = app.getPath('userData');
fs.mkdirSync(userData, { recursive: true });
initStorage(path.join(userData, 'storage.json'));

function initStorage(storagePath: string) {
  console.log('using storage', storagePath);
  localStorage.setFile(storagePath);
}

function restoreWindowBounds(): Partial<WindowBounds> {
  const defaultValue = { width: 800, height: 520 };
  const stored = localStorage.getItem('windowBounds');
  if (!stored) return defaultValue;

  try {
    const bounds = JSON.parse(stored);
    const { x, y, width, height } = bounds ?? {};
    if ([x, y, width, height].every(Number.isFinite) && width > 0 && height > 0) return bounds;
  } catch {}

  return defaultValue;
}

const mainWindow = createMainWindow(restoreWindowBounds());
let windowBoundsSaved = false;

const saveWindowBounds = () => {
  if (windowBoundsSaved) return;
  windowBoundsSaved = true;
  localStorage.setItem('windowBounds', JSON.stringify(mainWindow.getBounds()));
};

mainWindow.on('closed', saveWindowBounds);
mainWindow.on('quit', saveWindowBounds);

registerIpc(mainWindow);
app.setApplicationMenu(createApplicationMenu(mainWindow));

if (process.env.ANT_DESKTOP_RENDERER_URL) {
  await mainWindow.loadURL(process.env.ANT_DESKTOP_RENDERER_URL);
} else await mainWindow.loadFile(rendererEntry);

console.log('ant desktop started');
