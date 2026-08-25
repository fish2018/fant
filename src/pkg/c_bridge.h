#ifndef ANT_PKG_C_BRIDGE_H
#define ANT_PKG_C_BRIDGE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Keep Zig's @cImport surface deliberately small. Zig 0.16's Android
 * translate-c path can crash while expanding yyjson.h, even though Clang can
 * compile the header normally. The implementation below is compiled as C and
 * owns all contact with yyjson's large inline API.
 */
typedef struct ant_pkg_json_doc ant_pkg_json_doc;
typedef struct ant_pkg_json_value ant_pkg_json_value;
typedef struct ant_pkg_json_mut_doc ant_pkg_json_mut_doc;
typedef struct ant_pkg_json_mut_value ant_pkg_json_mut_value;
typedef struct ant_pkg_inflate ant_pkg_inflate;

/* Storage is implemented by the host runtime (libant).  Keep the Zig
 * package-manager archive independent from Android/JNI while allowing it to
 * use the same FILE_PATH and SAF_TREE backend. */
typedef struct ant_storage_context ant_storage_context_t;

int ant_pkg_storage_kind(const ant_storage_context_t *context);
const char *ant_pkg_storage_location(const ant_storage_context_t *context);
char *ant_pkg_storage_relative_path(
  const ant_storage_context_t *context, const char *path
);

int ant_pkg_storage_mkdirs(
  const ant_storage_context_t *context, const char *path
);
int ant_pkg_storage_read_file(
  const ant_storage_context_t *context, const char *path,
  uint8_t **data, size_t *size
);
int ant_pkg_storage_write_file(
  const ant_storage_context_t *context, const char *path,
  const uint8_t *data, size_t size, bool truncate
);
int ant_pkg_storage_stat(
  const ant_storage_context_t *context, const char *path,
  uint64_t *size, bool *is_directory, bool *exists
);
int ant_pkg_storage_list(
  const ant_storage_context_t *context, const char *path,
  bool (*visitor)(const char *name, bool is_directory, void *visitor_data),
  void *visitor_data
);
int ant_pkg_storage_remove(
  const ant_storage_context_t *context, const char *path, bool recursive
);
int ant_pkg_storage_rename(
  const ant_storage_context_t *context, const char *from, const char *to
);
int ant_pkg_storage_copy(
  const ant_storage_context_t *context, const char *from, const char *to
);
int ant_pkg_storage_copy_between(
  const ant_storage_context_t *from_context, const char *from,
  const ant_storage_context_t *to_context, const char *to
);
int ant_pkg_storage_atomic_replace(
  const ant_storage_context_t *context, const char *from, const char *to
);
int ant_pkg_storage_lock(
  const ant_storage_context_t *context, const char *path, uint64_t *token
);
void ant_pkg_storage_unlock(
  const ant_storage_context_t *context, uint64_t token
);
void ant_pkg_storage_free_data(
  const ant_storage_context_t *context, uint8_t *data
);
bool ant_pkg_storage_is_virtual_path(const char *path);
char *ant_pkg_storage_virtual_path(
  const ant_storage_context_t *context, const char *relative_path
);

typedef struct {
  uintptr_t storage[6];
} ant_pkg_json_iter;

ant_pkg_json_doc *ant_pkg_json_read(const char *data, size_t length);
ant_pkg_json_doc *ant_pkg_json_read_file(const char *path);
void ant_pkg_json_doc_free(ant_pkg_json_doc *doc);
ant_pkg_json_value *ant_pkg_json_doc_root(ant_pkg_json_doc *doc);

