const createRendererBridge = bindings => {
  const grants = new Set((bindings.capabilityManifest || '').split(';').filter(Boolean));
  const pending = new Map();
  const listeners = new Map();
  let nextRequest = 1;

  const requireGrant = (access, channel) => {
    if (typeof channel !== 'string' || !grants.has(`${access}:${channel}`)) {
      throw new DOMException(`IPC capability not granted: ${access}:${channel}`, 'SecurityError');
    }
  };

  const ipc = Object.freeze({
    send(channel, value) {
      requireGrant('send', channel);
      bindings.nativeIpc(0, 0, channel, encode(value));
    },

    invoke(channel, value) {
      requireGrant('invoke', channel);
      const id = nextRequest++;
      return new Promise((resolve, reject) => {
        pending.set(id, { resolve, reject });
        bindings.nativeIpc(1, id, channel, encode(value));
      });
    },

    on(channel, listener) {
      requireGrant('receive', channel);
      if (typeof listener !== 'function') throw new TypeError('listener must be a function');
      const values = listeners.get(channel) || [];
      values.push(listener);
      listeners.set(channel, values);
      return () =>
        listeners.set(
          channel,
          values.filter(value => value !== listener)
        );
    }
  });

  const expose = (name, value) => {
    if (typeof name !== 'string' || !name || name === 'Ant') {
      throw new TypeError('exposed API name must be a non-empty string other than Ant');
    }
    if (Object.prototype.hasOwnProperty.call(globalThis, name)) {
      throw new Error(`globalThis.${name} is already defined`);
    }
    Object.defineProperty(globalThis, name, {
      value,
      enumerable: true,
      configurable: false,
      writable: false
    });
  };
  const contextBridge = Object.freeze({ exposeInMainWorld: expose });

  let nodeRequire;
  if (bindings.sandbox === false) {
    nodeRequire = bindings.nativeRequire;
    if (bindings.nodeIntegration) {
      Object.defineProperty(globalThis, 'require', {
        value: nodeRequire,
        enumerable: false,
        configurable: false,
        writable: false
      });
      Object.defineProperty(globalThis, 'process', {
        value: Object.freeze({
          arch: bindings.nodeEnvironment.arch,
          cwd: () => bindings.nodeEnvironment.cwd,
          platform: bindings.nodeEnvironment.platform,
          versions
        }),
        enumerable: false,
        configurable: false,
        writable: false
      });
    }
  }

  Object.defineProperty(globalThis, 'Ant', {
    value: Object.freeze({ expose, ipc, versions }),
    enumerable: false,
    configurable: false
  });

  return Object.freeze({
    contextBridge,
    ipcRenderer: ipc,
    require: nodeRequire,
    receive(operation, id, channel, payload) {
      if (operation === 2) {
        if (!grants.has(`receive:${channel}`)) return;
        const value = decode(payload);
        for (const listener of listeners.get(channel) || []) listener(value);
        return;
      }
      const request = pending.get(id);
      if (!request) return;
      pending.delete(id);
      const value = decode(payload);
      (operation === 0 ? request.resolve : request.reject)(value);
    }
  });
};
