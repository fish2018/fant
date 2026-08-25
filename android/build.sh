#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

NDK_ROOT="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}"
BUILD_ROOT="${ANT_ANDROID_BUILD_DIR:-$SCRIPT_DIR/build}"
MIN_SDK=24
# Ant's value representation and optimizing VM currently require a 64-bit
# pointer layout. These are the Android phone/TV ABIs covered by this
# embedding layer; 32-bit ARM is rejected explicitly below.
ABIS=(arm64-v8a)

usage() {
  cat <<'EOF'
Usage: android/build.sh [options]

Options:
  --ndk PATH       Android NDK root (also ANDROID_NDK_ROOT)
  --min-sdk N      Android API level (default: 24)
  --abis LIST      comma-separated ABI list (default: arm64-v8a)
  --build-dir PATH output/build cache root
  -h, --help       show this help
EOF
}

while (($# > 0)); do
  case "$1" in
    --ndk)
      [[ $# -ge 2 ]] || { echo "--ndk requires a path" >&2; exit 2; }
      NDK_ROOT="$2"
      shift 2
      ;;
    --min-sdk)
      [[ $# -ge 2 ]] || { echo "--min-sdk requires a number" >&2; exit 2; }
      MIN_SDK="$2"
      shift 2
      ;;
    --abis)
      [[ $# -ge 2 ]] || { echo "--abis requires a comma-separated list" >&2; exit 2; }
      IFS=',' read -r -a ABIS <<< "$2"
      shift 2
      ;;
    --build-dir)
      [[ $# -ge 2 ]] || { echo "--build-dir requires a path" >&2; exit 2; }
      BUILD_ROOT="$2"
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

[[ -n "$NDK_ROOT" && -d "$NDK_ROOT" ]] || {
  echo "Set ANDROID_NDK_ROOT or pass --ndk" >&2
  exit 2
}

case "$MIN_SDK" in
  ''|*[!0-9]*) echo "--min-sdk must be numeric" >&2; exit 2 ;;
esac
((MIN_SDK >= 24)) || {
  echo "--min-sdk must be at least 24" >&2
  exit 2
}

HOST_TAG=""
case "$(uname -s)" in
  Darwin) HOST_CANDIDATES=(darwin-arm64 darwin-x86_64) ;;
  Linux) HOST_CANDIDATES=(linux-x86_64 linux-aarch64) ;;
  MINGW*|MSYS*|CYGWIN*) HOST_CANDIDATES=(windows-x86_64) ;;
  *) HOST_CANDIDATES=(darwin-arm64 darwin-x86_64 linux-x86_64 linux-aarch64 windows-x86_64) ;;
esac
for candidate in "${HOST_CANDIDATES[@]}"; do
  if [[ -d "$NDK_ROOT/toolchains/llvm/prebuilt/$candidate" ]]; then
    HOST_TAG="$candidate"
    break
  fi
done
[[ -n "$HOST_TAG" ]] || {
  echo "Could not find an NDK LLVM toolchain under $NDK_ROOT" >&2
  exit 2
}

TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG"
ANDROID_CMAKE="$NDK_ROOT/build/cmake/android.toolchain.cmake"
[[ -f "$ANDROID_CMAKE" ]] || {
  echo "Missing Android CMake toolchain: $ANDROID_CMAKE" >&2
  exit 2
}

CMAKE_BIN="$(command -v cmake || true)"
[[ -n "$CMAKE_BIN" ]] || {
  echo "CMake is required to build Ant dependencies" >&2
  exit 2
}

JAVAC_BIN="$(command -v javac || true)"
JAR_BIN="$(command -v jar || true)"
[[ -n "$JAVAC_BIN" && -n "$JAR_BIN" ]] || {
  echo "A JDK with javac and jar is required to package ant-runtime.aar" >&2
  exit 2
}

PYTHON_BIN="$(command -v python3 || true)"
[[ -n "$PYTHON_BIN" ]] || {
  echo "Python 3 is required to select and build Android toolchains" >&2
  exit 2
}
version_sort() {
  "$PYTHON_BIN" -c '
import re
import sys

def key(value):
    return [int(part) if part.isdigit() else part.lower()
            for part in re.split(r"([0-9]+)", value)]

for value in sorted((line.rstrip("\n") for line in sys.stdin if line.strip()), key=key):
    print(value)
'
}

# The runtime archive contains Android framework references (Context, Uri,
# DocumentsContract, ...).  javac does not provide those classes on a normal
# JDK class path, so resolve an installed platform jar explicitly instead of
# relying on a host-specific bootclasspath.
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
ANDROID_JAR="${ANT_ANDROID_JAR:-}"
if [[ -z "$ANDROID_JAR" && -n "$ANDROID_SDK_ROOT" && -d "$ANDROID_SDK_ROOT/platforms" ]]; then
  while IFS= read -r candidate; do
    ANDROID_JAR="$candidate"
  done < <(find "$ANDROID_SDK_ROOT/platforms" -mindepth 2 -maxdepth 2 -name android.jar -type f | version_sort)
fi
[[ -n "$ANDROID_JAR" && -f "$ANDROID_JAR" ]] || {
  echo "An Android platform android.jar is required; set ANDROID_SDK_ROOT or ANT_ANDROID_JAR" >&2
  exit 2
}

LLVM_READELF="$TOOLCHAIN/bin/llvm-readelf"
[[ -x "$LLVM_READELF" ]] || {
  echo "Missing NDK tool: $LLVM_READELF" >&2
  exit 2
}

abi_cpu_family() {
  case "$1" in
    arm64-v8a) echo aarch64 ;;
    x86_64) echo x86_64 ;;
    *) return 1 ;;
  esac
}

