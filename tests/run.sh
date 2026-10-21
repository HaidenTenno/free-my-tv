#!/usr/bin/env bash
# Local JVM checks; no TV, network, signing key or Android SDK is needed.
set -euo pipefail
cd "$(dirname "$0")/.."

for suite in remotes power apps; do
  bash "tests/$suite/run.sh"
done
