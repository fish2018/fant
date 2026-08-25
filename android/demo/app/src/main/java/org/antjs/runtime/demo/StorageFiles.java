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
import java.nio.charset.StandardCharsets;

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
