#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
test_dir="$(mktemp -d "${TMPDIR:-/tmp}/free-my-tv-power.XXXXXX")"
trap 'rm -rf "$test_dir"' EXIT
javac_bin="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
"$javac_bin" -encoding UTF-8 -d "$test_dir" \
  tests/power/stubs/android/content/Context.java \
  tests/power/stubs/android/os/Build.java tests/power/stubs/android/os/SystemClock.java \
  tests/power/stubs/io/freemytv/Adb.java tests/power/stubs/io/freemytv/AppLog.java \
  app/src/io/freemytv/PowerTimers.java app/src/io/freemytv/PowerSettings.java \
  tests/power/PowerSettingsTest.java
"$java_bin" -cp "$test_dir" io.freemytv.PowerSettingsTest
