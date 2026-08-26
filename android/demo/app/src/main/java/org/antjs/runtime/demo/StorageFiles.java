package org.antjs.runtime.demo;

import android.content.Context;

import org.antjs.runtime.StorageBridge;
import org.antjs.runtime.StorageLocation;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** Small host-side adapter for editing files in either supported storage mode. */
final class StorageFiles {
    private StorageFiles() {
    }

    static boolean exists(Context context, StorageLocation location, String relative)
            throws IOException {
        if (location.isFilePath()) return resolve(location, relative).exists();
        long[] stat = bridge(context, location).stat(relative);
        return stat[0] != 0L;
    }

    static boolean directory(Context context, StorageLocation location, String relative)
            throws IOException {
        if (location.isFilePath()) return resolve(location, relative).isDirectory();
        long[] stat = bridge(context, location).stat(relative);
        return stat[0] != 0L && stat[1] != 0L;
    }

    static void mkdirs(Context context, StorageLocation location, String relative)
            throws IOException {
        if (location.isFilePath()) {
            File directory = resolve(location, relative);
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("无法创建目录：" + directory.getAbsolutePath());
            }
            return;
        }
        int result = bridge(context, location).mkdirs(relative);
        if (result != StorageBridge.OK) throw error("创建目录", result, location);
    }

    static byte[] read(Context context, StorageLocation location, String relative)
            throws IOException {
        if (location.isFilePath()) return readFile(resolve(location, relative));
        try {
            return bridge(context, location).readFile(relative);
        } catch (IOException error) {
            throw new IOException("读取 " + relative + " 失败：" + error.getMessage(), error);
        }
    }

    static String readText(Context context, StorageLocation location, String relative)
            throws IOException {
        return new String(read(context, location, relative), StandardCharsets.UTF_8);
    }

    static void write(Context context, StorageLocation location, String relative, byte[] data)
            throws IOException {
        if (location.isFilePath()) {
            File file = resolve(location, relative);
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("无法创建目录：" + parent.getAbsolutePath());
            }
            FileOutputStream output = new FileOutputStream(file, false);
            try {
                output.write(data);
                output.flush();
            } finally {
                output.close();
            }
            return;
        }
        int result = bridge(context, location).writeFile(relative, data, true);
        if (result != StorageBridge.OK) throw error("写入 " + relative, result, location);
    }

    static void writeText(Context context, StorageLocation location, String relative, String text)
            throws IOException {
        write(context, location, relative, text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Removes the contents of a storage directory while retaining that
     * directory itself. This is used for the explicit cache-cleanup action;
     * the project tree and its cache root are never removed.
     */
    static int clearContents(Context context, StorageLocation location, String relative)
            throws IOException {
        if (location.isFilePath()) {
            File directory = resolve(location, relative);
            if (!directory.isDirectory()) return 0;
            File[] children = directory.listFiles();
            if (children == null) throw new IOException("无法读取缓存目录：" + directory);
            int removed = 0;
            for (File child : children) {
                if (deleteFileTree(child)) removed++;
            }
            return removed;
        }

        StorageBridge storage = bridge(context, location);
        StorageBridge.Entry[] children;
        try {
            children = storage.list(relative);
        } catch (IOException missing) {
            if (isMissing(missing)) return 0;
            throw missing;
        }
        int removed = 0;
        for (StorageBridge.Entry child : children) {
            String childPath = relative == null || relative.length() == 0
                    ? child.name : relative + "/" + child.name;
            int result = storage.remove(childPath, child.directory);
            if (result != StorageBridge.OK) throw error("删除缓存 " + child.name, result, location);
            removed++;
        }
        return removed;
    }

    /** Returns SHA-512 integrity keys referenced by the current ant.lockb. */
    static Set<String> lockfileIntegrities(Context context, StorageLocation project)
            throws IOException {
        byte[] data = read(context, project, "ant.lockb");
        if (data.length < 88) throw new IOException("ant.lockb 无效或尚未生成");
        ByteBuffer header = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (header.getInt(0) != 0x504B474C || header.getInt(4) != 4) {
            throw new IOException("ant.lockb 版本不受支持");
        }
        long count = Integer.toUnsignedLong(header.getInt(8));
        long offset = Integer.toUnsignedLong(header.getInt(24));
        long end = offset + count * 136L;
        if (offset < 88L || end < offset || end > data.length) {
            throw new IOException("ant.lockb 软件包表损坏");
        }
        Set<String> integrities = new HashSet<String>();
        for (int index = 0; index < count; index++) {
            int start = (int) (offset + index * 136L + 40L);
            integrities.add(hex(data, start, 64));
        }
        return integrities;
    }

    /** Physically removes portable-cache packages not referenced by ant.lockb. */
    static int prunePackageCache(Context context, StorageLocation location, String relative,
                                 Set<String> keep) throws IOException {
        if (location.isFilePath()) {
            File root = resolve(location, relative);
            File entries = new File(root, "entries");
            File[] markers = entries.listFiles();
            if (markers == null && entries.exists()) throw new IOException("无法读取缓存索引");
            Set<String> removed = new HashSet<String>();
            if (markers != null) for (File marker : markers) {
                String name = marker.getName();
                if (!marker.isFile() || !name.endsWith(".json")) continue;
                String integrity = name.substring(0, name.length() - 5);
                if (!validIntegrity(integrity) || keep.contains(integrity)) continue;
                deleteFileTree(new File(root, "packages/" + integrity));
                deleteFileTree(marker);
                removed.add(integrity);
            }
            removeFileAliases(new File(root, "names"), removed);
            return removed.size();
        }

        StorageBridge storage = bridge(context, location);
        String entriesPath = join(relative, "entries");
        StorageBridge.Entry[] markers;
        try {
            markers = storage.list(entriesPath);
        } catch (IOException missing) {
            if (isMissing(missing)) return 0;
            throw missing;
        }
        Set<String> removed = new HashSet<String>();
        for (StorageBridge.Entry marker : markers) {
            if (marker.directory || !marker.name.endsWith(".json")) continue;
            String integrity = marker.name.substring(0, marker.name.length() - 5);
            if (!validIntegrity(integrity) || keep.contains(integrity)) continue;
            removeSaf(storage, join(relative, "packages/" + integrity), true, location);
            removeSaf(storage, join(entriesPath, marker.name), false, location);
            removed.add(integrity);
        }
        String namesPath = join(relative, "names");
        try {
            for (StorageBridge.Entry alias : storage.list(namesPath)) {
                if (alias.directory) continue;
                String path = join(namesPath, alias.name);
                String integrity = new String(storage.readFile(path), StandardCharsets.UTF_8).trim();
                if (removed.contains(integrity)) removeSaf(storage, path, false, location);
            }
        } catch (IOException ignored) {
        }
        return removed.size();
    }

    private static void removeFileAliases(File names, Set<String> removed) throws IOException {
        if (removed.isEmpty() || !names.isDirectory()) return;
        File[] aliases = names.listFiles();
        if (aliases == null) throw new IOException("无法读取缓存别名索引");
        for (File alias : aliases) {
            if (!alias.isFile()) continue;
            String integrity = new String(readFile(alias), StandardCharsets.UTF_8).trim();
            if (removed.contains(integrity)) deleteFileTree(alias);
        }
    }

    private static void removeSaf(StorageBridge storage, String path, boolean recursive,
                                  StorageLocation location) throws IOException {
        int result = storage.remove(path, recursive);
        if (result != StorageBridge.OK) throw error("删除缓存 " + path, result, location);
    }

    private static String join(String base, String child) {
        return base == null || base.length() == 0 ? child : base + "/" + child;
    }

    private static boolean isMissing(IOException error) {
        String message = error.getMessage();
        return message != null && (message.contains("not found") || message.contains("不存在"));
    }

    private static boolean validIntegrity(String value) {
        if (value.length() != 128) return false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f')
                    || (ch >= 'A' && ch <= 'F'))) return false;
        }
        return true;
    }

    private static String hex(byte[] data, int offset, int length) {
        char[] result = new char[length * 2];
        char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < length; i++) {
            int value = data[offset + i] & 0xff;
            result[i * 2] = alphabet[value >>> 4];
            result[i * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(result);
    }

    private static boolean deleteFileTree(File file) throws IOException {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("无法读取目录：" + file);
            for (File child : children) deleteFileTree(child);
        }
        if (!file.delete() && file.exists()) {
            throw new IOException("无法删除：" + file.getAbsolutePath());
        }
        return true;
    }

    static void ensureAsset(Context context, StorageLocation location, String assetName,
                            String relative) throws IOException {
        if (exists(context, location, relative)) return;
        InputStream input = context.getAssets().open(assetName);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            write(context, location, relative, output.toByteArray());
        } finally {
            input.close();
        }
    }

    static StorageBridge bridge(Context context, StorageLocation location) {
        if (context == null) throw new IllegalArgumentException("context must not be null");
        if (!location.isSafTree()) throw new IllegalArgumentException("location is not SAF_TREE");
        return new StorageBridge(context, location.value());
    }

    static File resolve(StorageLocation location, String relative) throws IOException {
        if (!location.isFilePath()) throw new IllegalArgumentException("location is not FILE_PATH");
        if (relative == null || relative.length() == 0) return new File(location.value());
        if (relative.startsWith("/") || relative.indexOf('\\') >= 0) {
            throw new IOException("不安全的相对路径：" + relative);
        }
        String[] parts = relative.split("/", -1);
        File result = new File(location.value());
        for (String part : parts) {
            if (part.length() == 0 || ".".equals(part) || "..".equals(part)
                    || part.indexOf('\\') >= 0) {
                throw new IOException("不安全的相对路径：" + relative);
            }
            result = new File(result, part);
        }
        return result;
    }

    static String display(StorageLocation location) {
        return location == null ? "未设置" : location.kind().name() + "\n" + location.value();
    }

    private static byte[] readFile(File file) throws IOException {
        FileInputStream input = new FileInputStream(file);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static IOException error(String operation, int code, StorageLocation location) {
        String reason;
        switch (code) {
            case StorageBridge.PERMISSION:
                reason = "权限已撤销或没有写权限";
                break;
            case StorageBridge.NOT_FOUND:
                reason = "目录或文件不存在";
                break;
            case StorageBridge.UNSUPPORTED:
                reason = "SAF Provider 不支持此操作";
                break;
            default:
                reason = "错误码 " + code;
                break;
        }
        return new IOException(operation + "失败（" + reason + "）：" + location.value());
    }
}
