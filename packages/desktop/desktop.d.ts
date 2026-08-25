interface Storage {
  setFile(path: string): void;
}

declare module 'ant:desktop' {
  export interface WindowBounds {
    x: number;
    y: number;
    width: number;
    height: number;
  }

  export interface DesktopEvent {
    type: string;
    detail?: string;
    code: number;
  }

  export interface IpcMainEvent {
    sender: WebContents;
  }

  export interface WebContents {
    openDevTools(): void;
    closeDevTools(): void;
    toggleDevTools(): void;
    inspectElement(x: number, y: number): void;
    isDevToolsOpened(): boolean;
    reload(): void;
    send(channel: string, value?: unknown): void;
  }

  export interface WebPreferences {
    preload?: string;
    sandbox?: boolean;
    nodeIntegration?: boolean;
    antIntegration?: boolean;
    contextIsolation?: boolean;
    capabilities?: Array<{
      channel: string;
      access: Array<'invoke' | 'send' | 'receive'>;
    }>;
  }

  export interface BrowserWindowOptions extends Partial<WindowBounds> {
    width?: number;
    height?: number;
    title?: string;
    frame?: boolean;
    closable?: boolean;
    minimizable?: boolean;
    resizable?: boolean;
    maximizable?: boolean;
    transparent?: boolean;
    show?: boolean;
    alwaysOnTop?: boolean;
    focusable?: boolean;
    movable?: boolean;
    hasShadow?: boolean;
    center?: boolean;
    fullscreen?: boolean;
    fullscreenable?: boolean;
    backgroundColor?: string;
    borderColor?: string;
    borderWidth?: number;
    cornerRadius?: number;
    opacity?: number;
    minWidth?: number;
    minHeight?: number;
    maxWidth?: number;
    maxHeight?: number;
    titleBarStyle?: string;
    trafficLightPosition?: { x?: number; y?: number };
    webPreferences?: WebPreferences;
    [name: string]: unknown;
  }

  export class BrowserWindow {
    constructor(options?: BrowserWindowOptions);
    readonly webContents: WebContents;
    readonly preloadCapabilities?: WebPreferences['capabilities'];
    loadURL(url: string): Promise<void>;
    loadFile(path: string): Promise<void>;
    close(): void;
    show(): void;
    hide(): void;
    minimize(): void;
    restore(): void;
    maximize(): void;
    setAlwaysOnTop(enabled: boolean): void;
    setTitle(title: string): void;
    setFullScreen(enabled: boolean): void;
    getBounds(): WindowBounds;
    on(event: string, listener: (event: DesktopEvent) => void): this;
  }

  export type AppPathName =
    | 'home'
    | 'temp'
    | 'appData'
    | 'userData'
    | 'desktop'
    | 'documents'
    | 'downloads'
    | 'resources'
    | 'exe';

  export interface MenuItemOptions {
    label?: string;
    role?: string;
    type?: string;
    accelerator?: string;
    enabled?: boolean;
    visible?: boolean;
    checked?: boolean;
    click?: () => void;
    submenu?: MenuItemOptions[];
  }

  export class MenuItem {
    constructor(options?: MenuItemOptions);
  }

  export interface MenuInstance {
    append(item: MenuItem): void;
    insert(index: number, item: MenuItem): void;
  }

  export const Menu: {
    buildFromTemplate(template: MenuItemOptions[]): MenuInstance;
    setApplicationMenu(menu: MenuInstance | null): void;
    getApplicationMenu(): MenuInstance | null;
  };

  export const app: {
    readonly resourcesPath: string;
    readonly versions: typeof versions;
    ready(): Promise<void>;
    quit(): void;
    getPath(name: AppPathName): string;
    setApplicationMenu(menu: MenuInstance | null): void;
    getApplicationMenu(): MenuInstance | null;
  };

  export const ipcMain: {
    handle(channel: string, handler: (event: IpcMainEvent, ...args: any[]) => unknown): void;
    removeHandler(channel: string): void;
    on(channel: string, listener: (event: IpcMainEvent, ...args: any[]) => void): void;
  };

  export const versions: {
    ant: string;
    desktop: string;
    chrome: string;
  };
}

declare module 'ant:desktop/renderer' {
  export const contextBridge: {
    exposeInMainWorld(name: string, value: unknown): void;
  };

  export const ipcRenderer: {
    invoke(channel: string, ...args: unknown[]): Promise<any>;
    send(channel: string, value?: unknown): void;
    on(channel: string, listener: (value: unknown) => void): () => void;
  };
}
