#include "storage.h"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>

#ifdef _WIN32
#include <direct.h>
#include <io.h>
#include <windows.h>
#else
#include <dirent.h>
#include <pthread.h>
#include <unistd.h>
#endif

struct ant_storage_context {
  ant_storage_location_t location;
  const ant_storage_bridge_t *bridge;
  char *location_owned;
  char *virtual_prefix;
  char *base_relative;
};

#ifndef _WIN32
typedef struct storage_file_lock {
  char *key;
  uint64_t token;
  struct storage_file_lock *next;
} storage_file_lock_t;

static pthread_mutex_t storage_file_locks_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t storage_file_locks_condition = PTHREAD_COND_INITIALIZER;
static storage_file_lock_t *storage_file_locks = NULL;
static uint64_t storage_next_lock_token = 1u;
#endif

static char *storage_join_file_path(
  const ant_storage_context_t *context,
  const char *relative
);

static char *storage_strdup(const char *value) {
  if (!value) return NULL;
  size_t len = strlen(value);
  char *copy = (char *)malloc(len + 1u);
  if (!copy) return NULL;
  memcpy(copy, value, len + 1u);
  return copy;
}

static bool storage_is_sep(char ch) {
  return ch == '/' || ch == '\\';
}

bool ant_storage_path_is_virtual(const char *path) {
  return path && strncmp(path, "ant-saf://", 10) == 0;
}

bool ant_storage_path_is_safe_relative(const char *path) {
  if (!path || !path[0]) return true;
  if (strncmp(path, "content://", 10) == 0) return false;
  if (path[0] == '/' || path[0] == '\\') return false;
  const char *p = path;
  while (*p) {
    while (*p && storage_is_sep(*p)) p++;
    const char *start = p;
    while (*p && !storage_is_sep(*p)) {
      if ((unsigned char)*p < 0x20 || *p == '\0') return false;
      p++;
    }
    size_t len = (size_t)(p - start);
    if (len == 2 && start[0] == '.' && start[1] == '.') return false;
    if (len == 1 && start[0] == '.') continue;
  }
  return true;
}

static char *storage_normalize_relative(const char *path) {
  if (!path) return NULL;
  if (!ant_storage_path_is_safe_relative(path)) return NULL;

  size_t len = strlen(path);
  char *out = (char *)malloc(len + 1u);
  if (!out) return NULL;
  size_t used = 0;
  const char *p = path;
  while (*p) {
    while (*p && storage_is_sep(*p)) p++;
    const char *start = p;
    while (*p && !storage_is_sep(*p)) p++;
    size_t component_len = (size_t)(p - start);
    if (component_len == 0 || (component_len == 1 && start[0] == '.')) continue;
    if (used) out[used++] = '/';
    memcpy(out + used, start, component_len);
    used += component_len;
  }
  out[used] = '\0';
  return out;
}

/* Contexts may be rooted below the location selected by the caller (for
 * example a cache context below a project tree).  Keep that prefix in the
 * backend-relative path so every backend observes the same view. */
static char *storage_with_base(
  const ant_storage_context_t *context,
  const char *relative
) {
  if (!context || !relative) return NULL;
  const char *base = context->base_relative ? context->base_relative : "";
  if (!base[0]) return storage_strdup(relative);
  if (!relative[0]) return storage_strdup(base);
  size_t base_len = strlen(base);
  size_t relative_len = strlen(relative);
  char *out = (char *)malloc(base_len + relative_len + 2u);
  if (!out) return NULL;
  memcpy(out, base, base_len);
  out[base_len] = '/';
  memcpy(out + base_len + 1u, relative, relative_len + 1u);
  return out;
}

static bool storage_is_inside_base(
  const ant_storage_context_t *context,
  const char *relative
) {
  const char *base = context && context->base_relative
    ? context->base_relative : "";
  if (!base[0]) return true;
  size_t base_len = strlen(base);
  return strcmp(relative, base) == 0 ||
    (strncmp(relative, base, base_len) == 0 && relative[base_len] == '/');
}

static char *storage_virtual_prefix(const ant_storage_context_t *context) {
  if (!context || context->location.kind != ANT_STORAGE_SAF_TREE) return NULL;
  /* The pointer is only an in-process namespace token.  The URI itself is
   * retained by the context and is never interpreted as a POSIX path. */
  char token[64];
  int n = snprintf(token, sizeof(token), "ant-saf://%llx/",
                   (unsigned long long)(uintptr_t)context);
  if (n < 0 || (size_t)n >= sizeof(token)) return NULL;
  return storage_strdup(token);
}

