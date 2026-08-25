#ifndef ANT_ANDROID_STORAGE_BRIDGE_H
#define ANT_ANDROID_STORAGE_BRIDGE_H

#include <jni.h>
#include <stdbool.h>

#include "storage.h"

typedef struct ant_android_storage_bridge ant_android_storage_bridge_t;

ant_android_storage_bridge_t *ant_android_storage_bridge_create(
  JNIEnv *env,
  jobject context,
  const char *tree_uri
);
void ant_android_storage_bridge_destroy(ant_android_storage_bridge_t *bridge);
const ant_storage_bridge_t *ant_android_storage_bridge_callbacks(
  ant_android_storage_bridge_t *bridge
);

#endif
