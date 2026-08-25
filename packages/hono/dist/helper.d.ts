/** Message payload types passed to Hono websocket `onMessage` handlers. */
export type WSMessageReceive = string | ArrayBuffer | Uint8Array;

/** Ready state values used by WebSocket-compatible objects. */
export type WSReadyState = 0 | 1 | 2 | 3;

/** Options accepted by Hono-style websocket send calls. */
export interface SendOptions {
  compress?: boolean;
}

/** Initialization data used to create a Hono-style websocket context. */
export interface WSContextInit<T = WebSocket> {
  send(data: string | ArrayBuffer | Uint8Array, options: SendOptions): void;
  close(code?: number, reason?: string): void;
  raw?: T;
  readyState: WSReadyState;
  url?: string | URL | null;
  protocol?: string | null;
}

/**
 * Hono-style websocket context backed by a runtime-native socket.
 */
export declare class WSContext<T = WebSocket> {
  constructor(init: WSContextInit<T>);
  send(source: string | ArrayBuffer | Uint8Array, options?: SendOptions): void;
  raw?: T;
  binaryType: BinaryType;
  readonly readyState: WSReadyState;
  url: URL | null;
  protocol: string | null;
  close(code?: number, reason?: string): void;
}

/** Creates a standard `message` event for websocket handlers. */
export declare function createWSMessageEvent(source: WSMessageReceive): MessageEvent<WSMessageReceive>;

/** Hono context as seen by websocket event factories. */
export type AntHonoContext = any;

/** Hono-compatible middleware returned by websocket helpers. */
export type AntHonoMiddleware = (c: AntHonoContext, next: () => Promise<void>) => Promise<Response | void>;

/** Websocket lifecycle callbacks accepted by websocket helpers. */
export interface WSEvents<T = WebSocket> {
  onOpen?: (event: Event, ws: WSContext<T>) => void;
  onMessage?: (event: MessageEvent<WSMessageReceive>, ws: WSContext<T>) => void;
  onClose?: (event: CloseEvent, ws: WSContext<T>) => void;
  onError?: (event: Event, ws: WSContext<T>) => void;
}

/** Hono websocket middleware factory shape. */
export interface UpgradeWebSocket<T = WebSocket, U = any> {
  (createEvents: (c: AntHonoContext) => WSEvents<T> | Promise<WSEvents<T>>, options?: U): AntHonoMiddleware;
  (c: AntHonoContext, events: WSEvents<T>, options?: U): Promise<Response>;
}

/** Builds a Hono-compatible websocket helper around a runtime adapter. */
export declare function defineWebSocketHelper<T = WebSocket, U = any>(
  handler: (c: AntHonoContext, events: WSEvents<T>, options?: U) => Response | void | Promise<Response | void>
): UpgradeWebSocket<T, U>;