ant_storage_context_t *ant_storage_context_create(
  ant_storage_location_t location,
  const ant_storage_bridge_t *bridge
) {
  if (!location.location || !location.location[0]) return NULL;
  if (location.kind == ANT_STORAGE_SAF_TREE && !bridge) return NULL;
  if (location.kind != ANT_STORAGE_FILE_PATH && location.kind != ANT_STORAGE_SAF_TREE) return NULL;

  ant_storage_context_t *context = (ant_storage_context_t *)calloc(1, sizeof(*context));
  if (!context) return NULL;
  context->location_owned = storage_strdup(location.location);
  if (!context->location_owned) {
    free(context);
    return NULL;
  }
  context->location.kind = location.kind;
  context->location.location = context->location_owned;
  context->bridge = bridge;
  context->base_relative = storage_strdup("");
  if (!context->base_relative) {
    free(context->virtual_prefix);
    free(context->location_owned);
    free(context);
    return NULL;
  }
  if (location.kind == ANT_STORAGE_SAF_TREE) {
    context->virtual_prefix = storage_virtual_prefix(context);
    if (!context->virtual_prefix) {
      free(context->base_relative);
      free(context->location_owned);
      free(context);
      return NULL;
    }
  }
  return context;
}

ant_storage_context_t *ant_storage_context_create_child(
  const ant_storage_context_t *parent,
  const char *relative_path
) {
  if (!parent || !relative_path) return NULL;
  char *relative = storage_normalize_relative(relative_path);
  if (!relative) return NULL;

  ant_storage_location_t location = parent->location;
  ant_storage_context_t *child = ant_storage_context_create(location, parent->bridge);
  if (!child) {
    free(relative);
    return NULL;
  }
  free(child->base_relative);
  if (!relative[0]) {
    child->base_relative = storage_strdup(
      parent->base_relative ? parent->base_relative : ""
    );
    if (!child->base_relative) {
      ant_storage_context_destroy(child);
      free(relative);
      return NULL;
    }
  } else if (parent->base_relative && parent->base_relative[0]) {
    child->base_relative = (char *)malloc(
      strlen(parent->base_relative) + strlen(relative) + 2u
    );
    if (!child->base_relative) {
      ant_storage_context_destroy(child);
      free(relative);
      return NULL;
    }
    snprintf(child->base_relative,
      strlen(parent->base_relative) + strlen(relative) + 2u,
      "%s/%s", parent->base_relative, relative);
  } else {
    child->base_relative = storage_strdup(relative);
    if (!child->base_relative) {
      ant_storage_context_destroy(child);
      free(relative);
      return NULL;
    }
  }
  free(relative);
  return child;
}

void ant_storage_context_destroy(ant_storage_context_t *context) {
  if (!context) return;
  free(context->virtual_prefix);
  free(context->base_relative);
  free(context->location_owned);
  free(context);
}

ant_storage_kind_t ant_storage_context_kind(const ant_storage_context_t *context) {
  return context ? context->location.kind : ANT_STORAGE_FILE_PATH;
}

const char *ant_storage_context_location(const ant_storage_context_t *context) {
  return context ? context->location.location : NULL;
}

static const char *storage_virtual_relative(
  const ant_storage_context_t *context,
  const char *path
) {
  if (!context || !path || !context->virtual_prefix) return NULL;
  size_t prefix_len = strlen(context->virtual_prefix);
  if (strncmp(path, context->virtual_prefix, prefix_len) != 0) return NULL;
  return path + prefix_len;
}

char *ant_storage_virtual_path(
  const ant_storage_context_t *context,
  const char *relative_path
) {
  if (!context) return NULL;
  if (!relative_path) relative_path = "";
  if (context->location.kind == ANT_STORAGE_SAF_TREE) {
    const char *already_relative = storage_virtual_relative(context, relative_path);
    if (already_relative) {
      char *normalized = storage_normalize_relative(already_relative);
      if (!normalized) return NULL;
      if (!storage_is_inside_base(context, normalized)) {
        free(normalized);
        return NULL;
      }
      size_t prefix_len = strlen(context->virtual_prefix);
      size_t rel_len = strlen(normalized);
      char *out = (char *)malloc(prefix_len + rel_len + 1u);
      if (!out) {
        free(normalized);
        return NULL;
      }
      memcpy(out, context->virtual_prefix, prefix_len);
      memcpy(out + prefix_len, normalized, rel_len + 1u);
      free(normalized);
      return out;
    }
    if (strncmp(relative_path, "content://", 10) == 0 ||
        relative_path[0] == '/' || relative_path[0] == '\\') return NULL;
  } else if (ant_storage_path_is_virtual(relative_path) ||
             strncmp(relative_path, "content://", 10) == 0) {
    return NULL;
  }
  if (context->location.kind == ANT_STORAGE_FILE_PATH) {
    char *relative = NULL;
    if (relative_path[0] == '/') {
      relative = ant_storage_relative_path(context, relative_path);
    } else {
      relative = storage_normalize_relative(relative_path);
    }
    if (!relative) return NULL;
    char *result = storage_with_base(context, relative);
    free(relative);
    if (!result) return NULL;
    char *absolute = storage_join_file_path(context, result);
    free(result);
    return absolute;
  }
  char *relative = storage_normalize_relative(relative_path);
  if (!relative) return NULL;
  char *scoped = storage_with_base(context, relative);
  free(relative);
  if (!scoped) return NULL;
  size_t prefix_len = strlen(context->virtual_prefix);
  size_t rel_len = strlen(scoped);
  char *out = (char *)malloc(prefix_len + rel_len + 1u);
  if (!out) {
    free(scoped);
    return NULL;
  }
  memcpy(out, context->virtual_prefix, prefix_len);
  memcpy(out + prefix_len, scoped, rel_len + 1u);
  free(scoped);
  return out;
}

