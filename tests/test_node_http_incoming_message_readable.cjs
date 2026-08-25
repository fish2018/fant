const assert = require('node:assert');
const http = require('node:http');
const net = require('node:net');
const { Readable } = require('node:stream');

const server = http.createServer((req, res) => {
  assert(req instanceof Readable, 'IncomingMessage should inherit from Readable');
  assert.strictEqual(typeof req.resume, 'function');
  assert.strictEqual(typeof req.read, 'function');

  const chunks = [];
  req.on('data', (chunk) => chunks.push(Buffer.from(chunk)));
  req.on('end', () => {
    assert.strictEqual(Buffer.concat(chunks).toString(), 'fastify-body');
    res.end('ok');
  });
  req.resume();
});

server.listen(0, '127.0.0.1', () => {
  const { port } = server.address();
  const client = net.connect(port, '127.0.0.1');
  let response = '';

  client.on('connect', () => {
    client.write(
      'POST / HTTP/1.1\r\n' +
      'Host: 127.0.0.1\r\n' +
      'Connection: close\r\n' +
      'Content-Length: 12\r\n' +
      '\r\n' +
      'fastify-body'
    );
  });

  client.on('data', (chunk) => {
    response += chunk.toString();
  });

  client.on('end', () => {
    assert(response.includes('\r\n\r\nok'), response);
    server.close(() => {
      console.log('node-http-incoming-message-readable:ok');
    });
  });

  client.on('error', (error) => {
    server.close(() => {
      throw error;
    });
  });
});
