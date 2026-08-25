const assert = require('node:assert');
const { Readable } = require('node:stream');

async function main() {
  assert.strictEqual(typeof Readable.toWeb, 'function');

  let reads = 0;
  const readable = new Readable({
    read() {
      reads++;
      if (reads === 1) this.push(Buffer.from('hello'));
      else if (reads === 2) this.push(Buffer.from(' world'));
      else this.push(null);
    },
  });

  const web = Readable.toWeb(readable);
  assert.strictEqual(typeof web.getReader, 'function');

  const reader = web.getReader();
  const chunks = [];

  for (;;) {
    const result = await reader.read();
    if (result.done) break;
    chunks.push(Buffer.from(result.value));
  }

  assert.strictEqual(Buffer.concat(chunks).toString(), 'hello world');
  console.log('stream-readable-to-web:ok');
}

main().catch((error) => {
  console.error(error && error.stack ? error.stack : String(error));
  process.exit(1);
});