static char *storage_join_file_path(
  const ant_storage_context_t *context,
  const char *relative
) {
  if (!context || !relative) return NULL;
  size_t root_len = strlen(context->location.location);
  size_t rel_len = strlen(relative);
  bool slash = root_len > 0 && context->location.location[root_len - 1] != '/';
  char *out = (char *)malloc(root_len + rel_len + (slash ? 2u : 1u));
  if (!out) return NULL;
  memcpy(out, context->location.location, root_len);
  size_t off = root_len;
  if (slash) out[off++] = '/';
  memcpy(out + off, relative, rel_len + 1u);
  return out;
}

char *ant_storage_relative_path(
  const ant_storage_context_t *context,
  const char *path
) {
  if (!context || !path) return NULL;
  /* URI identities are only valid when supplied through a StorageLocation;
   * they must never be normalized into a relative POSIX filename. */
  if (strncmp(path, "content://", 10) == 0) return NULL;
  if (context->location.kind == ANT_STORAGE_FILE_PATH &&
      ant_storage_path_is_virtual(path)) return NULL;
  if (context->location.kind == ANT_STORAGE_SAF_TREE) {
    const char *relative = storage_virtual_relative(context, path);
    bool is_virtual = relative != NULL;
    /* A virtual path is an opaque identity owned by one Storage context.
     * Never reinterpret another context's ant-saf:// token as a relative
     * filename: doing so would allow cross-project access and would also
     * violate the rule that content:// locations are never POSIX paths. */
    if (!relative && ant_storage_path_is_virtual(path)) return NULL;
    if (!relative) relative = path;
    char *normalized = storage_normalize_relative(relative);
    if (!normalized) return NULL;
    if (is_virtual) {
      if (!storage_is_inside_base(context, normalized)) {
        free(normalized);
        return NULL;
      }
      return normalized;
    }
    char *result = storage_with_base(context, normalized);
    free(normalized);
    return result;
  }

  const char *root = context->location.location;
  size_t root_len = strlen(root);
  if (path[0] == '/') {
    if (strncmp(path, root, root_len) != 0 ||
        (path[root_len] != '\0' && path[root_len] != '/')) return NULL;
    /* `storage_normalize_relative()` accepts tree-relative paths only.  An
     * absolute FILE_PATH path has a separator at the root boundary, so strip
     * that separator before normalizing.  Keeping this conversion here also
     * ensures the resulting path remains constrained to the selected root. */
    const char *relative_start = path + root_len;
    while (*relative_start == '/') relative_start++;
    char *normalized = storage_normalize_relative(relative_start);
    if (!normalized) return NULL;
    if (!storage_is_inside_base(context, normalized)) {
      free(normalized);
      return NULL;
    }
    return normalized;
  }
  char *normalized = storage_normalize_relative(path);
  if (!normalized) return NULL;
  char *result = storage_with_base(context, normalized);
  free(normalized);
  return result;
}

char *ant_storage_file_path(
  const ant_storage_context_t *context,
  const char *path
) {
  if (!context || context->location.kind != ANT_STORAGE_FILE_PATH || !path)
    return NULL;
  char *relative = ant_storage_relative_path(context, path);
  if (!relative) return NULL;
  char *absolute = storage_join_file_path(context, relative);
  free(relative);
  return absolute;
}

static int storage_open_flags(bool truncate) {
#ifdef _WIN32
  return _O_BINARY | _O_CREAT | _O_WRONLY |
    (truncate ? _O_TRUNC : _O_APPEND);
#else
  return O_CREAT | O_WRONLY | (truncate ? O_TRUNC : O_APPEND);
#endif
}

static ant_storage_error_t storage_file_mkdirs(const char *path) {
  if (!path || !path[0]) return ANT_STORAGE_OK;
  char *copy = storage_strdup(path);
  if (!copy) return ANT_STORAGE_IO;
  size_t len = strlen(copy);
  while (len > 1 && copy[len - 1] == '/') copy[--len] = '\0';
  for (char *p = copy + 1; *p; p++) {
    if (*p != '/') continue;
    *p = '\0';
#ifdef _WIN32
    if (_mkdir(copy) != 0 && errno != EEXIST) { free(copy); return ANT_STORAGE_IO; }
#else
    if (mkdir(copy, 0700) != 0 && errno != EEXIST) { free(copy); return ANT_STORAGE_IO; }
#endif
    *p = '/';
  }
#ifdef _WIN32
  if (_mkdir(copy) != 0 && errno != EEXIST) { free(copy); return ANT_STORAGE_IO; }
#else
  if (mkdir(copy, 0700) != 0 && errno != EEXIST) { free(copy); return ANT_STORAGE_IO; }
#endif
  free(copy);
  return ANT_STORAGE_OK;
}

