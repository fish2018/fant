#include "storage_bridge.h"

#include <limits.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

struct ant_android_storage_bridge {
  JavaVM *vm;
  jobject object;
  jclass bridge_class;
  jclass entry_class;
  jmethodID mkdirs;
  jmethodID read_file;
  jmethodID write_file;
  jmethodID stat;
  jmethodID list;
  jmethodID remove;
  jmethodID rename;
  jmethodID copy;
  jmethodID copy_from_file_path;
  jmethodID atomic_replace;
  jmethodID probe;
  jmethodID lock;
  jmethodID unlock;
  jfieldID entry_name;
  jfieldID entry_directory;
  ant_storage_bridge_t callbacks;
};

static JNIEnv *bridge_env(
  ant_android_storage_bridge_t *bridge,
  bool *attached
) {
  if (attached) *attached = false;
  if (!bridge || !bridge->vm) return NULL;
  JNIEnv *env = NULL;
  jint result = (*bridge->vm)->GetEnv(
    bridge->vm, (void **)&env, JNI_VERSION_1_6
  );
  if (result == JNI_OK) return env;
  if (result != JNI_EDETACHED) return NULL;
  if ((*bridge->vm)->AttachCurrentThread(bridge->vm, &env, NULL) != JNI_OK)
    return NULL;
  if (attached) *attached = true;
  return env;
}

static void bridge_release_env(
  ant_android_storage_bridge_t *bridge,
  bool attached
) {
  if (attached && bridge && bridge->vm)
    (*bridge->vm)->DetachCurrentThread(bridge->vm);
}

static uint32_t bridge_utf8_decode(
  const unsigned char *bytes,
  size_t length,
  size_t *offset
) {
  size_t pos = *offset;
  unsigned char first = bytes[pos++];
  uint32_t value = 0xfffdu;
  size_t needed = 0;
  if (first < 0x80) { *offset = pos; return first; }
  if ((first & 0xe0) == 0xc0) { value = first & 0x1fu; needed = 1; }
  else if ((first & 0xf0) == 0xe0) { value = first & 0x0fu; needed = 2; }
  else if ((first & 0xf8) == 0xf0) { value = first & 0x07u; needed = 3; }
  else { *offset = pos; return value; }
  if (pos + needed > length) { *offset = pos; return 0xfffdu; }
  for (size_t i = 0; i < needed; i++) {
    unsigned char next = bytes[pos + i];
    if ((next & 0xc0) != 0x80) { *offset = pos; return 0xfffdu; }
    value = (value << 6) | (next & 0x3fu);
  }
  *offset = pos + needed;
  return value;
}

static jstring bridge_string(JNIEnv *env, const char *value) {
  if (!value) value = "";
  size_t length = strlen(value);
  size_t units = 0;
  for (size_t offset = 0; offset < length;) {
    uint32_t codepoint = bridge_utf8_decode(
      (const unsigned char *)value, length, &offset
    );
    units += codepoint > 0xffffu ? 2u : 1u;
    if (units > (size_t)INT_MAX) return NULL;
  }
  jchar *chars = units ? (jchar *)malloc(units * sizeof(*chars)) : NULL;
  if (units && !chars) return NULL;
  size_t out = 0;
  for (size_t offset = 0; offset < length;) {
    uint32_t codepoint = bridge_utf8_decode(
      (const unsigned char *)value, length, &offset
    );
    if (codepoint <= 0xffffu) chars[out++] = (jchar)codepoint;
    else {
      codepoint -= 0x10000u;
      chars[out++] = (jchar)(0xd800u | (codepoint >> 10));
      chars[out++] = (jchar)(0xdc00u | (codepoint & 0x3ffu));
    }
  }
  jstring result = (*env)->NewString(env, chars, (jsize)units);
  free(chars);
  return result;
}

