#include <jni.h>

#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <stdbool.h>
#include <stdint.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <android/log.h>

#include "ant.h"
#include "errors.h"
#include "esm/loader.h"
#include "internal.h"
#include "pkg.h"
#include "reactor.h"
#include "modules/child_process.h"
#include "modules/fetch.h"
#include "modules/fs.h"
#include "modules/readline.h"
#include "modules/timer.h"
#include "silver/vm.h"
#include "storage_bridge.h"

void ant_bootstrap_modules(ant_t *js);

typedef struct {
  ant_t *js;
  pthread_mutex_t mutex;
  bool mutex_initialized;
  pthread_t owner_thread;
  bool owner_thread_initialized;
  char *argv[2];
  JavaVM *vm;
  jobject android_context;
  ant_android_storage_bridge_t *project_bridge;
  ant_android_storage_bridge_t *cache_bridge;
} ant_android_runtime_t;

typedef enum {
  RUNTIME_STORAGE_FILE_PATH = 0,
  RUNTIME_STORAGE_SAF_TREE = 1,
} runtime_storage_kind_t;

typedef struct {
  runtime_storage_kind_t kind;
  const char *location;
} runtime_storage_location_t;

typedef struct {
  void *user_data;
  /* The complete callback table is intentionally opaque to the generic
   * runtime until the Android bridge is installed by the host. */
} runtime_storage_bridge_t;

/* Ant's module registry and several asynchronous module states are process
 * global. Keep the embedding contract explicit until those states become
 * isolate-owned in the core runtime. */
static pthread_mutex_t runtime_registry_mutex = PTHREAD_MUTEX_INITIALIZER;
static ant_android_runtime_t *active_runtime = NULL;
static bool runtime_created_once = false;

static void runtime_set_stack(ant_android_runtime_t *runtime, void *stack_base) {
  js_setstackbase(runtime->js, stack_base);
  size_t stack_size = os_thread_stack_size();
  if (stack_size > 0) js_setstacklimit(runtime->js, stack_size * 3 / 4);
}

static size_t runtime_utf8_encode(uint32_t codepoint, char *out) {
  if (codepoint <= 0x7f) {
    out[0] = (char)codepoint;
    return 1;
  }
  if (codepoint <= 0x7ff) {
    out[0] = (char)(0xc0 | (codepoint >> 6));
    out[1] = (char)(0x80 | (codepoint & 0x3f));
    return 2;
  }
  if (codepoint <= 0xffff) {
    out[0] = (char)(0xe0 | (codepoint >> 12));
    out[1] = (char)(0x80 | ((codepoint >> 6) & 0x3f));
    out[2] = (char)(0x80 | (codepoint & 0x3f));
    return 3;
  }
  out[0] = (char)(0xf0 | (codepoint >> 18));
  out[1] = (char)(0x80 | ((codepoint >> 12) & 0x3f));
  out[2] = (char)(0x80 | ((codepoint >> 6) & 0x3f));
  out[3] = (char)(0x80 | (codepoint & 0x3f));
  return 4;
}

static char *runtime_copy_java_source(
  JNIEnv *env, jstring source, size_t *length_out
) {
  jsize length = (*env)->GetStringLength(env, source);
  const jchar *chars = (*env)->GetStringChars(env, source, NULL);
  if (!chars) return NULL;

  size_t capacity = (size_t)length * 3u;
  char *result = (char *)malloc(capacity + 1u);
  if (!result) {
    (*env)->ReleaseStringChars(env, source, chars);
    return NULL;
  }

  size_t offset = 0;
  for (jsize i = 0; i < length; i++) {
    uint32_t codepoint = chars[i];
    if (codepoint >= 0xd800 && codepoint <= 0xdbff && i + 1 < length) {
      uint32_t low = chars[i + 1];
      if (low >= 0xdc00 && low <= 0xdfff) {
        codepoint = 0x10000u + ((codepoint - 0xd800u) << 10) + (low - 0xdc00u);
        i++;
      }
    }
    offset += runtime_utf8_encode(codepoint, result + offset);
  }
  result[offset] = '\0';
  (*env)->ReleaseStringChars(env, source, chars);
  if (length_out) *length_out = offset;
  return result;
}

static uint32_t runtime_utf8_decode(
  const unsigned char *bytes, size_t length, size_t *offset
) {
  size_t pos = *offset;
  unsigned char first = bytes[pos++];
  uint32_t codepoint = 0xfffdu;
  size_t needed = 0;

  if (first < 0x80) {
    *offset = pos;
    return first;
  }
  if ((first & 0xe0) == 0xc0) {
    codepoint = first & 0x1fu;
    needed = 1;
  } else if ((first & 0xf0) == 0xe0) {
    codepoint = first & 0x0fu;
    needed = 2;
  } else if ((first & 0xf8) == 0xf0) {
    codepoint = first & 0x07u;
    needed = 3;
  } else {
    *offset = pos;
    return codepoint;
  }

  if (pos + needed > length) {
    *offset = pos;
    return codepoint;
  }
  for (size_t i = 0; i < needed; i++) {
    unsigned char byte = bytes[pos + i];
    if ((byte & 0xc0) != 0x80) {
      *offset = pos;
      return 0xfffdu;
    }
    codepoint = (codepoint << 6) | (byte & 0x3fu);
  }
  pos += needed;
  *offset = pos;
  return codepoint;
}

