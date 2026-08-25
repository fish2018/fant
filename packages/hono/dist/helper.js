export class WSContext {
  #init;

  constructor(init) {
    this.#init = init;
    this.raw = init.raw;
    this.url = init.url ? new URL(init.url) : null;
    this.protocol = init.protocol ?? null;
  }

  send(source, options) {
    this.#init.send(source, options ?? {});
  }

  raw;
  binaryType = 'arraybuffer';

  get readyState() {
    return this.#init.readyState;
  }

  url;
  protocol;

  close(code, reason) {
    this.#init.close(code, reason);
  }
}

export function createWSMessageEvent(source) {
  return new MessageEvent('message', { data: source });
}

export function defineWebSocketHelper(handler) {
  return (...args) => {
    if (typeof args[0] === 'function') {
      const [createEvents, options] = args;
      return async function upgradeWebSocket(c, next) {
        const events = await createEvents(c);
        const result = await handler(c, events, options);
        if (result) return result;
        await next();
      };
    }

    const [c, events, options] = args;
    return (async () => {
      const upgraded = await handler(c, events, options);
      if (!upgraded) throw new Error('Failed to upgrade WebSocket');
      return upgraded;
    })();
  };
}