abi_cpu() {
  case "$1" in
    arm64-v8a) echo armv8-a ;;
    x86_64) echo x86-64 ;;
    *) return 1 ;;
  esac
}

abi_triple() {
  case "$1" in
    arm64-v8a) echo aarch64-linux-android ;;
    x86_64) echo x86_64-linux-android ;;
    *) return 1 ;;
  esac
}

[[ ${#ABIS[@]} -gt 0 ]] || {
  echo "--abis must contain at least one ABI" >&2
  exit 2
}
for abi in "${ABIS[@]}"; do
  abi_cpu_family "$abi" >/dev/null 2>&1 || {
    echo "Unsupported ABI: ${abi:-<empty>}" >&2
    exit 2
  }
done

write_cross_file() {
  local abi="$1"
  local output="$2"
  local family
  local cpu
  local triple
  family="$(abi_cpu_family "$abi")"
  cpu="$(abi_cpu "$abi")"
  triple="$(abi_triple "$abi")"
  local compiler="$TOOLCHAIN/bin/${triple}${MIN_SDK}-clang"
  local cxx_compiler="$TOOLCHAIN/bin/${triple}${MIN_SDK}-clang++"
  local ar="$TOOLCHAIN/bin/llvm-ar"
  local ranlib="$TOOLCHAIN/bin/llvm-ranlib"
  local strip="$TOOLCHAIN/bin/llvm-strip"

  for tool in "$compiler" "$cxx_compiler" "$ar" "$ranlib" "$strip"; do
    [[ -x "$tool" ]] || { echo "Missing NDK tool: $tool" >&2; exit 2; }
  done

  printf '%s\n' \
    '[binaries]' \
    "c = '$compiler'" \
    "cpp = '$cxx_compiler'" \
    "ar = '$ar'" \
    "ranlib = '$ranlib'" \
    "strip = '$strip'" \
    "cmake = '$CMAKE_BIN'" \
    '[host_machine]' \
    "system = 'android'" \
    "cpu_family = '$family'" \
    "cpu = '$cpu'" \
    "endian = 'little'" \
    '[properties]' \
    'needs_exe_wrapper = true' \
    '[built-in options]' \
    "c_args = ['-ffunction-sections', '-fdata-sections']" \
    "cpp_args = ['-ffunction-sections', '-fdata-sections']" \
    '[project options]' \
    'jit = false' \
    'lto = false' \
    "temporal = 'disabled'" \
    "native_tuning = 'disabled'" \
    "pgo = 'disabled'" \
    "android_api = '$MIN_SDK'" > "$output"

}

mkdir -p "$BUILD_ROOT"

# The Android package-manager archive is built from the same Zig source as the
# desktop package manager. ANT_ZIG_BIN can select a tool that is not on PATH.
ZIG_BIN="${ANT_ZIG_BIN:-}"
if [[ -z "$ZIG_BIN" ]]; then
  ZIG_BIN="$(command -v zig || true)"
fi
[[ -n "$ZIG_BIN" && -x "$ZIG_BIN" ]] || {
  echo "Zig 0.16.x is required to build Android npm support; set ANT_ZIG_BIN" >&2
  exit 2
}
ZIG_VERSION="$($ZIG_BIN version)"
[[ "$ZIG_VERSION" == 0.16.* ]] || {
  echo "Zig 0.16.x is required, found $ZIG_VERSION" >&2
  exit 2
}

for abi in "${ABIS[@]}"; do
  family="$(abi_cpu_family "$abi")" || {
    echo "Unsupported ABI: $abi" >&2
    exit 2
  }

  ABI_ROOT="$BUILD_ROOT/$abi"
  LIBANT_BUILD="$ABI_ROOT/libant-build"
  LIBANT_DIST="$ABI_ROOT/libant"
  JNI_BUILD="$ABI_ROOT/jni-build"
  CROSS_FILE="$ABI_ROOT/android-cross.ini"
  mkdir -p "$ABI_ROOT"
  write_cross_file "$abi" "$CROSS_FILE"

  echo "==> Building libant for $abi (Android API $MIN_SDK)"
  env \
    PATH="$(dirname "$ZIG_BIN"):$PATH" \
    ANDROID_NDK_ROOT="$NDK_ROOT" \
    ANDROID_NDK_HOME="$NDK_ROOT" \
    LIBANT_SKIP_EXTERNAL_DEPS=1 \
    LIBANT_BUILD_DIR="$LIBANT_BUILD" \
    LIBANT_DEPS_DIR="$LIBANT_BUILD/deps" \
    LIBANT_CACHE_DIR="$LIBANT_BUILD/.external" \
    LIBANT_DIST_DIR="$LIBANT_DIST" \
    LIBANT_AR="$TOOLCHAIN/bin/llvm-ar" \
    LIBANT_SKIP_LTO_BUNDLE=1 \
    NPM_CONFIG_CACHE="$ABI_ROOT/npm-cache" \
    npm_config_cache="$ABI_ROOT/npm-cache" \
    ANDROID_SYSROOT="$TOOLCHAIN/sysroot" \
    ZIG_GLOBAL_CACHE_DIR="$ABI_ROOT/.zig-global-cache" \
    ZIG_LOCAL_CACHE_DIR="$ABI_ROOT/.zig-local-cache" \
    "$ROOT_DIR/packages/libant/build.sh" \
      --cross-file "$CROSS_FILE" \
      --buildtype release \
      -Djit=false \
      -Dlto=false \
      -Dtemporal=disabled \
      -Dnative_tuning=disabled \
      -Dpgo=disabled \
      -Dandroid_api="$MIN_SDK"

  UV_HEADER="$(find "$ROOT_DIR/packages/libant/vendor" -path '*/include/uv.h' -print -quit)"
  [[ -n "$UV_HEADER" ]] || {
    echo "Could not find the libuv headers below $ROOT_DIR/packages/libant/vendor" >&2
    exit 2
  }
  UV_INCLUDE_DIR="$(dirname "$UV_HEADER")"

  [[ -f "$LIBANT_DIST/libpkg.a" ]] || {
    echo "Package manager archive was not produced: $LIBANT_DIST/libpkg.a" >&2
    exit 2
  }
  [[ -f "$LIBANT_DIST/pkg.h" ]] || cp "$ROOT_DIR/include/pkg.h" "$LIBANT_DIST/pkg.h"

  echo "==> Building JNI bridge for $abi"
  cmake -S "$SCRIPT_DIR" -B "$JNI_BUILD" \
    -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_CMAKE" \
    -DANDROID_ABI="$abi" \
    -DANDROID_PLATFORM="android-$MIN_SDK" \
    -DANDROID_STL=c++_static \
    -DANT_LIB_DIR="$LIBANT_DIST" \
    -DANT_PKG_DIR="$LIBANT_DIST" \
    -DANT_INCLUDE_DIR="$LIBANT_DIST" \
    -DANT_UV_INCLUDE_DIR="$UV_INCLUDE_DIR" \
    -DCMAKE_BUILD_TYPE=Release
  cmake --build "$JNI_BUILD" --target ant_android
  JNI_OUTPUT_DIR="$ABI_ROOT/jniLibs/$abi"
  mkdir -p "$JNI_OUTPUT_DIR"

  # Strip into a private staging directory and verify the result before
  # replacing the published artifact. Some llvm-strip/filesystem combinations
  # can leave a truncated destination when an in-place rewrite is interrupted.
  NATIVE_STAGE="$(mktemp -d "$JNI_OUTPUT_DIR/.ant-native.XXXXXX")"
  STRIPPED_SO="$NATIVE_STAGE/libant_android.so"
  "$TOOLCHAIN/bin/llvm-strip" \
    --strip-unneeded \
    -o "$STRIPPED_SO" \
    "$JNI_BUILD/libant_android.so"
  "$LLVM_READELF" -h "$STRIPPED_SO" >/dev/null
  mv "$STRIPPED_SO" "$JNI_OUTPUT_DIR/libant_android.so"
  rmdir "$NATIVE_STAGE"
  echo "Built $JNI_OUTPUT_DIR/libant_android.so"
done

echo "==> Packaging Android archive"
AAR_STAGE="$(mktemp -d "$BUILD_ROOT/.ant-runtime-aar.XXXXXX")"
cleanup_aar_stage() {
  rm -rf "$AAR_STAGE"
}
trap cleanup_aar_stage EXIT

mkdir -p "$AAR_STAGE/classes" "$AAR_STAGE/jni"
"$JAVAC_BIN" --release 8 \
  -classpath "$ANDROID_JAR" \
  -d "$AAR_STAGE/classes" \
  "$SCRIPT_DIR"/src/main/java/org/antjs/runtime/*.java
"$JAR_BIN" --create \
  --file "$AAR_STAGE/classes.jar" \
  -C "$AAR_STAGE/classes" .

sed "s/@ANT_MIN_SDK@/$MIN_SDK/g" \
  "$SCRIPT_DIR/AndroidManifest.xml" > "$AAR_STAGE/AndroidManifest.xml"
cp "$SCRIPT_DIR/proguard.txt" "$AAR_STAGE/proguard.txt"
for abi in "${ABIS[@]}"; do
  mkdir -p "$AAR_STAGE/jni/$abi"
  cp \
    "$BUILD_ROOT/$abi/jniLibs/$abi/libant_android.so" \
    "$AAR_STAGE/jni/$abi/libant_android.so"
done

AAR_OUTPUT="$BUILD_ROOT/ant-runtime.aar"
AAR_STAGED_OUTPUT="$AAR_STAGE/ant-runtime.aar"
"$JAR_BIN" --create \
  --file "$AAR_STAGED_OUTPUT" \
  -C "$AAR_STAGE" AndroidManifest.xml \
  -C "$AAR_STAGE" classes.jar \
  -C "$AAR_STAGE" proguard.txt \
  -C "$AAR_STAGE" jni
"$JAR_BIN" --list --file "$AAR_STAGED_OUTPUT" >/dev/null
mv "$AAR_STAGED_OUTPUT" "$AAR_OUTPUT"

cleanup_aar_stage
trap - EXIT
echo "Built $AAR_OUTPUT"
echo "Android artifacts are under $BUILD_ROOT"