static ant_storage_error_t storage_file_read(
  const char *path, uint8_t **data, size_t *size
) {
  if (!path || !data || !size) return ANT_STORAGE_INVALID_ARGUMENT;
  *data = NULL; *size = 0;
  FILE *fp = fopen(path, "rb");
  if (!fp) return errno == ENOENT ? ANT_STORAGE_NOT_FOUND : ANT_STORAGE_IO;
  if (fseek(fp, 0, SEEK_END) != 0) { fclose(fp); return ANT_STORAGE_IO; }
  long end = ftell(fp);
  if (end < 0) { fclose(fp); return ANT_STORAGE_IO; }
  if (fseek(fp, 0, SEEK_SET) != 0) { fclose(fp); return ANT_STORAGE_IO; }
  size_t length = (size_t)end;
  uint8_t *buffer = (uint8_t *)malloc(length + 1u);
  if (!buffer) { fclose(fp); return ANT_STORAGE_IO; }
  size_t got = fread(buffer, 1, length, fp);
  int close_rc = fclose(fp);
  if (got != length || close_rc != 0) { free(buffer); return ANT_STORAGE_IO; }
  buffer[length] = 0;
  *data = buffer; *size = length;
  return ANT_STORAGE_OK;
}

static ant_storage_error_t storage_file_write(
  const char *path, const uint8_t *data, size_t size, bool truncate
) {
  if (!path || (!data && size != 0)) return ANT_STORAGE_INVALID_ARGUMENT;

  /* Every backend has mkdirs semantics.  Package extraction writes deeply
   * nested files and must not rely on callers to pre-create each parent. */
  char *parent = storage_strdup(path);
  if (!parent) return ANT_STORAGE_IO;
  char *slash = strrchr(parent, '/');
#ifdef _WIN32
  char *backslash = strrchr(parent, '\\');
  if (!slash || (backslash && backslash > slash)) slash = backslash;
#endif
  if (slash) {
    if (slash == parent) {
      slash[1] = '\0';
    } else {
      *slash = '\0';
      if (storage_file_mkdirs(parent) != ANT_STORAGE_OK) {
        free(parent);
        return ANT_STORAGE_IO;
      }
    }
  }
  free(parent);
  int fd;
#ifdef _WIN32
  fd = _open(path, storage_open_flags(truncate), _S_IREAD | _S_IWRITE);
#else
  fd = open(path, storage_open_flags(truncate), 0600);
#endif
  if (fd < 0) return errno == EACCES ? ANT_STORAGE_PERMISSION : ANT_STORAGE_IO;
  size_t off = 0;
  while (off < size) {
#ifdef _WIN32
    int n = _write(fd, data + off, (unsigned int)(size - off));
#else
    ssize_t n = write(fd, data + off, size - off);
#endif
    if (n <= 0) {
#ifdef _WIN32
      _close(fd);
#else
      close(fd);
#endif
      return ANT_STORAGE_IO;
    }
    off += (size_t)n;
  }
#ifdef _WIN32
  if (_close(fd) != 0) return ANT_STORAGE_IO;
#else
  if (close(fd) != 0) return ANT_STORAGE_IO;
#endif
  return ANT_STORAGE_OK;
}

static ant_storage_error_t storage_file_stat(
  const char *path, uint64_t *size, bool *is_directory, bool *exists
) {
  if (!path || !size || !is_directory || !exists) return ANT_STORAGE_INVALID_ARGUMENT;
  struct stat st;
  if (stat(path, &st) != 0) {
    *exists = false; *size = 0; *is_directory = false;
    return errno == ENOENT ? ANT_STORAGE_OK : ANT_STORAGE_IO;
  }
  *exists = true;
  *size = (uint64_t)st.st_size;
  *is_directory = S_ISDIR(st.st_mode);
  return ANT_STORAGE_OK;
}

static ant_storage_error_t storage_file_remove(const char *path, bool recursive) {
  if (!path) return ANT_STORAGE_INVALID_ARGUMENT;
  struct stat st;
  if (stat(path, &st) != 0) return errno == ENOENT ? ANT_STORAGE_OK : ANT_STORAGE_IO;
  if (!S_ISDIR(st.st_mode) || !recursive) {
    int rc = S_ISDIR(st.st_mode) ? rmdir(path) : remove(path);
    return rc == 0 ? ANT_STORAGE_OK : ANT_STORAGE_IO;
  }
#ifdef _WIN32
  return ANT_STORAGE_UNSUPPORTED;
#else
  DIR *dir = opendir(path);
  if (!dir) return ANT_STORAGE_IO;
  struct dirent *entry;
  while ((entry = readdir(dir)) != NULL) {
    if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) continue;
    size_t plen = strlen(path), nlen = strlen(entry->d_name);
    char *child = (char *)malloc(plen + nlen + 2u);
    if (!child) { closedir(dir); return ANT_STORAGE_IO; }
    snprintf(child, plen + nlen + 2u, "%s/%s", path, entry->d_name);
    ant_storage_error_t result = storage_file_remove(child, true);
    free(child);
    if (result != ANT_STORAGE_OK) { closedir(dir); return result; }
  }
  closedir(dir);
  return rmdir(path) == 0 ? ANT_STORAGE_OK : ANT_STORAGE_IO;