ant_pkg_json_value *ant_pkg_json_object_get(
  ant_pkg_json_value *value, const char *key
);
bool ant_pkg_json_is_null(ant_pkg_json_value *value);
bool ant_pkg_json_is_bool(ant_pkg_json_value *value);
bool ant_pkg_json_is_int(ant_pkg_json_value *value);
bool ant_pkg_json_is_uint(ant_pkg_json_value *value);
bool ant_pkg_json_is_real(ant_pkg_json_value *value);
bool ant_pkg_json_is_string(ant_pkg_json_value *value);
bool ant_pkg_json_is_array(ant_pkg_json_value *value);
bool ant_pkg_json_is_object(ant_pkg_json_value *value);
bool ant_pkg_json_get_bool(ant_pkg_json_value *value);
int64_t ant_pkg_json_get_sint(ant_pkg_json_value *value);
uint64_t ant_pkg_json_get_uint(ant_pkg_json_value *value);
double ant_pkg_json_get_real(ant_pkg_json_value *value);
const char *ant_pkg_json_get_string(
  ant_pkg_json_value *value, size_t *length
);
size_t ant_pkg_json_array_size(ant_pkg_json_value *value);
ant_pkg_json_value *ant_pkg_json_array_get(
  ant_pkg_json_value *value, size_t index
);

bool ant_pkg_json_object_iter_init(
  ant_pkg_json_value *value, ant_pkg_json_iter *iter
);
bool ant_pkg_json_object_iter_next(
  ant_pkg_json_iter *iter,
  const char **key,
  size_t *key_length,
  ant_pkg_json_value **value
);
bool ant_pkg_json_array_iter_init(
  ant_pkg_json_value *value, ant_pkg_json_iter *iter
);
ant_pkg_json_value *ant_pkg_json_array_iter_next(ant_pkg_json_iter *iter);

ant_pkg_json_mut_doc *ant_pkg_json_mut_doc_new(void);
ant_pkg_json_mut_doc *ant_pkg_json_doc_mut_copy(ant_pkg_json_doc *doc);
void ant_pkg_json_mut_doc_free(ant_pkg_json_mut_doc *doc);
ant_pkg_json_mut_value *ant_pkg_json_mut_doc_root(ant_pkg_json_mut_doc *doc);
void ant_pkg_json_mut_doc_set_root(
  ant_pkg_json_mut_doc *doc, ant_pkg_json_mut_value *value
);

ant_pkg_json_mut_value *ant_pkg_json_mut_object(ant_pkg_json_mut_doc *doc);
ant_pkg_json_mut_value *ant_pkg_json_mut_array(ant_pkg_json_mut_doc *doc);
ant_pkg_json_mut_value *ant_pkg_json_mut_string(
  ant_pkg_json_mut_doc *doc, const char *value, size_t length
);
ant_pkg_json_mut_value *ant_pkg_json_mut_sint(
  ant_pkg_json_mut_doc *doc, int64_t value
);
ant_pkg_json_mut_value *ant_pkg_json_mut_uint(
  ant_pkg_json_mut_doc *doc, uint64_t value
);
ant_pkg_json_mut_value *ant_pkg_json_mut_real(
  ant_pkg_json_mut_doc *doc, double value
);
ant_pkg_json_mut_value *ant_pkg_json_mut_bool(
  ant_pkg_json_mut_doc *doc, bool value
);
ant_pkg_json_mut_value *ant_pkg_json_mut_null(ant_pkg_json_mut_doc *doc);

ant_pkg_json_mut_value *ant_pkg_json_mut_object_get(
  ant_pkg_json_mut_value *object, const char *key
);
bool ant_pkg_json_mut_object_add(
  ant_pkg_json_mut_doc *doc,
  ant_pkg_json_mut_value *object,
  const char *key,
  size_t key_length,
  ant_pkg_json_mut_value *value
);
bool ant_pkg_json_mut_array_append(
  ant_pkg_json_mut_value *array, ant_pkg_json_mut_value *value
);
bool ant_pkg_json_mut_is_string(ant_pkg_json_mut_value *value);
bool ant_pkg_json_mut_is_array(ant_pkg_json_mut_value *value);
const char *ant_pkg_json_mut_get_string(
  ant_pkg_json_mut_value *value, size_t *length
);
bool ant_pkg_json_mut_array_iter_init(
  ant_pkg_json_mut_value *value, ant_pkg_json_iter *iter
);
ant_pkg_json_mut_value *ant_pkg_json_mut_array_iter_next(
  ant_pkg_json_iter *iter
);

