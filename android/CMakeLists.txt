cmake_minimum_required(VERSION 3.22)

project(ant_android LANGUAGES C CXX)

set(ANT_LIB_DIR "" CACHE PATH "Directory containing the Android libant.a")
set(ANT_PKG_DIR "" CACHE PATH "Directory containing the Android libpkg.a")
set(ANT_INCLUDE_DIR "" CACHE PATH "Directory containing the generated ant.h")
set(ANT_UV_INCLUDE_DIR "" CACHE PATH "Directory containing libuv headers")

if(NOT ANT_LIB_DIR OR NOT EXISTS "${ANT_LIB_DIR}/libant.a")
  message(FATAL_ERROR "ANT_LIB_DIR must contain libant.a")
endif()

if(NOT ANT_PKG_DIR OR NOT EXISTS "${ANT_PKG_DIR}/libpkg.a")
  message(FATAL_ERROR "ANT_PKG_DIR must contain libpkg.a")
endif()

if(NOT ANT_INCLUDE_DIR OR NOT EXISTS "${ANT_INCLUDE_DIR}/ant.h")
  message(FATAL_ERROR "ANT_INCLUDE_DIR must contain ant.h")
endif()

if(NOT ANT_UV_INCLUDE_DIR OR NOT EXISTS "${ANT_UV_INCLUDE_DIR}/uv.h")
  message(FATAL_ERROR "ANT_UV_INCLUDE_DIR must contain uv.h")
endif()

add_library(ant STATIC IMPORTED GLOBAL)
set_target_properties(ant PROPERTIES
  IMPORTED_LOCATION "${ANT_LIB_DIR}/libant.a"
)

add_library(pkg STATIC IMPORTED GLOBAL)
set_target_properties(pkg PROPERTIES
  IMPORTED_LOCATION "${ANT_PKG_DIR}/libpkg.a"
)

set(ANT_ANDROID_SOURCES
  src/main/cpp/ant_android.c
  src/main/cpp/storage_bridge.c
)
# Zig's x86_64 package-manager code uses the IEEE quad-precision `roundq`
# entry point. Android bionic exposes the equivalent 128-bit `roundl`, but not
# the GNU `roundq` alias, so provide the ABI-compatible forwarding symbol only
# for this ABI. arm64 does not emit a quad-precision reference.
if(ANDROID_ABI STREQUAL "x86_64")
  list(APPEND ANT_ANDROID_SOURCES src/main/cpp/roundq_compat.c)
endif()
add_library(ant_android SHARED ${ANT_ANDROID_SOURCES})
target_include_directories(ant_android PRIVATE
  "${ANT_INCLUDE_DIR}"
  "${ANT_UV_INCLUDE_DIR}"
  "${CMAKE_CURRENT_SOURCE_DIR}/../include"
)
# The generated amalgamated header intentionally contains the public runtime
# declarations but not internal support headers such as storage.h. Keep the
# typed Storage API visible before including ant.h in the JNI translation unit.
target_compile_options(ant_android PRIVATE
  -include "${CMAKE_CURRENT_SOURCE_DIR}/../include/storage.h"
)
target_compile_features(ant_android PRIVATE c_std_23)
set_target_properties(ant_android PROPERTIES LINKER_LANGUAGE CXX)
target_compile_options(ant_android PRIVATE
  -fvisibility=hidden
  -fdata-sections
  -ffunction-sections
)
target_link_options(ant_android PRIVATE
  -Wl,--gc-sections
  -Wl,--exclude-libs,ALL
  -Wl,--no-undefined
)

find_library(log-lib log)
find_library(android-lib android)
find_library(dl-lib dl)
find_library(m-lib m)
find_library(z-lib z)
find_library(atomic-lib atomic)

set(ANT_ANDROID_SYSTEM_LIBS
  ${log-lib}
  ${android-lib}
  ${dl-lib}
  ${m-lib}
  ${z-lib}
)
if(atomic-lib)
  list(APPEND ANT_ANDROID_SYSTEM_LIBS ${atomic-lib})
endif()

target_link_libraries(ant_android PRIVATE
  "-Wl,--start-group"
  ant
  pkg
  "-Wl,--end-group"
  ${ANT_ANDROID_SYSTEM_LIBS}
)

set_target_properties(ant_android PROPERTIES
  OUTPUT_NAME ant_android
  C_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN YES
)
