package org.antjs.runtime;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Direct Storage Access Framework backend used by the native Ant runtime.
 * Paths passed here are tree-relative and are never converted to filesystem
 * paths. The native side owns module semantics and calls these methods through
 * JNI callbacks.
 */
public final class StorageBridge {
    private static final String TAG = "FAntSAF";
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
    /* DocumentsProvider lookups are much more expensive than ordinary file
       path joins. Package extraction revisits the same parent and file paths
       for every tar chunk, so retain document URIs for the lifetime of one
       install. The cache is invalidated whenever Ant mutates the tree. */
    private final Map<String, Uri> resolvedUris = new HashMap<String, Uri>();
    /* A DocumentsProvider directory query is expensive on some OEM devices.
       Keep one complete child index per directory, including negative lookups,
       for the lifetime of this bridge/install. New and removed children update
       the index so the common extract path never re-queries the same directory
       for every file. */
    private final Map<String, Map<String, Uri>> childIndexes =
            new HashMap<String, Map<String, Uri>>();
    /* Locks are process-wide, not instance-wide. A project bridge and a cache
       bridge may be separate Java objects while still referring to the same
       SAF tree, and two native Storage contexts must serialize updates. */
    private static final Object LOCK_MONITOR = new Object();
    private static final Map<String, Long> PROCESS_LOCKS = new HashMap<String, Long>();
    private static final Map<Long, String> PROCESS_LOCK_TOKENS = new HashMap<Long, String>();
    private static long NEXT_LOCK = 1L;
    /* Probing a DocumentsProvider is deliberately expensive. Every bridge
       still validates the persisted grant, but a given tree is probed once
       per process and then reused by later install/evaluate operations. */
    private static final Object PROBE_MONITOR = new Object();
    private static final Set<String> PROBED_TREES = new HashSet<String>();

    private long traceStart(String operation, String path) {
        long started = System.nanoTime();
        Log.i(TAG, "BEGIN " + operation + " path=" + String.valueOf(path)
                + " tree=" + tree + " thread=" + Thread.currentThread().getName());
        return started;
    }

    private void traceEnd(String operation, String path, long started, String result) {
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        Log.i(TAG, "END " + operation + " path=" + String.valueOf(path)
                + " result=" + String.valueOf(result) + " elapsedMs=" + elapsedMs);
    }

    public StorageBridge(Context context, String treeUri) {
        if (context == null) throw new NullPointerException("context");
        if (treeUri == null || !treeUri.startsWith("content://")) {
            throw new IllegalArgumentException("treeUri must be a content:// URI");
        }
        resolver = context.getApplicationContext().getContentResolver();
        tree = Uri.parse(treeUri);
        resolvedUris.put("", rootDocument());
        validatePersistedGrant();
        String probeKey = tree.toString();
        synchronized (PROBE_MONITOR) {
            if (!PROBED_TREES.contains(probeKey)) {
                int probeResult = probe();
                if (probeResult == PERMISSION) {
                    throw new SecurityException("SAF permission was revoked for " + tree);
                }
                if (probeResult != OK) {
                    throw new IllegalStateException(
                            "SAF provider cannot create, read, rename and delete files in " + tree
                                    + " (error " + probeResult + ")");
                }
                PROBED_TREES.add(probeKey);
            }
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
        long started = traceStart("findChild", name);
        String parentId = DocumentsContract.getDocumentId(parent);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        Map<String, Uri> index = childIndexes.get(parentId);
        if (index != null) {
            Uri found = index.get(name);
            traceEnd("findChild", name, started,
                    found == null ? "not-found cached" : found.toString() + " cached");
            return found;
        }
        Cursor cursor = null;
        Uri found = null;
        index = new HashMap<String, Uri>();
        try {
            Log.i(TAG, "QUERY children=" + children + " parentId=" + parentId);
            cursor = resolver.query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null);
            if (cursor == null) throw new IOException("SAF provider returned no cursor");
            int idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            while (cursor.moveToNext()) {
                String childName = cursor.getString(nameColumn);
                Uri child = DocumentsContract.buildDocumentUriUsingTree(
                        tree, cursor.getString(idColumn));
                index.put(childName, child);
                if (name.equals(childName)) found = child;
            }
            childIndexes.put(parentId, index);
            return found;
        } catch (SecurityException error) {
            throw new IOException("SAF permission revoked", error);
        } finally {
            if (cursor != null) cursor.close();
            traceEnd("findChild", name, started, found == null ? "not-found" : found.toString());
        }
    }

