import http from "node:http";
import lodash from "lodash";

// Listen on all interfaces so a browser on the same Wi-Fi network can call
// the demo. The Android UI displays the current LAN address and port.
const HOST: string = "0.0.0.0";
const PORT: number = 8787;

function sendJson(response: any, status: number, value: unknown): void {
  const body: string = JSON.stringify(value, null, 2) + "\n";
  response.statusCode = status;
  response.setHeader("content-type", "application/json; charset=utf-8");
  response.setHeader("content-length", String(Buffer.byteLength(body)));
  response.setHeader("connection", "close");
  response.end(body);
}

let server: any;
let startPromise: Promise<void> | null = null;

function createServer(): any {
  const created = http.createServer((request: any, response: any) => {
    const url = new URL(request.url ?? "/", `http://${HOST}:${PORT}`);

    if (url.pathname === "/api/health") {
      sendJson(response, 200, {
        ok: true,
        runtime: `FAnt (Ant core) ${Ant.version}`,
        language: "JavaScript + erasable TypeScript",
        dependency: "lodash@4.17.21"
      });
      return;
    }

    if (url.pathname === "/api/format") {
      const text: string = url.searchParams.get("text") ?? "Hello Android TV";
      const words: string[] = text.trim().split(/\s+/).filter(Boolean);
      sendJson(response, 200, {
        input: text,
        kebabCase: lodash.kebabCase(text),
        chunks: lodash.chunk(words, 2)
      });
      return;
    }

    sendJson(response, 404, {
      error: "not_found",
      routes: ["/api/health", "/api/format?text=Hello%20Android%20TV"]
    });
  });
  created.on("error", (error: unknown) => {
    globalThis.__antDemoError = String(error);
  });
  return created;
}

function startServer(): Promise<void> {
  if (server?.listening) {
    globalThis.__antDemoReady = true;
    return Promise.resolve();
  }
  if (startPromise) return startPromise;

  // FAnt's Android net listener is one-shot after close; keep the isolate but
  // create a fresh JS Server object for every subsequent Start.
  if (!server || !server.listening) server = createServer();
  globalThis.__antDemoReady = false;
  globalThis.__antDemoError = "";
  startPromise = new Promise<void>((resolve, reject) => {
    const onError = (error: unknown) => {
      globalThis.__antDemoError = String(error);
      startPromise = null;
      reject(error);
    };
    server.once("error", onError);
    server.listen(PORT, HOST, () => {
      server.removeListener("error", onError);
      globalThis.__antDemoReady = true;
      startPromise = null;
      console.log(`FAnt Android API listening on http://${HOST}:${PORT}`);
      resolve();
    });
  });
  return startPromise;
}

async function stopServer(): Promise<void> {
  const pendingStart = startPromise;
  if (pendingStart) {
    try {
      await pendingStart;
    } catch (_) {
      // The listener may have failed before it became closable.
    }
  }
  startPromise = null;
  globalThis.__antDemoReady = false;
  if (!server || !server.listening) return Promise.resolve();
  return new Promise<void>((resolve) => {
    server.close(() => resolve());
  });
}

globalThis.__antDemoReady = false;
globalThis.__antDemoError = "";
globalThis.__antDemoStart = startServer;
globalThis.__antDemoStop = stopServer;

export default { start: startServer, stop: stopServer };
