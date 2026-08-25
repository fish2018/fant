import { contextBridge as bridge } from 'ant:desktop/renderer';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

globalThis.sharedFromPreload = 'shared-world';
bridge.exposeInMainWorld('privilegedPreload', {
  homeExists: fs.existsSync(os.homedir()),
  separator: path.sep
});
