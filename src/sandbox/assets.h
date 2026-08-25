#ifndef ANT_SANDBOX_ASSETS_H
#define ANT_SANDBOX_ASSETS_H

#include <stddef.h>
#include <stdbool.h>

int ant_sandbox_assets_download_missing(
  const char *image_path,
  const char *kernel_path,
  char *err,
  size_t err_len
);

extern bool ant_sandbox_assets_bypass_manifest;

#endif
