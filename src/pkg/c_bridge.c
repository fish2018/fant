#include "c_bridge.h"

#include "storage.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <yyjson.h>
#include <zlib.h>

int ant_pkg_storage_kind(const ant_storage_context_t *context) {
  return (int)ant_storage_context_kind(context);
}

const char *ant_pkg_storage_location(const ant_storage_context_t *context) {
  return ant_storage_context_location(context);
}

char *ant_pkg_storage_relative_path(
  const ant_storage_context_t *context, const char *path
) {
  return ant_storage_relative_path(
    (const ant_storage_context_t *)context, path
  );
}

int ant_pkg_storage_mkdirs(
  const ant_storage_context_t *context, const char *path
) {
  return (int)ant_storage_mkdirs((ant_storage_context_t *)context, path);
}

int ant_pkg_storage_read_file(
  const ant_storage_context_t *context, const char *path,
  uint8_t **data, size_t *size
) {
  return (int)ant_storage_read_file((ant_storage_context_t *)context, path, data, size);
}

int ant_pkg_storage_write_file(
  const ant_storage_context_t *context, const char *path,
  const uint8_t *data, size_t size, bool truncate
) {
  return (int)ant_storage_write_file(
    (ant_storage_context_t *)context, path, data, size, truncate
  );
}

int ant_pkg_storage_stat(
  const ant_storage_context_t *context, const char *path,
  uint64_t *size, bool *is_directory, bool *exists
) {
  return (int)ant_storage_stat(
    (ant_storage_context_t *)context, path, size, is_directory, exists
  );
}

int ant_pkg_storage_list(
  const ant_storage_context_t *context, const char *path,
  bool (*visitor)(const char *name, bool is_directory, void *visitor_data),
  void *visitor_data
) {
  return (int)ant_storage_list(
    (ant_storage_context_t *)context, path, visitor, visitor_data
  );
}

int ant_pkg_storage_remove(
  const ant_storage_context_t *context, const char *path, bool recursive
) {
  return (int)ant_storage_remove((ant_storage_context_t *)context, path, recursive);
}

int ant_pkg_storage_rename(
  const ant_storage_context_t *context, const char *from, const char *to
) {
  return (int)ant_storage_rename((ant_storage_context_t *)context, from, to);
}

int ant_pkg_storage_copy(
  const ant_storage_context_t *context, const char *from, const char *to
) {
  return (int)ant_storage_copy((ant_storage_context_t *)context, from, to);
}

int ant_pkg_storage_copy_between(
  const ant_storage_context_t *from_context, const char *from,
  const ant_storage_context_t *to_context, const char *to
) {
  return (int)ant_storage_copy_between(
    (ant_storage_context_t *)from_context, from,
    (ant_storage_context_t *)to_context, to
  );
}

int ant_pkg_storage_atomic_replace(
  const ant_storage_context_t *context, const char *from, const char *to
) {
  return (int)ant_storage_atomic_replace(
    (ant_storage_context_t *)context, from, to
  );
}

int ant_pkg_storage_lock(
  const ant_storage_context_t *context, const char *path, uint64_t *token
) {
  return (int)ant_storage_lock((ant_storage_context_t *)context, path, token);
}

void ant_pkg_storage_unlock(
  const ant_storage_context_t *context, uint64_t token
) {
  ant_storage_unlock((ant_storage_context_t *)context, token);
}

void ant_pkg_storage_free_data(
  const ant_storage_context_t *context, uint8_t *data
) {
  ant_storage_free_data((ant_storage_context_t *)context, data);
}

bool ant_pkg_storage_is_virtual_path(const char *path) {
  return ant_storage_path_is_virtual(path);
}

char *ant_pkg_storage_virtual_path(
  const ant_storage_context_t *context, const char *relative_path
) {
  return ant_storage_virtual_path(
    (const ant_storage_context_t *)context, relative_path
  );
}

struct ant_pkg_inflate {
  z_stream stream;
};

_Static_assert(sizeof(yyjson_obj_iter) <= sizeof(ant_pkg_json_iter),
               "ant_pkg_json_iter is too small for yyjson_obj_iter");
