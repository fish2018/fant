const createMainBridge = bindings =>
  Object.freeze({
    encode,
    dispatch: async (windowId, operation, requestId, channel, payload) => {
      const window = bindings.windows[String(windowId)];

      const event = Object.freeze({
        sender: window && window.webContents,
        senderFrame: null
      });

      if (operation === 0) {
        const listeners = bindings.listeners[channel] || [];
        const value = decode(payload);
        for (const listener of listeners) listener(event, value);
        return;
      }

      try {
        const handler = bindings.handlers[channel];
        if (typeof handler !== 'function') throw new Error(`No ipcMain handler registered for ${channel}`);
        const result = await handler(event, decode(payload));
        bindings.reply(windowId, requestId, true, encode(result));
      } catch (error) {
        const normalized = error instanceof Error ? error : new Error(String(error));
        bindings.reply(windowId, requestId, false, encode(normalized));
      }
    }
  });