    private Uri resolve(String relative, boolean createDirectories) throws IOException {
        long started = traceStart("resolve", relative + " create=" + createDirectories);
        String[] path = parts(relative);
        StringBuilder resolvedPath = new StringBuilder();
        Uri current = resolvedUris.get("");
        if (current == null) {
            current = rootDocument();
            resolvedUris.put("", current);
        }
        try {
            for (String part : path) {
                if (resolvedPath.length() > 0) resolvedPath.append('/');
                resolvedPath.append(part);
                String key = resolvedPath.toString();
                Uri child = resolvedUris.get(key);
                if (child == null) child = findChild(current, part);
                if (child == null && createDirectories) {
                    try {
                        Log.i(TAG, "CREATE directory parent=" + current + " name=" + part);
                        child = DocumentsContract.createDocument(resolver, current,
                                DocumentsContract.Document.MIME_TYPE_DIR, part);
                        if (child != null) rememberChild(current, part, child);
                    } catch (SecurityException error) {
                        throw new IOException("SAF permission revoked", error);
                    }
                }
                if (child == null) return null;
                resolvedUris.put(key, child);
                current = child;
            }
            return current;
        } finally {
            traceEnd("resolve", relative + " create=" + createDirectories, started,
                    current == null ? "null" : current.toString());
        }
    }

    private void invalidate(String relative) {
        if (relative == null) return;
        String prefix = relative.length() == 0 ? "" : relative + "/";
        ArrayList<String> stale = new ArrayList<String>();
        for (String key : resolvedUris.keySet()) {
            if (key.equals(relative) || (prefix.length() > 0 && key.startsWith(prefix))) {
                stale.add(key);
            }
        }
        for (String key : stale) resolvedUris.remove(key);
        if (relative.length() > 0) {
            try {
                String[] path = parts(relative);
                StringBuilder parentPath = new StringBuilder();
                for (int i = 0; i < path.length - 1; i++) {
                    if (parentPath.length() > 0) parentPath.append('/');
                    parentPath.append(path[i]);
                }
                Uri parent = resolvedUris.get(parentPath.toString());
                if (parent != null) {
                    Map<String, Uri> index = childIndexes.get(
                            DocumentsContract.getDocumentId(parent));
                    if (index != null) index.remove(path[path.length - 1]);
                }
            } catch (Exception ignored) {
                // Cache invalidation must not turn a completed storage mutation
                // into an application-visible failure.
            }
        }
        if (!resolvedUris.containsKey("")) resolvedUris.put("", rootDocument());
    }

    private void rememberChild(Uri parent, String name, Uri child) {
        if (parent == null || name == null || child == null) return;
        String parentId = DocumentsContract.getDocumentId(parent);
        Map<String, Uri> index = childIndexes.get(parentId);
        if (index == null) {
            index = new HashMap<String, Uri>();
            childIndexes.put(parentId, index);
        }
        index.put(name, child);
    }

    private static IOException io(String operation, Exception error) {
        return new IOException(operation + ": " + error.getMessage(), error);
    }

    public int mkdirs(String relative) {
        long started = traceStart("mkdirs", relative);
        try {
            int result = resolve(relative, true) == null ? NOT_FOUND : OK;
            traceEnd("mkdirs", relative, started, Integer.toString(result));
            return result;
        } catch (SecurityException error) {
            traceEnd("mkdirs", relative, started, "PERMISSION");
            return PERMISSION;
        } catch (IllegalArgumentException error) {
            traceEnd("mkdirs", relative, started, "INVALID_ARGUMENT");
            return INVALID_ARGUMENT;
        } catch (IOException error) {
            int result = error.getMessage() != null && error.getMessage().contains("permission")
                    ? PERMISSION : IO;
            traceEnd("mkdirs", relative, started, result + ":" + error.getMessage());
            return result;
        }
    }

