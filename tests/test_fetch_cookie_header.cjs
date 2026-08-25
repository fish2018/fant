const assert = require('node:assert');
const http = require('node:http');

const server = http.createServer((req, res) => {
  res.writeHead(200, { 'content-type': 'application/json' });
  res.end(JSON.stringify({
    cookie: req.headers.cookie || null,
    host: req.headers.host || null,
    xTest: req.headers['x-test'] || null,
  }));
});

server.listen(0, async () => {
  const { port } = server.address();

  try {
    const request = new Request(`http://127.0.0.1:${port}/`, {
      headers: {
        cookie: 'session=abc123',
        host: 'example.test',
        'x-test': 'hello',
      },
    });

    assert.equal(request.headers.get('cookie'), 'session=abc123');
    assert.equal(request.headers.get('host'), 'example.test');

    const response = await fetch(request);
    assert.equal(response.status, 200);

    const body = await response.json();
    assert.equal(body.cookie, 'session=abc123');
    assert.equal(body.host, `127.0.0.1:${port}`);
    assert.equal(body.xTest, 'hello');

    console.log('ok');
  } finally {
    server.close();
  }
});
