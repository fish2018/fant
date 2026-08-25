#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
CALLER_DIR="$(pwd)"

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
NDK_ROOT="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}"
BUILD_ROOT="${ANT_ANDROID_BUILD_DIR:-$SCRIPT_DIR/build}"
ABIS="arm64-v8a"
MIN_SDK=24
VARIANT="debug"
SIGNING_KEYSTORE="${ANT_DEMO_KEYSTORE:-}"
SIGNING_ALIAS="${ANT_DEMO_KEY_ALIAS:-fant-demo}"
SIGNING_STORE_PASSWORD="${ANT_DEMO_STORE_PASSWORD:-}"
SIGNING_KEY_PASSWORD="${ANT_DEMO_KEY_PASSWORD:-}"
SIGNING_KEYSTORE_EXPLICIT=0
if [[ -n "$SIGNING_KEYSTORE" ]]; then
  SIGNING_KEYSTORE_EXPLICIT=1
fi

usage() {
  cat <<'EOF'
Usage: android/build-demo.sh [options]

Build ant-runtime.aar and the Android demo APK.

Options:
  --sdk PATH        Android SDK root (also ANDROID_SDK_ROOT)
  --ndk PATH        Android NDK root (also ANDROID_NDK_ROOT)
  --abis LIST       Comma-separated ABIs (default: arm64-v8a)
  --min-sdk N       Android minimum API (default: 24)
  --build-dir PATH  Native/AAR build directory (default: android/build)
  --release         Build the final signed release APK directly
  --keystore PATH   Release keystore (default: android/build/fant-demo-release.keystore)
  --key-alias NAME  Release key alias (default: fant-demo)
  -h, --help        Show this help

Environment:
  ANT_ZIG_BIN              Path to the Zig 0.16.x executable
  ANT_DEMO_KEYSTORE        Release keystore path
  ANT_DEMO_KEY_ALIAS       Release key alias
  ANT_DEMO_STORE_PASSWORD  Release keystore password
  ANT_DEMO_KEY_PASSWORD    Release key password

If no keystore is supplied for --release, a temporary Demo keystore is created
under the selected build directory. It is suitable for local installation and
testing, not for publishing an update signed by a production key.
EOF
}

