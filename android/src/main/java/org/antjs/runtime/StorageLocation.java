package org.antjs.runtime;

import java.io.File;

/**
 * A project or package-cache location owned by the host application.
 *
 * <p>FILE_PATH is a real absolute filesystem path. SAF_TREE is an Android
 * Storage Access Framework tree URI and must never be converted to a fake
 * POSIX path. Native support for SAF_TREE is provided by the Android storage
 * bridge; callers should surface bridge errors instead of silently copying to
 * another directory.</p>
 */
public final class StorageLocation {
    public enum Kind {
        FILE_PATH,
        SAF_TREE
    }

    private final Kind kind;
    private final String value;

    private StorageLocation(Kind kind, String value) {
        this.kind = kind;
        this.value = value;
    }

    public static StorageLocation filePath(File path) {
        if (path == null) throw new NullPointerException("path");
        return filePath(path.getPath());
    }

    public static StorageLocation filePath(String path) {
        if (path == null) throw new NullPointerException("path");
        File file = new File(path);
        if (!file.isAbsolute()) {
            throw new IllegalArgumentException("FILE_PATH must be absolute: " + path);
        }
        return new StorageLocation(Kind.FILE_PATH, file.getAbsolutePath());
    }

    public static StorageLocation safTree(String treeUri) {
        if (treeUri == null) throw new NullPointerException("treeUri");
        if (!treeUri.startsWith("content://")) {
            throw new IllegalArgumentException("SAF_TREE must be a content:// URI");
        }
        return new StorageLocation(Kind.SAF_TREE, treeUri);
    }

    public Kind kind() {
        return kind;
    }

    public String value() {
        return value;
    }

    public boolean isFilePath() {
        return kind == Kind.FILE_PATH;
    }

    public boolean isSafTree() {
        return kind == Kind.SAF_TREE;
    }

    @Override
    public String toString() {
        return kind.name() + "(" + value + ")";
    }
}
