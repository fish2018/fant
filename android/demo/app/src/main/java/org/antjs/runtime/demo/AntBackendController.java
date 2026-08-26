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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns the single Ant isolate used by the demo. All native calls are serialized. */
final class AntBackendController {
    interface Listener {
        void onLog(String message);
        void onInstallProgress(String message, int current, int total);
        void onReady(String baseUrl);
        void onStopped();
        void onBackendError(String message);
        void onInstallFinished(boolean success, boolean cancelled);
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
    private volatile String registryUrl = "https://registry.npmjs.org";
    private boolean pumping;
    private int readyChecks;
    private volatile boolean installing;
    private volatile long installStartedAtMs;
    private volatile long installLastProgressAtMs;
    private volatile String installLastProgress = "";
    private volatile int installProgressCurrent;
    private volatile int installProgressTotal;
    private volatile boolean closeRequested;
    private volatile boolean installCancelRequested;
    private volatile int restartFailures;
    private volatile long nextRestartAtMs;
    private volatile String installedProjectKey;
    private volatile String installedCacheKey;
    private volatile String installedPackageJsonFingerprint;
    private volatile String installedRegistry;

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

    void setRegistryUrl(String value) {
        if (value != null && value.length() > 0) registryUrl = value;
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

    boolean isInstalling() {
        return installing;
    }

    long installElapsedMs() {
        if (installStartedAtMs == 0L) return 0L;
        return Math.max(0L, SystemClock.elapsedRealtime() - installStartedAtMs);
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
        beginInstallProgress();
        closeRequested = false;
        installCancelRequested = false;
        ensureRuntimeThread();
        Handler handler = runtimeHandler;
        if (handler == null) {
            finishInstallProgress(true, false);
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
        if (installing || state == State.STARTING) {
            installCancelRequested = true;
            state = State.STOPPING;
            AntRuntime current = runtime;
            if (current != null) {
                current.cancelInstall();
            }
            log(installing ? "正在停止依赖安装…" : "正在停止后端启动…");
            return;
        }
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
            if (installCancelRequested) {
                installCancelRequested = false;
                state = State.STOPPED;
                postStopped();
                return;
            }
            prepareProject(project);
            if (runtime == null) {
                runtime = new AntRuntime(context);
                log("已创建 FAnt 运行时。");
            }

            log("项目：" + StorageFiles.display(project));
            log("npm 依赖源：" + registryUrl);
            String packageFingerprint = packageJsonFingerprint(project);
            if (isInstallStateCached(project, packageFingerprint)) {
                log("依赖已安装且 package.json 未变化，跳过依赖安装流程。");
            } else {
                log("正在安装 package.json 中声明的 npm 依赖…");
                beginInstallProgress();
                AntRuntime.InstallResult installed;
                try {
                    installed = runtime.install(project, installOptions());
                } finally {
                    finishInstallProgress(false, false);
                }
                rememberInstallState(project, packageFingerprint);
                log("依赖安装完成：包 " + installed.packageCount + "，缓存命中 "
                        + installed.cacheHits + "，下载 " + installed.cacheMisses);
            }

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
            if (installCancelRequested) {
                installCancelRequested = false;
                state = State.STOPPED;
                finishInstallProgress(false, false);
                log("依赖安装已停止。");
                postStopped();
                return;
            }
            finishInstallProgress(false, false);
            String message = "后端启动失败：" + Log.getStackTraceString(error);
            log(message);
            cleanupFailedStart(message);
        }
    }

    private void installOnRuntimeThread(StorageLocation project) {
        boolean success = false;
        try {
            if (closeRequested || installCancelRequested) return;
            prepareProject(project);
            if (runtime == null) runtime = new AntRuntime(context);
            log("项目：" + StorageFiles.display(project));
            log("npm 依赖源：" + registryUrl);
            log("正在安装 npm 依赖…");
            String packageFingerprint = packageJsonFingerprint(project);
            AntRuntime.InstallResult installed = runtime.install(project, installOptions());
            rememberInstallState(project, packageFingerprint);
            log("依赖安装完成：包 " + installed.packageCount + "，缓存命中 "
                    + installed.cacheHits + "，下载 " + installed.cacheMisses);
            success = true;
        } catch (Throwable error) {
            if (installCancelRequested) log("依赖安装已停止。");
            else log("依赖安装失败：" + Log.getStackTraceString(error));
        } finally {
            boolean cancelled = installCancelRequested;
            installCancelRequested = false;
            state = State.STOPPED;
            finishInstallProgress(true, success && !cancelled && !closeRequested, cancelled);
            if (closeRequested) shutdownOnRuntimeThread();
        }
    }

    private AntRuntime.InstallOptions installOptions() {
        AntRuntime.InstallOptions options = new AntRuntime.InstallOptions();
        options.registryUrl = registryUrl;
        options.verbose = true;
        options.progressListener = (phase, current, total, message) -> {
            String[] phases = {"解析依赖", "下载软件包", "解压软件包", "链接文件", "写入缓存", "运行脚本"};
            String label = phase >= 0 && phase < phases.length ? phases[phase] : "安装依赖";
            String count = total > 0 ? " " + current + "/" + total : " " + current + "/?";
            String detail = message == null || message.length() == 0 ? "" : " · " + message;
            installLastProgressAtMs = SystemClock.elapsedRealtime();
            installLastProgress = label + count + detail;
            installProgressCurrent = current;
            installProgressTotal = total;
            postInstallProgress(installLastProgress, current, total);
        };
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

    private String packageJsonFingerprint(StorageLocation project) throws IOException {
        byte[] data = StorageFiles.read(context, project, "package.json");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte value : hash) result.append(String.format("%02x", value & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("系统不支持 SHA-256", error);
        }
    }

    private String projectKey(StorageLocation project) {
        return project.kind().ordinal() + ":" + project.value();
    }

    private boolean isInstallStateCached(StorageLocation project, String packageFingerprint) {
        if (packageFingerprint == null || installedProjectKey == null
                || !projectKey(project).equals(installedProjectKey)
                || !cacheKey().equals(installedCacheKey)
                || !packageFingerprint.equals(installedPackageJsonFingerprint)
                || !registryUrl.equals(installedRegistry)) return false;
        try {
            return StorageFiles.exists(context, project, "node_modules/.ant/install-state");
        } catch (IOException error) {
            log("无法读取依赖安装标记，将重新校验依赖：" + error.getMessage());
            return false;
        }
    }

    private void rememberInstallState(StorageLocation project, String packageFingerprint) {
        installedProjectKey = projectKey(project);
        installedCacheKey = cacheKey();
        installedPackageJsonFingerprint = packageFingerprint;
        installedRegistry = registryUrl;
    }

    private String cacheKey() {
        StorageLocation cache = cacheLocation;
        return cache == null ? "" : cache.kind().ordinal() + ":" + cache.value();
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
        postInstallFinished(success, false);
    }

    private void postInstallFinished(boolean success, boolean cancelled) {
        Listener target = listener;
        if (target != null) mainHandler.post(() -> target.onInstallFinished(success, cancelled));
    }

    private final Runnable installProgressRunnable = new Runnable() {
        @Override public void run() {
            if (!installing) return;
            long elapsed = Math.max(0L, SystemClock.elapsedRealtime() - installStartedAtMs);
            long seconds = elapsed / 1000L;
            long sinceProgress = SystemClock.elapsedRealtime() - installLastProgressAtMs;
            String phase = sinceProgress < 2500L && installLastProgress.length() > 0
                    ? installLastProgress
                    : seconds < 8L ? "正在解析 package.json 和 registry…"
                    : seconds < 45L ? "正在连接依赖源并下载元数据…"
                    : "依赖安装仍在进行，网络较慢或源响应较慢…";
            postInstallProgress(phase, installProgressCurrent, installProgressTotal);
            mainHandler.postDelayed(this, 1000L);
        }
    };

    private synchronized void beginInstallProgress() {
        if (installing) return;
        installing = true;
        installStartedAtMs = SystemClock.elapsedRealtime();
        installLastProgressAtMs = installStartedAtMs;
        installLastProgress = "";
        installProgressCurrent = 0;
        installProgressTotal = 0;
        postInstallProgress("正在准备安装依赖… 0/?", 0, 0);
        mainHandler.removeCallbacks(installProgressRunnable);
        mainHandler.post(installProgressRunnable);
    }

    private synchronized void finishInstallProgress(boolean notifyFinished, boolean success) {
        finishInstallProgress(notifyFinished, success, false);
    }

    private synchronized void finishInstallProgress(
            boolean notifyFinished, boolean success, boolean cancelled) {
        if (!installing && !notifyFinished) return;
        installing = false;
        mainHandler.removeCallbacks(installProgressRunnable);
        postInstallProgress(null, 0, 0);
        if (notifyFinished) postInstallFinished(success, cancelled);
    }

    private void postInstallProgress(String message, int current, int total) {
        if (message != null && installing) {
            long elapsed = installElapsedMs();
            String progress = total > 0
                    ? " " + Math.min(100, Math.max(0, (int) (((long) current * 100L) / total)))
                            + "% (" + current + "/" + total + ")"
                    : "";
            message = message + progress + " · 已用 " + formatElapsed(elapsed);
        }
        final String deliveredMessage = message;
        Listener target = listener;
        if (target != null) mainHandler.post(
                () -> target.onInstallProgress(deliveredMessage, current, total));
    }

    private static String formatElapsed(long elapsedMs) {
        long seconds = elapsedMs / 1000L;
        return seconds < 60L ? seconds + " 秒" : (seconds / 60L) + " 分 " + (seconds % 60L) + " 秒";
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
