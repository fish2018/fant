package org.antjs.runtime;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A small, single-isolate wrapper around the Ant JavaScript runtime.
 *
 * <p>There can be one instance per Android process. Create, use, and close it
 * on the same long-lived worker or service thread. The underlying Ant isolate
 * and libuv loop are not a multi-threaded JavaScript API.</p>
 */
public final class AntRuntime implements AutoCloseable {
    private static final int MAX_INSTALL_CONNECTIONS = 6;

    static {
        System.loadLibrary("ant_android");
    }

    private volatile long handle;
    private final Context applicationContext;

    public AntRuntime() {
        this(null);
    }

    /** Creates a runtime with an Android context used for SAF_TREE bridges. */
    public AntRuntime(Context context) {
        applicationContext = context == null ? null : context.getApplicationContext();
        handle = nativeCreate(applicationContext);
        if (handle == 0) {
            throw new AntRuntimeException("Unable to create Ant runtime");
        }
    }

    /** Evaluates JavaScript or erasable TypeScript source synchronously. */
    public synchronized String evaluate(String source) {
        ensureOpen();
        if (source == null) throw new NullPointerException("source");
        return nativeEvaluate(handle, source);
    }

    /**
     * Loads and evaluates an absolute JavaScript or erasable TypeScript entry
     * file. Relative imports and npm packages resolve from the entry file's
     * real project directory.
     */
    public synchronized String evaluateFile(String entryFile) {
        ensureOpen();
        if (entryFile == null) throw new NullPointerException("entryFile");
        File file = new File(entryFile);
        if (!file.isAbsolute()) {
            throw new IllegalArgumentException("entryFile must be an absolute path");
        }
        if (!file.isFile()) {
            throw new IllegalArgumentException("entryFile must be an existing regular file");
        }
        return nativeEvaluateFile(handle, file.getPath());
    }

    /**
     * Loads an entry from a typed storage location. SAF_TREE is passed to the
     * native storage bridge as a URI; it is never treated as a POSIX path.
     */
    public synchronized String evaluateFile(StorageLocation project, String entryFile) {
        ensureOpen();
        if (project == null) throw new NullPointerException("project");
        if (entryFile == null || entryFile.length() == 0) {
            throw new IllegalArgumentException("entryFile must not be empty");
        }
        if (entryFile.indexOf('\\') >= 0 || entryFile.charAt(0) == '/') {
            throw new IllegalArgumentException("entryFile must be a relative path");
        }
        String[] components = entryFile.split("/", -1);
        for (String component : components) {
            if (component.length() == 0 || ".".equals(component) || "..".equals(component)) {
                throw new IllegalArgumentException("entryFile contains an unsafe path component");
            }
            for (int i = 0; i < component.length(); i++) {
                if (component.charAt(i) < 0x20 || component.charAt(i) == 0x7f) {
                    throw new IllegalArgumentException("entryFile contains a control character");
                }
            }
        }
        return nativeEvaluateLocation(handle, project.kind().ordinal(), project.value(), entryFile);
    }

    /** Advances pending timers, fetches, streams, and microtasks without blocking. */
    public synchronized void pump() {
        ensureOpen();
        nativePump(handle);
    }

    /**
     * Installs dependencies described by projectDirectory/package.json.
     * Package downloads, extraction, and node_modules linking are enabled by
     * default. Lifecycle scripts remain disabled unless explicitly enabled.
     */
    public synchronized InstallResult install(String projectDirectory, String... packageSpecs) {
        return install(StorageLocation.filePath(projectDirectory), new InstallOptions(), packageSpecs);
    }

    public synchronized InstallResult install(
            String projectDirectory, InstallOptions options, String... packageSpecs) {
        return install(StorageLocation.filePath(projectDirectory), options, packageSpecs);
    }

