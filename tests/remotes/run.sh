#!/usr/bin/env bash
# Focused JVM tests; Android API doubles are isolated under this suite's stubs directory.
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$TEST_DIR/../.." && pwd)"
TEST_BUILD="$(mktemp -d "${TMPDIR:-/tmp}/freemytv-remotes.XXXXXX")"
trap 'rm -rf "$TEST_BUILD"' EXIT
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
SOURCES=()
while IFS= read -r -d '' source; do SOURCES+=("$source"); done < <(find "$TEST_DIR/stubs" "$TEST_DIR/src" -name '*.java' -print0)
for source in RemoteKeys RemoteProfile TvPlatform LiveTv Prefs; do
    SOURCES+=("$REPO_ROOT/app/src/io/freemytv/$source.java")
done
"$JAVAC" -d "$TEST_BUILD/classes" "${SOURCES[@]}"
"$JAVA" -cp "$TEST_BUILD/classes" io.freemytv.RemoteTests "$TEST_BUILD"
"$JAVA" -cp "$TEST_BUILD/classes" io.freemytv.PrefsTests "$TEST_BUILD"
profile() {
    "$JAVA" -Dtest.manufacturer="$1" -Dtest.brand="$2" -Dtest.device="$3" \
        -cp "$TEST_BUILD/classes" io.freemytv.ProfileTests "$4"
}
profile TCL TCL G08 tcl-ru
profile Other tCl g08 tcl-ru
profile TCL TCL Other tcl-standard
profile Haier Haier irvine haier
profile Other hAiEr IRVINE haier
profile Vestel F\&U Other vestel
profile Haier Haier Other unsupported
profile Sony Sony G08 unsupported
