#ifndef ANT_STORAGE_H
#define ANT_STORAGE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/*
 * Storage is deliberately independent from Android.  Android supplies the
 * callback table for SAF_TREE, while desktop and fully-authorized Android
 * paths use the FILE_PATH backend implemented by storage.c.
 */
typedef enum {
  ANT_STORAGE_FILE_PATH = 0,
  ANT_STORAGE_SAF_TREE = 1,
} ant_storage_kind_t;

typedef enum {
  ANT_STORAGE_OK = 0,
  ANT_STORAGE_INVALID_ARGUMENT = -1,
  ANT_STORAGE_NOT_FOUND = -2,
  ANT_STORAGE_PERMISSION = -3,
  ANT_STORAGE_IO = -4,
  ANT_STORAGE_UNSUPPORTED = -5,
  ANT_STORAGE_CONFLICT = -6,
} ant_storage_error_t;

typedef struct {
  ant_storage_kind_t kind;
  const char *location;
} ant_storage_location_t;

typedef bool (*ant_storage_list_visitor)(
  const char *name,
  bool is_directory,
  void *visitor_data
);

typedef struct ant_storage_bridge {
  void *user_data;
  ant_storage_error_t (*mkdirs)(void *user_data, const char *relative_path);
  ant_storage_error_t (*read_file)(
    void *user_data,
    const char *relative_path,
    uint8_t **data,
    size_t *size
  );
  ant_storage_error_t (*write_file)(
    void *user_data,
    const char *relative_path,
    const uint8_t *data,
    size_t size,
    /* true replaces the file, false appends to it. */
    bool truncate
  );
  ant_storage_error_t (*stat)(
    void *user_data,
    const char *relative_path,
    uint64_t *size,
    bool *is_directory,
    bool *exists
  );
  ant_storage_error_t (*list)(
    void *user_data,
    const char *relative_path,
    ant_storage_list_visitor visitor,
    void *visitor_data
  );
  ant_storage_error_t (*remove)(
    void *user_data,
    const char *relative_path,
    bool recursive
  );
  ant_storage_error_t (*rename)(
    void *user_data,
    const char *from_relative_path,
    const char *to_relative_path
  );
  ant_storage_error_t (*copy)(
    void *user_data,
    const char *from_relative_path,
    const char *to_relative_path
  );
  /* Replace `to_relative_path` with `from_relative_path`. Providers should
   * use their strongest same-tree primitive. When a provider cannot offer a
   * single atomic operation it must serialize the replacement so Ant never
   * falls back to a private filesystem staging area. */
  ant_storage_error_t (*atomic_replace)(
    void *user_data,
    const char *from_relative_path,
    const char *to_relative_path
  );
  ant_storage_error_t (*lock)(
    void *user_data,
    const char *relative_path,
    uint64_t *lock_token
  );
  void (*unlock)(void *user_data, uint64_t lock_token);
  void (*free_data)(void *user_data, uint8_t *data);
} ant_storage_bridge_t;

typedef struct ant_storage_context ant_storage_context_t;

ant_storage_context_t *ant_storage_context_create(
  ant_storage_location_t location,
  const ant_storage_bridge_t *bridge
);
/* Creates a view rooted below an existing location. The bridge remains owned
 * by the caller and is shared by both contexts. */
ant_storage_context_t *ant_storage_context_create_child(
  const ant_storage_context_t *parent,
  const char *relative_path
);
void ant_storage_context_destroy(ant_storage_context_t *context);

ant_storage_kind_t ant_storage_context_kind(const ant_storage_context_t *context);
const char *ant_storage_context_location(const ant_storage_context_t *context);

/* Returns an owned, opaque path used as a module identity for SAF files.
 * It is never passed to POSIX APIs.  The caller frees the returned string. */
char *ant_storage_virtual_path(
  const ant_storage_context_t *context,
  const char *relative_path
);

/* Resolves a path inside a FILE_PATH context to an absolute POSIX path. It
 * returns NULL for SAF_TREE (there is intentionally no POSIX representation)
 * or when the path escapes the selected root. The returned string is owned by
 * the caller. */
char *ant_storage_file_path(
  const ant_storage_context_t *context,
  const char *path
);

/* Converts an input path to a safe relative path.  For FILE_PATH this also
 * accepts an absolute path below the configured root. */
char *ant_storage_relative_path(
  const ant_storage_context_t *context,
  const char *path
);

ant_storage_error_t ant_storage_mkdirs(
  ant_storage_context_t *context,
  const char *path
);
ant_storage_error_t ant_storage_read_file(
  ant_storage_context_t *context,
  const char *path,
  uint8_t **data,
  size_t *size
);
ant_storage_error_t ant_storage_write_file(
  ant_storage_context_t *context,
  const char *path,
  const uint8_t *data,
  size_t size,
  /* true replaces the file, false appends to it. */
  bool truncate
);
ant_storage_error_t ant_storage_stat(
  ant_storage_context_t *context,
  const char *path,
  uint64_t *size,
  bool *is_directory,
  bool *exists
);
ant_storage_error_t ant_storage_list(
  ant_storage_context_t *context,
  const char *path,
  ant_storage_list_visitor visitor,
  void *visitor_data
);
ant_storage_error_t ant_storage_remove(
  ant_storage_context_t *context,
  const char *path,
  bool recursive
);
ant_storage_error_t ant_storage_rename(
  ant_storage_context_t *context,
  const char *from,
  const char *to
);
ant_storage_error_t ant_storage_copy(
  ant_storage_context_t *context,
  const char *from,
  const char *to
);
/* Copies between two independently selected locations. This is the portable
 * primitive used when the package cache and project use different backends
 * (for example SAF cache -> FILE_PATH project). */
ant_storage_error_t ant_storage_copy_between(
  ant_storage_context_t *from_context,
  const char *from,
  ant_storage_context_t *to_context,
  const char *to
);
ant_storage_error_t ant_storage_atomic_replace(
  ant_storage_context_t *context,
  const char *from,
  const char *to
);
ant_storage_error_t ant_storage_lock(
  ant_storage_context_t *context,
  const char *path,
  uint64_t *lock_token
);
void ant_storage_unlock(
  ant_storage_context_t *context,
  uint64_t lock_token
);
void ant_storage_free_data(
  ant_storage_context_t *context,
  uint8_t *data
);

/* Convenience helpers used by embedders and the runtime. */
bool ant_storage_path_is_virtual(const char *path);
bool ant_storage_path_is_safe_relative(const char *path);
const char *ant_storage_error_string(ant_storage_error_t error);

#endif
