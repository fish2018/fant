import { WSContext, createWSMessageEvent, defineWebSocketHelper } from './helper.js';

function getAntServerContext(c) {
  const env = c && c.env;
  if (env && typeof env.upgradeWebSocket === 'function') return env;
  if (env && env.server && typeof env.server.upgradeWebSocket === 'function') {
    return env.server;
  }
  throw new TypeError('env has to include Ant server context as the 2nd argument of fetch.');
}

function normalizeMessageData(data) {
  if (typeof data === 'string') return data;
  if (data instanceof ArrayBuffer) return data;
  if (ArrayBuffer.isView(data)) {
    return data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength);
  }
  return data;
}

export function createWSContext(socket, request) {
  return new WSContext({
    send(source) {
      socket.send(source);
    },
    raw: socket,
    get readyState() {
      return socket.readyState;
    },
    url: request && request.url ? new URL(request.url) : null,
    protocol: socket.protocol || null,
    close(code, reason) {
      socket.close(code, reason);
    }
  });
}

function addSocketListener(socket, event, listener) {
  if (typeof socket.addEventListener === 'function') {
    socket.addEventListener(event, listener);
    return;
  }
  socket[`on${event}`] = listener;
}

export const upgradeWebSocket = defineWebSocketHelper((c, events) => {
  const upgradeHeader = c.req.header('Upgrade');
  if (String(upgradeHeader || '').toLowerCase() !== 'websocket') return;

  const server = getAntServerContext(c);
  const request = c.req.raw;
  const { socket, response } = server.upgradeWebSocket(request);
  const wsContext = createWSContext(socket, request);

  if (events.onOpen) {
    addSocketListener(socket, 'open', event => {
      events.onOpen(event, wsContext);
    });
  }

  if (events.onMessage) {
    addSocketListener(socket, 'message', event => {
      events.onMessage(createWSMessageEvent(normalizeMessageData(event.data)), wsContext);
    });
  }

  if (events.onClose) {
    addSocketListener(socket, 'close', event => {
      events.onClose(event, wsContext);
    });
  }

  if (events.onError) {
    addSocketListener(socket, 'error', event => {
      events.onError(event, wsContext);
    });
  }

  return response;
});

export function serve(options) {
  if (!options || typeof options.fetch !== 'function') {
    throw new TypeError('serve() requires a fetch function');
  }

  return { ...options };
}
