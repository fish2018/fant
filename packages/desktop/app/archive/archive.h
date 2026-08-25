#ifndef ANT_DESKTOP_APP_ARCHIVE_H
#define ANT_DESKTOP_APP_ARCHIVE_H

#include <stddef.h>

int ant_desktop_extract_archive(const char *archive, const char *destination, char *error, size_t error_capacity);

#endif
