#include "archive.h"

#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static const unsigned char k_magic[8] = {'A', 'N', 'T', 'A', 'P', 'P', '0', '1'};

static void SetError(char *error, size_t capacity, const char *message) {
  if (capacity) snprintf(error, capacity, "%s", message);
}

static uint32_t ReadU32(const unsigned char *value) {
  return (uint32_t)value[0] | (uint32_t)value[1] << 8 | (uint32_t)value[2] << 16 | (uint32_t)value[3] << 24;
}

static uint64_t ReadU64(const unsigned char *value) {
  uint64_t result = 0;
  for (int index = 7; index >= 0; index--)
    result = result << 8 | value[index];
  return result;
}

static int Read(FILE *file, void *data, size_t size) {
  return size == 0 || fread(data, 1, size, file) == size;
}

static int SafeRelativePath(const char *value) {
  if (!value[0] || value[0] == '/') return 0;
  const char *part = value;
  while (*part) {
    const char *end = strchr(part, '/');
    size_t length = end ? (size_t)(end - part) : strlen(part);
    if (!length || (length == 1 && part[0] == '.') || (length == 2 && part[0] == '.' && part[1] == '.')) return 0;
    if (!end) break;
    part = end + 1;
  }
  return 1;
}

static int MakeParents(char *path) {
  for (char *cursor = path + 1; *cursor; cursor++) {
    if (*cursor != '/') continue;
    *cursor = '\0';
    if (mkdir(path, 0755) != 0 && errno != EEXIST) return 0;
    *cursor = '/';
  }
  return 1;
}

static int CopyBytes(FILE *source, FILE *destination, uint64_t length) {
  unsigned char buffer[64 * 1024];
  while (length) {
    size_t chunk = length > sizeof(buffer) ? sizeof(buffer) : (size_t)length;
    if (!Read(source, buffer, chunk) || fwrite(buffer, 1, chunk, destination) != chunk) { return 0; }
    length -= chunk;
  }
  return 1;
}

int ant_desktop_extract_archive(const char *archive, const char *destination, char *error, size_t error_capacity) {
  FILE *file = fopen(archive, "rb");
  if (!file) {
    SetError(error, error_capacity, "cannot open application archive");
    return 0;
  }
  unsigned char header[12];
  if (!Read(file, header, sizeof(header)) || memcmp(header, k_magic, 8) != 0) {
    SetError(error, error_capacity, "invalid application archive header");
    fclose(file);
    return 0;
  }
  uint32_t count = ReadU32(header + 8);
  for (uint32_t index = 0; index < count; index++) {
    unsigned char entry_header[20];
    if (!Read(file, entry_header, sizeof(entry_header))) {
      SetError(error, error_capacity, "truncated application archive");
      fclose(file);
      return 0;
    }
    uint32_t path_length = ReadU32(entry_header);
    uint64_t data_length = ReadU64(entry_header + 4);
    uint32_t mode = ReadU32(entry_header + 12) & 0777;
    unsigned char type = entry_header[16];
    if (!path_length || path_length > 1024 * 1024 || (type != 1 && type != 2) ||
        (type == 2 && data_length > 1024 * 1024)) {
      SetError(error, error_capacity, "invalid application archive entry");
      fclose(file);
      return 0;
    }
    char *relative = malloc((size_t)path_length + 1);
    size_t full_length = strlen(destination) + 1 + path_length + 1;
    char *output = malloc(full_length);
    if (!relative || !output || !Read(file, relative, path_length)) {
      free(relative);
      free(output);
      SetError(error, error_capacity, "cannot read application archive entry");
      fclose(file);
      return 0;
    }
    relative[path_length] = '\0';
    if (!SafeRelativePath(relative)) {
      free(relative);
      free(output);
      SetError(error, error_capacity, "unsafe application archive path");
      fclose(file);
      return 0;
    }
    snprintf(output, full_length, "%s/%s", destination, relative);
    free(relative);
    if (!MakeParents(output)) {
      free(output);
      SetError(error, error_capacity, "cannot create application archive directory");
      fclose(file);
      return 0;
    }
    if (type == 2) {
      char *target = malloc((size_t)data_length + 1);
      if (!target || !Read(file, target, (size_t)data_length)) {
        free(target);
        free(output);
        SetError(error, error_capacity, "cannot read application archive symlink");
        fclose(file);
        return 0;
      }
      target[data_length] = '\0';
      if (symlink(target, output) != 0) {
        free(target);
        free(output);
        SetError(error, error_capacity, "cannot create application archive symlink");
        fclose(file);
        return 0;
      }
      free(target);
    } else {
      FILE *target = fopen(output, "wb");
      int written = target && CopyBytes(file, target, data_length);
      int closed = target ? fclose(target) == 0 : 0;
      if (!written || !closed || chmod(output, mode) != 0) {
        free(output);
        SetError(error, error_capacity, "cannot write application archive file");
        fclose(file);
        return 0;
      }
    }
    free(output);
  }
  fclose(file);
  return 1;
}
