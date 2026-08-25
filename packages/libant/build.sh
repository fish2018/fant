#!/bin/bash
set -e

SCRIPTS_DIR="$(cd "$(dirname "$0")/scripts" && pwd)"

"$SCRIPTS_DIR/setup.sh"
if [ "${LIBANT_SKIP_EXTERNAL_DEPS:-0}" != "1" ]; then
  "$SCRIPTS_DIR/deps.sh"
fi
"$SCRIPTS_DIR/compile.sh" "$@"
"$SCRIPTS_DIR/bundle.sh"