#endif
}

static ant_storage_error_t storage_file_copy(const char *from, const char *to) {
  uint8_t *data = NULL; size_t size = 0;
  ant_storage_error_t result = storage_file_read(from, &data, &size);
  if (result != ANT_STORAGE_OK) return result;
  result = storage_file_write(to, data, size, true);
  free(data);
  return result;
}

typedef struct {
  char *name;
  bool is_directory;
} storage_copy_entry_t;

typedef struct {
  storage_copy_entry_t *entries;
  size_t length;
  size_t capacity;
  bool failed;
} storage_copy_list_t;

static bool storage_collect_copy_entry(
  const char *name, bool is_directory, void *visitor_data
) {
  storage_copy_list_t *list = (storage_copy_list_t *)visitor_data;
  if (!list || !name) return false;
  if (list->length == list->capacity) {
    size_t capacity = list->capacity ? list->capacity * 2u : 16u;
    storage_copy_entry_t *entries = (storage_copy_entry_t *)realloc(
      list->entries, capacity * sizeof(*entries)
    );
    if (!entries) {
      list->failed = true;
      return false;
    }
    list->entries = entries;
    list->capacity = capacity;
  }
  char *copy = storage_strdup(name);
  if (!copy) {
    list->failed = true;
    return false;
  }
  list->entries[list->length++] = (storage_copy_entry_t){
    .name = copy,
    .is_directory = is_directory,
  };
  return true;
}

static void storage_copy_list_clear(storage_copy_list_t *list) {
  if (!list) return;
  for (size_t i = 0; i < list->length; i++) free(list->entries[i].name);
  free(list->entries);
  memset(list, 0, sizeof(*list));
}

static char *storage_join_relative(const char *base, const char *name) {
  if (!base || !name) return NULL;
  if (!base[0]) return storage_strdup(name);
  size_t base_len = strlen(base);
  size_t name_len = strlen(name);
  char *path = (char *)malloc(base_len + name_len + 2u);
  if (!path) return NULL;
  memcpy(path, base, base_len);
  path[base_len] = '/';
  memcpy(path + base_len + 1u, name, name_len + 1u);
  return path;
}

static ant_storage_error_t storage_prepare(
  ant_storage_context_t *context,
  const char *path,
  char **relative_out,
  char **file_path_out
) {
  if (!context || !path) return ANT_STORAGE_INVALID_ARGUMENT;
  if (relative_out) *relative_out = NULL;
  if (file_path_out) *file_path_out = NULL;
  char *relative = ant_storage_relative_path(context, path);
  if (!relative) return ANT_STORAGE_INVALID_ARGUMENT;
  if (relative_out) *relative_out = relative;
  else free(relative);
  if (context->location.kind == ANT_STORAGE_FILE_PATH && file_path_out) {
    *file_path_out = storage_join_file_path(context, relative);
    if (!*file_path_out) {
      if (relative_out) { free(*relative_out); *relative_out = NULL; }
      return ANT_STORAGE_IO;
    }
  }
  return ANT_STORAGE_OK;
}

ant_storage_error_t ant_storage_mkdirs(ant_storage_context_t *context, const char *path) {
  char *relative = NULL, *file = NULL;
  ant_storage_error_t result = storage_prepare(context, path ? path : "", &relative, &file);
  if (result != ANT_STORAGE_OK) return result;
  if (context->location.kind == ANT_STORAGE_SAF_TREE) {
    if (!context->bridge->mkdirs) result = ANT_STORAGE_UNSUPPORTED;
    else
    result = context->bridge->mkdirs(context->bridge->user_data, relative);
  } else result = storage_file_mkdirs(file);
  free(relative); free(file);
  return result;
}

ant_storage_error_t ant_storage_read_file(
  ant_storage_context_t *context, const char *path, uint8_t **data, size_t *size
) {
  char *relative = NULL, *file = NULL;
  ant_storage_error_t result = storage_prepare(context, path, &relative, &file);
  if (result != ANT_STORAGE_OK) return result;
  if (context->location.kind == ANT_STORAGE_SAF_TREE)
    result = context->bridge->read_file
      ? context->bridge->read_file(context->bridge->user_data, relative, data, size)
      : ANT_STORAGE_UNSUPPORTED;
  else result = storage_file_read(file, data, size);
  free(relative); free(file);
  return result;
}

ant_storage_error_t ant_storage_write_file(
  ant_storage_context_t *context, const char *path, const uint8_t *data,
  size_t size, bool truncate
) {
  char *relative = NULL, *file = NULL;
  ant_storage_error_t result = storage_prepare(context, path, &relative, &file);
  if (result != ANT_STORAGE_OK) return result;
  if (context->location.kind == ANT_STORAGE_SAF_TREE)
    result = context->bridge->write_file
      ? context->bridge->write_file(context->bridge->user_data, relative, data, size, truncate)
      : ANT_STORAGE_UNSUPPORTED;
  else result = storage_file_write(file, data, size, truncate);
  free(relative); free(file);
  return result;
}

