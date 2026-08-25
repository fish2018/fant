#!/bin/bash
set -e

. "$(dirname "$0")/common.sh"

DIST_DIR="${LIBANT_DIST_DIR:-$SCRIPT_DIR/dist}"
mkdir -p "$DIST_DIR"

bundle_lib() {
  NAME="$1"
  EXCLUDE="$2"
  OUTPUT="$BUILD_DIR/$NAME"
  
  echo "Bundling $NAME..."

  LIBS=$(find "$BUILD_DIR" -name '*.a' \
    ! -name 'libant.a' \
    ! -name 'libant-lto.a' \
    ! -name 'libpkg.a' \
    ! -path '*/.external/*' \
    ! -path '*/.zig-global-cache/*' \
    ! -path '*/.zig-local-cache/*' \
    ! -path '*/zig-cache/*' \
    2>/dev/null | grep -E -v "$EXCLUDE" | sort -u)

  # Android uses the Meson cross-build dependencies and deliberately skips
  # packages/libant/scripts/deps.sh. A reused build directory may still contain
  # host archives below $DEPS_DIR from an older native build; merging those
  # Mach-O/ELF objects into the Android archive makes the final JNI link fail.
  # Keep the normal libant distribution behaviour unchanged, but ignore that
  # external dependency prefix when the caller selected the cross-build path.
  if [ "${LIBANT_SKIP_EXTERNAL_DEPS:-0}" = "1" ] && [ -n "$DEPS_DIR" ]; then
    LIBS=$(printf '%s\n' "$LIBS" | awk -v prefix="$DEPS_DIR/" 'index($0, prefix) != 1')
  fi

  if [ -z "$LIBS" ]; then
    echo "No libraries found, skipping $NAME"
    return
  fi

  if [ -n "${LIBANT_AR:-}" ] && [ -x "$LIBANT_AR" ]; then
    AR="$LIBANT_AR"
  elif [ -n "${AR:-}" ] && command -v "$AR" >/dev/null 2>&1; then
    AR="$AR"
  elif command -v llvm-ar >/dev/null 2>&1; then
    AR=llvm-ar
  elif ar --version 2>/dev/null | head -n 1 | grep -q GNU; then
    AR=ar
  else
    AR=""
  fi

  if [ -n "$AR" ]; then
    if command -v cygpath >/dev/null 2>&1; then
      mri_path() { cygpath -m "$1"; }
    else
      mri_path() { printf '%s\n' "$1"; }
    fi
    rm -f "$OUTPUT.tmp"
    {
      echo "CREATE $(mri_path "$OUTPUT.tmp")"
      for lib in $LIBS; do
        echo "ADDLIB $(mri_path "$lib")"
      done
      echo "SAVE"
      echo "END"
    } | "$AR" -M
    mv "$OUTPUT.tmp" "$OUTPUT"
  else
    temp_dir=$(mktemp -d)
    cd "$temp_dir"
    for lib in $LIBS; do
      libname=$(basename "$lib" .a)
      mkdir -p "$libname"
      (cd "$libname" && ar x "$lib")
    done
    find . -name '*.o' > objects.txt
    ar rcs "$OUTPUT" $(cat objects.txt)
    rm -rf "$temp_dir"
    cd "$SCRIPT_DIR"
  fi

  cp "$OUTPUT" "$DIST_DIR/"
  echo "Created: $DIST_DIR/$NAME ($(du -h "$OUTPUT" | cut -f1))"
}

bundle_lib "libant.a" "_lto"

if [ "${LIBANT_SKIP_LTO_BUNDLE:-0}" != "1" ] && \
   [ -f "$BUILD_DIR/libant_core_lto.a" ]; then
  bundle_lib "libant-lto.a" "libant_core.a"
fi

if [ -f "$BUILD_DIR/libant.h" ]; then
  cp "$BUILD_DIR/libant.h" "$DIST_DIR/ant.h"
  echo "Created: $DIST_DIR/ant.h"
fi

pkg_lib_path=$(find "$BUILD_DIR" -name 'libpkg.a' -print | head -n 1)
if [ -n "$pkg_lib_path" ] && [ -f "$pkg_lib_path" ]; then
  cp "$pkg_lib_path" "$DIST_DIR/libpkg.a"
  echo "Created: $DIST_DIR/libpkg.a ($(du -h "$pkg_lib_path" | cut -f1))"
fi

if [ -f "$ROOT_DIR/include/pkg.h" ]; then
  cp "$ROOT_DIR/include/pkg.h" "$DIST_DIR/pkg.h"
  echo "Created: $DIST_DIR/pkg.h"
fi

echo ""
echo "Done! Distribution files in $DIST_DIR:"
ls -lh "$DIST_DIR"/ 2>/dev/null || echo "No files found"