    /** Installs dependencies into a typed project location. */
    public synchronized InstallResult install(
            StorageLocation project, InstallOptions options, String... packageSpecs) {
        ensureOpen();
        if (project == null) throw new NullPointerException("project");
        if (options == null) throw new NullPointerException("options");
        String registry = options.registryUrl == null ? "https://registry.npmjs.org" : options.registryUrl;
        if (options.maxConnections <= 0 || options.maxConnections > MAX_INSTALL_CONNECTIONS) {
            throw new IllegalArgumentException("maxConnections must be between 1 and 6");
        }
        StorageLocation cacheLocation = options.cacheLocation;
        if (cacheLocation == null && options.cacheDirectory != null) {
            cacheLocation = StorageLocation.filePath(options.cacheDirectory);
        }
        int cacheKind = cacheLocation == null ? -1 : cacheLocation.kind().ordinal();
        String cache = cacheLocation == null ? null : cacheLocation.value();
        String json = nativeInstall(
                handle,
                project.kind().ordinal(),
                project.value(),
                packageSpecs == null ? new String[0] : packageSpecs,
                registry,
                cache,
                cacheKind,
                options.maxConnections,
                options.verbose,
                options.force,
                options.runLifecycleScripts,
                options.progressListener);
        return InstallResult.fromJson(json);
    }

    /** Requests cancellation of an in-flight dependency installation. */
    public void cancelInstall() {
        long current = handle;
        if (current != 0) nativeCancelInstall(current);
    }

    /** Runs approved lifecycle scripts; an empty package list means all discovered packages. */
    public synchronized void runPostinstall(String projectDirectory, String... packageNames) {
        ensureOpen();
        if (projectDirectory == null) throw new NullPointerException("projectDirectory");
        File project = requireAbsoluteProjectDirectory(projectDirectory);
        nativeRunPostinstall(
                handle,
                project.getPath(),
                packageNames == null ? new String[0] : packageNames);
    }

    /**
     * Runs lifecycle scripts for a FILE_PATH project. SAF_TREE lifecycle
     * execution is intentionally rejected by the portable package manager,
     * because it would require spawning a host shell against a virtual tree.
     */
    public synchronized void runPostinstall(StorageLocation project, String... packageNames) {
        ensureOpen();
        if (project == null) throw new NullPointerException("project");
        if (project.isSafTree()) {
            throw new UnsupportedOperationException(
                    "Lifecycle scripts are not supported for SAF_TREE projects");
        }
        nativeRunPostinstall(handle, project.value(),
                packageNames == null ? new String[0] : packageNames);
    }

    /** Scans installed packages without executing package code. */
    public synchronized CompatibilityReport inspectDependencies(String projectDirectory) {
        ensureOpen();
        if (projectDirectory == null) throw new NullPointerException("projectDirectory");
        File project = requireAbsoluteProjectDirectory(projectDirectory);
        return DependencyInspector.inspect(new File(project, "node_modules"));
    }

    /** Scans a typed project location without executing package code. */
    public synchronized CompatibilityReport inspectDependencies(StorageLocation project) {
        ensureOpen();
        if (project == null) throw new NullPointerException("project");
        return DependencyInspector.inspect(project, applicationContext);
    }

    /** Android host variant that can inspect a SAF tree directly. */
    public synchronized CompatibilityReport inspectDependencies(
            Context context, StorageLocation project) {
        ensureOpen();
        if (context == null) throw new NullPointerException("context");
        if (project == null) throw new NullPointerException("project");
        return DependencyInspector.inspect(project, context);
    }

    public synchronized boolean isOpen() {
        return handle != 0;
    }

    @Override
    public synchronized void close() {
        if (handle != 0) {
            if (nativeDestroy(handle)) {
                handle = 0;
            }
        }
    }

    private void ensureOpen() {
        if (handle == 0) throw new IllegalStateException("Ant runtime is closed");
    }

    private static File requireAbsoluteProjectDirectory(String path) {
        File project = new File(path);
        if (!project.isAbsolute()) {
            throw new IllegalArgumentException("projectDirectory must be an absolute path");
        }
        return project;
    }

    private static native long nativeCreate(Context context);
    private static native String nativeEvaluate(long handle, String source);
    private static native String nativeEvaluateFile(long handle, String entryFile);
    private static native String nativeEvaluateLocation(
            long handle, int projectKind, String projectLocation, String entryFile);
    private static native void nativePump(long handle);
    private static native boolean nativeDestroy(long handle);
    private static native String nativeInstall(
            long handle, int projectKind, String projectLocation, String[] packageSpecs,
            String registryUrl, String cacheLocation, int cacheKind, int maxConnections,
            boolean verbose, boolean force, boolean runLifecycleScripts,
            InstallProgressListener progressListener);
    private static native void nativeCancelInstall(long handle);
    private static native void nativeRunPostinstall(
            long handle, String projectDirectory, String[] packageNames);