ant_storage_error_t ant_storage_stat(
  ant_storage_context_t *context, const char *path, uint64_t *size,
  bool *is_directory, bool *exists
) {
  char *relative = NULL, *file = NULL;
  ant_storage_error_t result = storage_prepare(context, path, &relative, &file);
  if (result != ANT_STORAGE_OK) return result;
  if (context->location.kind == ANT_STORAGE_SAF_TREE)
    result = context->bridge->stat
      ? context->bridge->stat(context->bridge->user_data, relative, size, is_directory, exists)
      : ANT_STORAGE_UNSUPPORTED;
  else result = storage_file_stat(file, size, is_directory, exists);
  free(relative); free(file);
  return result;
}

ant_storage_error_t ant_storage_list(
  ant_storage_context_t *context, const char *path,
  ant_storage_list_visitor visitor, void *visitor_data
) {
  if (!visitor) return ANT_STORAGE_INVALID_ARGUMENT;
  char *relative = NULL, *file = NULL;
  ant_storage_error_t result = storage_prepare(context, path ? path : "", &relative, &file);
  if (result != ANT_STORAGE_OK) return result;
  if (context->location.kind == ANT_STORAGE_SAF_TREE) {
    result = context->bridge->list
      ? context->bridge->list(context->bridge->user_data, relative, visitor, visitor_data)
      : ANT_STORAGE_UNSUPPORTED;
  } else {
#ifdef _WIN32
    result = ANT_STORAGE_UNSUPPORTED;
#else
    DIR *dir = opendir(file);
    if (!dir) result = errno == ENOENT ? ANT_STORAGE_NOT_FOUND : ANT_STORAGE_IO;
    else {
      struct dirent *entry;
      result = ANT_STORAGE_OK;
      while ((entry = readdir(dir)) != NULL) {
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) continue;
        size_t plen = strlen(file), nlen = strlen(entry->d_name);
        char *child = (char *)malloc(plen + nlen + 2u);
        if (!child) { result = ANT_STORAGE_IO; break; }
        snprintf(child, plen + nlen + 2u, "%s/%s", file, entry->d_name);
        struct stat st;
        bool is_dir = stat(child, &st) == 0 && S_ISDIR(st.st_mode);
        free(child);
        if (!visitor(entry->d_name, is_dir, visitor_data)) break;
      }
      closedir(dir);
    }
#endif
  }
  free(relative); free(file);
  return result;
}

ant_storage_error_t ant_storage_remove(ant_storage_context_t *context, const char *path, bool recursive) {
  char *relative = NULL, *file = NULL;
  ant_storage_error_t result = storage_prepare(context, path, &relative, &file);
  if (result != ANT_STORAGE_OK) return result;
  if (context->location.kind == ANT_STORAGE_SAF_TREE)
    result = context->bridge->remove
      ? context->bridge->remove(context->bridge->user_data, relative, recursive)
      : ANT_STORAGE_UNSUPPORTED;
  else result = storage_file_remove(file, recursive);
  free(relative); free(file);
  return result;
}

ant_storage_error_t ant_storage_rename(
  ant_storage_context_t *context, const char *from, const char *to
) {
  char *from_rel = NULL, *from_file = NULL, *to_rel = NULL, *to_file = NULL;
  ant_storage_error_t result = storage_prepare(context, from, &from_rel, &from_file);
  if (result != ANT_STORAGE_OK) return result;
  result = storage_prepare(context, to, &to_rel, &to_file);
  if (result != ANT_STORAGE_OK) { free(from_rel); free(from_file); return result; }
  if (context->location.kind == ANT_STORAGE_SAF_TREE)
    result = context->bridge->rename
      ? context->bridge->rename(context->bridge->user_data, from_rel, to_rel)
      : ANT_STORAGE_UNSUPPORTED;
  else result = rename(from_file, to_file) == 0 ? ANT_STORAGE_OK : ANT_STORAGE_IO;
  free(from_rel); free(from_file); free(to_rel); free(to_file);
  return result;
}

ant_storage_error_t ant_storage_copy(
  ant_storage_context_t *context, const char *from, const char *to
) {
  char *from_rel = NULL, *from_file = NULL, *to_rel = NULL, *to_file = NULL;
  ant_storage_error_t result = storage_prepare(context, from, &from_rel, &from_file);
  if (result != ANT_STORAGE_OK) return result;
  result = storage_prepare(context, to, &to_rel, &to_file);
  if (result != ANT_STORAGE_OK) { free(from_rel); free(from_file); return result; }
  if (context->location.kind == ANT_STORAGE_SAF_TREE)
    result = context->bridge->copy
      ? context->bridge->copy(context->bridge->user_data, from_rel, to_rel)
      : ANT_STORAGE_UNSUPPORTED;
  else result = storage_file_copy(from_file, to_file);
  free(from_rel); free(from_file); free(to_rel); free(to_file);
  return result;
}

