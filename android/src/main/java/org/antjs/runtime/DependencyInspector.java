package org.antjs.runtime;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Static, code-free compatibility inspection for an installed node_modules tree. */
final class DependencyInspector {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_SOURCE_BYTES = 2 * 1024 * 1024;

    private static final Pattern REQUIRE = Pattern.compile(
            "\\brequire\\s*\\(\\s*([\\\"'])([^\\\"']*)\\1\\s*\\)");
    private static final Pattern IMPORT_CALL = Pattern.compile(
            "\\bimport\\s*\\(\\s*([\\\"'])([^\\\"']*)\\1\\s*\\)");
    private static final Pattern IMPORT_FROM = Pattern.compile(
            "\\b(?:import|export)\\s+[^;\\r\\n]*?\\bfrom\\s*([\\\"'])([^\\\"']*)\\1");
    private static final Pattern IMPORT_SIDE_EFFECT = Pattern.compile(
            "\\bimport\\s*([\\\"'])([^\\\"']*)\\1");
    private static final Pattern DYNAMIC_REQUIRE = Pattern.compile(
            "\\brequire\\s*\\(\\s*(?![\\\"'])[^)]*\\)");
    private static final Pattern DYNAMIC_IMPORT = Pattern.compile(
            "\\bimport\\s*\\(\\s*(?![\\\"'])[^)]*\\)");
    private static final Pattern PROCESS_DLOPEN = Pattern.compile("\\bprocess\\s*\\.\\s*dlopen\\s*\\(");

    private static final Set<String> SUPPORTED_NODE_APIS = setOf(
            "assert", "async_hooks", "buffer", "console", "constants", "crypto",
            "dns", "domain", "diagnostics_channel", "events", "fs", "fs/promises",
            "http", "https", "module", "net", "os", "path", "path/posix",
            "path/win32", "perf_hooks", "process", "querystring", "stream",
            "stream/promises", "stream/web", "string_decoder", "timers", "timers/promises",
            "tls", "tty", "url", "util", "util/types", "v8", "zlib");
    private static final Set<String> UNSUPPORTED_NODE_APIS = setOf(
            "assert/strict", "child_process", "cluster", "dgram", "dns/promises",
            "http2", "inspector", "punycode", "readline", "readline/promises", "repl",
            "stream/consumers", "sys", "test", "trace_events", "vm", "wasi",
            "worker_threads");
    private static final Set<String> NATIVE_TOOL_PACKAGES = setOf(
            "@mapbox/node-pre-gyp", "bindings", "cmake-js", "nan", "node-addon-api",
            "node-gyp", "node-gyp-build", "node-pre-gyp", "prebuild-install", "prebuildify");

    private DependencyInspector() {
    }

    static AntRuntime.CompatibilityReport inspect(File nodeModules) {
        if (nodeModules == null) return new AntRuntime.CompatibilityReport(
                Collections.<AntRuntime.DependencyReport>emptyList());
        return inspectTree(new FileTree(nodeModules), "", nodeModules.getAbsolutePath());
    }

    static AntRuntime.CompatibilityReport inspect(StorageLocation project, Context context) {
        if (project == null) throw new NullPointerException("project");
        if (project.isFilePath()) {
            return inspect(new File(project.value(), "node_modules"));
        }
        if (context == null) {
            throw new IllegalStateException(
                    "A Context supplied to AntRuntime is required to inspect a SAF_TREE project");
        }
        StorageBridge bridge = new StorageBridge(context, project.value());
        return inspectTree(new SafTree(bridge, project.value()), "node_modules", project.toString());
    }

    private static AntRuntime.CompatibilityReport inspectTree(
            Tree tree, String nodeModules, String location) {
        List<AntRuntime.DependencyReport> reports = new ArrayList<AntRuntime.DependencyReport>();
        Set<String> visitedModules = new HashSet<String>();
        Set<String> visitedPackages = new HashSet<String>();
        try {
            if (tree.stat(nodeModules).directory) {
                scanNodeModules(tree, nodeModules, reports, visitedModules, visitedPackages);
            }
        } catch (IOException error) {
            throw new AntRuntime.AntRuntimeException(
                    "Unable to inspect dependencies in " + location + ": " + error.getMessage(), error);
        }
        return new AntRuntime.CompatibilityReport(reports);
    }

