#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
BUILD_DIR="${ANT_HOST_BUILD_DIR:-$ROOT_DIR/build}"
TEMPORAL="disabled"
JOBS=""

usage() {
  cat <<'EOF'
Usage: scripts/build-ant.sh [options]

Build the host Ant executable from the repository root.

Options:
  --build-dir PATH  Meson build directory (default: ./build)
  --temporal        Enable Temporal and its pinned Rust toolchain
  --jobs N          Limit parallel compiler jobs
  -h, --help        Show this help

Environment:
  ANT_ZIG_BIN        Path to the Zig 0.16.x executable
  CC / CXX           Optional host C/C++ compiler overrides
EOF
}

while (($# > 0)); do
  case "$1" in
    --build-dir)
      [[ $# -ge 2 ]] || { echo "--build-dir requires a path" >&2; exit 2; }
      BUILD_DIR="$2"
      shift 2
      ;;
    --temporal)
      TEMPORAL="enabled"
      shift
      ;;
    --jobs)
      [[ $# -ge 2 ]] || { echo "--jobs requires a number" >&2; exit 2; }
      JOBS="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 2
  }
}

for command_name in git python3 meson ninja cmake pkg-config node npm; do
  require_command "$command_name"
done

ZIG_BIN="${ANT_ZIG_BIN:-}"
if [[ -z "$ZIG_BIN" ]]; then
  ZIG_BIN="$(command -v zig || true)"
fi
[[ -n "$ZIG_BIN" && -x "$ZIG_BIN" ]] || {
  echo "Zig 0.16.x is required; install it or set ANT_ZIG_BIN" >&2
  exit 2
}
ZIG_VERSION="$($ZIG_BIN version)"
[[ "$ZIG_VERSION" == 0.16.* ]] || {
  echo "Zig 0.16.x is required, found $ZIG_VERSION" >&2
  exit 2
}

# Meson locates Zig by program name. Prepend the selected executable's
# directory so ANT_ZIG_BIN and normal PATH installations behave the same.
export PATH="$(dirname "$ZIG_BIN"):$PATH"

CC_USER_EXPLICIT="${CC:-}"
CXX_USER_EXPLICIT="${CXX:-}"
CC_EXPLICIT="$CC_USER_EXPLICIT"
if [[ -z "$CC_EXPLICIT" ]]; then
  for candidate in \
      /opt/homebrew/opt/llvm/bin/clang \
      /usr/local/opt/llvm/bin/clang \
      "$(command -v clang-21 || true)" \
      "$(command -v clang-20 || true)" \
      "$(command -v clang-19 || true)" \
      "$(command -v clang-18 || true)" \
      "$(command -v gcc-15 || true)" \
      "$(command -v gcc-14 || true)" \
      "$(command -v clang || true)" \
      "$(command -v gcc || true)" \
      "$(command -v cc || true)"; do
    if [[ -n "$candidate" && -x "$candidate" ]]; then
      export CC="$candidate"
      compiler_dir="$(dirname "$candidate")"
      compiler_name="$(basename "$candidate")"
      case "$compiler_name" in
        clang) cxx_candidate="$compiler_dir/clang++" ;;
        clang-*) cxx_candidate="$compiler_dir/clang++-${compiler_name#clang-}" ;;
        gcc) cxx_candidate="$compiler_dir/g++" ;;
        gcc-*) cxx_candidate="$compiler_dir/g++-${compiler_name#gcc-}" ;;
        *) cxx_candidate="$(command -v c++ || true)" ;;
      esac
      if [[ -z "${CXX:-}" && -n "$cxx_candidate" && -x "$cxx_candidate" ]]; then
        export CXX="$cxx_candidate"
      fi
      CC_EXPLICIT="$candidate"
      break
    fi
  done