while (($# > 0)); do
  case "$1" in
    --sdk)
      [[ $# -ge 2 ]] || { echo "--sdk requires a path" >&2; exit 2; }
      SDK_ROOT="$2"
      shift 2
      ;;
    --ndk)
      [[ $# -ge 2 ]] || { echo "--ndk requires a path" >&2; exit 2; }
      NDK_ROOT="$2"
      shift 2
      ;;
    --abis)
      [[ $# -ge 2 ]] || { echo "--abis requires a comma-separated list" >&2; exit 2; }
      ABIS="$2"
      shift 2
      ;;
    --min-sdk)
      [[ $# -ge 2 ]] || { echo "--min-sdk requires a number" >&2; exit 2; }
      MIN_SDK="$2"
      shift 2
      ;;
    --build-dir)
      [[ $# -ge 2 ]] || { echo "--build-dir requires a path" >&2; exit 2; }
      BUILD_ROOT="$2"
      shift 2
      ;;
    --release)
      VARIANT="release"
      shift
      ;;
    --keystore)
      [[ $# -ge 2 ]] || { echo "--keystore requires a path" >&2; exit 2; }
      SIGNING_KEYSTORE="$2"
      SIGNING_KEYSTORE_EXPLICIT=1
      shift 2
      ;;
    --key-alias)
      [[ $# -ge 2 ]] || { echo "--key-alias requires a name" >&2; exit 2; }
      SIGNING_ALIAS="$2"
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

# Gradle is launched from android/demo, so make a user-provided relative
# build directory absolute before passing its artifact path to the project.
if [[ "$BUILD_ROOT" != /* ]]; then
  BUILD_ROOT="$ROOT_DIR/$BUILD_ROOT"
fi

[[ -n "$SDK_ROOT" && -d "$SDK_ROOT" ]] || {
  echo "Set ANDROID_SDK_ROOT or pass --sdk" >&2
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

if [[ -z "$NDK_ROOT" && -d "$SDK_ROOT/ndk" ]]; then
  while IFS= read -r candidate; do
    NDK_ROOT="$candidate"
  done < <(find "$SDK_ROOT/ndk" -mindepth 1 -maxdepth 1 -type d | version_sort)
fi
[[ -n "$NDK_ROOT" && -d "$NDK_ROOT" ]] || {
  echo "Set ANDROID_NDK_ROOT, pass --ndk, or install an NDK below $SDK_ROOT/ndk" >&2
  exit 2
}

[[ -x "$SCRIPT_DIR/demo/gradlew" ]] || {
  echo "Missing executable Gradle wrapper: android/demo/gradlew" >&2
  exit 2
}

echo "==> Building Android runtime AAR"
ANDROID_SDK_ROOT="$SDK_ROOT" \
ANDROID_NDK_ROOT="$NDK_ROOT" \
ANT_ANDROID_BUILD_DIR="$BUILD_ROOT" \
  "$SCRIPT_DIR/build.sh" \
    --ndk "$NDK_ROOT" \
    --abis "$ABIS" \
    --min-sdk "$MIN_SDK" \
    --build-dir "$BUILD_ROOT"

AAR="$BUILD_ROOT/ant-runtime.aar"
[[ -f "$AAR" ]] || {
  echo "Android runtime archive was not produced: $AAR" >&2
  exit 1
}

case "$VARIANT" in
  debug)
    GRADLE_TASK=":app:assembleDebug"
    APK="$SCRIPT_DIR/demo/app/build/outputs/apk/debug/app-debug.apk"
    ;;
  release)
    GRADLE_TASK=":app:assembleRelease"
    APK="$SCRIPT_DIR/demo/app/build/outputs/apk/release/app-release.apk"
    ;;
esac

if [[ "$VARIANT" == "release" ]]; then
  APKSIGNER="${ANT_APKSIGNER:-}"
  ZIPALIGN="${ANT_ZIPALIGN:-}"
  if [[ -z "$APKSIGNER" ]]; then
    while IFS= read -r candidate; do
      APKSIGNER="$candidate/apksigner"
    done < <(find "$SDK_ROOT/build-tools" -mindepth 1 -maxdepth 1 -type d | version_sort)
  fi
  if [[ -z "$ZIPALIGN" ]]; then
    while IFS= read -r candidate; do
      ZIPALIGN="$candidate/zipalign"
    done < <(find "$SDK_ROOT/build-tools" -mindepth 1 -maxdepth 1 -type d | version_sort)
  fi
  [[ -x "$APKSIGNER" ]] || {
    echo "Android apksigner was not found; set ANT_APKSIGNER or install SDK build-tools" >&2
    exit 2
  }
  [[ -x "$ZIPALIGN" ]] || {
    echo "Android zipalign was not found; set ANT_ZIPALIGN or install SDK build-tools" >&2
    exit 2
  }

  if [[ -z "$SIGNING_KEYSTORE" ]]; then
    SIGNING_KEYSTORE="$BUILD_ROOT/fant-demo-release.keystore"
  fi
  if [[ "$SIGNING_KEYSTORE" != /* ]]; then
    SIGNING_KEYSTORE="$CALLER_DIR/$SIGNING_KEYSTORE"
  fi
  if ((SIGNING_KEYSTORE_EXPLICIT)); then
    [[ -f "$SIGNING_KEYSTORE" ]] || {
      echo "Release keystore was not found: $SIGNING_KEYSTORE" >&2
      exit 2
    }
    [[ -n "$SIGNING_STORE_PASSWORD" ]] || {
      echo "Set ANT_DEMO_STORE_PASSWORD for the supplied release keystore" >&2
      exit 2
    }
    if [[ -z "$SIGNING_KEY_PASSWORD" ]]; then
      SIGNING_KEY_PASSWORD="$SIGNING_STORE_PASSWORD"
    fi
  else
    if [[ -z "$SIGNING_STORE_PASSWORD" ]]; then
      SIGNING_STORE_PASSWORD="changeit"
    fi
    if [[ -z "$SIGNING_KEY_PASSWORD" ]]; then
      SIGNING_KEY_PASSWORD="$SIGNING_STORE_PASSWORD"
    fi
  fi
  if [[ ! -f "$SIGNING_KEYSTORE" ]]; then
    KEYTOOL_BIN="${ANT_KEYTOOL:-$(command -v keytool || true)}"
    [[ -x "$KEYTOOL_BIN" ]] || {
      echo "keytool is required to create the default Demo keystore" >&2
      exit 2
    }
    mkdir -p "$(dirname "$SIGNING_KEYSTORE")"
    echo "==> Creating local Demo keystore: $SIGNING_KEYSTORE"
    "$KEYTOOL_BIN" -genkeypair -noprompt \
      -keystore "$SIGNING_KEYSTORE" \
      -storetype PKCS12 \
      -storepass "$SIGNING_STORE_PASSWORD" \
      -keypass "$SIGNING_KEY_PASSWORD" \
      -alias "$SIGNING_ALIAS" \
      -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=FAnt Demo, OU=FAnt, O=fish2018, C=CN" >/dev/null 2>&1
  fi
  [[ -f "$SIGNING_KEYSTORE" ]] || {
    echo "Release keystore was not found: $SIGNING_KEYSTORE" >&2
    exit 2
  }
  rm -f \
    "$APK" \
    "$SCRIPT_DIR/demo/app/build/outputs/apk/release/app-release-unsigned.apk" \
    "$SCRIPT_DIR/demo/app/build/outputs/apk/release/app-release.apk.idsig"
fi

echo "==> Building Android demo APK ($VARIANT)"
(
  cd "$SCRIPT_DIR/demo"
  # Keep Gradle's wrapper and dependency cache beside the selected build
  # directory. This makes a fresh clone independent of a root-owned global
  # ~/.gradle and keeps generated state out of the source tree.
  GRADLE_USER_HOME="${GRADLE_USER_HOME:-$BUILD_ROOT/gradle-home}"
  mkdir -p "$GRADLE_USER_HOME"
  if [[ "$VARIANT" == "release" ]]; then
    export ANT_DEMO_KEYSTORE="$SIGNING_KEYSTORE"
    export ANT_DEMO_KEY_ALIAS="$SIGNING_ALIAS"
    export ANT_DEMO_STORE_PASSWORD="$SIGNING_STORE_PASSWORD"
    export ANT_DEMO_KEY_PASSWORD="$SIGNING_KEY_PASSWORD"
  fi
  GRADLE_ARGS=(
    "$GRADLE_TASK"
    --no-daemon
    "-PantAbis=$ABIS"
    "-PantMinSdk=$MIN_SDK"
    "-PantAar=$AAR"
  )
  if [[ "$VARIANT" == "release" ]]; then
    GRADLE_ARGS+=("-PantRequireReleaseSigning=true")
  fi
  ANDROID_SDK_ROOT="$SDK_ROOT" ANDROID_HOME="$SDK_ROOT" \
    GRADLE_USER_HOME="$GRADLE_USER_HOME" \
    ./gradlew "${GRADLE_ARGS[@]}"
)

if [[ "$VARIANT" == "release" ]]; then
  if [[ -f "$SCRIPT_DIR/demo/app/build/outputs/apk/release/app-release-unsigned.apk" ]]; then
    echo "Gradle produced an unsigned release artifact; signing configuration was not applied" >&2
    exit 1
  fi
fi

[[ -f "$APK" ]] || {
  echo "Demo APK was not produced: $APK" >&2
  exit 1
}

if [[ "$VARIANT" == "release" ]]; then
  echo "==> Verifying release APK alignment"
  "$ZIPALIGN" -c -v 4 "$APK"
  echo "==> Verifying release APK signature"
  "$APKSIGNER" verify --verbose --print-certs "$APK"
fi

AAR_BYTES="$(wc -c < "$AAR" | tr -d '[:space:]')"
APK_BYTES="$(wc -c < "$APK" | tr -d '[:space:]')"
echo "Built $AAR ($AAR_BYTES bytes, $(du -h "$AAR" | awk '{print $1}'))"
echo "Built $APK ($APK_BYTES bytes, $(du -h "$APK" | awk '{print $1}'))"
