#!/usr/bin/env bash
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$TEST_DIR/../.." && pwd)"
BUILD_DIR="$(mktemp -d "${TMPDIR:-/tmp}/fmt-app-tests.XXXXXX")"
trap 'rm -rf "$BUILD_DIR"' EXIT
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
sources=("$ROOT/app/src/io/freemytv/AppStopper.java" "$ROOT/app/src/io/freemytv/Spoken.java")
while IFS= read -r source; do sources+=("$source"); done < <(find "$TEST_DIR/src" -name '*.java' -type f)
"$JAVAC" -d "$BUILD_DIR" "${sources[@]}"
"$JAVA" -cp "$BUILD_DIR" io.freemytv.AppManagementTest
