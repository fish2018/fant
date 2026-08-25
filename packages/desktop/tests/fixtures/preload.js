import { contextBridge, ipcRenderer } from 'ant:desktop/renderer';

const isolatedMarker = 'preload-only';
globalThis.preloadOnlyGlobal = true;

contextBridge.exposeInMainWorld('preloadApi', {
  invoke: value => ipcRenderer.invoke('preload:echo', value),
  readyState: document.readyState,
  sandboxed: typeof require === 'undefined'
});
