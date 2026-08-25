interface AntRendererApi {
  ipc: {
    invoke(channel: string): Promise<Record<string, string>>;
    send(channel: string, value: unknown): void;
  };
  versions: {
    ant: string;
    chrome: string;
    desktop: string;
  };
}

interface DesktopApi {
  platform: string;
  runtimeInfo(): Promise<{ rendererIsWindow: boolean }>;
}

declare const Ant: AntRendererApi;
declare const desktop: DesktopApi;
declare var preloadReady: boolean;