static ant_storage_error_t bridge_exception(JNIEnv *env) {
  jthrowable error = (*env)->ExceptionOccurred(env);
  if (!error) return ANT_STORAGE_IO;
  (*env)->ExceptionClear(env);

  ant_storage_error_t result = ANT_STORAGE_IO;
  jclass security = (*env)->FindClass(env, "java/lang/SecurityException");
  jclass invalid = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
  if (security && (*env)->IsInstanceOf(env, error, security)) {
    result = ANT_STORAGE_PERMISSION;
  } else if (invalid && (*env)->IsInstanceOf(env, error, invalid)) {
    result = ANT_STORAGE_INVALID_ARGUMENT;
  } else {
    jclass throwable = (*env)->FindClass(env, "java/lang/Throwable");
    jmethodID get_message = throwable
      ? (*env)->GetMethodID(env, throwable, "getMessage", "()Ljava/lang/String;")
      : NULL;
    jstring message = get_message
      ? (jstring)(*env)->CallObjectMethod(env, error, get_message)
      : NULL;
    if (!(*env)->ExceptionCheck(env) && message) {
      const char *text = (*env)->GetStringUTFChars(env, message, NULL);
      if (text) {
        if (strstr(text, "permission") || strstr(text, "Permission") ||
            strstr(text, "revoked")) result = ANT_STORAGE_PERMISSION;
        else if (strstr(text, "not found")) result = ANT_STORAGE_NOT_FOUND;
        (*env)->ReleaseStringUTFChars(env, message, text);
      }
      (*env)->DeleteLocalRef(env, message);
    } else if ((*env)->ExceptionCheck(env)) {
      (*env)->ExceptionClear(env);
    }
    if (throwable) (*env)->DeleteLocalRef(env, throwable);
  }
  if (security) (*env)->DeleteLocalRef(env, security);
  if (invalid) (*env)->DeleteLocalRef(env, invalid);
  (*env)->DeleteLocalRef(env, error);
  return result;
}

static ant_storage_error_t bridge_mkdirs(void *data, const char *path) {
  ant_android_storage_bridge_t *bridge = data;
  bool attached = false;
  JNIEnv *env = bridge_env(bridge, &attached);
  if (!env) return ANT_STORAGE_IO;
  jstring value = bridge_string(env, path);
  if (!value) { bridge_release_env(bridge, attached); return ANT_STORAGE_IO; }
  jint result = (*env)->CallIntMethod(env, bridge->object, bridge->mkdirs, value);
  (*env)->DeleteLocalRef(env, value);
  ant_storage_error_t error = (*env)->ExceptionCheck(env)
    ? bridge_exception(env) : (ant_storage_error_t)result;
  bridge_release_env(bridge, attached);
  return error;
}

static ant_storage_error_t bridge_read_file(
  void *data,
  const char *path,
  uint8_t **output,
  size_t *size
) {
  if (!output || !size) return ANT_STORAGE_INVALID_ARGUMENT;
  *output = NULL;
  *size = 0;
  ant_android_storage_bridge_t *bridge = data;
  bool attached = false;
  JNIEnv *env = bridge_env(bridge, &attached);
  if (!env) return ANT_STORAGE_IO;
  jstring value = bridge_string(env, path);
  if (!value) { bridge_release_env(bridge, attached); return ANT_STORAGE_IO; }
  jbyteArray bytes = (jbyteArray)(*env)->CallObjectMethod(
    env, bridge->object, bridge->read_file, value
  );
  (*env)->DeleteLocalRef(env, value);
  if ((*env)->ExceptionCheck(env)) {
    ant_storage_error_t error = bridge_exception(env);
    bridge_release_env(bridge, attached);
    return error;
  }
  if (!bytes) { bridge_release_env(bridge, attached); return ANT_STORAGE_IO; }
  jsize length = (*env)->GetArrayLength(env, bytes);
  uint8_t *buffer = (uint8_t *)malloc((size_t)length + 1u);
  if (!buffer) {
    (*env)->DeleteLocalRef(env, bytes);
    bridge_release_env(bridge, attached);
    return ANT_STORAGE_IO;
  }
  if (length > 0)
    (*env)->GetByteArrayRegion(env, bytes, 0, length, (jbyte *)buffer);
  (*env)->DeleteLocalRef(env, bytes);
  if ((*env)->ExceptionCheck(env)) {
    free(buffer);
    ant_storage_error_t error = bridge_exception(env);
    bridge_release_env(bridge, attached);
    return error;
  }
  buffer[length] = 0;
  *output = buffer;
  *size = (size_t)length;
  bridge_release_env(bridge, attached);
  return ANT_STORAGE_OK;
}

