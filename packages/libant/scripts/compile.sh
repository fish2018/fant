#!/bin/bash
set -e

. "$(dirname "$0")/common.sh"

cd "$SCRIPT_DIR"
export PKG_CONFIG_PATH="$DEPS_DIR/lib/pkgconfig:$PKG_CONFIG_PATH"
if [ -n "${LIBANT_CC:-}" ]; then
  export CC="$LIBANT_CC"
elif command -v ccache >/dev/null 2>&1; then
  export CC="ccache clang"
else
  export CC="clang"
fi
if [ -n "${LIBANT_CXX:-}" ]; then
  export CXX="$LIBANT_CXX"
elif command -v ccache >/dev/null 2>&1; then
  export CXX="ccache clang++"
else
  export CXX="clang++"
fi
export CCACHE_DIR="${CCACHE_DIR:-$BUILD_DIR/.ccache}"

tlsuv_wrap="$SCRIPT_DIR/vendor/tlsuv.wrap"
tlsuv_dir="$(awk -F '=' '/^directory[[:space:]]*=/{gsub(/[[:space:]]/, "", $2); print $2; exit}' "$tlsuv_wrap")"

if [ -z "$tlsuv_dir" ]; then
  tlsuv_dir="$(basename "$tlsuv_wrap" .wrap)"
fi

tlsuv_prefix="$BUILD_DIR/vendor/$tlsuv_dir/deps"
use_external_deps="${LIBANT_SKIP_EXTERNAL_DEPS:-0}"
if [ "$use_external_deps" != "1" ]; then
  mkdir -p "$BUILD_DIR/vendor/$tlsuv_dir"
  rm -rf "$tlsuv_prefix"
  cp -RL "$BUILD_DIR/deps" "$tlsuv_prefix"
fi

setup_args=()
if [ ! -f "$BUILD_DIR/meson-private/coredata.dat" ]; then
  setup_args=("$BUILD_DIR" --prefer-static)
elif [ "$#" -gt 0 ]; then
  setup_args=("$BUILD_DIR" --reconfigure --prefer-static)
else
  coredata="$BUILD_DIR/meson-private/coredata.dat"
  for config_input in "$SCRIPT_DIR/meson.build" "$SCRIPT_DIR/meson_options.txt" "$ROOT_DIR/sources.json" "$ROOT_DIR/meson/meson.build" "$ROOT_DIR/meson/deps/meson.build"; do
    if [ "$config_input" -nt "$coredata" ]; then
      setup_args=("$BUILD_DIR" --reconfigure --prefer-static)
      break
    fi
  done
fi

if [ "$use_external_deps" != "1" ]; then
  setup_args+=("-Ddeps_prefix_cmake=$tlsuv_prefix")
fi

if [ "${#setup_args[@]}" -gt 0 ]; then
  meson setup "${setup_args[@]}" "$@"
fi
meson compile -C "$BUILD_DIR"
