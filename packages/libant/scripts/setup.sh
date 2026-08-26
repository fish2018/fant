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

# Re-apply directory-based packagefile overlays after rsync. Do not use
# `meson subprojects packagefiles --apply` here: for wrap-file subprojects it
# re-extracts the patch archive without replaying diff_files, which can undo
# Android fixes already present in the root vendor checkout (for example the
# LMDB robust-mutex fallback). Directory overlays such as WAMR still need to
# follow the checked-in packagefiles exactly.
for overlay in "$SCRIPT_DIR/vendor/packagefiles"/*/; do
  [[ -d "$overlay" ]] || continue
  overlay_name="$(basename "$overlay")"
  [[ "$overlay_name" == patches ]] && continue
  [[ -d "$SCRIPT_DIR/vendor/$overlay_name" ]] || continue
  rsync -a $RSYNC_OPTS "$overlay" "$SCRIPT_DIR/vendor/$overlay_name/"
done

mkdir -p "$BUILD_DIR"