    private static void scanNodeModules(
            Tree tree, String nodeModules, List<AntRuntime.DependencyReport> reports,
            Set<String> visitedModules, Set<String> visitedPackages) throws IOException {
        if (!visitedModules.add(tree.identity(nodeModules))) return;
        for (Entry child : tree.list(nodeModules)) {
            if (!child.directory || ".bin".equals(child.name)) continue;
            String childPath = join(nodeModules, child.name);
            if (child.name.startsWith("@")) {
                for (Entry scoped : tree.list(childPath)) {
                    if (!scoped.directory) continue;
                    scanPackage(tree, join(childPath, scoped.name), scoped.name,
                            child.name + "/" + scoped.name, reports,
                            visitedModules, visitedPackages);
                }
            } else {
                scanPackage(tree, childPath, child.name, child.name, reports,
                        visitedModules, visitedPackages);
            }
        }
    }

    private static void scanPackage(
            Tree tree, String packageDir, String fallbackName, String displayName,
            List<AntRuntime.DependencyReport> reports,
            Set<String> visitedModules, Set<String> visitedPackages) throws IOException {
        if (!visitedPackages.add(tree.identity(packageDir))) return;
        Map<String, Object> metadata = readObject(tree, join(packageDir, "package.json"));
        String name = stringValue(metadata.get("name"),
                displayName == null ? fallbackName : displayName);
        String version = stringValue(metadata.get("version"), "");
        Analysis analysis = new Analysis();

        inspectMetadata(tree, packageDir, metadata, analysis);
        scanFiles(tree, packageDir, packageDir, analysis, new HashSet<String>());
        reports.add(analysis.toReport(name, version));

        String nested = join(packageDir, "node_modules");
        if (tree.stat(nested).directory) {
            scanNodeModules(tree, nested, reports, visitedModules, visitedPackages);
        }
    }

    private static void inspectMetadata(
            Tree tree, String packageDir, Map<String, Object> metadata,
            Analysis analysis) throws IOException {
        if (tree.stat(join(packageDir, "binding.gyp")).file
                || tree.stat(join(packageDir, "binding.gypi")).file) {
            analysis.nativeBuildMetadata = true;
            analysis.evidence.add("binding.gyp");
        }
        if (Boolean.TRUE.equals(metadata.get("gypfile"))) {
            analysis.nativeBuildMetadata = true;
            analysis.evidence.add("package.json:gypfile");
        }
        Map<String, Object> scripts = objectValue(metadata.get("scripts"));
        for (String key : new String[]{"preinstall", "install", "postinstall", "prepare"}) {
            if (scripts.containsKey(key)) analysis.lifecycleScriptRequired = true;
        }
        for (String section : new String[]{"dependencies", "optionalDependencies", "devDependencies"}) {
            Map<String, Object> dependencies = objectValue(metadata.get(section));
            for (String token : NATIVE_TOOL_PACKAGES) {
                if (!dependencies.containsKey(token)) continue;
                analysis.nativeBuildMetadata = true;
                analysis.evidence.add("package.json:" + section + ":" + token);
            }
        }
        if (metadata.get("binary") instanceof Map) {
            analysis.nativeBuildMetadata = true;
            analysis.evidence.add("package.json:binary");
        }
        String scriptsText = String.valueOf(scripts);
        for (String token : NATIVE_TOOL_PACKAGES) {
            if (scriptsText.indexOf(token) < 0) continue;
            analysis.nativeBuildMetadata = true;
            analysis.evidence.add("package.json:scripts:" + token);
        }
    }

    private static void scanFiles(
            Tree tree, String root, String current, Analysis analysis,
            Set<String> visitedDirectories) throws IOException {
        if (!visitedDirectories.add(tree.identity(current))) return;
        for (Entry entry : tree.list(current)) {
            String path = join(current, entry.name);
            if (entry.directory) {
                if (!"node_modules".equals(entry.name)) {
                    scanFiles(tree, root, path, analysis, visitedDirectories);
                }
                continue;
            }
            String relative = relativePath(root, path);
            if (entry.name.endsWith(".node")) {
                analysis.nativeBuildMetadata = true;
                analysis.evidence.add(relative);
                continue;
            }
            if (!isSourceFile(entry.name)) continue;
            String source = readText(tree, path);
            if (source != null) inspectImports(source, relative, analysis);
        }
    }