static jstring runtime_new_java_string(
  JNIEnv *env, const char *bytes, size_t length
) {
  if (!bytes) bytes = "";

  size_t units = 0;
  for (size_t offset = 0; offset < length;) {
    uint32_t codepoint = runtime_utf8_decode(
      (const unsigned char *)bytes, length, &offset
    );
    size_t add = codepoint > 0xffffu ? 2u : 1u;
    if (units > (size_t)INT_MAX - add) {
      jclass error = (*env)->FindClass(env, "java/lang/OutOfMemoryError");
      if (error) (*env)->ThrowNew(env, error, "Ant result is too large");
      return NULL;
    }
    units += add;
  }

  jchar *chars = units ? (jchar *)malloc(units * sizeof(*chars)) : NULL;
  if (units && !chars) return NULL;

  size_t out = 0;
  for (size_t offset = 0; offset < length;) {
    uint32_t codepoint = runtime_utf8_decode(
      (const unsigned char *)bytes, length, &offset
    );
    if (codepoint <= 0xffffu) {
      chars[out++] = (jchar)codepoint;
    } else {
      codepoint -= 0x10000u;
      chars[out++] = (jchar)(0xd800u | (codepoint >> 10));
      chars[out++] = (jchar)(0xdc00u | (codepoint & 0x3ffu));
    }
  }

  jstring result = (*env)->NewString(env, chars, (jsize)units);
  free(chars);
  return result;
}

static void throw_runtime_exception_text(
  JNIEnv *env, const char *message, size_t length
) {
  jclass cls = (*env)->FindClass(env, "org/antjs/runtime/AntRuntime$AntRuntimeException");
  if (!cls) {
    (*env)->ExceptionClear(env);
    cls = (*env)->FindClass(env, "java/lang/RuntimeException");
  }
  if (!cls) return;

  jstring text = runtime_new_java_string(
    env, message ? message : "Ant runtime error",
    message ? length : strlen("Ant runtime error")
  );
  if (!text) return;

  jmethodID ctor = (*env)->GetMethodID(env, cls, "<init>", "(Ljava/lang/String;)V");
  if (!ctor) return;
  jobject exception = (*env)->NewObject(env, cls, ctor, text);
  if (exception) (*env)->Throw(env, (jthrowable)exception);
}

static void throw_runtime_exception(JNIEnv *env, const char *message) {
  throw_runtime_exception_text(env, message, message ? strlen(message) : 0);
}

static void throw_runtime_exception_value(
  JNIEnv *env, ant_t *js, ant_value_t value
) {
  char stack_buf[256];
  js_cstr_t text = js_to_cstr(js, value, stack_buf, sizeof(stack_buf));
  throw_runtime_exception_text(env, text.ptr, text.len);
  if (text.needs_free) free((void *)text.ptr);
}

static ant_android_runtime_t *runtime_from_handle(jlong handle) {
  if (handle == 0) return NULL;
  return (ant_android_runtime_t *)(uintptr_t)handle;
}

typedef struct {
  char **items;
  uint32_t count;
} runtime_string_array_t;

static void runtime_string_array_free(runtime_string_array_t *array) {
  if (!array) return;
  for (uint32_t i = 0; i < array->count; i++) free(array->items[i]);
  free(array->items);
  array->items = NULL;
  array->count = 0;
}

static char *runtime_copy_java_cstring(
  JNIEnv *env, jstring value, const char *label
) {
  if (!value) {
    char message[128];
    snprintf(message, sizeof(message), "%s must not be null", label);
    throw_runtime_exception(env, message);
    return NULL;
  }
  size_t length = 0;
  char *text = runtime_copy_java_source(env, value, &length);
  if (!text) return NULL;
  if (memchr(text, '\0', length)) {
    char message[128];
    snprintf(message, sizeof(message), "%s contains an embedded NUL", label);
    free(text);
    throw_runtime_exception(env, message);
    return NULL;
  }
  return text;
}

static bool runtime_copy_java_string_array(
  JNIEnv *env, jobjectArray values, const char *label,
  runtime_string_array_t *out
) {
  memset(out, 0, sizeof(*out));
  if (!values) return true;
  jsize length = (*env)->GetArrayLength(env, values);
  if (length < 0 || (uint64_t)length > UINT32_MAX) {
    throw_runtime_exception(env, "Too many package names");
    return false;
  }
  if (length == 0) return true;
  out->items = calloc((size_t)length, sizeof(*out->items));
  if (!out->items) return false;
  out->count = (uint32_t)length;
  for (jsize i = 0; i < length; i++) {
    jstring value = (jstring)(*env)->GetObjectArrayElement(env, values, i);
    if ((*env)->ExceptionCheck(env)) {
      runtime_string_array_free(out);
      return false;
    }
    out->items[i] = runtime_copy_java_cstring(env, value, label);
    if (value) (*env)->DeleteLocalRef(env, value);
    if (!out->items[i]) {
      runtime_string_array_free(out);
      return false;
    }
  }
  return true;
}

static bool runtime_path_is_absolute(const char *path) {
  return path && path[0] == '/';
}

static char *runtime_join_path(const char *base, const char *leaf) {
  size_t base_len = strlen(base);
  size_t leaf_len = strlen(leaf);
  bool separator = base_len > 0 && base[base_len - 1] != '/';
  if (base_len > SIZE_MAX - leaf_len - (separator ? 2u : 1u)) return NULL;
  size_t length = base_len + leaf_len + (separator ? 1u : 0u);
  char *path = malloc(length + 1u);
  if (!path) return NULL;
  memcpy(path, base, base_len);
  size_t offset = base_len;
  if (separator) path[offset++] = '/';
  memcpy(path + offset, leaf, leaf_len);
  path[length] = '\0';
  return path;
}