_Static_assert(sizeof(yyjson_arr_iter) <= sizeof(ant_pkg_json_iter),
               "ant_pkg_json_iter is too small for yyjson_arr_iter");
_Static_assert(sizeof(yyjson_mut_arr_iter) <= sizeof(ant_pkg_json_iter),
               "ant_pkg_json_iter is too small for yyjson_mut_arr_iter");

#define DOC(value) ((yyjson_doc *)(value))
#define VAL(value) ((yyjson_val *)(value))
#define MDOC(value) ((yyjson_mut_doc *)(value))
#define MVAL(value) ((yyjson_mut_val *)(value))

ant_pkg_json_doc *ant_pkg_json_read(const char *data, size_t length) {
  return (ant_pkg_json_doc *)yyjson_read(data, length, 0);
}

ant_pkg_json_doc *ant_pkg_json_read_file(const char *path) {
  return (ant_pkg_json_doc *)yyjson_read_file(path, 0, NULL, NULL);
}

void ant_pkg_json_doc_free(ant_pkg_json_doc *doc) {
  yyjson_doc_free(DOC(doc));
}

ant_pkg_json_value *ant_pkg_json_doc_root(ant_pkg_json_doc *doc) {
  return (ant_pkg_json_value *)yyjson_doc_get_root(DOC(doc));
}

ant_pkg_json_value *ant_pkg_json_object_get(
  ant_pkg_json_value *value, const char *key
) {
  return (ant_pkg_json_value *)yyjson_obj_get(VAL(value), key);
}

bool ant_pkg_json_is_null(ant_pkg_json_value *value) {
  return yyjson_is_null(VAL(value));
}

bool ant_pkg_json_is_bool(ant_pkg_json_value *value) {
  return yyjson_is_bool(VAL(value));
}

bool ant_pkg_json_is_int(ant_pkg_json_value *value) {
  return yyjson_is_int(VAL(value));
}

bool ant_pkg_json_is_uint(ant_pkg_json_value *value) {
  return yyjson_is_uint(VAL(value));
}

bool ant_pkg_json_is_real(ant_pkg_json_value *value) {
  return yyjson_is_real(VAL(value));
}

bool ant_pkg_json_is_string(ant_pkg_json_value *value) {
  return yyjson_is_str(VAL(value));
}

bool ant_pkg_json_is_array(ant_pkg_json_value *value) {
  return yyjson_is_arr(VAL(value));
}

bool ant_pkg_json_is_object(ant_pkg_json_value *value) {
  return yyjson_is_obj(VAL(value));
}

bool ant_pkg_json_get_bool(ant_pkg_json_value *value) {
  return yyjson_get_bool(VAL(value));
}

int64_t ant_pkg_json_get_sint(ant_pkg_json_value *value) {
  return yyjson_get_sint(VAL(value));
}

uint64_t ant_pkg_json_get_uint(ant_pkg_json_value *value) {
  return yyjson_get_uint(VAL(value));
}

double ant_pkg_json_get_real(ant_pkg_json_value *value) {
  return yyjson_get_real(VAL(value));
}

const char *ant_pkg_json_get_string(
  ant_pkg_json_value *value, size_t *length
) {
  yyjson_val *val = VAL(value);
  if (length) *length = yyjson_get_len(val);
  return yyjson_get_str(val);
}

size_t ant_pkg_json_array_size(ant_pkg_json_value *value) {
  return yyjson_arr_size(VAL(value));
}

ant_pkg_json_value *ant_pkg_json_array_get(
  ant_pkg_json_value *value, size_t index
) {
  return (ant_pkg_json_value *)yyjson_arr_get(VAL(value), index);
}

bool ant_pkg_json_object_iter_init(
  ant_pkg_json_value *value, ant_pkg_json_iter *iter
) {
  return yyjson_obj_iter_init(VAL(value), (yyjson_obj_iter *)iter);
}