    private static void inspectImports(String source, String file, Analysis analysis) {
        if (DYNAMIC_REQUIRE.matcher(source).find() || DYNAMIC_IMPORT.matcher(source).find()) {
            analysis.unknownDynamicRequire = true;
        }
        if (PROCESS_DLOPEN.matcher(source).find()) {
            analysis.nativeBuildMetadata = true;
            analysis.evidence.add(file + ":process.dlopen");
        }
        inspectPattern(REQUIRE.matcher(source), file, analysis);
        inspectPattern(IMPORT_CALL.matcher(source), file, analysis);
        inspectPattern(IMPORT_FROM.matcher(source), file, analysis);
        inspectPattern(IMPORT_SIDE_EFFECT.matcher(source), file, analysis);
    }

    private static void inspectPattern(Matcher matcher, String file, Analysis analysis) {
        while (matcher.find()) {
            String module = matcher.group(2);
            if (NATIVE_TOOL_PACKAGES.contains(module)) {
                analysis.nativeBuildMetadata = true;
                analysis.evidence.add(file + ":" + module);
            }
            String normalized = normalizeNodeApi(module);
            if (normalized == null) continue;
            if (SUPPORTED_NODE_APIS.contains(normalized)) {
                analysis.supportedApis.add(normalized);
                analysis.evidence.add(file);
            } else if (module.startsWith("node:") || UNSUPPORTED_NODE_APIS.contains(normalized)) {
                analysis.unsupportedApis.add(normalized);
                analysis.evidence.add(file);
            }
        }
    }

    private static String normalizeNodeApi(String module) {
        if (module == null) return null;
        if (module.startsWith("node:")) return module.substring("node:".length());
        if (SUPPORTED_NODE_APIS.contains(module) || UNSUPPORTED_NODE_APIS.contains(module)) return module;
        return null;
    }

