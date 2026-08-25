import { Menu, MenuItem, type BrowserWindow } from 'ant:desktop';
import { template } from './template.json';

export function createApplicationMenu(mainWindow: BrowserWindow) {
  const menu = Menu.buildFromTemplate(template);

  menu.append(
    new MenuItem({
      label: 'View',
      submenu: [
        {
          label: 'Reload',
          accelerator: 'CmdOrCtrl+R',
          click: () => mainWindow.webContents.reload()
        },
        {
          label: 'Developer Tools',
          accelerator: 'Alt+CmdOrCtrl+I',
          click: () => mainWindow.webContents.openDevTools()
        },
        { role: 'toggleFullScreen' }
      ]
    })
  );

  return menu;
}
