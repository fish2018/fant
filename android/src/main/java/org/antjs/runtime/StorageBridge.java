package org.antjs.runtime;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Direct Storage Access Framework backend used by the native Ant runtime.
 * Paths passed here are tree-relative and are never converted to filesystem
 * paths. The native side owns module semantics and calls these methods through
 * JNI callbacks.
 */
public final class StorageBridge {
    public static final int OK = 0;
    public static final int INVALID_ARGUMENT = -1;
    public static final int NOT_FOUND = -2;
    public static final int PERMISSION = -3;
    public static final int IO = -4;
    public static final int UNSUPPORTED = -5;
    public static final int CONFLICT = -6;

    public static final class Entry {
        public final String name;
        public final boolean directory;

        Entry(String name, boolean directory) {
            this.name = name;
            this.directory = directory;
        }
    }

    private final ContentResolver resolver;
    private final Uri tree;
    /* Locks are process-wide, not instance-wide. A project bridge and a cache
       bridge may be separate Java objects while still referring to the same
       SAF tree, and two native Storage contexts must serialize updates. */
    private static final Object LOCK_MONITOR = new Object();
    private static final Map<String, Long> PROCESS_LOCKS = new HashMap<String, Long>();
    private static final Map<Long, String> PROCESS_LOCK_TOKENS = new HashMap<Long, String>();
    private static long NEXT_LOCK = 1L;

    public StorageBridge(Context context, String treeUri) {
        if (context == null) throw new NullPointerException("context");
        if (treeUri == null || !treeUri.startsWith("content://")) {
            throw new IllegalArgumentException("treeUri must be a content:// URI");
        }
        resolver = context.getApplicationContext().getContentResolver();
        tree = Uri.parse(treeUri);
        validatePersistedGrant();
        int probeResult = probe();
        if (probeResult == PERMISSION) {
            throw new SecurityException("SAF permission was revoked for " + tree);
        }
        if (probeResult != OK) {
            throw new IllegalStateException(
                    "SAF provider cannot create, read, rename and delete files in " + tree
                            + " (error " + probeResult + ")");
        }
    }

    private void validatePersistedGrant() {
        try {
            List<android.content.UriPermission> permissions =
                    resolver.getPersistedUriPermissions();
            for (android.content.UriPermission permission : permissions) {
                if (tree.equals(permission.getUri())
                        && permission.isReadPermission()
                        && permission.isWritePermission()) {
                    return;
                }
            }
        } catch (SecurityException error) {
            throw new IllegalStateException("SAF permission is revoked", error);
        }
        throw new SecurityException("SAF tree has no persisted read/write permission: " + tree);
    }

    private static String[] parts(String relative) {
        if (relative == null || relative.length() == 0) return new String[0];
        if (relative.startsWith("/") || relative.indexOf("\\") >= 0) {
            throw new IllegalArgumentException("relative path must be tree-relative");
        }
        String[] raw = relative.split("/");
        for (String part : raw) {
            if (part.length() == 0 || ".".equals(part) || "..".equals(part)) {
                throw new IllegalArgumentException("unsafe relative path");
            }
        }
        return raw;
    }

    private static String requireNonRoot(String relative) {
        if (relative == null || relative.length() == 0) {
            throw new IllegalArgumentException("the SAF tree root cannot be modified");
        }
        return relative;
    }

    private Uri rootDocument() {
        String id = DocumentsContract.getTreeDocumentId(tree);
        return DocumentsContract.buildDocumentUriUsingTree(tree, id);
    }