ant_storage_error_t ant_storage_copy_between(
  ant_storage_context_t *from_context,
  const char *from,
  ant_storage_context_t *to_context,
  const char *to
) {
  if (!from_context || !to_context || !from || !to)
    return ANT_STORAGE_INVALID_ARGUMENT;
  if (from_context == to_context)
    return ant_storage_copy(from_context, from, to);

  /* SAF contexts rooted at the same URI may have different bridge objects
   * (for example, when the user explicitly selects the same tree as both
   * project and cache). They still address the same DocumentsProvider tree,
   * so keep the operation inside that provider instead of recursively
   * reading and rewriting every file through JNI. The Android bridge can use
   * DocumentsContract.copyDocument() for this same-tree fast path. */
  if (from_context->location.kind == ANT_STORAGE_SAF_TREE &&
      to_context->location.kind == ANT_STORAGE_SAF_TREE &&
      from_context->bridge && to_context->bridge &&
      strcmp(from_context->location.location, to_context->location.location) == 0) {
    char *from_rel = NULL, *from_file = NULL, *to_rel = NULL, *to_file = NULL;
    ant_storage_error_t same_tree = storage_prepare(from_context, from, &from_rel, &from_file);
    if (same_tree == ANT_STORAGE_OK)
      same_tree = storage_prepare(to_context, to, &to_rel, &to_file);
    if (same_tree == ANT_STORAGE_OK) {
      same_tree = from_context->bridge->copy
        ? from_context->bridge->copy(from_context->bridge->user_data, from_rel, to_rel)
        : ANT_STORAGE_UNSUPPORTED;
    }
    free(from_rel); free(from_file); free(to_rel); free(to_file);
    if (same_tree != ANT_STORAGE_UNSUPPORTED) return same_tree;
  }

  /* A separately configured absolute cache often points into the same shared
   * external-storage tree as the SAF project. Let the destination bridge map
   * that source to a DocumentsProvider URI and copy the complete directory in
   * one provider operation. If the mapping is not safe or unsupported, keep
   * the portable read/write fallback below. */
  if (from_context->location.kind == ANT_STORAGE_FILE_PATH &&
      to_context->location.kind == ANT_STORAGE_SAF_TREE &&
      to_context->bridge && to_context->bridge->copy_from_file_path) {
    char *from_rel = NULL, *from_file = NULL, *to_rel = NULL, *to_file = NULL;
    ant_storage_error_t native_copy = storage_prepare(
      from_context, from, &from_rel, &from_file
    );
    if (native_copy == ANT_STORAGE_OK)
      native_copy = storage_prepare(to_context, to, &to_rel, &to_file);
    if (native_copy == ANT_STORAGE_OK) {
      native_copy = to_context->bridge->copy_from_file_path(
        to_context->bridge->user_data, from_file, to_rel
      );
    }
    free(from_rel); free(from_file); free(to_rel); free(to_file);
    if (native_copy != ANT_STORAGE_UNSUPPORTED) return native_copy;
  }

  uint64_t size = 0;
  bool is_directory = false;
  bool exists = false;
  ant_storage_error_t result = ant_storage_stat(
    from_context, from, &size, &is_directory, &exists
  );
  if (result != ANT_STORAGE_OK) return result;
  if (!exists) return ANT_STORAGE_NOT_FOUND;

  if (!is_directory) {
    uint8_t *data = NULL;
    size_t data_size = 0;
    result = ant_storage_read_file(from_context, from, &data, &data_size);
    if (result != ANT_STORAGE_OK) return result;
    result = ant_storage_write_file(to_context, to, data, data_size, true);
    ant_storage_free_data(from_context, data);
    return result;
  }

  result = ant_storage_mkdirs(to_context, to);
  if (result != ANT_STORAGE_OK) return result;

  storage_copy_list_t list = {0};
  result = ant_storage_list(
    from_context, from, storage_collect_copy_entry, &list
  );
  if (result != ANT_STORAGE_OK || list.failed) {
    storage_copy_list_clear(&list);
    return result != ANT_STORAGE_OK ? result : ANT_STORAGE_IO;
  }

  for (size_t i = 0; i < list.length; i++) {
    char *source = storage_join_relative(from, list.entries[i].name);
    char *destination = storage_join_relative(to, list.entries[i].name);
    if (!source || !destination) {
      free(source);
      free(destination);
      result = ANT_STORAGE_IO;
      break;
    }
    result = ant_storage_copy_between(
      from_context, source, to_context, destination
    );
    free(source);
    free(destination);
    if (result != ANT_STORAGE_OK) break;
  }
  storage_copy_list_clear(&list);
  return result;
}

