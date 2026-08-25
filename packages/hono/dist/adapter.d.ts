import type { AntHonoMiddleware, UpgradeWebSocket, WSContext } from './helper.js';

/**
 * Wraps an Ant native WebSocket in Hono's WebSocket context API.
 *
 * This is mainly useful for adapter internals and tests. Application code
 * normally receives this context as the second argument to Hono websocket
 * event handlers.
 */
export declare function createWSContext<T = WebSocket>(socket: T, request?: Request): WSContext<T>;

/**
 * Hono middleware helper backed by Ant's native `ctx.upgradeWebSocket()`.
 */
export declare const upgradeWebSocket: UpgradeWebSocket<WebSocket>;

/**
 * Export shape accepted by Ant's module-first server runtime.
 */
export interface AntHonoServeOptions {
  /** The Hono fetch handler, usually `app.fetch`. */
  fetch: (request: Request, env?: unknown, ctx?: unknown) => Response | Promise<Response>;

  /** TCP port for Ant to listen on. Defaults to Ant's server default. */
  port?: number;

  /** Hostname or address for Ant to bind. */
  hostname?: string;

  /** Unix domain socket path instead of TCP. */
  unix?: string;

  /** Idle connection timeout in seconds. */
  idleTimeout?: number;

  /** Request timeout in seconds. */
  requestTimeout?: number;

  /** WebSocket behavior for upgraded connections. */
  websocket?: {
    /** Idle WebSocket timeout in seconds. Defaults to 120. Set 0 to disable. */
    idleTimeout?: number;

    /** Maximum incoming WebSocket message size in bytes. Defaults to 16 MiB. */
    maxPayloadLength?: number;

    /** Enables permessage-deflate negotiation for clients that request it. */
    perMessageDeflate?: boolean | object;
  };

  /** Allows future Ant server options without forcing package updates. */
  [key: string]: unknown;
}

/**
 * Returns an Ant server export object from Hono options.
 *
 * This is a convenience wrapper only; Ant starts the server from the default
 * export and still calls the provided `fetch` handler directly.
 */
export declare function serve<T extends AntHonoServeOptions>(options: T): T;

export type { AntHonoMiddleware, UpgradeWebSocket, WSContext };