static ant_storage_error_t bridge_write_file(
  void *data,
  const char *path,
  const uint8_t *input,
  size_t size,
  bool truncate
) {
  if (size > INT_MAX) return ANT_STORAGE_INVALID_ARGUMENT;
  ant_android_storage_bridge_t *bridge = data;
  bool attached = false;
  JNIEnv *env = bridge_env(bridge, &attached);
  if (!env) return ANT_STORAGE_IO;
  jstring value = bridge_string(env, path);
  jbyteArray bytes = (*env)->NewByteArray(env, (jsize)size);
  if (!value || !bytes) {
    if (value) (*env)->DeleteLocalRef(env, value);
    if (bytes) (*env)->DeleteLocalRef(env, bytes);
    bridge_release_env(bridge, attached);
    return ANT_STORAGE_IO;
  }
  if (size > 0)
    (*env)->SetByteArrayRegion(env, bytes, 0, (jsize)size, (const jbyte *)input);
  jint result = (*env)->CallIntMethod(
    env, bridge->object, bridge->write_file, value, bytes,
    truncate ? JNI_TRUE : JNI_FALSE
  );
  (*env)->DeleteLocalRef(env, value);
  (*env)->DeleteLocalRef(env, bytes);
  ant_storage_error_t error = (*env)->ExceptionCheck(env)
    ? bridge_exception(env) : (ant_storage_error_t)result;
  bridge_release_env(bridge, attached);
  return error;
}

static ant_storage_error_t bridge_stat(
  void *data,
  const char *path,
  uint64_t *size,
  bool *is_directory,
  bool *exists
) {
  if (!size || !is_directory || !exists) return ANT_STORAGE_INVALID_ARGUMENT;
  ant_android_storage_bridge_t *bridge = data;
  bool attached = false;
  JNIEnv *env = bridge_env(bridge, &attached);
  if (!env) return ANT_STORAGE_IO;
  jstring value = bridge_string(env, path);
  if (!value) { bridge_release_env(bridge, attached); return ANT_STORAGE_IO; }
  jlongArray values = (jlongArray)(*env)->CallObjectMethod(
    env, bridge->object, bridge->stat, value
  );
  (*env)->DeleteLocalRef(env, value);
  if ((*env)->ExceptionCheck(env)) {
    ant_storage_error_t error = bridge_exception(env);
    bridge_release_env(bridge, attached);
    return error;
  }
  if (!values || (*env)->GetArrayLength(env, values) < 3) {
    if (values) (*env)->DeleteLocalRef(env, values);
    bridge_release_env(bridge, attached);
    return ANT_STORAGE_IO;
  }
  jlong raw[3] = {0, 0, 0};
  (*env)->GetLongArrayRegion(env, values, 0, 3, raw);
  (*env)->DeleteLocalRef(env, values);
  if ((*env)->ExceptionCheck(env)) {
    ant_storage_error_t error = bridge_exception(env);
    bridge_release_env(bridge, attached);
    return error;
  }
  *exists = raw[0] != 0;
  *is_directory = raw[1] != 0;
  *size = raw[2] < 0 ? 0 : (uint64_t)raw[2];
  bridge_release_env(bridge, attached);
  return ANT_STORAGE_OK;
}