char *ant_pkg_json_mut_write(
  ant_pkg_json_mut_doc *doc, size_t *length
);
bool ant_pkg_json_mut_write_file(
  const char *path, ant_pkg_json_mut_doc *doc, bool escape_unicode
);
void ant_pkg_json_string_free(char *value);

ant_pkg_inflate *ant_pkg_inflate_new(void);
void ant_pkg_inflate_free(ant_pkg_inflate *stream);
int ant_pkg_inflate_chunk(
  ant_pkg_inflate *stream,
  const uint8_t *input,
  size_t input_length,
  size_t *input_consumed,
  uint8_t *output,
  size_t output_capacity,
  size_t *output_produced
);

/* Small LMDB declaration subset used by cache.zig. */
typedef struct MDB_env MDB_env;
typedef struct MDB_txn MDB_txn;
typedef struct MDB_cursor MDB_cursor;
typedef unsigned int MDB_dbi;
typedef struct MDB_val {
  size_t mv_size;
  void *mv_data;
} MDB_val;
typedef struct MDB_stat {
  unsigned int ms_psize;
  unsigned int ms_depth;
  size_t ms_branch_pages;
  size_t ms_leaf_pages;
  size_t ms_overflow_pages;
  size_t ms_entries;
} MDB_stat;
typedef struct MDB_envinfo {
  void *me_mapaddr;
  size_t me_mapsize;
  size_t me_last_pgno;
  size_t me_last_txnid;
  unsigned int me_maxreaders;
  unsigned int me_numreaders;
} MDB_envinfo;

enum {
  MDB_NOSUBDIR = 0x4000,
  MDB_NOSYNC = 0x10000,
  MDB_RDONLY = 0x20000,
  MDB_CREATE = 0x40000,
  MDB_FIRST = 0,
  MDB_NEXT = 8,
};

int mdb_env_create(MDB_env **env);
int mdb_env_set_mapsize(MDB_env *env, size_t size);
int mdb_env_set_maxdbs(MDB_env *env, unsigned int dbs);
int mdb_env_open(MDB_env *env, const char *path, unsigned int flags, int mode);
void mdb_env_close(MDB_env *env);
int mdb_env_info(MDB_env *env, MDB_envinfo *stat);
int mdb_env_sync(MDB_env *env, int force);
int mdb_txn_begin(MDB_env *env, MDB_txn *parent, unsigned int flags, MDB_txn **txn);
int mdb_txn_commit(MDB_txn *txn);
void mdb_txn_abort(MDB_txn *txn);
int mdb_dbi_open(MDB_txn *txn, const char *name, unsigned int flags, MDB_dbi *dbi);
void mdb_dbi_close(MDB_env *env, MDB_dbi dbi);
int mdb_get(MDB_txn *txn, MDB_dbi dbi, MDB_val *key, MDB_val *data);
int mdb_put(MDB_txn *txn, MDB_dbi dbi, MDB_val *key, MDB_val *data, unsigned int flags);
int mdb_del(MDB_txn *txn, MDB_dbi dbi, MDB_val *key, MDB_val *data);
int mdb_stat(MDB_txn *txn, MDB_dbi dbi, MDB_stat *stat);
int mdb_cursor_open(MDB_txn *txn, MDB_dbi dbi, MDB_cursor **cursor);
void mdb_cursor_close(MDB_cursor *cursor);
int mdb_cursor_get(MDB_cursor *cursor, MDB_val *key, MDB_val *data, int op);

#ifdef __cplusplus
}
#endif

#endif
