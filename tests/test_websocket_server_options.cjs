const assert = require('node:assert');
const { spawn } = require('node:child_process');
const crypto = require('node:crypto');
const fs = require('node:fs');
const net = require('node:net');
const os = require('node:os');
const path = require('node:path');
const zlib = require('node:zlib');

async function reservePort() {
  return await new Promise((resolve, reject) => {
    const server = net.createServer();
    server.on('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address();
      server.close(() => resolve(port));
    });
  });
}

async function waitForServer(port, child) {
  const startedAt = Date.now();
  while (Date.now() - startedAt < 2000) {
    assert.equal(child.exitCode, null, 'server exited before accepting connections');
    try {
      const response = await fetch(`http://127.0.0.1:${port}/ready`);
      await response.text();
      return;
    } catch {
      await new Promise(resolve => setTimeout(resolve, 10));
    }
  }
  throw new Error('server did not start');
}

function parseHeaders(head) {
  const headers = {};
  for (const line of head.split('\r\n').slice(1)) {
    const index = line.indexOf(':');
    if (index === -1) continue;
    headers[line.slice(0, index).toLowerCase()] = line.slice(index + 1).trim();
  }
  return headers;
}

async function connectWebSocket(port) {
  return await new Promise((resolve, reject) => {
    let buffer = Buffer.alloc(0);
    const timeout = setTimeout(() => {
      socket.destroy();
      reject(new Error('timed out waiting for websocket handshake'));
    }, 2000);
    const socket = net.createConnection({ host: '127.0.0.1', port }, () => {
      const key = crypto.randomBytes(16).toString('base64');
      socket.write(
        'GET /ws HTTP/1.1\r\n' +
        'Host: 127.0.0.1\r\n' +
        'Connection: Upgrade\r\n' +
        'Upgrade: websocket\r\n' +
        'Sec-WebSocket-Version: 13\r\n' +
        `Sec-WebSocket-Key: ${key}\r\n` +
        'Sec-WebSocket-Extensions: permessage-deflate\r\n' +
        '\r\n'
      );
    });

    socket.on('data', chunk => {
      buffer = Buffer.concat([buffer, chunk]);
      const end = buffer.indexOf('\r\n\r\n');
      if (end === -1) return;
      socket.removeAllListeners('data');
      clearTimeout(timeout);
      const head = buffer.subarray(0, end).toString('latin1');
      const rest = buffer.subarray(end + 4);
      resolve({ socket, head, headers: parseHeaders(head), rest });
    });
    socket.on('error', error => {
      clearTimeout(timeout);
      reject(error);
    });
  });
}

function sendMaskedFrame(socket, opcode, payload, rsv1 = false) {
  const bytes = Buffer.isBuffer(payload) ? payload : Buffer.from(payload);
  const mask = crypto.randomBytes(4);
  const header = [];
  header.push(0x80 | (rsv1 ? 0x40 : 0) | opcode);
  if (bytes.length < 126) {
    header.push(0x80 | bytes.length);
  } else if (bytes.length <= 0xffff) {
    header.push(0x80 | 126, (bytes.length >> 8) & 0xff, bytes.length & 0xff);
  } else {
    throw new Error('test frame too large');
  }

  const masked = Buffer.alloc(bytes.length);
  for (let i = 0; i < bytes.length; i++) masked[i] = bytes[i] ^ mask[i % 4];
  socket.write(Buffer.concat([Buffer.from(header), mask, masked]));
}

async function readFrame(socket, initial = Buffer.alloc(0)) {
  let buffer = initial;

  return await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      socket.destroy();
      reject(new Error('timed out waiting for websocket frame'));
    }, 2000);
    const tryRead = () => {
      if (buffer.length < 2) return false;
      let pos = 2;
      let len = buffer[1] & 0x7f;
      if (len === 126) {
        if (buffer.length < pos + 2) return false;
        len = (buffer[pos] << 8) | buffer[pos + 1];
        pos += 2;
      } else if (len === 127) {
        reject(new Error('unexpected 64-bit test frame'));
        return true;
      }
      if (buffer.length < pos + len) return false;
      const frame = {
        fin: (buffer[0] & 0x80) !== 0,
        rsv1: (buffer[0] & 0x40) !== 0,
        opcode: buffer[0] & 0x0f,
        payload: buffer.subarray(pos, pos + len),
        rest: buffer.subarray(pos + len),
      };
      socket.removeListener('data', onData);
      socket.removeListener('end', onEnd);
      socket.removeListener('close', onClose);
      clearTimeout(timeout);
      resolve(frame);
      return true;
    };

    const onData = chunk => {
      buffer = Buffer.concat([buffer, chunk]);
      tryRead();
    };

    const onEnd = () => {
      clearTimeout(timeout);
      reject(new Error(`websocket ended before frame; buffered=${buffer.toString('hex')}`));
    };
    const onClose = () => {
      clearTimeout(timeout);
      reject(new Error(`websocket closed before frame; buffered=${buffer.toString('hex')}`));
    };

    socket.on('data', onData);
    socket.on('end', onEnd);
    socket.on('close', onClose);
    socket.on('error', error => {
      clearTimeout(timeout);
      reject(error);
    });
    tryRead();
  });
}

function inflateMessage(payload) {
  return Buffer.from(
    zlib.inflateRawSync(Buffer.concat([payload, Buffer.from([0x00, 0x00, 0xff, 0xff])]))
  ).toString();
}

function deflatedWebSocketHello() {
  return Buffer.from('ca48cdc9c90700', 'hex');
}

async function main() {
  const port = await reservePort();
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'ant-websocket-options-'));
  const serverPath = path.join(tmpDir, 'server.mjs');

  fs.writeFileSync(serverPath, `
export default {
  hostname: '127.0.0.1',
  port: ${port},
  websocket: {
    idleTimeout: 1,
    maxPayloadLength: 64,
    perMessageDeflate: true,
  },
  fetch(request, ctx) {
    const url = new URL(request.url);
    if (url.pathname === '/ready') return new Response('ok');
    const { socket, response } = ctx.upgradeWebSocket(request);
    socket.onmessage = event => socket.send('echo:' + event.data);
    return response;
  },
};
`);

  const child = spawn(process.execPath, [serverPath], {
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let stderr = '';
  child.stderr.on('data', chunk => { stderr += String(chunk); });

  try {
    await waitForServer(port, child);

    const { socket, head, headers, rest } = await connectWebSocket(port);
    assert.match(head, /^HTTP\/1\.1 101 /);
    assert.match(headers['sec-websocket-extensions'], /permessage-deflate/);

    sendMaskedFrame(socket, 0x1, deflatedWebSocketHello(), true);
    const echo = await readFrame(socket, rest);
    assert.equal(echo.opcode, 0x1);
    assert.equal(echo.rsv1, true);
    assert.equal(inflateMessage(echo.payload), 'echo:hello');

    sendMaskedFrame(socket, 0x1, 'x'.repeat(65));
    const close = await readFrame(socket, echo.rest);
    assert.equal(close.opcode, 0x8);
    assert.equal(close.payload.readUInt16BE(0), 1009);
    socket.end();

    console.log('websocket:server-options:ok');
  } finally {
    child.kill('SIGTERM');
    fs.rmSync(tmpDir, { recursive: true, force: true });
    assert.equal(child.exitCode, null, `server crashed: ${stderr}`);
  }
}

main().catch(error => {
  console.error(error && error.stack ? error.stack : error);
  process.exit(1);
});