    private Uri findChild(Uri parent, String name) throws IOException {
        String parentId = DocumentsContract.getDocumentId(parent);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        Cursor cursor = null;
        try {
            cursor = resolver.query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null);
            if (cursor == null) throw new IOException("SAF provider returned no cursor");
            int idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            while (cursor.moveToNext()) {
                if (name.equals(cursor.getString(nameColumn))) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(idColumn));
                }
            }
            return null;
        } catch (SecurityException error) {
            throw new IOException("SAF permission revoked", error);
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private Uri resolve(String relative, boolean createDirectories) throws IOException {
        Uri current = rootDocument();
        for (String part : parts(relative)) {
            Uri child = findChild(current, part);
            if (child == null && createDirectories) {
                try {
                    child = DocumentsContract.createDocument(resolver, current,
                            DocumentsContract.Document.MIME_TYPE_DIR, part);
                } catch (SecurityException error) {
                    throw new IOException("SAF permission revoked", error);
                }
            }
            if (child == null) return null;
            current = child;
        }
        return current;
    }

    private static IOException io(String operation, Exception error) {
        return new IOException(operation + ": " + error.getMessage(), error);
    }

    public int mkdirs(String relative) {
        try {
            return resolve(relative, true) == null ? NOT_FOUND : OK;
        } catch (SecurityException error) {
            return PERMISSION;
        } catch (IllegalArgumentException error) {
            return INVALID_ARGUMENT;
        } catch (IOException error) {
            return error.getMessage() != null && error.getMessage().contains("permission")
                    ? PERMISSION : IO;
        }
    }

    public byte[] readFile(String relative) throws IOException {
        Uri file = resolve(relative, false);
        if (file == null) throw new IOException("not found");
        ParcelFileDescriptor descriptor;
        try {
            descriptor = resolver.openFileDescriptor(file, "r");
        } catch (SecurityException error) {
            throw io("SAF permission revoked", error);
        }
        if (descriptor == null) throw new IOException("provider could not open file");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        FileInputStream input = new FileInputStream(descriptor.getFileDescriptor());
        byte[] buffer = new byte[32 * 1024];
        int count;
        try {
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) output.write(buffer, 0, count);
            }
        } finally {
            try { input.close(); } finally { descriptor.close(); }
        }
        return output.toByteArray();
    }

    public int writeFile(String relative, byte[] data, boolean truncate) {
        if (data == null) return INVALID_ARGUMENT;
        try {
            String[] path = parts(relative);
            if (path.length == 0) return INVALID_ARGUMENT;
            StringBuilder parentPath = new StringBuilder();
            for (int i = 0; i < path.length - 1; i++) {
                if (parentPath.length() > 0) parentPath.append('/');
                parentPath.append(path[i]);
            }
            Uri parent = resolve(parentPath.toString(), true);
            if (parent == null) return NOT_FOUND;
            Uri file = findChild(parent, path[path.length - 1]);
            if (file == null) {
                file = DocumentsContract.createDocument(resolver, parent,
                        "application/octet-stream", path[path.length - 1]);
            }
            if (file == null) return IO;
            /* "rwt" is the provider-defined read/write/truncate mode. "wt"
               is not implemented consistently by DocumentsProviders. */
            String mode = truncate ? "rwt" : "wa";
            ParcelFileDescriptor descriptor = resolver.openFileDescriptor(file, mode);
            if (descriptor == null) return IO;
            FileOutputStream output = new FileOutputStream(descriptor.getFileDescriptor());
            try {
                output.write(data);
                output.flush();
            } finally {
                try { output.close(); } finally { descriptor.close(); }
            }
            return OK;
        } catch (SecurityException error) {
            return PERMISSION;
        } catch (IllegalArgumentException error) {
            return INVALID_ARGUMENT;
        } catch (IOException error) {
            return error.getMessage() != null && error.getMessage().contains("permission")
                    ? PERMISSION : IO;
        }
    }

    public long[] stat(String relative) throws IOException {
        Uri file = resolve(relative, false);
        if (file == null) return new long[]{0L, 0L, 0L};
        Cursor cursor = null;
        try {
            cursor = resolver.query(file,
                    new String[]{DocumentsContract.Document.COLUMN_MIME_TYPE,
                            OpenableColumns.SIZE}, null, null, null);
            if (cursor == null || !cursor.moveToFirst()) return new long[]{0L, 0L, 0L};
            int mimeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
            boolean directory = mimeColumn >= 0
                    && DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(mimeColumn));
            long size = sizeColumn >= 0 && !cursor.isNull(sizeColumn) ? cursor.getLong(sizeColumn) : 0L;
            return new long[]{1L, directory ? 1L : 0L, size};
        } catch (SecurityException error) {
            throw io("SAF permission revoked", error);
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    public Entry[] list(String relative) throws IOException {
        Uri directory = resolve(relative, false);
        if (directory == null) throw new IOException("not found");
        String id = DocumentsContract.getDocumentId(directory);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
        Cursor cursor = null;
        ArrayList<Entry> entries = new ArrayList<Entry>();
        try {
            cursor = resolver.query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
            if (cursor == null) throw new IOException("SAF provider returned no cursor");
            int nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            while (cursor.moveToNext()) {
                String name = cursor.getString(nameColumn);
                boolean directoryType = mimeColumn >= 0
                        && DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(mimeColumn));
                entries.add(new Entry(name, directoryType));
            }
            return entries.toArray(new Entry[entries.size()]);
        } catch (SecurityException error) {
            throw io("SAF permission revoked", error);
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    public int remove(String relative, boolean recursive) {
        try {
            requireNonRoot(relative);
            Uri target = resolve(relative, false);
            if (target == null) return OK;
            if (recursive) {
                Entry[] children = list(relative);
                for (Entry child : children) {
                    String childPath = relative.length() == 0 ? child.name : relative + "/" + child.name;
                    int result = remove(childPath, true);
                    if (result != OK) return result;
                }
            }
            return DocumentsContract.deleteDocument(resolver, target) ? OK : IO;
        } catch (SecurityException error) {
            return PERMISSION;
        } catch (IllegalArgumentException error) {
            return INVALID_ARGUMENT;
        } catch (IOException error) {
            return error.getMessage() != null && error.getMessage().contains("permission")
                    ? PERMISSION : IO;
        }
    }

    public int rename(String from, String to) {
        try {
            requireNonRoot(from);
            requireNonRoot(to);
            String[] target = parts(to);
            if (target.length == 0) return INVALID_ARGUMENT;
            StringBuilder parentPath = new StringBuilder();
            for (int i = 0; i < target.length - 1; i++) {
                if (parentPath.length() > 0) parentPath.append('/');
                parentPath.append(target[i]);
            }
            Uri source = resolve(from, false);
            Uri parent = resolve(parentPath.toString(), true);
            if (source == null || parent == null) return NOT_FOUND;
            Uri sourceParent = resolveParent(from);
            if (sourceParent != null && DocumentsContract.getDocumentId(sourceParent)
                    .equals(DocumentsContract.getDocumentId(parent))) {
                Uri renamed = DocumentsContract.renameDocument(resolver, source, target[target.length - 1]);
                return renamed == null ? IO : OK;
            }
            int result = copy(from, to);
            if (result != OK) return result;
            return remove(from, true);
        } catch (SecurityException error) {
            return PERMISSION;
        } catch (IllegalArgumentException error) {
            return INVALID_ARGUMENT;
        } catch (IOException error) {
            return error.getMessage() != null && error.getMessage().contains("permission")
                    ? PERMISSION : IO;
        }
    }

    private Uri resolveParent(String relative) throws IOException {
        String[] path = parts(relative);
        if (path.length == 0) return null;
        StringBuilder parent = new StringBuilder();
        for (int i = 0; i < path.length - 1; i++) {
            if (parent.length() > 0) parent.append('/');
            parent.append(path[i]);
        }
        return resolve(parent.toString(), false);
    }

    public int copy(String from, String to) {
        try {
            requireNonRoot(to);
            long[] info = stat(from);
            if (info[0] == 0L) return NOT_FOUND;
            if (info[1] != 0L) {
                int result = mkdirs(to);
                if (result != OK) return result;
                Entry[] children = list(from);
                for (Entry child : children) {
                    String childFrom = from.length() == 0 ? child.name : from + "/" + child.name;
                    String childTo = to.length() == 0 ? child.name : to + "/" + child.name;
                    result = copy(childFrom, childTo);
                    if (result != OK) return result;
                }
                return OK;
            }
            return writeFile(to, readFile(from), true);
        } catch (SecurityException error) {
            return PERMISSION;
        } catch (IllegalArgumentException error) {
            return INVALID_ARGUMENT;
        } catch (IOException error) {
            return error.getMessage() != null && error.getMessage().contains("permission")
                    ? PERMISSION : IO;
        }
    }

    private String lockKey(String relative) {
        return tree.toString() + "\n" + (relative == null ? "" : relative);
    }

    public long lock(String relative) throws InterruptedException {
        String key = lockKey(relative);
        synchronized (LOCK_MONITOR) {
            while (PROCESS_LOCKS.containsKey(key)) LOCK_MONITOR.wait();
            long token = NEXT_LOCK++;
            if (token == 0L) token = NEXT_LOCK++;
            PROCESS_LOCKS.put(key, token);
            PROCESS_LOCK_TOKENS.put(token, key);
            return token;
        }
    }

    public void unlock(long token) {
        synchronized (LOCK_MONITOR) {
            String key = PROCESS_LOCK_TOKENS.remove(token);
            if (key != null) {
                PROCESS_LOCKS.remove(key);
                LOCK_MONITOR.notifyAll();
            }
        }
    }

    /**
     * Replaces a file or directory using only this SAF tree. Providers with a
     * real same-parent rename get that fast path; providers without rename are
     * handled by copy/delete with a same-tree backup and rollback. No POSIX or
     * app-private temporary directory is involved.
     */
    public int atomicReplace(String from, String to) {
        try {
            requireNonRoot(from);
            requireNonRoot(to);
            long token = lock(to);
            String backup = to + ".ant-backup-" + Long.toUnsignedString(System.nanoTime());
            boolean backedUp = false;
            try {
                long[] targetInfo = stat(to);
                if (targetInfo[0] != 0L) {
                    int backupResult = copy(to, backup);
                    if (backupResult != OK) return backupResult;
                    backedUp = true;
                    int removeResult = remove(to, true);
                    if (removeResult != OK) return removeResult;
                }

                int result = rename(from, to);
                if (result != OK) {
                    result = copy(from, to);
                    if (result == OK) result = remove(from, true);
                }
                if (result != OK && backedUp) {
                    remove(to, true);
                    int restore = rename(backup, to);
                    if (restore != OK) restore = copy(backup, to);
                    if (restore != OK) return CONFLICT;
                }
                if (backedUp) remove(backup, true);
                return result;
            } finally {
                if (backedUp) remove(backup, true);
                unlock(token);
            }
        } catch (SecurityException error) {
            return PERMISSION;
        } catch (IllegalArgumentException error) {
            return INVALID_ARGUMENT;
        } catch (IOException error) {
            return error.getMessage() != null && error.getMessage().contains("permission")
                    ? PERMISSION : IO;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return CONFLICT;
        }
    }

    /** Probes the selected tree without modifying anything outside it. */
    public int probe() {
        String prefix = ".ant-probe-" + Long.toUnsignedString(System.nanoTime());
        String file = prefix + "/probe.bin";
        String renamed = prefix + "/renamed.bin";
        try {
            int result = mkdirs(prefix);
            if (result != OK) return result;
            result = writeFile(file, new byte[]{1, 2, 3}, true);
            if (result != OK) return result;
            byte[] bytes = readFile(file);
            if (bytes.length != 3) return IO;
            result = rename(file, renamed);
            if (result != OK) {
                result = copy(file, renamed);
                if (result == OK) result = remove(file, true);
            }
            return result;
        } catch (SecurityException error) {
            return PERMISSION;
        } catch (IOException error) {
            return IO;
        } finally {
            remove(prefix, true);
        }
    }
}