ant_storage_error_t ant_storage_atomic_replace(
  ant_storage_context_t *context, const char *from, const char *to
) {
  char *from_rel = NULL, *from_file = NULL, *to_rel = NULL, *to_file = NULL;
  ant_storage_error_t result = storage_prepare(context, from, &from_rel, &from_file);
  if (result != ANT_STORAGE_OK) return result;
  result = storage_prepare(context, to, &to_rel, &to_file);
  if (result != ANT_STORAGE_OK) { free(from_rel); free(from_file); return result; }

  if (context->location.kind == ANT_STORAGE_SAF_TREE) {
    if (context->bridge->atomic_replace) {
      result = context->bridge->atomic_replace(
        context->bridge->user_data, from_rel, to_rel
      );
    } else if (context->bridge->rename) {
      /* Older embedders may not expose the stronger callback. Their rename
       * implementation remains authoritative; Ant never interprets the URI
       * or redirects the update to a POSIX staging directory. */
      result = context->bridge->rename(
        context->bridge->user_data, from_rel, to_rel
      );
    } else {
      result = ANT_STORAGE_UNSUPPORTED;
    }
  } else {
    result = rename(from_file, to_file) == 0 ? ANT_STORAGE_OK :
      (errno == EACCES ? ANT_STORAGE_PERMISSION : ANT_STORAGE_IO);
  }

  free(from_rel); free(from_file); free(to_rel); free(to_file);
  return result;
}

ant_storage_error_t ant_storage_lock(
  ant_storage_context_t *context, const char *path, uint64_t *lock_token
) {
  if (!context || !lock_token) return ANT_STORAGE_INVALID_ARGUMENT;
  char *relative = NULL, *file = NULL;
  ant_storage_error_t result = storage_prepare(context, path, &relative, &file);
  if (result != ANT_STORAGE_OK) return result;
  if (context->location.kind == ANT_STORAGE_SAF_TREE) {
    result = context->bridge->lock
      ? context->bridge->lock(context->bridge->user_data, relative, lock_token)
      : ANT_STORAGE_UNSUPPORTED;
  } else {
    /* FILE_PATH has no Java bridge, so coordinate all Ant contexts in this
     * process by their resolved absolute path. This covers separate project
     * and cache context objects and keeps atomic writes serialized. */
#ifdef _WIN32
    *lock_token = (uint64_t)(uintptr_t)context;
    result = ANT_STORAGE_OK;
#else
    const char *key = file ? file : relative;
    char *owned_key = storage_strdup(key ? key : "");
    if (!owned_key) {
      result = ANT_STORAGE_IO;
    } else {
      pthread_mutex_lock(&storage_file_locks_mutex);
      for (;;) {
        storage_file_lock_t *entry = storage_file_locks;
        bool busy = false;
        while (entry) {
          if (strcmp(entry->key, owned_key) == 0) {
            busy = true;
            break;
          }
          entry = entry->next;
        }
        if (!busy) break;
        pthread_cond_wait(&storage_file_locks_condition, &storage_file_locks_mutex);
      }
      storage_file_lock_t *entry = (storage_file_lock_t *)calloc(1, sizeof(*entry));
      if (!entry) {
        pthread_mutex_unlock(&storage_file_locks_mutex);
        free(owned_key);
        result = ANT_STORAGE_IO;
      } else {
        entry->key = owned_key;
        entry->token = storage_next_lock_token++;
        if (entry->token == 0) entry->token = storage_next_lock_token++;
        entry->next = storage_file_locks;
        storage_file_locks = entry;
        *lock_token = entry->token;
        pthread_mutex_unlock(&storage_file_locks_mutex);
        result = ANT_STORAGE_OK;
      }
    }
#endif
  }
  free(relative); free(file);
  return result;
}

void ant_storage_unlock(ant_storage_context_t *context, uint64_t lock_token) {
  if (!context) return;
  if (context->location.kind == ANT_STORAGE_SAF_TREE && context->bridge->unlock)
    context->bridge->unlock(context->bridge->user_data, lock_token);
#ifndef _WIN32
  else if (context->location.kind == ANT_STORAGE_FILE_PATH) {
    pthread_mutex_lock(&storage_file_locks_mutex);
    storage_file_lock_t **cursor = &storage_file_locks;
    while (*cursor) {
      if ((*cursor)->token == lock_token) {
        storage_file_lock_t *entry = *cursor;
        *cursor = entry->next;
        free(entry->key);
        free(entry);
        pthread_cond_broadcast(&storage_file_locks_condition);
        break;
      }
      cursor = &(*cursor)->next;
    }
    pthread_mutex_unlock(&storage_file_locks_mutex);
  }
#endif
}

void ant_storage_free_data(ant_storage_context_t *context, uint8_t *data) {
  if (!data) return;
  if (context && context->location.kind == ANT_STORAGE_SAF_TREE && context->bridge->free_data)
    context->bridge->free_data(context->bridge->user_data, data);
  else free(data);
}

const char *ant_storage_error_string(ant_storage_error_t error) {
  switch (error) {
    case ANT_STORAGE_OK: return "ok";
    case ANT_STORAGE_INVALID_ARGUMENT: return "invalid argument";
    case ANT_STORAGE_NOT_FOUND: return "not found";
    case ANT_STORAGE_PERMISSION: return "permission denied";
    case ANT_STORAGE_IO: return "I/O error";
    case ANT_STORAGE_UNSUPPORTED: return "operation unsupported";
    case ANT_STORAGE_CONFLICT: return "conflict";
    default: return "unknown storage error";
  }
}
