import { ipcMain, type BrowserWindow } from 'ant:desktop';

const darkTheme = {
  background: '#202124',
  surface: '#303134',
  text: '#f1f1f1',
  muted: '#bdc1c6',
  border: '#5f6368',
  accent: '#8ab4f8'
};

const lightTheme = {
  background: '#f8f9fa',
  surface: '#ffffff',
  text: '#202124',
  muted: '#5f6368',
  border: '#dadce0',
  accent: '#1a73e8'
};

export function registerIpc(mainWindow: BrowserWindow) {
  let useLightTheme = false;

  ipcMain.handle('app:get-runtime-info', event => ({
    platform: process.platform,
    rendererIsWindow: event.sender === mainWindow.webContents
  }));

  ipcMain.handle('app:toggle-theme', () => {
    useLightTheme = !useLightTheme;
    return useLightTheme ? lightTheme : darkTheme;
  });

  ipcMain.on('page:ready', (_event, value) => {
    console.log(`renderer ready: ${value.title}`);
  });
}