bool ant_pkg_json_object_iter_next(
  ant_pkg_json_iter *iter,
  const char **key,
  size_t *key_length,
  ant_pkg_json_value **value
) {
  yyjson_val *key_value = yyjson_obj_iter_next((yyjson_obj_iter *)iter);
  if (!key_value) return false;
  if (key) *key = yyjson_get_str(key_value);
  if (key_length) *key_length = yyjson_get_len(key_value);
  if (value) {
    *value = (ant_pkg_json_value *)yyjson_obj_iter_get_val(key_value);
  }
  return true;
}

bool ant_pkg_json_array_iter_init(
  ant_pkg_json_value *value, ant_pkg_json_iter *iter
) {
  return yyjson_arr_iter_init(VAL(value), (yyjson_arr_iter *)iter);
}

ant_pkg_json_value *ant_pkg_json_array_iter_next(ant_pkg_json_iter *iter) {
  return (ant_pkg_json_value *)yyjson_arr_iter_next((yyjson_arr_iter *)iter);
}

ant_pkg_json_mut_doc *ant_pkg_json_mut_doc_new(void) {
  return (ant_pkg_json_mut_doc *)yyjson_mut_doc_new(NULL);
}

ant_pkg_json_mut_doc *ant_pkg_json_doc_mut_copy(ant_pkg_json_doc *doc) {
  return (ant_pkg_json_mut_doc *)yyjson_doc_mut_copy(DOC(doc), NULL);
}

void ant_pkg_json_mut_doc_free(ant_pkg_json_mut_doc *doc) {
  yyjson_mut_doc_free(MDOC(doc));
}

ant_pkg_json_mut_value *ant_pkg_json_mut_doc_root(ant_pkg_json_mut_doc *doc) {
  return (ant_pkg_json_mut_value *)yyjson_mut_doc_get_root(MDOC(doc));
}

void ant_pkg_json_mut_doc_set_root(
  ant_pkg_json_mut_doc *doc, ant_pkg_json_mut_value *value
) {
  yyjson_mut_doc_set_root(MDOC(doc), MVAL(value));
}

ant_pkg_json_mut_value *ant_pkg_json_mut_object(ant_pkg_json_mut_doc *doc) {
  return (ant_pkg_json_mut_value *)yyjson_mut_obj(MDOC(doc));
}

ant_pkg_json_mut_value *ant_pkg_json_mut_array(ant_pkg_json_mut_doc *doc) {
  return (ant_pkg_json_mut_value *)yyjson_mut_arr(MDOC(doc));
}

ant_pkg_json_mut_value *ant_pkg_json_mut_string(
  ant_pkg_json_mut_doc *doc, const char *value, size_t length
) {
  return (ant_pkg_json_mut_value *)yyjson_mut_strncpy(
    MDOC(doc), value, length
  );
}

ant_pkg_json_mut_value *ant_pkg_json_mut_sint(
  ant_pkg_json_mut_doc *doc, int64_t value
) {
  return (ant_pkg_json_mut_value *)yyjson_mut_sint(MDOC(doc), value);
}

ant_pkg_json_mut_value *ant_pkg_json_mut_uint(
  ant_pkg_json_mut_doc *doc, uint64_t value
) {
  return (ant_pkg_json_mut_value *)yyjson_mut_uint(MDOC(doc), value);
}

ant_pkg_json_mut_value *ant_pkg_json_mut_real(
  ant_pkg_json_mut_doc *doc, double value
) {
  return (ant_pkg_json_mut_value *)yyjson_mut_real(MDOC(doc), value);
}

ant_pkg_json_mut_value *ant_pkg_json_mut_bool(
  ant_pkg_json_mut_doc *doc, bool value
) {
  return (ant_pkg_json_mut_value *)yyjson_mut_bool(MDOC(doc), value);
}

ant_pkg_json_mut_value *ant_pkg_json_mut_null(ant_pkg_json_mut_doc *doc) {
  return (ant_pkg_json_mut_value *)yyjson_mut_null(MDOC(doc));
}

ant_pkg_json_mut_value *ant_pkg_json_mut_object_get(
  ant_pkg_json_mut_value *object, const char *key
) {
  return (ant_pkg_json_mut_value *)yyjson_mut_obj_get(MVAL(object), key);
}