static ant_storage_error_t bridge_list(
  void *data,
  const char *path,
  ant_storage_list_visitor visitor,
  void *visitor_data
) {
  ant_android_storage_bridge_t *bridge = data;
  bool attached = false;
  JNIEnv *env = bridge_env(bridge, &attached);
  if (!env) return ANT_STORAGE_IO;
  jstring value = bridge_string(env, path);
  if (!value) { bridge_release_env(bridge, attached); return ANT_STORAGE_IO; }
  jobjectArray entries = (jobjectArray)(*env)->CallObjectMethod(
    env, bridge->object, bridge->list, value
  );
  (*env)->DeleteLocalRef(env, value);
  if ((*env)->ExceptionCheck(env)) {
    ant_storage_error_t error = bridge_exception(env);
    bridge_release_env(bridge, attached);
    return error;
  }
  if (!entries) { bridge_release_env(bridge, attached); return ANT_STORAGE_IO; }
  jsize count = (*env)->GetArrayLength(env, entries);
  ant_storage_error_t result = ANT_STORAGE_OK;
  for (jsize i = 0; i < count; i++) {
    jobject entry = (*env)->GetObjectArrayElement(env, entries, i);
    if (!entry) { result = ANT_STORAGE_IO; break; }
    jstring name = (jstring)(*env)->GetObjectField(env, entry, bridge->entry_name);
    jboolean directory = (*env)->GetBooleanField(
      env, entry, bridge->entry_directory
    );
    const char *utf = name ? (*env)->GetStringUTFChars(env, name, NULL) : NULL;
    if (!utf) result = ANT_STORAGE_IO;
    else {
      bool keep_going = visitor(utf, directory == JNI_TRUE, visitor_data);
      (*env)->ReleaseStringUTFChars(env, name, utf);
      if (!keep_going) {
        if (name) (*env)->DeleteLocalRef(env, name);
        (*env)->DeleteLocalRef(env, entry);
        break;
      }
    }
    if (name) (*env)->DeleteLocalRef(env, name);
    (*env)->DeleteLocalRef(env, entry);
    if (result != ANT_STORAGE_OK) break;
  }
  (*env)->DeleteLocalRef(env, entries);
  if ((*env)->ExceptionCheck(env)) result = bridge_exception(env);
  bridge_release_env(bridge, attached);
  return result;
}

static ant_storage_error_t bridge_call_path_bool(
  ant_android_storage_bridge_t *bridge,
  jmethodID method,
  const char *path,
  bool flag
) {
  bool attached = false;
  JNIEnv *env = bridge_env(bridge, &attached);
  if (!env) return ANT_STORAGE_IO;
  jstring value = bridge_string(env, path);
  if (!value) { bridge_release_env(bridge, attached); return ANT_STORAGE_IO; }
  jint result = (*env)->CallIntMethod(
    env, bridge->object, method, value, flag ? JNI_TRUE : JNI_FALSE
  );
  (*env)->DeleteLocalRef(env, value);
  ant_storage_error_t error = (*env)->ExceptionCheck(env)
    ? bridge_exception(env) : (ant_storage_error_t)result;
  bridge_release_env(bridge, attached);
  return error;
}

static ant_storage_error_t bridge_remove(
  void *data, const char *path, bool recursive
) {
  ant_android_storage_bridge_t *bridge = data;
  return bridge_call_path_bool(bridge, bridge->remove, path, recursive);
}

static ant_storage_error_t bridge_call_two_paths(
  ant_android_storage_bridge_t *bridge,
  jmethodID method,
  const char *from,
  const char *to
) {
  bool attached = false;
  JNIEnv *env = bridge_env(bridge, &attached);
  if (!env) return ANT_STORAGE_IO;
  jstring from_value = bridge_string(env, from);
  jstring to_value = bridge_string(env, to);
  if (!from_value || !to_value) {
    if (from_value) (*env)->DeleteLocalRef(env, from_value);
    if (to_value) (*env)->DeleteLocalRef(env, to_value);
    bridge_release_env(bridge, attached);
    return ANT_STORAGE_IO;
  }
  jint result = (*env)->CallIntMethod(
    env, bridge->object, method, from_value, to_value
  );
  (*env)->DeleteLocalRef(env, from_value);
  (*env)->DeleteLocalRef(env, to_value);
  ant_storage_error_t error = (*env)->ExceptionCheck(env)
    ? bridge_exception(env) : (ant_storage_error_t)result;
  bridge_release_env(bridge, attached);
  return error;
}

