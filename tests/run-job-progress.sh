#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
kotlinc app/src/main/java/com/phoneassistant/JobProgress.kt \
  app/src/main/java/com/phoneassistant/WorkLifetime.kt tests/JobProgressTest.kt \
  -include-runtime -d "$tmp/job-progress.jar"
java -jar "$tmp/job-progress.jar"