    public byte[] readFile(String relative) throws IOException {
        long started = traceStart("readFile", relative);
        Uri file = resolve(relative, false);
        if (file == null) throw new IOException("not found");
        ParcelFileDescriptor descriptor;
        try {
            Log.i(TAG, "OPEN read uri=" + file);
            descriptor = resolver.openFileDescriptor(file, "r");
            Log.i(TAG, "OPEN read complete uri=" + file + " descriptor=" + (descriptor != null));
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
            Log.i(TAG, "READ complete path=" + relative + " bytes=" + output.size());
        } finally {
            try { input.close(); } finally { descriptor.close(); }
        }
        traceEnd("readFile", relative, started, "OK bytes=" + output.size());
        return output.toByteArray();
    }

    public int writeFile(String relative, byte[] data, boolean truncate) {
        long started = traceStart("writeFile", relative + " bytes=" + (data == null ? -1 : data.length)
                + " truncate=" + truncate);
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
            Uri file = resolvedUris.get(relative);
            if (file == null) file = findChild(parent, path[path.length - 1]);
            if (file == null) {
                file = DocumentsContract.createDocument(resolver, parent,
                        "application/octet-stream", path[path.length - 1]);
                if (file != null) rememberChild(parent, path[path.length - 1], file);
            }
            if (file == null) return IO;
            resolvedUris.put(relative, file);
            /* Some DocumentsProviders (including vendor external-storage
               providers) reject "rwt" when the document already exists,
               although they accept it for newly-created documents. Always
               use the broadly supported read/write mode and implement
               truncation explicitly through FileChannel. This also keeps
               append writes deterministic when a file is written through
               several bridge calls. */
            String mode = "rw";
            Log.i(TAG, "OPEN write uri=" + file + " mode=" + mode);
            ParcelFileDescriptor descriptor = resolver.openFileDescriptor(file, mode);
            if (descriptor == null) return IO;
            FileOutputStream output = new FileOutputStream(descriptor.getFileDescriptor());
            try {
                FileChannel channel = output.getChannel();
                if (truncate) channel.truncate(0);
                else channel.position(channel.size());
                channel.position(truncate ? 0 : channel.size());
                output.write(data);
                output.flush();
            } finally {
                try { output.close(); } finally { descriptor.close(); }
            }
            Log.i(TAG, "WRITE complete path=" + relative + " bytes=" + data.length);
            traceEnd("writeFile", relative, started, "OK");
            return OK;
        } catch (SecurityException error) {
            traceEnd("writeFile", relative, started, "PERMISSION");
            return PERMISSION;
        } catch (IllegalArgumentException error) {
            Log.e(TAG, "WRITE invalid argument path=" + relative + " message="
                    + error.getMessage(), error);
            traceEnd("writeFile", relative, started, "INVALID_ARGUMENT");
            return INVALID_ARGUMENT;
        } catch (IOException error) {
            int result = error.getMessage() != null && error.getMessage().contains("permission")
                    ? PERMISSION : IO;
            traceEnd("writeFile", relative, started, result + ":" + error.getMessage());
            return result;
        }
    }

    public long[] stat(String relative) throws IOException {
        long started = traceStart("stat", relative);
        Uri file = resolve(relative, false);
        if (file == null) {
            traceEnd("stat", relative, started, "not-found");
            return new long[]{0L, 0L, 0L};
        }
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
            long[] result = new long[]{1L, directory ? 1L : 0L, size};
            traceEnd("stat", relative, started, "exists dir=" + directory + " size=" + size);
            return result;
        } catch (SecurityException error) {
            throw io("SAF permission revoked", error);
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    public Entry[] list(String relative) throws IOException {
        long started = traceStart("list", relative);
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
            Entry[] result = entries.toArray(new Entry[entries.size()]);
            traceEnd("list", relative, started, "OK entries=" + result.length);
            return result;
        } catch (SecurityException error) {
            throw io("SAF permission revoked", error);
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    public int remove(String relative, boolean recursive) {
        long started = traceStart("remove", relative + " recursive=" + recursive);
        try {
            requireNonRoot(relative);
            Uri target = resolve(relative, false);
            if (target == null) return OK;
            if (recursive) {
                // Most modern DocumentsProviders can remove a directory in a
                // single operation. This avoids one JNI/query/delete round
                // trip per file in node_modules. Fall back to the portable
                // recursive walk for providers that reject it.
                try {
                    if (DocumentsContract.deleteDocument(resolver, target)) {
                        invalidate(relative);
                        traceEnd("remove", relative, started, "OK direct");
                        return OK;
                    }
                } catch (SecurityException error) {
                    return PERMISSION;
                }
                Entry[] children = list(relative);
                for (Entry child : children) {
                    String childPath = relative.length() == 0 ? child.name : relative + "/" + child.name;
                    int result = remove(childPath, true);
                    if (result != OK) return result;
                }
            }
            int result = DocumentsContract.deleteDocument(resolver, target) ? OK : IO;
            if (result == OK) invalidate(relative);
            traceEnd("remove", relative, started, Integer.toString(result));
            return result;
        } catch (SecurityException error) {
            traceEnd("remove", relative, started, "PERMISSION");
            return PERMISSION;
        } catch (IllegalArgumentException error) {
            traceEnd("remove", relative, started, "INVALID_ARGUMENT");
            return INVALID_ARGUMENT;
        } catch (IOException error) {
            int result = error.getMessage() != null && error.getMessage().contains("permission")
                    ? PERMISSION : IO;
            traceEnd("remove", relative, started, result + ":" + error.getMessage());
            return result;
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
                if (renamed == null) return IO;
                invalidate(from);
                invalidate(to);
                rememberChild(parent, target[target.length - 1], renamed);
                resolvedUris.put(to, renamed);
                return OK;
            }
            int result = copy(from, to);
            if (result != OK) return result;
            result = remove(from, true);
            if (result == OK) invalidate(from);
            return result;
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
        long started = traceStart("copy", from + " -> " + to);
        try {
            requireNonRoot(to);
            Uri source = resolve(from, false);
            if (source == null) return NOT_FOUND;
            Uri targetParent = resolveParent(to);
            if (targetParent == null) return NOT_FOUND;
            String[] targetParts = parts(to);
            String targetName = targetParts[targetParts.length - 1];
            /* Android's DocumentsProvider can copy an entire directory inside
               the provider. This avoids a JNI/query/open/write call for every
               file in node_modules. Only use it when the target name is the
               same as the source name; otherwise fall back to the portable
               recursive implementation below. */
            String[] sourceParts = parts(from);
            String sourceName = sourceParts[sourceParts.length - 1];
            try {
                Uri copied = DocumentsContract.copyDocument(resolver, source, targetParent);
                if (copied != null) {
                    if (!targetName.equals(sourceName)) {
                        Uri renamed = DocumentsContract.renameDocument(resolver, copied, targetName);
                        if (renamed == null) {
                            DocumentsContract.deleteDocument(resolver, copied);
                            copied = null;
                        } else {
                            copied = renamed;
                        }
                    }
                    if (copied != null) {
                        rememberChild(targetParent, targetName, copied);
                        resolvedUris.put(to, copied);
                        traceEnd("copy", from + " -> " + to, started, "OK provider");
                        return OK;
                    }
                }
            } catch (UnsupportedOperationException ignored) {
                // Fall through to the portable recursive copy.
            } catch (IllegalArgumentException ignored) {
                // Some providers reject copyDocument for a directory.
            }
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
                traceEnd("copy", from + " -> " + to, started, "OK recursive");
                return OK;
            }
            int result = writeFile(to, readFile(from), true);
            if (result == OK) invalidate(to);
            traceEnd("copy", from + " -> " + to, started, Integer.toString(result));
            return result;
        } catch (SecurityException error) {
            traceEnd("copy", from + " -> " + to, started, "PERMISSION");
            return PERMISSION;
        } catch (IllegalArgumentException error) {
            traceEnd("copy", from + " -> " + to, started, "INVALID_ARGUMENT");
            return INVALID_ARGUMENT;
        } catch (IOException error) {
            return error.getMessage() != null && error.getMessage().contains("permission")
                    ? PERMISSION : IO;
        }
    }

    /**
     * Attempts a provider-native copy from an absolute shared-storage path.
     * This is intentionally conservative: only the primary external-storage
     * volume and paths below this selected tree are eligible. App-private and
     * arbitrary filesystem paths return UNSUPPORTED so native code can use its
     * portable fallback without weakening SAF path isolation.
     */
    public int copyFromFilePath(String absolutePath, String to) {
        long started = traceStart("copyFromFilePath", absolutePath + " -> " + to);
        try {
            if (absolutePath == null || !absolutePath.startsWith("/")) return UNSUPPORTED;
            File sourceFile = new File(absolutePath);
            String primaryRoot = Environment.getExternalStorageDirectory().getCanonicalPath();
            String source = sourceFile.getCanonicalPath();
            String treeId = DocumentsContract.getTreeDocumentId(tree);
            if (treeId == null || !treeId.startsWith("primary:")) return UNSUPPORTED;
            String treeRelative = treeId.substring("primary:".length());
            String treeRoot = primaryRoot + (treeRelative.length() == 0 ? "" : "/" + treeRelative);
            if (!isSameOrChild(source, treeRoot)) return UNSUPPORTED;
            String relativeSource = source.substring(treeRoot.length());
            while (relativeSource.startsWith("/")) relativeSource = relativeSource.substring(1);
            if (relativeSource.length() == 0) return UNSUPPORTED;
            String[] targetParts = parts(to);
            if (targetParts.length == 0) return INVALID_ARGUMENT;
            StringBuilder parentPath = new StringBuilder();
            for (int i = 0; i < targetParts.length - 1; i++) {
                if (parentPath.length() > 0) parentPath.append('/');
                parentPath.append(targetParts[i]);
            }
            Uri sourceUri = documentForRelativePath(relativeSource);
            Uri targetParent = resolve(parentPath.toString(), true);
            if (sourceUri == null || targetParent == null) return NOT_FOUND;
            String sourceName = sourceFile.getName();
            String targetName = targetParts[targetParts.length - 1];
            Uri copied;
            try {
                copied = DocumentsContract.copyDocument(resolver, sourceUri, targetParent);
            } catch (UnsupportedOperationException error) {
                return UNSUPPORTED;
            } catch (IllegalArgumentException error) {
                return UNSUPPORTED;
            }
            if (copied == null) return UNSUPPORTED;
            if (!targetName.equals(sourceName)) {
                Uri renamed = DocumentsContract.renameDocument(resolver, copied, targetName);
                if (renamed == null) {
                    try { DocumentsContract.deleteDocument(resolver, copied); }
                    catch (Exception ignored) { }
                    return IO;
                }
                copied = renamed;
            }
            rememberChild(targetParent, targetName, copied);
            resolvedUris.put(to, copied);
            traceEnd("copyFromFilePath", absolutePath + " -> " + to, started,
                    "OK provider source=" + sourceUri);
            return OK;
        } catch (SecurityException error) {
            traceEnd("copyFromFilePath", absolutePath + " -> " + to, started, "PERMISSION");
            return PERMISSION;
        } catch (IOException error) {
            traceEnd("copyFromFilePath", absolutePath + " -> " + to, started,
                    "UNSUPPORTED:" + error.getMessage());
            return UNSUPPORTED;
        } catch (IllegalArgumentException error) {
            traceEnd("copyFromFilePath", absolutePath + " -> " + to, started, "INVALID_ARGUMENT");
            return INVALID_ARGUMENT;
        }
    }

    private static boolean isSameOrChild(String path, String root) {
        return path.equals(root) || (path.startsWith(root) && path.length() > root.length()
                && path.charAt(root.length()) == '/');
    }

    private Uri documentForRelativePath(String relative) throws IOException {
        String treeId = DocumentsContract.getTreeDocumentId(tree);
        if (treeId == null || !treeId.startsWith("primary:")) return null;
        String documentId = treeId + "/" + relative;
        return DocumentsContract.buildDocumentUriUsingTree(tree, documentId);
    }

    private String lockKey(String relative) {
        return tree.toString() + "\n" + (relative == null ? "" : relative);
    }

    public long lock(String relative) throws InterruptedException {
        String key = lockKey(relative);
        long started = traceStart("lock", relative);
        synchronized (LOCK_MONITOR) {
            while (PROCESS_LOCKS.containsKey(key)) LOCK_MONITOR.wait();
            long token = NEXT_LOCK++;
            if (token == 0L) token = NEXT_LOCK++;
            PROCESS_LOCKS.put(key, token);
            PROCESS_LOCK_TOKENS.put(token, key);
            traceEnd("lock", relative, started, "OK token=" + token);
            return token;
        }
    }

    public void unlock(long token) {
        long started = traceStart("unlock", "token=" + token);
        synchronized (LOCK_MONITOR) {
            String key = PROCESS_LOCK_TOKENS.remove(token);
            if (key != null) {
                PROCESS_LOCKS.remove(key);
                LOCK_MONITOR.notifyAll();
            }
            traceEnd("unlock", key, started, key == null ? "not-found" : "OK");
        }
    }

    /**
     * Replaces a file or directory using only this SAF tree. Providers with a
     * real same-parent rename get that fast path; providers without rename are
     * handled by copy/delete with a same-tree backup and rollback. No POSIX or
     * app-private temporary directory is involved.
     */
    public int atomicReplace(String from, String to) {
        long started = traceStart("atomicReplace", from + " -> " + to);
        int outcome = IO;
        try {
            requireNonRoot(from);
            requireNonRoot(to);
            String backup = to + ".ant-backup-" + Long.toUnsignedString(System.nanoTime());
            boolean backedUp = false;
            try {
                Log.i(TAG, "ATOMIC_REPLACE from=" + from + " to=" + to
                        + " backup=" + backup);
                long[] targetInfo = stat(to);
                if (targetInfo[0] != 0L) {
                    Log.i(TAG, "ATOMIC_REPLACE backup target=" + to);
                    int backupResult = copy(to, backup);
                    if (backupResult != OK) {
                        outcome = backupResult;
                        return outcome;
                    }
                    backedUp = true;
                    Log.i(TAG, "ATOMIC_REPLACE remove target=" + to);
                    int removeResult = remove(to, true);
                    if (removeResult != OK) {
                        outcome = removeResult;
                        return outcome;
                    }
                }

                Log.i(TAG, "ATOMIC_REPLACE rename from=" + from + " to=" + to);
                int result = rename(from, to);
                if (result != OK) {
                    Log.i(TAG, "ATOMIC_REPLACE rename failed result=" + result
                            + ", falling back to copy/delete");
                    result = copy(from, to);
                    if (result == OK) result = remove(from, true);
                }
                if (result != OK && backedUp) {
                    Log.i(TAG, "ATOMIC_REPLACE rollback target=" + to);
                    remove(to, true);
                    int restore = rename(backup, to);
                    if (restore != OK) restore = copy(backup, to);
                    if (restore != OK) {
                        outcome = CONFLICT;
                        return outcome;
                    }
                }
                if (backedUp) remove(backup, true);
                outcome = result;
                return result;
            } finally {
                if (backedUp) remove(backup, true);
            }
        } catch (SecurityException error) {
            outcome = PERMISSION;
            return outcome;
        } catch (IllegalArgumentException error) {
            outcome = INVALID_ARGUMENT;
            return outcome;
        } catch (IOException error) {
            outcome = error.getMessage() != null && error.getMessage().contains("permission")
                    ? PERMISSION : IO;
            return outcome;
        } finally {
            traceEnd("atomicReplace", from + " -> " + to, started, Integer.toString(outcome));
        }
    }

    /** Probes the selected tree without modifying anything outside it. */
    public int probe() {
        String probeKey = tree.toString();
        synchronized (PROBE_MONITOR) {
            if (PROBED_TREES.contains(probeKey)) {
                Log.i(TAG, "probe cache hit tree=" + tree);
                return OK;
            }
        }
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
            if (result == OK) {
                synchronized (PROBE_MONITOR) {
                    PROBED_TREES.add(probeKey);
                }
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
