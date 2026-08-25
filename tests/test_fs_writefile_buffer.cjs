const assert = require('node:assert');
const fs = require('node:fs');
const fsp = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');

async function main() {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'ant-writefile-buffer-'));

  try {
    const uploadFile = path.join(tmpDir, 'upload.bin');
    const arrayBuffer = new Uint8Array([0, 1, 2, 255]).buffer;
    await fsp.writeFile(uploadFile, Buffer.from(arrayBuffer));
    assert.deepStrictEqual(Array.from(fs.readFileSync(uploadFile)), [0, 1, 2, 255]);

    await fsp.appendFile(uploadFile, new Uint8Array([3, 4]).subarray(1));
    assert.deepStrictEqual(Array.from(fs.readFileSync(uploadFile)), [0, 1, 2, 255, 4]);

    const syncFile = path.join(tmpDir, 'sync.bin');
    fs.writeFileSync(syncFile, Buffer.from([65, 0, 66]));
    fs.appendFileSync(syncFile, new Uint8Array([67]));
    assert.deepStrictEqual(Array.from(fs.readFileSync(syncFile)), [65, 0, 66, 67]);

    const callbackFile = path.join(tmpDir, 'callback.bin');
    await new Promise((resolve, reject) => {
      fs.writeFile(callbackFile, Buffer.from([9, 8]), (err) => err ? reject(err) : resolve());
    });
    await new Promise((resolve, reject) => {
      fs.appendFile(callbackFile, new Uint8Array([7]), (err) => err ? reject(err) : resolve());
    });
    assert.deepStrictEqual(Array.from(fs.readFileSync(callbackFile)), [9, 8, 7]);

    const emptyFile = path.join(tmpDir, 'empty.bin');
    await fsp.writeFile(emptyFile, Buffer.alloc(0));
    assert.strictEqual(fs.statSync(emptyFile).size, 0);
  } finally {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
}

main().catch((err) => {
  console.error(err && err.stack ? err.stack : String(err));
  process.exit(1);
});
