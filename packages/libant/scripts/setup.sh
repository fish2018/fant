#!/bin/bash
set -e

. "$(dirname "$0")/common.sh"

cd "$ROOT_DIR"
# Meson reports a false failure the first time it downloads CMake-only
# subprojects such as BoringSSL: the checkout is complete, but the downloader
# still looks for a meson.build file. A second pass recognizes the downloaded
# CMake subproject. Retry once, while still propagating a real repeated error.
if ! meson subprojects download; then
  echo "Initial Meson subproject download reported an incomplete CMake-only subproject; retrying once..." >&2
  meson subprojects download
fi

RSYNC_OPTS=""
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*|CLANG*|UCRT*) RSYNC_OPTS="--copy-links" ;;
esac

mkdir -p "$SCRIPT_DIR/vendor"
rsync -a $RSYNC_OPTS --exclude='.git/' "$ROOT_DIR/vendor/" "$SCRIPT_DIR/vendor/"

mkdir -p "$BUILD_DIR"