    public static final class InstallOptions {
        public String registryUrl = "https://registry.npmjs.org";
        /** Typed cache location. Takes precedence over cacheDirectory. */
        public StorageLocation cacheLocation;
        /** @deprecated Use cacheLocation with StorageLocation.filePath(). */
        @Deprecated
        public File cacheDirectory;
        /** Registry metadata/tarball connection pool size, from 1 through 6. */
        public int maxConnections = 6;
        public boolean verbose;
        public boolean force;
        /** Code-executing package lifecycle hooks are opt-in on Android. */
        public boolean runLifecycleScripts;
        /** Called from the installing thread as dependency work advances. */
        public InstallProgressListener progressListener;
    }

    @FunctionalInterface
    public interface InstallProgressListener {
        void onProgress(int phase, int current, int total, String message);
    }

    public static final class InstallResult {
        public final int packageCount;
        public final int cacheHits;
        public final int cacheMisses;
        public final int filesLinked;
        public final int filesCopied;
        public final int packagesInstalled;
        public final int packagesSkipped;
        public final int lifecycleBuilds;
        public final long elapsedMs;
        public final int lifecycleScriptCount;

        private InstallResult(Map<String, Object> values) {
            packageCount = number(values, "packageCount");
            cacheHits = number(values, "cacheHits");
            cacheMisses = number(values, "cacheMisses");
            filesLinked = number(values, "filesLinked");
            filesCopied = number(values, "filesCopied");
            packagesInstalled = number(values, "packagesInstalled");
            packagesSkipped = number(values, "packagesSkipped");
            lifecycleBuilds = number(values, "lifecycleBuilds");
            elapsedMs = longNumber(values, "elapsedMs");
            lifecycleScriptCount = number(values, "lifecycleScriptCount");
        }

        @SuppressWarnings("unchecked")
        static InstallResult fromJson(String json) {
            Object value = MiniJson.parse(json);
            if (!(value instanceof Map)) throw new AntRuntimeException("Invalid package-manager result");
            return new InstallResult((Map<String, Object>) value);
        }
    }

    public static final class CompatibilityReport {
        public final List<DependencyReport> dependencies;

        CompatibilityReport(List<DependencyReport> dependencies) {
            this.dependencies = Collections.unmodifiableList(new ArrayList<DependencyReport>(dependencies));
        }

        public boolean hasBlockingDependencies() {
            for (DependencyReport dependency : dependencies) {
                if (dependency.category == DependencyCategory.NATIVE_ADDON ||
                        dependency.category == DependencyCategory.UNSUPPORTED_NODE_API ||
                        dependency.category == DependencyCategory.UNKNOWN_DYNAMIC_REQUIRE) return true;
            }
            return false;
        }
    }

    public enum DependencyCategory {
        PORTABLE_JS,
        SUPPORTED_NODE_API,
        UNSUPPORTED_NODE_API,
        NATIVE_ADDON,
        UNKNOWN_DYNAMIC_REQUIRE
    }

    public static final class DependencyReport {
        public final String name;
        public final String version;
        public final DependencyCategory category;
        public final List<String> nodeApis;
        public final List<String> files;
        public final boolean lifecycleScriptRequired;
        public final boolean nativeBuildMetadata;

        DependencyReport(String name, String version, DependencyCategory category,
                         List<String> nodeApis, List<String> files,
                         boolean lifecycleScriptRequired, boolean nativeBuildMetadata) {
            this.name = name;
            this.version = version;
            this.category = category;
            this.nodeApis = Collections.unmodifiableList(new ArrayList<String>(nodeApis));
            this.files = Collections.unmodifiableList(new ArrayList<String>(files));
            this.lifecycleScriptRequired = lifecycleScriptRequired;
            this.nativeBuildMetadata = nativeBuildMetadata;
        }
    }

    private static int number(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private static long longNumber(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    public static final class AntRuntimeException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public AntRuntimeException(String message) {
            super(message);
        }

        public AntRuntimeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
