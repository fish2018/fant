package org.antjs.runtime.demo;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.antjs.runtime.AntRuntime;
import org.antjs.runtime.StorageLocation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns the single Ant isolate used by the demo. All native calls are serialized. */
final class AntBackendController {
    interface Listener {
        void onLog(String message);
        void onReady(String baseUrl);
        void onStopped();
        void onBackendError(String message);
        void onInstallFinished(boolean success);
    }

    private enum State { STOPPED, STARTING, READY, STOPPING, CLOSED }

    private static final String TAG = "FAntApiDemo";
    private static final String LOOPBACK_URL = "http://127.0.0.1:8787";
    private static final int PORT = 8787;
    private static AntBackendController shared;

    private final Context context;
    private volatile Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService httpExecutor = Executors.newSingleThreadExecutor();

    private volatile State state = State.STOPPED;
    private volatile Handler runtimeHandler;
    private HandlerThread runtimeThread;
    private AntRuntime runtime;
    private StorageLocation projectLocation;
    private StorageLocation cacheLocation;
    private boolean pumping;
    private int readyChecks;
    private volatile boolean installing;
    private volatile boolean closeRequested;
    private volatile int restartFailures;
    private volatile long nextRestartAtMs;

    private AntBackendController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    static synchronized AntBackendController shared(Context context) {
        if (shared == null || shared.isClosed()) shared =
                new AntBackendController(context, null);
        return shared;
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void clearListener(Listener listener) {
        if (this.listener == listener) this.listener = null;
    }

    synchronized void setLocations(StorageLocation project, StorageLocation cache) {
        projectLocation = project;
        cacheLocation = cache;
    }

    synchronized StorageLocation projectLocation() {
        return projectLocation;
    }

    synchronized StorageLocation cacheLocation() {
        return cacheLocation;
    }

    /** Compatibility overload: strings are accepted only as real absolute paths. */
    void setCacheDirectory(String path) {
        if (path == null || path.length() == 0) {
            cacheLocation = null;
        } else {
            cacheLocation = StorageLocation.filePath(path);
        }
    }

    boolean isReady() {
        return state == State.READY;
    }

    boolean isStartingOrReady() {
        return state == State.STARTING || state == State.READY;
    }

    boolean isClosed() {
        return state == State.CLOSED;
    }

    void start(StorageLocation project) {
        beginStart(project, true);
    }

    void start(String projectPath) {
        start(StorageLocation.filePath(projectPath));
    }

    void ensureStarted(StorageLocation project) {
        if (project == null || installing || state == State.CLOSED) return;
        if (state == State.STOPPED && SystemClock.elapsedRealtime() >= nextRestartAtMs) {
            beginStart(project, false);
        }
    }

    void ensureStarted(String projectPath) {
        if (projectPath != null) ensureStarted(StorageLocation.filePath(projectPath));
    }

    void watchdog(StorageLocation project) {
        if (project == null || installing || state == State.CLOSED) return;
        if (state == State.STOPPED) {
            ensureStarted(project);
            return;
        }
        if (state != State.READY || runtime == null) return;
        Handler handler = runtimeHandler;
        if (handler == null) return;
        handler.post(() -> {
            if (state != State.READY || runtime == null) return;
            try {
                runtime.pump();
                String status = runtime.evaluate(
                        "globalThis.__antDemoError || "
                                + "(globalThis.__antDemoReady ? 'ready' : 'stopped')");
                if (!"ready".equals(status)) {
                    String message = "后端健康检查失败，准备重启。";
                    log(message);
                    cleanupFailedStart(message);
                }
            } catch (Throwable error) {
                String message = "后端健康检查失败：" + Log.getStackTraceString(error);
                log(message);
                cleanupFailedStart(message);
            }
        });
    }

    void watchdog(String projectPath) {
        if (projectPath != null) watchdog(StorageLocation.filePath(projectPath));
    }

    private void beginStart(StorageLocation project, boolean userRequested) {
        if (project == null) {
            log("项目目录未设置。");
            return;
        }
        synchronized (this) {
            projectLocation = project;
        }
        if (state != State.STOPPED || installing) {
            log("后端当前状态为 " + state.name() + "，无法启动。");
            return;
        }
        if (userRequested) {
            restartFailures = 0;
            nextRestartAtMs = 0;
        }
        closeRequested = false;
        state = State.STARTING;
        ensureRuntimeThread();
        Handler handler = runtimeHandler;
        if (handler == null) {
            state = State.STOPPED;
            log("无法创建 FAnt 运行线程。");
            return;
        }
        handler.post(() -> startOnRuntimeThread(project));
    }

    /** Installs and inspects dependencies without starting the HTTP server. */
    void installDependencies(StorageLocation project) {
        if (project == null) {
            log("项目目录未设置。");
            postInstallFinished(false);
            return;
        }
        synchronized (this) {
            projectLocation = project;
        }
        if (state != State.STOPPED || installing) {
            log("请先停止后端，再安装依赖。");
            postInstallFinished(false);
            return;
        }
        installing = true;
        closeRequested = false;
        ensureRuntimeThread();
        Handler handler = runtimeHandler;
        if (handler == null) {
            installing = false;
            postInstallFinished(false);
            return;
        }
        handler.post(() -> installOnRuntimeThread(project));
    }

    void installDependencies(String projectPath) {
        installDependencies(StorageLocation.filePath(projectPath));
    }

    void request(String path) {
        if (state != State.READY) {
            log("请先启动后端，再发送请求。");
            return;
        }
        httpExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(LOOPBACK_URL + path).openConnection();
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setRequestProperty("connection", "close");
                int status = connection.getResponseCode();
                InputStream input = status >= 400
                        ? connection.getErrorStream() : connection.getInputStream();
                String body = input == null ? "" : readUtf8(input);
                log("GET " + path + " -> HTTP " + status + "\n" + body.trim());
            } catch (Exception error) {
                log("请求失败：" + error);
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    void invalidateEntry() {
        /* evaluateFile(StorageLocation, ...) resets the storage context and
           ESM cache on every Start. Kept as an explicit host signal for UI. */
    }

    void stop() {
        Handler handler = runtimeHandler;
        if (handler == null || state == State.STOPPED || state == State.STOPPING
                || state == State.CLOSED) return;
        state = State.STOPPING;
        handler.post(this::stopOnRuntimeThread);
    }

    void close() {
        if (state == State.CLOSED) return;
        closeRequested = true;
        httpExecutor.shutdownNow();
        Handler handler = runtimeHandler;
        if (handler == null) {
            state = State.CLOSED;
            return;
        }
        if (state == State.STOPPED) {
            state = State.STOPPING;
            handler.post(this::shutdownOnRuntimeThread);
        } else if (state != State.STOPPING) {
            state = State.STOPPING;
            handler.post(this::stopOnRuntimeThread);
        }
    }

    private void ensureRuntimeThread() {
        if (runtimeThread != null && runtimeThread.isAlive() && runtimeHandler != null) return;
        runtimeThread = new HandlerThread("ant-js-backend");
        runtimeThread.start();
        runtimeHandler = new Handler(runtimeThread.getLooper());
    }

    private void startOnRuntimeThread(StorageLocation project) {
        try {
            if (closeRequested) {
                shutdownOnRuntimeThread();
                return;
            }
            prepareProject(project);
            if (runtime == null) {
                runtime = new AntRuntime(context);
                log("已创建 FAnt 运行时。");
            }

            log("项目：" + StorageFiles.display(project));
            log("正在安装 package.json 中声明的 npm 依赖…");
            AntRuntime.InstallResult installed = runtime.install(project, installOptions());
            log("依赖安装完成：包 " + installed.packageCount + "，缓存命中 "
                    + installed.cacheHits + "，下载 " + installed.cacheMisses);
            logDependencyReport(project);

            log("正在加载 server.ts…");
            runtime.evaluateFile(project, "server.ts");
            runtime.evaluate("await (globalThis.__antDemoStart ? "
                    + "globalThis.__antDemoStart() : Promise.resolve())");
            if (closeRequested || state == State.STOPPING) {
                stopOnRuntimeThread();
                return;
            }
            pumping = true;
            readyChecks = 0;
            Handler handler = runtimeHandler;
            if (handler != null) {
                handler.post(pumpRunnable);
                handler.postDelayed(readyRunnable, 50);
            }
        } catch (Throwable error) {
            String message = "后端启动失败：" + Log.getStackTraceString(error);
            log(message);
            cleanupFailedStart(message);
        }
    }

    private void installOnRuntimeThread(StorageLocation project) {
        boolean success = false;
        try {
            if (closeRequested) return;
            prepareProject(project);
            if (runtime == null) runtime = new AntRuntime(context);
            log("项目：" + StorageFiles.display(project));
            log("正在安装 npm 依赖…");
            AntRuntime.InstallResult installed = runtime.install(project, installOptions());
            log("依赖安装完成：包 " + installed.packageCount + "，缓存命中 "
                    + installed.cacheHits + "，下载 " + installed.cacheMisses);
            logDependencyReport(project);
            success = true;
        } catch (Throwable error) {
            log("依赖安装失败：" + Log.getStackTraceString(error));
        } finally {
            installing = false;
            postInstallFinished(success && !closeRequested);
            if (closeRequested) shutdownOnRuntimeThread();
        }
    }

    private void logDependencyReport(StorageLocation project) {
        AntRuntime.CompatibilityReport report = runtime.inspectDependencies(project);
        for (AntRuntime.DependencyReport dependency : report.dependencies) {
            log("依赖 " + dependency.name + "@" + dependency.version
                    + " -> " + dependency.category);
        }
        if (report.hasBlockingDependencies()) {
            throw new IllegalStateException("依赖包含 FAnt 当前不支持的 Node API 或原生扩展");
        }
    }

    private AntRuntime.InstallOptions installOptions() {
        AntRuntime.InstallOptions options = new AntRuntime.InstallOptions();
        StorageLocation cache = cacheLocation;
        if (cache != null) options.cacheLocation = cache;
        return options;
    }

    private final Runnable pumpRunnable = new Runnable() {
        @Override
        public void run() {
            if (!pumping || runtime == null) return;
            try {
                runtime.pump();
                Handler handler = runtimeHandler;
                if (handler != null) handler.postDelayed(this, 10);
            } catch (Throwable error) {
                pumping = false;
                String message = "运行时循环失败：" + Log.getStackTraceString(error);
                log(message);
                cleanupFailedStart(message);
            }
        }
    };

    private final Runnable readyRunnable = new Runnable() {
        @Override
        public void run() {
            if (!pumping || runtime == null || state != State.STARTING) return;
            try {
                String status = runtime.evaluate("globalThis.__antDemoError || "
                        + "(globalThis.__antDemoReady ? 'ready' : 'pending')");
                if ("ready".equals(status)) {
                    state = State.READY;
                    restartFailures = 0;
                    nextRestartAtMs = 0;
                    String accessUrl = accessUrl();
                    log("后端已就绪：" + accessUrl);
                    postReady(accessUrl);
                    return;
                }
                if (!"pending".equals(status)) throw new IllegalStateException(status);
                if (++readyChecks >= 100) {
                    throw new IllegalStateException("等待 server.listen() 超时");
                }
                Handler handler = runtimeHandler;
                if (handler != null) handler.postDelayed(this, 50);
            } catch (Throwable error) {
                String message = "后端就绪检查失败：" + Log.getStackTraceString(error);
                log(message);
                cleanupFailedStart(message);
            }
        }
    };

    private void stopOnRuntimeThread() {
        pumping = false;
        Handler handler = runtimeHandler;
        if (handler != null) {
            handler.removeCallbacks(pumpRunnable);
            handler.removeCallbacks(readyRunnable);
        }
        try {
            if (runtime != null) runtime.evaluate("await (globalThis.__antDemoStop ? "
                    + "globalThis.__antDemoStop() : Promise.resolve())");
            log("后端已停止，可再次点击启动。");
        } catch (Throwable error) {
            log("停止后端失败：" + Log.getStackTraceString(error));
        } finally {
            if (closeRequested) shutdownOnRuntimeThread();
            else {
                state = State.STOPPED;
                postStopped();
            }
        }
    }

    private void shutdownOnRuntimeThread() {
        pumping = false;
        Handler handler = runtimeHandler;
        if (handler != null) {
            handler.removeCallbacks(pumpRunnable);
            handler.removeCallbacks(readyRunnable);
        }
        try {
            if (runtime != null) runtime.close();
        } catch (Throwable error) {
            log("运行时清理失败：" + Log.getStackTraceString(error));
        } finally {
            runtime = null;
            state = State.CLOSED;
            HandlerThread thread = runtimeThread;
            runtimeThread = null;
            runtimeHandler = null;
            if (thread != null) thread.quitSafely();
        }
    }

    private void cleanupFailedStart(String message) {
        pumping = false;
        Handler handler = runtimeHandler;
        if (handler != null) {
            handler.removeCallbacks(pumpRunnable);
            handler.removeCallbacks(readyRunnable);
        }
        try {
            if (runtime != null) runtime.evaluate("await (globalThis.__antDemoStop ? "
                    + "globalThis.__antDemoStop() : Promise.resolve())");
        } catch (Throwable ignored) {
        }
        if (closeRequested) {
            shutdownOnRuntimeThread();
        } else {
            state = State.STOPPED;
            restartFailures++;
            long delay = Math.min(30_000L,
                    1_000L << Math.min(5, Math.max(0, restartFailures - 1)));
            nextRestartAtMs = SystemClock.elapsedRealtime() + delay;
            postBackendError(message + "\n将在 " + (delay / 1000L) + " 秒后重试。");
        }
    }

    private void prepareProject(StorageLocation project) throws IOException {
        StorageFiles.mkdirs(context, project, "");
        StorageFiles.ensureAsset(context, project, "backend/package.json", "package.json");
        StorageFiles.ensureAsset(context, project, "backend/server.ts", "server.ts");
    }

    private static String readUtf8(InputStream input) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8 * 1024];
            int count;
            while ((count = source.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void log(String message) {
        Log.i(TAG, message);
        Listener target = listener;
        if (target != null) mainHandler.post(() -> target.onLog(message));
    }

    private void postInstallFinished(boolean success) {
        Listener target = listener;
        if (target != null) mainHandler.post(() -> target.onInstallFinished(success));
    }

    private void postReady(String baseUrl) {
        Listener target = listener;
        if (target != null) mainHandler.post(() -> target.onReady(baseUrl));
    }

    private void postStopped() {
        Listener target = listener;
        if (target != null) mainHandler.post(target::onStopped);
    }

    private void postBackendError(String message) {
        Listener target = listener;
        if (target != null) mainHandler.post(() -> target.onBackendError(message));
    }

    String accessUrl() {
        String address = firstLanAddress();
        return address == null ? LOOPBACK_URL : "http://" + address + ":" + PORT;
    }

    private String firstLanAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            String fallback = null;
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()
                            && !address.isLinkLocalAddress()) {
                        String host = address.getHostAddress();
                        String name = network.getName() == null ? ""
                                : network.getName().toLowerCase();
                        if (name.startsWith("wlan") || name.startsWith("wifi")
                                || name.startsWith("eth")) return host;
                        if (fallback == null) fallback = host;
                    }
                }
            }
            return fallback;
        } catch (Exception error) {
            log("无法获取局域网地址：" + error.getMessage());
            return null;
        }
    }
}