bool ant_pkg_json_mut_object_add(
  ant_pkg_json_mut_doc *doc,
  ant_pkg_json_mut_value *object,
  const char *key,
  size_t key_length,
  ant_pkg_json_mut_value *value
) {
  yyjson_mut_val *key_value = yyjson_mut_strncpy(MDOC(doc), key, key_length);
  return key_value && yyjson_mut_obj_add(MVAL(object), key_value, MVAL(value));
}

bool ant_pkg_json_mut_array_append(
  ant_pkg_json_mut_value *array, ant_pkg_json_mut_value *value
) {
  return yyjson_mut_arr_append(MVAL(array), MVAL(value));
}

bool ant_pkg_json_mut_is_string(ant_pkg_json_mut_value *value) {
  return yyjson_mut_is_str(MVAL(value));
}

bool ant_pkg_json_mut_is_array(ant_pkg_json_mut_value *value) {
  return yyjson_mut_is_arr(MVAL(value));
}

const char *ant_pkg_json_mut_get_string(
  ant_pkg_json_mut_value *value, size_t *length
) {
  yyjson_mut_val *val = MVAL(value);
  if (length) *length = yyjson_mut_get_len(val);
  return yyjson_mut_get_str(val);
}

bool ant_pkg_json_mut_array_iter_init(
  ant_pkg_json_mut_value *value, ant_pkg_json_iter *iter
) {
  return yyjson_mut_arr_iter_init(MVAL(value), (yyjson_mut_arr_iter *)iter);
}

ant_pkg_json_mut_value *ant_pkg_json_mut_array_iter_next(
  ant_pkg_json_iter *iter
) {
  return (ant_pkg_json_mut_value *)yyjson_mut_arr_iter_next(
    (yyjson_mut_arr_iter *)iter
  );
}

char *ant_pkg_json_mut_write(
  ant_pkg_json_mut_doc *doc, size_t *length
) {
  return yyjson_mut_write(
    MDOC(doc), YYJSON_WRITE_PRETTY_TWO_SPACES, length
  );
}

bool ant_pkg_json_mut_write_file(
  const char *path, ant_pkg_json_mut_doc *doc, bool escape_unicode
) {
  yyjson_write_flag flags = YYJSON_WRITE_PRETTY_TWO_SPACES;
  if (escape_unicode) flags |= YYJSON_WRITE_ESCAPE_UNICODE;
  return yyjson_mut_write_file(path, MDOC(doc), flags, NULL, NULL);
}

void ant_pkg_json_string_free(char *value) {
  free(value);
}

ant_pkg_inflate *ant_pkg_inflate_new(void) {
  ant_pkg_inflate *state = (ant_pkg_inflate *)calloc(1, sizeof(*state));
  if (!state) return NULL;
  if (inflateInit2(&state->stream, 15 + 32) != Z_OK) {
    free(state);
    return NULL;
  }
  return state;
}

void ant_pkg_inflate_free(ant_pkg_inflate *state) {
  if (!state) return;
  inflateEnd(&state->stream);
  free(state);
}

int ant_pkg_inflate_chunk(
  ant_pkg_inflate *state,
  const uint8_t *input,
  size_t input_length,
  size_t *input_consumed,
  uint8_t *output,
  size_t output_capacity,
  size_t *output_produced
) {
  if (!state || !input_consumed || !output_produced ||
      (!input && input_length != 0) || (!output && output_capacity != 0) ||
      input_length > UINT_MAX || output_capacity > UINT_MAX) {
    return -1;
  }

  state->stream.next_in = (Bytef *)(uintptr_t)input;
  state->stream.avail_in = (uInt)input_length;
  state->stream.next_out = output;
  state->stream.avail_out = (uInt)output_capacity;

  int result = inflate(&state->stream, Z_NO_FLUSH);
  *input_consumed = input_length - state->stream.avail_in;
  *output_produced = output_capacity - state->stream.avail_out;
  if (result == Z_STREAM_END) return 1;
  if (result == Z_OK) return 0;
  return -1;
}
