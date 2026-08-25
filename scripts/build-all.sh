#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

BUILD_HOST=1
BUILD_ANDROID=1
ANDROID_ARGS=()
while (($# > 0)); do
  case "$1" in
    --host-only)
      BUILD_ANDROID=0
      shift
      ;;
    --android-only)
      BUILD_HOST=0
      shift
      ;;
    -h|--help)
      cat <<'EOF'
Usage: scripts/build-all.sh [--host-only|--android-only] [Android options]

Build the host Ant executable and/or the Android AAR/demo APK.
Android options are forwarded to android/build-demo.sh.
EOF
      exit 0
      ;;
    *)
      ANDROID_ARGS+=("$1")
      shift
      ;;
  esac
done

if ((BUILD_HOST)); then
  "$SCRIPT_DIR/build-ant.sh"
fi
if ((BUILD_ANDROID)); then
  "$ROOT_DIR/android/build-demo.sh" "${ANDROID_ARGS[@]}"
fi