fi
[[ -n "$CC_EXPLICIT" ]] || {
  echo "A C23-capable compiler is required (Clang 18+ or GCC 14+)" >&2
  exit 2
}
read -r -a CC_PROBE_CMD <<< "$CC_EXPLICIT"
CC_PROBE_INDEX=$((${#CC_PROBE_CMD[@]} - 1))
CC_PROBE="${CC_PROBE_CMD[$CC_PROBE_INDEX]:-}"
if [[ -z "${CXX:-}" ]]; then
  compiler_dir="$(dirname "$CC_PROBE")"
  compiler_name="$(basename "$CC_PROBE")"
  case "$compiler_name" in
    clang) cxx_candidate="$compiler_dir/clang++" ;;
    clang-*) cxx_candidate="$compiler_dir/clang++-${compiler_name#clang-}" ;;
    gcc) cxx_candidate="$compiler_dir/g++" ;;
    gcc-*) cxx_candidate="$compiler_dir/g++-${compiler_name#gcc-}" ;;
    *) cxx_candidate="" ;;
  esac
  if [[ -n "$cxx_candidate" && ! -x "$cxx_candidate" && "$compiler_dir" == "." ]]; then
    cxx_candidate="$(command -v "${cxx_candidate#./}" || true)"
  fi
  if [[ -n "$cxx_candidate" && -x "$cxx_candidate" ]]; then
    export CXX="$cxx_candidate"
  fi
fi
C_STD=""
export ZIG_GLOBAL_CACHE_DIR="${ZIG_GLOBAL_CACHE_DIR:-$BUILD_DIR/.zig-global-cache}"
export ZIG_LOCAL_CACHE_DIR="${ZIG_LOCAL_CACHE_DIR:-$BUILD_DIR/.zig-local-cache}"
# macOS mktemp only treats trailing X characters as a template when they are
# at the end of the name. Create a temporary directory first so the probe paths
# are valid on both macOS and GNU/Linux.
PROBE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/ant-c23-probe.XXXXXX")"
C23_PROBE="$PROBE_DIR/probe.c"
C23_OBJECT="$PROBE_DIR/probe.o"
printf '%s\n' 'constexpr int ant_c23_probe = 1; int main(void) { return ant_c23_probe; }' > "$C23_PROBE"
trap 'rm -rf "$PROBE_DIR"' EXIT
compiler_supports_c23() {
  local standard="$1"
  "${CC_PROBE_CMD[@]}" -x c "-std=$standard" -c "$C23_PROBE" -o "$C23_OBJECT" >/dev/null 2>&1
}

if compiler_supports_c23 gnu23; then
  C_STD="gnu23"
elif compiler_supports_c23 gnu2x; then
  C_STD="gnu2x"
fi

if [[ -z "$C_STD" && -z "$CC_USER_EXPLICIT" ]]; then
  # An older clang may be first on PATH while a supported GCC/Clang is also
  # installed. Probe every automatic candidate before reporting a toolchain
  # error instead of binding the build to PATH order.
  for candidate in \
      /opt/homebrew/opt/llvm/bin/clang \
      /usr/local/opt/llvm/bin/clang \
      "$(command -v clang-21 || true)" \
      "$(command -v clang-20 || true)" \
      "$(command -v clang-19 || true)" \
      "$(command -v clang-18 || true)" \
      "$(command -v gcc-15 || true)" \
      "$(command -v gcc-14 || true)" \
      "$(command -v clang || true)" \
      "$(command -v gcc || true)" \
      "$(command -v cc || true)"; do
    [[ -n "$candidate" && -x "$candidate" ]] || continue
    CC_PROBE_CMD=("$candidate")
    candidate_std=""
    if compiler_supports_c23 gnu23; then
      candidate_std="gnu23"
    elif compiler_supports_c23 gnu2x; then
      candidate_std="gnu2x"
    fi
    [[ -n "$candidate_std" ]] || continue

    CC="$candidate"
    export CC
    if [[ -z "$CXX_USER_EXPLICIT" ]]; then
      compiler_dir="$(dirname "$candidate")"
      compiler_name="$(basename "$candidate")"
      case "$compiler_name" in
        clang) CXX="$compiler_dir/clang++" ;;
        clang-*) CXX="$compiler_dir/clang++-${compiler_name#clang-}" ;;
        gcc) CXX="$compiler_dir/g++" ;;
        gcc-*) CXX="$compiler_dir/g++-${compiler_name#gcc-}" ;;
        *) CXX="" ;;
      esac
      [[ -n "$CXX" && -x "$CXX" ]] || continue
      export CXX
    fi
    CC_PROBE="$candidate"
    C_STD="$candidate_std"
    break
  done
fi

if [[ -z "$C_STD" ]]; then
  echo "The selected compiler does not implement the C23 features Ant uses: $CC_PROBE" >&2
  echo "Install Clang 18+ or GCC 14+, then set CC and CXX." >&2
  exit 2
fi
[[ -n "${CXX:-}" ]] || {
  echo "A C++ compiler matching CC is required; set CXX explicitly" >&2
  exit 2
}

LLVM_NM="$(command -v llvm-nm || command -v nm || true)"
[[ -n "$LLVM_NM" ]] || {
  echo "An nm/llvm-nm tool is required to inspect the Darwin link archive" >&2
  exit 2
}

cd "$ROOT_DIR"
echo "==> Downloading Meson subprojects"
# Meson can return a false failure immediately after cloning a CMake-only
# subproject (notably BoringSSL) because it has no meson.build. The checkout is
# already usable by cmake.subproject(); a second pass recognizes it. Keep the
# retry bounded so genuine download failures still stop the build.
if ! meson subprojects download; then
  echo "Initial Meson subproject download reported an incomplete CMake-only subproject; retrying once..." >&2
  meson subprojects download
fi

# Keep npm's build-time cache inside the selected build tree. This avoids
# relying on a user's global cache permissions (and makes CI/clone builds
# independent of a previously root-owned ~/.npm directory).
export NPM_CONFIG_CACHE="${NPM_CONFIG_CACHE:-$BUILD_DIR/.npm-cache}"
export npm_config_cache="$NPM_CONFIG_CACHE"

SETUP_ARGS=(
  "$BUILD_DIR"
  "--buildtype=release"
  "-Dc_std=$C_STD"
  "-Dtemporal=$TEMPORAL"
  "-Dpgo=disabled"
  "-Dnative_tuning=disabled"
  "-Dllvm_nm=$LLVM_NM"
  "-Db_lto=false"
)
if [[ -f "$BUILD_DIR/meson-private/coredata.dat" ]]; then
  SETUP_ARGS+=("--reconfigure")
fi

echo "==> Configuring host Ant"
meson setup "${SETUP_ARGS[@]}"

echo "==> Building host Ant"
COMPILE_ARGS=(-C "$BUILD_DIR")
if [[ -n "$JOBS" ]]; then
  COMPILE_ARGS+=("-j$JOBS")
fi
meson compile "${COMPILE_ARGS[@]}"

ANT_EXE="$BUILD_DIR/ant"
if [[ "$(uname -s)" == MINGW* || "$(uname -s)" == MSYS* || "$(uname -s)" == CYGWIN* ]]; then
  ANT_EXE="$BUILD_DIR/ant.exe"
fi
[[ -x "$ANT_EXE" ]] || {
  echo "Ant executable was not produced: $ANT_EXE" >&2
  exit 1
}

echo "==> Verifying host Ant"
"$ANT_EXE" --version
ANT_BYTES="$(wc -c < "$ANT_EXE" | tr -d '[:space:]')"
echo "Built $ANT_EXE ($ANT_BYTES bytes, $(du -h "$ANT_EXE" | awk '{print $1}'))"