static ant_storage_error_t bridge_rename(
  void *data, const char *from, const char *to
) {
  ant_android_storage_bridge_t *bridge = data;
  return bridge_call_two_paths(bridge, bridge->rename, from, to);
}

static ant_storage_error_t bridge_copy(
  void *data, const char *from, const char *to
) {
  ant_android_storage_bridge_t *bridge = data;
  return bridge_call_two_paths(bridge, bridge->copy, from, to);
}

static ant_storage_error_t bridge_copy_from_file_path(
  void *data, const char *from, const char *to
) {
  ant_android_storage_bridge_t *bridge = data;
  return bridge_call_two_paths(bridge, bridge->copy_from_file_path, from, to);
}

static ant_storage_error_t bridge_atomic_replace(
  void *data, const char *from, const char *to
) {
  ant_android_storage_bridge_t *bridge = data;
  return bridge_call_two_paths(bridge, bridge->atomic_replace, from, to);
}

static ant_storage_error_t bridge_lock(
  void *data, const char *path, uint64_t *token
) {
  if (!token) return ANT_STORAGE_INVALID_ARGUMENT;
  ant_android_storage_bridge_t *bridge = data;
  bool attached = false;
  JNIEnv *env = bridge_env(bridge, &attached);
  if (!env) return ANT_STORAGE_IO;
  jstring value = bridge_string(env, path);
  if (!value) { bridge_release_env(bridge, attached); return ANT_STORAGE_IO; }
  jlong result = (*env)->CallLongMethod(env, bridge->object, bridge->lock, value);
  (*env)->DeleteLocalRef(env, value);
  if ((*env)->ExceptionCheck(env)) {
    ant_storage_error_t error = bridge_exception(env);
    bridge_release_env(bridge, attached);
    return error;
  }
  *token = (uint64_t)result;
  bridge_release_env(bridge, attached);
  return ANT_STORAGE_OK;
}

static void bridge_unlock(void *data, uint64_t token) {
  ant_android_storage_bridge_t *bridge = data;
  bool attached = false;
  JNIEnv *env = bridge_env(bridge, &attached);
  if (!env) return;
  (*env)->CallVoidMethod(env, bridge->object, bridge->unlock, (jlong)token);
  if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
  bridge_release_env(bridge, attached);
}

static void bridge_free_data(void *data, uint8_t *value) {
  (void)data;
  free(value);
}

