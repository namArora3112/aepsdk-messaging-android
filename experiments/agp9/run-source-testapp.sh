#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)

exec "$SCRIPT_DIR/gradlew" \
    --project-dir "$REPO_ROOT/code" \
    --init-script "$SCRIPT_DIR/source-build.init.gradle" \
    :testapp:assembleDebug \
    --stacktrace --console=plain --no-daemon