static bool runtime_ensure_directory(const char *path) {
  if (!runtime_path_is_absolute(path)) return false;
  char *copy = strdup(path);
  if (!copy) return false;
  size_t length = strlen(copy);
  while (length > 1 && copy[length - 1] == '/') copy[--length] = '\0';
  for (char *cursor = copy + 1; *cursor; cursor++) {
    if (*cursor != '/') continue;
    *cursor = '\0';
    if (mkdir(copy, 0700) != 0 && errno != EEXIST) {
      free(copy);
      return false;
    }
    *cursor = '/';
  }
  if (mkdir(copy, 0700) != 0 && errno != EEXIST) {
    free(copy);
    return false;
  }
  struct stat info;
  bool valid = stat(copy, &info) == 0 && S_ISDIR(info.st_mode);
  free(copy);
  return valid;
}

static bool runtime_ensure_package_json(const char *path) {
  struct stat info;
  if (stat(path, &info) == 0) return S_ISREG(info.st_mode);
  if (errno != ENOENT) return false;

  int fd = open(path, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
  if (fd < 0) {
    if (errno == EEXIST) return stat(path, &info) == 0 && S_ISREG(info.st_mode);
    return false;
  }
  static const char content[] = "{\"private\":true}\n";
  size_t offset = 0;
  bool ok = true;
  while (offset < sizeof(content) - 1u) {
    ssize_t written = write(fd, content + offset, sizeof(content) - 1u - offset);
    if (written < 0) {
      if (errno == EINTR) continue;
      ok = false;
      break;
    }
    if (written == 0) {
      ok = false;
      break;
    }
    offset += (size_t)written;
  }
  if (close(fd) != 0) ok = false;
  if (!ok) unlink(path);
  return ok;
}

static char *runtime_normalize_registry(const char *value) {
  const char *start = value;
  while (*start && isspace((unsigned char)*start)) start++;
  if (strncmp(start, "https://", 8) == 0) start += 8;
  else if (strstr(start, "://")) return NULL;

  const char *end = start + strlen(start);
  while (end > start && (isspace((unsigned char)end[-1]) || end[-1] == '/')) end--;
  if (end == start) return NULL;
  for (const char *cursor = start; cursor < end; cursor++) {
    if (*cursor == '/' || *cursor == '?' || *cursor == '#' || isspace((unsigned char)*cursor)) {
      return NULL;
    }
  }
  size_t length = (size_t)(end - start);
  char *host = malloc(length + 1u);
  if (!host) return NULL;
  memcpy(host, start, length);
  host[length] = '\0';
  return host;
}

static void runtime_format_pkg_error(
  char *output, size_t capacity, const char *operation,
  pkg_context_t *ctx, pkg_error_t error
) {
  const char *detail = ctx ? pkg_error_string(ctx) : "package manager initialization failed";
  snprintf(output, capacity, "%s failed (%d): %s", operation, (int)error,
           detail ? detail : "unknown package-manager error");
}

static bool runtime_check_thread(JNIEnv *env, ant_android_runtime_t *runtime) {
  if (!runtime || !runtime->owner_thread_initialized ||
      pthread_equal(runtime->owner_thread, pthread_self())) return true;
  throw_runtime_exception(
    env, "AntRuntime must be used from its creating thread"
  );
  return false;
}

static void runtime_count_active_handle(uv_handle_t *handle, void *arg) {
  bool *active = (bool *)arg;
  if (!uv_is_closing(handle)) *active = true;
}

static bool runtime_has_pending_work(void) {
  bool active_handle = false;
  uv_walk(uv_default_loop(), runtime_count_active_handle, &active_handle);
  return active_handle || uv_loop_alive(uv_default_loop()) ||
    has_pending_timers() || has_pending_microtasks() || has_pending_immediates() ||
    has_pending_fetches() || has_pending_fs_ops() ||
    has_pending_child_processes() || has_active_readline_interfaces();
}

static void runtime_destroy(ant_android_runtime_t *runtime) {
  if (!runtime) return;
  if (runtime->js) ant_runtime_clear_storage(runtime->js);
  if (runtime->project_bridge) {
    ant_android_storage_bridge_destroy(runtime->project_bridge);
    runtime->project_bridge = NULL;
  }
  if (runtime->cache_bridge) {
    ant_android_storage_bridge_destroy(runtime->cache_bridge);
    runtime->cache_bridge = NULL;
  }
  if (runtime->android_context && runtime->vm) {
    JNIEnv *env = NULL;
    bool attached = false;
    if ((*runtime->vm)->GetEnv(runtime->vm, (void **)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
      if ((*runtime->vm)->AttachCurrentThread(runtime->vm, &env, NULL) == JNI_OK)
        attached = true;
    }
    if (env) (*env)->DeleteGlobalRef(env, runtime->android_context);
    if (attached) (*runtime->vm)->DetachCurrentThread(runtime->vm);
  }
  if (runtime->js) js_destroy(runtime->js);
  if (runtime->mutex_initialized) pthread_mutex_destroy(&runtime->mutex);
  free(runtime);
}

static void runtime_clear_project_storage(ant_android_runtime_t *runtime) {
  if (!runtime) return;
  if (runtime->js) ant_runtime_clear_storage(runtime->js);
  if (runtime->project_bridge) {
    ant_android_storage_bridge_destroy(runtime->project_bridge);
    runtime->project_bridge = NULL;
  }
}

static bool runtime_set_project_storage(
  JNIEnv *env,
  ant_android_runtime_t *runtime,
  jint kind,
  const char *location
) {
  if (!runtime || !runtime->js || !location || !location[0]) return false;
  runtime_clear_project_storage(runtime);

  ant_storage_location_t storage_location = {
    .kind = kind == 1 ? ANT_STORAGE_SAF_TREE : ANT_STORAGE_FILE_PATH,
    .location = location,
  };
  const ant_storage_bridge_t *callbacks = NULL;
  if (kind == 1) {
    if (!runtime->android_context) {
      throw_runtime_exception(env, "SAF_TREE requires an Android Context");
      return false;
    }
    runtime->project_bridge = ant_android_storage_bridge_create(
      env, runtime->android_context, location
    );
    if (!runtime->project_bridge) {
      if (!(*env)->ExceptionCheck(env)) {
        throw_runtime_exception(env, "Unable to create SAF Storage Bridge; permission may be revoked");
      }
      return false;
    }
    callbacks = ant_android_storage_bridge_callbacks(runtime->project_bridge);
  }
  if (!ant_runtime_set_storage(runtime->js, storage_location, callbacks)) {
    runtime_clear_project_storage(runtime);
    throw_runtime_exception(env, "Unable to register Ant storage location");
    return false;
  }
  return true;
}

static bool runtime_take_exception(
  JNIEnv *env, ant_android_runtime_t *runtime, ant_value_t result,
  jstring *output
) {
  bool had_throw = runtime->js->thrown_exists;
  result = js_take_thrown(runtime->js, result);
  /* An Error object is still a valid JavaScript value when it is returned
   * normally (for example, `new Error("x")`). Only the isolate's throw state
   * represents an exception crossing the Java/Kotlin boundary. */
  bool is_error = had_throw;

  if (is_error) {
    throw_runtime_exception_value(env, runtime->js, result);
    return true;
  }

  if (output) {
    char stack_buf[256];
    js_cstr_t text = js_to_cstr(
      runtime->js, result, stack_buf, sizeof(stack_buf)
    );
    *output = runtime_new_java_string(env, text.ptr, text.len);
    if (text.needs_free) free((void *)text.ptr);
  }
  return false;
}

JNIEXPORT jlong JNICALL
Java_org_antjs_runtime_AntRuntime_nativeCreate(JNIEnv *env, jclass clazz, jobject context) {
  (void)clazz;

  pthread_mutex_lock(&runtime_registry_mutex);
  if (runtime_created_once) {
    pthread_mutex_unlock(&runtime_registry_mutex);
    throw_runtime_exception(
      env, "Only one AntRuntime may be created in an Android process"
    );
    return 0;
  }
  runtime_created_once = true;

  ant_android_runtime_t *runtime = calloc(1, sizeof(*runtime));
  if (!runtime) {
    pthread_mutex_unlock(&runtime_registry_mutex);
    return 0;
  }

  if (pthread_mutex_init(&runtime->mutex, NULL) != 0) {
    free(runtime);
    pthread_mutex_unlock(&runtime_registry_mutex);
    return 0;
  }
  runtime->mutex_initialized = true;
  runtime->owner_thread = pthread_self();
  runtime->owner_thread_initialized = true;
  if ((*env)->GetJavaVM(env, &runtime->vm) != JNI_OK) {
    runtime_destroy(runtime);
    pthread_mutex_unlock(&runtime_registry_mutex);
    return 0;
  }
  if (context) {
    runtime->android_context = (*env)->NewGlobalRef(env, context);
    if (!runtime->android_context) {
      runtime_destroy(runtime);
      pthread_mutex_unlock(&runtime_registry_mutex);
      return 0;
    }
  }

  runtime->js = ant_create();
  if (!runtime->js) {
    runtime_destroy(runtime);
    pthread_mutex_unlock(&runtime_registry_mutex);
    return 0;
  }

  volatile char stack_base;
  runtime_set_stack(runtime, (void *)&stack_base);
  runtime->argv[0] = (char *)"ant-android";
  runtime->argv[1] = NULL;
  ant_runtime_init(runtime->js, 1, runtime->argv, NULL);
  ant_bootstrap_modules(runtime->js);
  if (runtime->js->thrown_exists) {
    (void)js_take_thrown(runtime->js, js_mkundef());
    runtime_destroy(runtime);
    pthread_mutex_unlock(&runtime_registry_mutex);
    return 0;
  }
  active_runtime = runtime;
  pthread_mutex_unlock(&runtime_registry_mutex);
  return (jlong)(uintptr_t)runtime;
}

JNIEXPORT jstring JNICALL
Java_org_antjs_runtime_AntRuntime_nativeEvaluate(
  JNIEnv *env, jclass clazz, jlong handle, jstring source
) {
  (void)clazz;
  ant_android_runtime_t *runtime = runtime_from_handle(handle);
  if (!runtime || !runtime->js) {
    throw_runtime_exception(env, "Ant runtime is closed");
    return NULL;
  }
  if (!runtime_check_thread(env, runtime)) return NULL;

  if (!source) {
    throw_runtime_exception(env, "source must not be null");
    return NULL;
  }

  size_t code_len = 0;
  char *code = runtime_copy_java_source(env, source, &code_len);
  if (!code) return NULL;

  pthread_mutex_lock(&runtime->mutex);
  volatile char stack_base;
  runtime_set_stack(runtime, (void *)&stack_base);
  (void)js_take_thrown(runtime->js, js_mkundef());
  js_eval_result_t evaluation = js_eval_bytecode_repl(
    runtime->js, code, code_len
  );
  ant_value_t result = evaluation.value;

  if (evaluation.kind == JS_EVAL_ASYNC_ENTRY) {
    ant_value_t settled = js_mkundef();
    js_reactor_await_status_t status = js_reactor_blocking_await_promise(
      runtime->js, result, &settled, NULL, NULL
    );
    js_eval_async_entry_release(evaluation.async_entry);
    if (status == JS_REACTOR_AWAIT_REJECTED) result = js_throw(runtime->js, settled);
    else if (status == JS_REACTOR_AWAIT_FULFILLED) result = settled;
    else result = js_mkerr(runtime->js, "async evaluation did not settle");
  } else {
    js_eval_async_entry_release(evaluation.async_entry);
    js_reactor_pump_repl_nowait(runtime->js);
  }

  jstring output = NULL;
  bool failed = runtime_take_exception(env, runtime, result, &output);
  pthread_mutex_unlock(&runtime->mutex);
  free(code);
  if (failed) return NULL;
  return output;
}

JNIEXPORT jstring JNICALL
Java_org_antjs_runtime_AntRuntime_nativeEvaluateFile(
  JNIEnv *env, jclass clazz, jlong handle, jstring entry_file
) {
  (void)clazz;
  ant_android_runtime_t *runtime = runtime_from_handle(handle);
  if (!runtime || !runtime->js) {
    throw_runtime_exception(env, "Ant runtime is closed");
    return NULL;
  }
  if (!runtime_check_thread(env, runtime)) return NULL;

  char *path = runtime_copy_java_cstring(env, entry_file, "entryFile");
  if (!path) return NULL;
  struct stat info;
  if (!runtime_path_is_absolute(path) || stat(path, &info) != 0 || !S_ISREG(info.st_mode)) {
    free(path);
    throw_runtime_exception(env, "entryFile must be an absolute existing regular file");
    return NULL;
  }

  pthread_mutex_lock(&runtime->mutex);
  runtime_clear_project_storage(runtime);
  volatile char stack_base;
  runtime_set_stack(runtime, (void *)&stack_base);
  (void)js_take_thrown(runtime->js, js_mkundef());
  ant_value_t result = js_esm_import_sync_cstr(runtime->js, path, strlen(path));
  js_reactor_pump_repl_nowait(runtime->js);

  jstring output = NULL;
  bool failed = runtime_take_exception(env, runtime, result, &output);
  pthread_mutex_unlock(&runtime->mutex);
  free(path);
  if (failed) return NULL;
  return output;
}

JNIEXPORT jstring JNICALL
Java_org_antjs_runtime_AntRuntime_nativeEvaluateLocation(
  JNIEnv *env, jclass clazz, jlong handle, jint project_kind,
  jstring project_location, jstring entry_file
) {
  (void)clazz;
  ant_android_runtime_t *runtime = runtime_from_handle(handle);
  if (!runtime || !runtime->js) {
    throw_runtime_exception(env, "Ant runtime is closed");
    return NULL;
  }
  if (!runtime_check_thread(env, runtime)) return NULL;
  char *project = runtime_copy_java_cstring(env, project_location, "projectLocation");
  char *entry = runtime_copy_java_cstring(env, entry_file, "entryFile");
  if (!project || !entry) {
    free(project);
    free(entry);
    return NULL;
  }
  if ((project_kind == 0 && !runtime_path_is_absolute(project)) ||
      (project_kind != 0 && project_kind != 1) ||
      !ant_storage_path_is_safe_relative(entry)) {
    free(project);
    free(entry);
    throw_runtime_exception(env, "storage location or relative entry is invalid");
    return NULL;
  }

  pthread_mutex_lock(&runtime->mutex);
  if (!runtime_set_project_storage(env, runtime, project_kind, project)) {
    pthread_mutex_unlock(&runtime->mutex);
    free(project);
    free(entry);
    return NULL;
  }

  ant_storage_context_t *project_storage = (ant_storage_context_t *)
    ant_runtime_storage(runtime->js);
  char *path = ant_storage_virtual_path(project_storage, entry);
  if (!path) {
    free(project);
    free(entry);
    pthread_mutex_unlock(&runtime->mutex);
    throw_runtime_exception(env, "Unable to allocate project entry path");
    return NULL;
  }
  __android_log_print(
    ANDROID_LOG_INFO, "AntRuntime", "evaluate path kind=%d root=%s entry=%s resolved=%s",
    project_kind, ant_storage_context_location(project_storage),
    entry ? entry : "", path
  );
  uint64_t size = 0;
  bool is_directory = false;
  bool exists = false;
  ant_storage_error_t stat_error = ant_storage_stat(
    /* `entry` is already constrained to the selected root and is the
     * canonical backend-relative form. Use it for backend validation; the
     * generated path below remains the module identity used by the loader. */
    project_storage, entry, &size, &is_directory, &exists
  );
  __android_log_print(
    ANDROID_LOG_INFO, "AntRuntime", "evaluate stat rc=%d exists=%d dir=%d size=%llu",
    (int)stat_error, exists ? 1 : 0, is_directory ? 1 : 0,
    (unsigned long long)size
  );
  if (stat_error != ANT_STORAGE_OK || !exists || is_directory) {
    free(path);
    free(project);
    free(entry);
    pthread_mutex_unlock(&runtime->mutex);
    throw_runtime_exception(env, stat_error == ANT_STORAGE_PERMISSION
      ? "Storage permission was revoked"
      : "entryFile must be an existing regular file");
    return NULL;
  }

  volatile char stack_base;
  runtime_set_stack(runtime, (void *)&stack_base);
  (void)js_take_thrown(runtime->js, js_mkundef());
  ant_value_t result = js_esm_import_sync_cstr(runtime->js, path, strlen(path));
  js_reactor_pump_repl_nowait(runtime->js);
  jstring output = NULL;
  bool failed = runtime_take_exception(env, runtime, result, &output);
  pthread_mutex_unlock(&runtime->mutex);
  free(path);
  free(project);
  free(entry);
  if (failed) return NULL;
  return output;
}

JNIEXPORT void JNICALL
Java_org_antjs_runtime_AntRuntime_nativePump(
  JNIEnv *env, jclass clazz, jlong handle
) {
  (void)env;
  (void)clazz;
  ant_android_runtime_t *runtime = runtime_from_handle(handle);
  if (!runtime || !runtime->js) return;
  if (!runtime_check_thread(env, runtime)) return;
  pthread_mutex_lock(&runtime->mutex);
  volatile char stack_base;
  runtime_set_stack(runtime, (void *)&stack_base);
  js_reactor_pump_repl_nowait(runtime->js);
  bool failed = runtime_take_exception(env, runtime, js_mkundef(), NULL);
  pthread_mutex_unlock(&runtime->mutex);
  if (failed) return;
}

static jstring runtime_install_result_json(
  JNIEnv *env, const pkg_install_result_t *result, uint32_t lifecycle_count
) {
  char json[512];
  int length = snprintf(
    json, sizeof(json),
    "{\"packageCount\":%" PRIu32 ",\"cacheHits\":%" PRIu32
    ",\"cacheMisses\":%" PRIu32 ",\"filesLinked\":%" PRIu32
    ",\"filesCopied\":%" PRIu32 ",\"packagesInstalled\":%" PRIu32
    ",\"packagesSkipped\":%" PRIu32 ",\"lifecycleBuilds\":%" PRIu32
    ",\"elapsedMs\":%" PRIu64 ",\"lifecycleScriptCount\":%" PRIu32 "}",
    result->package_count, result->cache_hits, result->cache_misses,
    result->files_linked, result->files_copied, result->packages_installed,
    result->packages_skipped, result->lifecycle_builds, result->elapsed_ms,
    lifecycle_count
  );
  if (length < 0 || (size_t)length >= sizeof(json)) {
    throw_runtime_exception(env, "Package-manager result is too large");
    return NULL;
  }
  return runtime_new_java_string(env, json, (size_t)length);
}

static bool runtime_prepare_package_paths(
  JNIEnv *env, const char *project, char **package_json,
  char **lockfile, char **node_modules, char **cache_dir
) {
  char *ant_dir = runtime_join_path(project, ".ant");
  if (!ant_dir) return false;
  *cache_dir = runtime_join_path(ant_dir, "pkg-cache");
  free(ant_dir);
  *package_json = runtime_join_path(project, "package.json");
  *lockfile = runtime_join_path(project, "ant.lockb");
  *node_modules = runtime_join_path(project, "node_modules");
  if (!*cache_dir || !*package_json || !*lockfile || !*node_modules) return false;
  if (!runtime_ensure_directory(project) || !runtime_ensure_directory(*cache_dir) ||
      !runtime_ensure_package_json(*package_json) || !runtime_ensure_directory(*node_modules)) {
    char message[256];
    snprintf(message, sizeof(message), "Unable to prepare npm project directories under %s", project);
    throw_runtime_exception(env, message);
    return false;
  }
  return true;
}

static void runtime_free_package_paths(
  char *package_json, char *lockfile, char *node_modules, char *cache_dir
) {
  free(package_json);
  free(lockfile);
  free(node_modules);
  free(cache_dir);
}

JNIEXPORT jstring JNICALL
Java_org_antjs_runtime_AntRuntime_nativeInstall(
  JNIEnv *env, jclass clazz, jlong handle, jint project_kind,
  jstring project_directory, jobjectArray package_specs, jstring registry_url,
  jstring cache_directory, jint cache_kind, jint max_connections, jboolean verbose, jboolean force,
  jboolean run_lifecycle_scripts
) {
  (void)clazz;
  ant_android_runtime_t *runtime = runtime_from_handle(handle);
  if (!runtime || !runtime->js) {
    throw_runtime_exception(env, "Ant runtime is closed");
    return NULL;
  }
  if (!runtime_check_thread(env, runtime)) return NULL;
  if (max_connections <= 0 || max_connections > 6) {
    throw_runtime_exception(env, "maxConnections must be between 1 and 6");
    return NULL;
  }
  if (project_kind != 0 && project_kind != 1) {
    throw_runtime_exception(env, "project storage kind is invalid");
    return NULL;
  }

  char *project = runtime_copy_java_cstring(env, project_directory, "projectDirectory");
  if (!project) return NULL;
  if ((project_kind == 0 && !runtime_path_is_absolute(project)) ||
      (project_kind == 1 && strncmp(project, "content://", 10) != 0)) {
    free(project);
    throw_runtime_exception(env, "project storage location is invalid");
    return NULL;
  }
  char *registry_value = runtime_copy_java_cstring(env, registry_url, "registryUrl");
  if (!registry_value) {
    free(project);
    return NULL;
  }
  char *registry = runtime_normalize_registry(registry_value);
  free(registry_value);
  if (!registry) {
    free(project);
    throw_runtime_exception(env, "registryUrl must be an HTTPS registry host");
    return NULL;
  }

  runtime_string_array_t specs;
  if (!runtime_copy_java_string_array(env, package_specs, "packageSpec", &specs)) {
    free(project);
    free(registry);
    return NULL;
  }

  /* Package installation is deliberately expressed only in terms of the
   * Storage API.  The four combinations (FILE_PATH/SAF_TREE project and
   * FILE_PATH/SAF_TREE cache) therefore share exactly the same resolver,
   * extractor and linker.  In particular, a content:// tree is never
   * converted to a guessed filesystem path and no private workspace is
   * created as a fallback. */
  char *requested_cache = NULL;
  ant_android_storage_bridge_t *project_bridge = NULL;
  ant_android_storage_bridge_t *cache_bridge = NULL;
  ant_storage_context_t *project_storage = NULL;
  ant_storage_context_t *cache_storage = NULL;
  bool cache_bridge_is_project = false;
  pkg_context_t *ctx = NULL;
  pkg_error_t error = PKG_CACHE_ERROR;
  pkg_install_result_t result = {0};
  uint32_t lifecycle_count = 0;
  char error_text[512] = "npm install failed";
  const char *package_json = "package.json";
  const char *lockfile = "ant.lockb";
  const char *node_modules = "node_modules";

  if (cache_directory) {
    requested_cache = runtime_copy_java_cstring(env, cache_directory, "cacheDirectory");
    if (!requested_cache) goto install_cleanup;
    if ((cache_kind != 0 && cache_kind != 1) ||
        (cache_kind == 0 && !runtime_path_is_absolute(requested_cache)) ||
        (cache_kind == 1 && strncmp(requested_cache, "content://", 10) != 0)) {
      throw_runtime_exception(env, "cache storage location is invalid");
      goto install_cleanup;
    }
  }

  if (project_kind == 1) {
    if (!runtime->android_context) {
      throw_runtime_exception(env, "SAF_TREE requires an Android Context");
      goto install_cleanup;
    }
    project_bridge = ant_android_storage_bridge_create(
      env, runtime->android_context, project
    );
    if (!project_bridge) goto install_cleanup;
  }
  project_storage = ant_storage_context_create(
    (ant_storage_location_t){
      .kind = project_kind == 1 ? ANT_STORAGE_SAF_TREE : ANT_STORAGE_FILE_PATH,
      .location = project,
    },
    project_bridge ? ant_android_storage_bridge_callbacks(project_bridge) : NULL
  );
  if (!project_storage) {
    throw_runtime_exception(env, "Unable to create project Storage context");
    goto install_cleanup;
  }

  if (requested_cache) {
    if (cache_kind == 1) {
      if (!runtime->android_context) {
        throw_runtime_exception(env, "SAF_TREE requires an Android Context");
        goto install_cleanup;
      }
      cache_bridge = ant_android_storage_bridge_create(
        env, runtime->android_context, requested_cache
      );
      if (!cache_bridge) goto install_cleanup;
    }
    cache_storage = ant_storage_context_create(
      (ant_storage_location_t){
        .kind = cache_kind == 1 ? ANT_STORAGE_SAF_TREE : ANT_STORAGE_FILE_PATH,
        .location = requested_cache,
      },
      cache_bridge ? ant_android_storage_bridge_callbacks(cache_bridge) : NULL
    );
    if (!cache_storage) {
      throw_runtime_exception(env, "Unable to create dependency cache Storage context");
      goto install_cleanup;
    }
  } else {
    /* The default cache is a child view in the selected project tree. It is
     * still user-visible project storage, never an app-private mirror. */
    cache_storage = ant_storage_context_create_child(project_storage, ".ant/pkg-cache");
    if (!cache_storage) {
      throw_runtime_exception(env, "Unable to create project dependency cache context");
      goto install_cleanup;
    }
    cache_bridge_is_project = true;
  }

  if (ant_storage_mkdirs(project_storage, "") != ANT_STORAGE_OK ||
      ant_storage_mkdirs(project_storage, node_modules) != ANT_STORAGE_OK ||
      ant_storage_mkdirs(cache_storage, "") != ANT_STORAGE_OK) {
    throw_runtime_exception(env, "Selected storage location is not writable");
    goto install_cleanup;
  }
  {
    uint8_t *package_data = NULL;
    size_t package_size = 0;
    ant_storage_error_t read_result = ant_storage_read_file(
      project_storage, package_json, &package_data, &package_size
    );
    if (read_result == ANT_STORAGE_NOT_FOUND) {
      static const uint8_t empty_package[] = "{}\n";
      if (ant_storage_write_file(
            project_storage, package_json, empty_package,
            sizeof(empty_package) - 1u, true
          ) != ANT_STORAGE_OK) {
        throw_runtime_exception(env, "Unable to create package.json in project storage");
        goto install_cleanup;
      }
    } else if (read_result != ANT_STORAGE_OK) {
      throw_runtime_exception(env, read_result == ANT_STORAGE_PERMISSION
        ? "Project storage permission was revoked"
        : "Unable to read package.json from project storage");
      goto install_cleanup;
    }
    ant_storage_free_data(project_storage, package_data);
    (void)package_size;
  }

  pkg_options_t options = {
    .cache_dir = NULL,
    .cache_location = {
      .kind = ant_storage_context_kind(cache_storage),
      .location = ant_storage_context_location(cache_storage),
    },
    .cache_bridge = ant_storage_context_kind(cache_storage) == ANT_STORAGE_SAF_TREE
      ? ant_android_storage_bridge_callbacks(cache_bridge_is_project ? project_bridge : cache_bridge)
      : NULL,
    .project_storage = project_storage,
    .cache_storage = cache_storage,
    .registry_url = registry,
    .max_connections = (uint32_t)max_connections,
    .progress_callback = NULL,
    .user_data = NULL,
    .verbose = verbose == JNI_TRUE,
    .force = force == JNI_TRUE,
    .run_lifecycle_scripts = run_lifecycle_scripts == JNI_TRUE,
  };

  pthread_mutex_lock(&runtime->mutex);
  ctx = pkg_init(&options);
  error = ctx ? PKG_OK : PKG_CACHE_ERROR;
  if (ctx && specs.count > 0) {
    error = pkg_add_many(ctx, package_json,
      (const char *const *)specs.items, specs.count, false);
  }
  if (ctx && error == PKG_OK) {
    error = pkg_resolve_and_install(ctx, package_json, lockfile, node_modules);
  }
  if (ctx && error == PKG_OK) {
    error = pkg_get_install_result(ctx, &result);
    /* The portable Storage installer deliberately does not expose lifecycle
     * discovery through the POSIX-only compatibility API. It either rejects
     * lifecycle execution explicitly or keeps it disabled, so no operation
     * can escape the selected Storage backend. */
  }
  if (error != PKG_OK) {
    runtime_format_pkg_error(error_text, sizeof(error_text), "npm install", ctx, error);
  }
  if (ctx) {
    pkg_free(ctx);
    ctx = NULL;
  }
  pthread_mutex_unlock(&runtime->mutex);

install_cleanup:
  if (ctx) pkg_free(ctx);
  if (cache_storage) ant_storage_context_destroy(cache_storage);
  if (project_storage) ant_storage_context_destroy(project_storage);
  if (cache_bridge) ant_android_storage_bridge_destroy(cache_bridge);
  if (project_bridge) ant_android_storage_bridge_destroy(project_bridge);
  runtime_string_array_free(&specs);
  free(project);
  free(registry);
  free(requested_cache);
  if ((*env)->ExceptionCheck(env)) return NULL;
  if (error != PKG_OK) {
    throw_runtime_exception(env, error_text);
    return NULL;
  }
  return runtime_install_result_json(env, &result, lifecycle_count);
}

JNIEXPORT void JNICALL
Java_org_antjs_runtime_AntRuntime_nativeRunPostinstall(
  JNIEnv *env, jclass clazz, jlong handle, jstring project_directory,
  jobjectArray package_names
) {
  (void)clazz;
  ant_android_runtime_t *runtime = runtime_from_handle(handle);
  if (!runtime || !runtime->js) {
    throw_runtime_exception(env, "Ant runtime is closed");
    return;
  }
  if (!runtime_check_thread(env, runtime)) return;

  char *project = runtime_copy_java_cstring(env, project_directory, "projectDirectory");
  if (!project) return;
  if (!runtime_path_is_absolute(project)) {
    free(project);
    throw_runtime_exception(env, "projectDirectory must be an absolute path");
    return;
  }
  char *package_json = NULL;
  char *lockfile = NULL;
  char *node_modules = NULL;
  char *cache_dir = NULL;
  if (!runtime_prepare_package_paths(env, project, &package_json, &lockfile,
                                     &node_modules, &cache_dir)) {
    free(project);
    runtime_free_package_paths(package_json, lockfile, node_modules, cache_dir);
    return;
  }
  free(package_json);
  free(lockfile);

  runtime_string_array_t names;
  if (!runtime_copy_java_string_array(env, package_names, "packageName", &names)) {
    free(project);
    runtime_free_package_paths(NULL, NULL, node_modules, cache_dir);
    return;
  }

  pkg_options_t options = {
    .cache_dir = cache_dir,
    .registry_url = "registry.npmjs.org",
    .max_connections = 6,
    .progress_callback = NULL,
    .user_data = NULL,
    .verbose = false,
    .force = false,
    .run_lifecycle_scripts = false,
  };
  pthread_mutex_lock(&runtime->mutex);
  pkg_context_t *ctx = pkg_init(&options);
  pkg_error_t error = ctx ? pkg_discover_lifecycle_scripts(ctx, node_modules) : PKG_CACHE_ERROR;
  runtime_string_array_t discovered = {0};
  const char **run_names = (const char **)names.items;
  uint32_t run_count = names.count;
  if (ctx && error == PKG_OK && run_count == 0) {
    run_count = pkg_get_lifecycle_script_count(ctx);
    if (run_count > 0) {
      discovered.items = calloc(run_count, sizeof(*discovered.items));
      discovered.count = run_count;
      if (!discovered.items) {
        error = PKG_OUT_OF_MEMORY;
        run_count = 0;
      } else {
        for (uint32_t i = 0; i < run_count; i++) {
          pkg_lifecycle_script_t script;
          if (pkg_get_lifecycle_script(ctx, i, &script) != PKG_OK) {
            error = PKG_INVALID_ARGUMENT;
            run_count = 0;
            break;
          }
          discovered.items[i] = (char *)script.name;
        }
        run_names = (const char **)discovered.items;
      }
    }
  }
  if (ctx && error == PKG_OK && run_count > 0) {
    error = pkg_run_postinstall(ctx, node_modules, run_names, run_count);
  }
  char error_text[512];
  if (error != PKG_OK) runtime_format_pkg_error(error_text, sizeof(error_text), "postinstall", ctx, error);
  if (ctx) pkg_free(ctx);
  pthread_mutex_unlock(&runtime->mutex);

  free(discovered.items);
  runtime_string_array_free(&names);
  free(project);
  runtime_free_package_paths(NULL, NULL, node_modules, cache_dir);
  if (error != PKG_OK) throw_runtime_exception(env, error_text);
}

JNIEXPORT jboolean JNICALL
Java_org_antjs_runtime_AntRuntime_nativeDestroy(
  JNIEnv *env, jclass clazz, jlong handle
) {
  (void)env;
  (void)clazz;
  ant_android_runtime_t *runtime = runtime_from_handle(handle);
  if (!runtime) return JNI_FALSE;
  if (!runtime_check_thread(env, runtime)) return JNI_FALSE;
  pthread_mutex_lock(&runtime->mutex);
  volatile char stack_base;
  runtime_set_stack(runtime, (void *)&stack_base);
  js_reactor_pump_repl_nowait(runtime->js);
  if (runtime_has_pending_work()) {
    pthread_mutex_unlock(&runtime->mutex);
    throw_runtime_exception(
      env,
      "Cannot close AntRuntime while asynchronous work is pending; "
      "clear timers and close network/filesystem resources first"
    );
    return JNI_FALSE;
  }
  pthread_mutex_unlock(&runtime->mutex);

  pthread_mutex_lock(&runtime_registry_mutex);
  if (active_runtime == runtime) active_runtime = NULL;
  runtime_created_once = false;
  pthread_mutex_unlock(&runtime_registry_mutex);

  /* Use one destruction path so the Storage context, Android Context global
   * reference, SAF bridge objects and isolate are released together. */
  runtime_destroy(runtime);
  return JNI_TRUE;
}
