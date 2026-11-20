#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
kotlinc app/src/main/java/com/phoneassistant/JobProgress.kt \
  app/src/main/java/com/phoneassistant/WorkLifetime.kt app/src/main/java/com/phoneassistant/ToyLifetime.kt \
  app/src/main/java/com/phoneassistant/PopSheet.kt app/src/main/java/com/phoneassistant/PlantGrowth.kt \
  app/src/main/java/com/phoneassistant/DragMotion.kt app/src/main/java/com/phoneassistant/PipTransition.kt \
  app/src/main/java/com/phoneassistant/ToyMotion.kt app/src/main/java/com/phoneassistant/SpotifyQuickPlan.kt \
  app/src/main/java/com/phoneassistant/DoomscrollMotion.kt app/src/main/java/com/phoneassistant/HomeScreenPolicy.kt \
  app/src/main/java/com/phoneassistant/SetupPolicy.kt app/src/main/java/com/phoneassistant/WateringPose.kt \
  app/src/main/java/com/phoneassistant/PreferredName.kt app/src/main/java/com/phoneassistant/MemorySuggestion.kt \
  app/src/main/java/com/phoneassistant/NowPlaying.kt app/src/main/java/com/phoneassistant/PhoneContext.kt \
  app/src/main/java/com/phoneassistant/JobApproach.kt app/src/main/java/com/phoneassistant/PickupMotion.kt \
  app/src/main/java/com/phoneassistant/PlantSpecies.kt tests/JobProgressTest.kt \
  -include-runtime -d "$tmp/job-progress.jar"
java -jar "$tmp/job-progress.jar"