    private static boolean isSourceFile(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".js") || lower.endsWith(".mjs") || lower.endsWith(".cjs")
                || lower.endsWith(".ts") || lower.endsWith(".mts") || lower.endsWith(".cts")
                || lower.endsWith(".jsx") || lower.endsWith(".tsx");
    }

    private static String readText(Tree tree, String path) throws IOException {
        Stat stat = tree.stat(path);
        if (!stat.file || stat.size > MAX_SOURCE_BYTES) return null;
        byte[] bytes = tree.read(path);
        return bytes.length > MAX_SOURCE_BYTES ? null : new String(bytes, UTF8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readObject(Tree tree, String path) throws IOException {
        String text = readText(tree, path);
        if (text == null) return Collections.emptyMap();
        try {
            Object value = MiniJson.parse(text);
            return value instanceof Map
                    ? (Map<String, Object>) value : Collections.<String, Object>emptyMap();
        } catch (RuntimeException ignored) {
            return Collections.emptyMap();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectValue(Object value) {
        return value instanceof Map
                ? (Map<String, Object>) value : Collections.<String, Object>emptyMap();
    }

    private static String stringValue(Object value, String fallback) {
        return value instanceof String ? (String) value : fallback;
    }

    private static String join(String parent, String name) throws IOException {
        requireSafeName(name);
        return parent == null || parent.length() == 0 ? name : parent + "/" + name;
    }

    private static void requireSafeName(String name) throws IOException {
        if (name == null || name.length() == 0 || ".".equals(name) || "..".equals(name)
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            throw new IOException("Storage provider returned an unsafe file name");
        }
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch < 0x20 || ch == 0x7f) {
                throw new IOException("Storage provider returned a control character in a file name");
            }
        }
    }

    private static String relativePath(String root, String path) {
        if (root == null || root.length() == 0) return path;
        return path.startsWith(root + "/") ? path.substring(root.length() + 1) : path;
    }

    private static Set<String> setOf(String... values) {
        return Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(values)));
    }

    private interface Tree {
        Stat stat(String path) throws IOException;
        List<Entry> list(String path) throws IOException;
        byte[] read(String path) throws IOException;
        String identity(String path) throws IOException;
    }

    private static final class Stat {
        final boolean file;
        final boolean directory;
        final long size;

        Stat(boolean exists, boolean directory, long size) {
            this.file = exists && !directory;
            this.directory = exists && directory;
            this.size = size;
        }
    }

    private static final class Entry {
        final String name;
        final boolean directory;

        Entry(String name, boolean directory) {
            this.name = name;
            this.directory = directory;
        }
    }

    private static final class FileTree implements Tree {
        private final File root;

        FileTree(File root) {
            this.root = root;
        }

        private File resolve(String path) {
            return path == null || path.length() == 0 ? root : new File(root, path);
        }

        @Override
        public Stat stat(String path) {
            File file = resolve(path);
            return new Stat(file.exists(), file.isDirectory(), file.isFile() ? file.length() : 0L);
        }

        @Override
        public List<Entry> list(String path) throws IOException {
            File directory = resolve(path);
            File[] files = directory.listFiles();
            if (files == null) {
                if (!directory.exists()) return Collections.emptyList();
                throw new IOException("Cannot enumerate " + directory.getAbsolutePath());
            }
            Arrays.sort(files);
            List<Entry> entries = new ArrayList<Entry>(files.length);
            for (File file : files) entries.add(new Entry(file.getName(), file.isDirectory()));
            return entries;
        }

        @Override
        public byte[] read(String path) throws IOException {
            BufferedInputStream input = new BufferedInputStream(new FileInputStream(resolve(path)));
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                return output.toByteArray();
            } finally {
                input.close();
            }
        }

        @Override
        public String identity(String path) throws IOException {
            return resolve(path).getCanonicalPath();
        }
    }

    private static final class SafTree implements Tree {
        private final StorageBridge bridge;
        private final String treeUri;

        SafTree(StorageBridge bridge, String treeUri) {
            this.bridge = bridge;
            this.treeUri = treeUri;
        }

        @Override
        public Stat stat(String path) throws IOException {
            long[] value = bridge.stat(path);
            return new Stat(value[0] != 0L, value[1] != 0L, value[2]);
        }

        @Override
        public List<Entry> list(String path) throws IOException {
            StorageBridge.Entry[] values = bridge.list(path);
            List<Entry> entries = new ArrayList<Entry>(values.length);
            for (StorageBridge.Entry value : values) {
                requireSafeName(value.name);
                entries.add(new Entry(value.name, value.directory));
            }
            Collections.sort(entries, new Comparator<Entry>() {
                @Override
                public int compare(Entry left, Entry right) {
                    return left.name.compareTo(right.name);
                }
            });
            return entries;
        }

        @Override
        public byte[] read(String path) throws IOException {
            return bridge.readFile(path);
        }

        @Override
        public String identity(String path) {
            return treeUri + "\n" + path;
        }
    }

    private static final class Analysis {
        final Set<String> supportedApis = new LinkedHashSet<String>();
        final Set<String> unsupportedApis = new LinkedHashSet<String>();
        final Set<String> evidence = new LinkedHashSet<String>();
        boolean unknownDynamicRequire;
        boolean nativeBuildMetadata;
        boolean lifecycleScriptRequired;

        AntRuntime.DependencyReport toReport(String name, String version) {
            AntRuntime.DependencyCategory category;
            List<String> apis = new ArrayList<String>();
            apis.addAll(unsupportedApis);
            apis.addAll(supportedApis);
            if (nativeBuildMetadata) category = AntRuntime.DependencyCategory.NATIVE_ADDON;
            else if (!unsupportedApis.isEmpty()) category = AntRuntime.DependencyCategory.UNSUPPORTED_NODE_API;
            else if (unknownDynamicRequire) category = AntRuntime.DependencyCategory.UNKNOWN_DYNAMIC_REQUIRE;
            else if (!supportedApis.isEmpty()) category = AntRuntime.DependencyCategory.SUPPORTED_NODE_API;
            else category = AntRuntime.DependencyCategory.PORTABLE_JS;
            return new AntRuntime.DependencyReport(name, version, category,
                    apis, new ArrayList<String>(evidence), lifecycleScriptRequired, nativeBuildMetadata);
        }
    }
}