ant_android_storage_bridge_t *ant_android_storage_bridge_create(
  JNIEnv *env,
  jobject context,
  const char *tree_uri
) {
  if (!env || !context || !tree_uri) return NULL;
  ant_android_storage_bridge_t *bridge = calloc(1, sizeof(*bridge));
  if (!bridge) return NULL;
  if ((*env)->GetJavaVM(env, &bridge->vm) != JNI_OK) {
    free(bridge);
    return NULL;
  }

  jclass local_class = (*env)->FindClass(env, "org/antjs/runtime/StorageBridge");
  jclass local_entry = (*env)->FindClass(
    env, "org/antjs/runtime/StorageBridge$Entry"
  );
  if (!local_class || !local_entry) goto fail;
  jmethodID constructor = (*env)->GetMethodID(
    env, local_class, "<init>", "(Landroid/content/Context;Ljava/lang/String;)V"
  );
  jstring uri = bridge_string(env, tree_uri);
  if (!constructor || !uri) goto fail;
  jobject local_object = (*env)->NewObject(
    env, local_class, constructor, context, uri
  );
  (*env)->DeleteLocalRef(env, uri);
  if (!local_object || (*env)->ExceptionCheck(env)) goto fail;

  bridge->object = (*env)->NewGlobalRef(env, local_object);
  bridge->bridge_class = (jclass)(*env)->NewGlobalRef(env, local_class);
  bridge->entry_class = (jclass)(*env)->NewGlobalRef(env, local_entry);
  (*env)->DeleteLocalRef(env, local_object);
  (*env)->DeleteLocalRef(env, local_class);
  (*env)->DeleteLocalRef(env, local_entry);
  if (!bridge->object || !bridge->bridge_class || !bridge->entry_class) goto fail;

#define METHOD(field, name, signature) \
  bridge->field = (*env)->GetMethodID(env, bridge->bridge_class, name, signature); \
  if (!bridge->field) goto fail
  METHOD(mkdirs, "mkdirs", "(Ljava/lang/String;)I");
  METHOD(read_file, "readFile", "(Ljava/lang/String;)[B");
  METHOD(write_file, "writeFile", "(Ljava/lang/String;[BZ)I");
  METHOD(stat, "stat", "(Ljava/lang/String;)[J");
  METHOD(list, "list", "(Ljava/lang/String;)[Lorg/antjs/runtime/StorageBridge$Entry;");
  METHOD(remove, "remove", "(Ljava/lang/String;Z)I");
  METHOD(rename, "rename", "(Ljava/lang/String;Ljava/lang/String;)I");
  METHOD(copy, "copy", "(Ljava/lang/String;Ljava/lang/String;)I");
  METHOD(copy_from_file_path, "copyFromFilePath", "(Ljava/lang/String;Ljava/lang/String;)I");
  METHOD(atomic_replace, "atomicReplace", "(Ljava/lang/String;Ljava/lang/String;)I");
  METHOD(probe, "probe", "()I");
  METHOD(lock, "lock", "(Ljava/lang/String;)J");
  METHOD(unlock, "unlock", "(J)V");
#undef METHOD
  bridge->entry_name = (*env)->GetFieldID(
    env, bridge->entry_class, "name", "Ljava/lang/String;"
  );
  bridge->entry_directory = (*env)->GetFieldID(
    env, bridge->entry_class, "directory", "Z"
  );
  if (!bridge->entry_name || !bridge->entry_directory) goto fail;

  jint probe_result = (*env)->CallIntMethod(env, bridge->object, bridge->probe);
  if ((*env)->ExceptionCheck(env)) goto fail;
  if (probe_result != ANT_STORAGE_OK) goto fail;

  bridge->callbacks = (ant_storage_bridge_t){
    .user_data = bridge,
    .mkdirs = bridge_mkdirs,
    .read_file = bridge_read_file,
    .write_file = bridge_write_file,
    .stat = bridge_stat,
    .list = bridge_list,
    .remove = bridge_remove,
    .rename = bridge_rename,
    .copy = bridge_copy,
    .copy_from_file_path = bridge_copy_from_file_path,
    .atomic_replace = bridge_atomic_replace,
    .lock = bridge_lock,
    .unlock = bridge_unlock,
    .free_data = bridge_free_data,
  };
  return bridge;

fail:
  if (local_class) (*env)->DeleteLocalRef(env, local_class);
  if (local_entry) (*env)->DeleteLocalRef(env, local_entry);
  ant_android_storage_bridge_destroy(bridge);
  return NULL;
}

void ant_android_storage_bridge_destroy(ant_android_storage_bridge_t *bridge) {
  if (!bridge) return;
  bool attached = false;
  JNIEnv *env = bridge_env(bridge, &attached);
  if (env) {
    if (bridge->object) (*env)->DeleteGlobalRef(env, bridge->object);
    if (bridge->bridge_class) (*env)->DeleteGlobalRef(env, bridge->bridge_class);
    if (bridge->entry_class) (*env)->DeleteGlobalRef(env, bridge->entry_class);
  }
  bridge_release_env(bridge, attached);
  free(bridge);
}

const ant_storage_bridge_t *ant_android_storage_bridge_callbacks(
  ant_android_storage_bridge_t *bridge
) {
  return bridge ? &bridge->callbacks : NULL;
}
