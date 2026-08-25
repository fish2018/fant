#!/bin/bash

SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
BUILD_DIR="${LIBANT_BUILD_DIR:-$SCRIPT_DIR/build}"
DEPS_DIR="${LIBANT_DEPS_DIR:-$BUILD_DIR/deps}"
CACHE_DIR="${LIBANT_CACHE_DIR:-$BUILD_DIR/.external}"
DIST_DIR="${LIBANT_DIST_DIR:-$SCRIPT_DIR/dist}"
NCPU=$(sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null || echo 4)
