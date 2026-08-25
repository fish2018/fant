function applyTheme(theme: Record<string, string>) {
  for (const [name, value] of Object.entries(theme)) {
    document.documentElement.style.setProperty(`--${name}`, value);
  }
}

async function toggleTheme() {
  applyTheme(await Ant.ipc.invoke('app:toggle-theme'));
}

const info = await desktop.runtimeInfo();

if (!preloadReady || !info.rendererIsWindow) {
  throw new Error('preload and renderer integration failed');
}

Ant.ipc.send('page:ready', { title: document.title });

export function Window() {
  return (
    <>
      <header className="titlebar" />
      <main>
        <h1>Ant Desktop</h1>
        <p>This page is rendered by Chromium.</p>
        <section>
          <div className="status">
            <span className="dot" />
            <strong>
              Ant {Ant.versions.ant} | Desktop {Ant.versions.desktop}
            </strong>
          </div>
          <p>
            Chrome {Ant.versions.chrome} | {desktop.platform} | {crypto.randomUUID().slice(0, 8)}
          </p>
          <button type="button" onClick={toggleTheme}>
            Toggle theme
          </button>
        </section>
      </main>
    </>
  );
}
