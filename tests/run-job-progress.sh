#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
kotlinc app/src/main/java/com/phoneassistant/JobProgress.kt app/src/main/java/com/phoneassistant/InstagramReelsPolicy.kt \
  app/src/main/java/com/phoneassistant/WorkLifetime.kt app/src/main/java/com/phoneassistant/ToyLifetime.kt \
  app/src/main/java/com/phoneassistant/PopSheet.kt app/src/main/java/com/phoneassistant/PlantGrowth.kt \
  app/src/main/java/com/phoneassistant/DragMotion.kt app/src/main/java/com/phoneassistant/PipTransition.kt \
  app/src/main/java/com/phoneassistant/ToyMotion.kt app/src/main/java/com/phoneassistant/SpotifyQuickPlan.kt \
  app/src/main/java/com/phoneassistant/DoomscrollMotion.kt app/src/main/java/com/phoneassistant/HomeScreenPolicy.kt \
  app/src/main/java/com/phoneassistant/SetupPolicy.kt app/src/main/java/com/phoneassistant/WateringPose.kt \
  app/src/main/java/com/phoneassistant/PreferredName.kt app/src/main/java/com/phoneassistant/MemorySuggestion.kt \
  app/src/main/java/com/phoneassistant/NowPlaying.kt app/src/main/java/com/phoneassistant/PhoneContext.kt \
  app/src/main/java/com/phoneassistant/MessageNotifications.kt \
  app/src/main/java/com/phoneassistant/JobApproach.kt app/src/main/java/com/phoneassistant/PickupMotion.kt \
  app/src/main/java/com/phoneassistant/PlantSpecies.kt app/src/main/java/com/phoneassistant/SoccerLayout.kt app/src/main/java/com/phoneassistant/SoccerAi.kt \
  app/src/main/java/com/phoneassistant/VoiceProtocol.kt app/src/main/java/com/phoneassistant/SpeechCaption.kt \
  app/src/main/java/com/phoneassistant/VoiceChoice.kt app/src/main/java/com/phoneassistant/KokoroHealth.kt \
  app/src/main/java/com/phoneassistant/ChatRecovery.kt app/src/main/java/com/phoneassistant/BoundedText.kt \
  app/src/main/java/com/phoneassistant/OverlayBounds.kt app/src/main/java/com/phoneassistant/LemonadeLayout.kt app/src/main/java/com/phoneassistant/ToyFooting.kt \
  app/src/main/java/com/phoneassistant/ReplyBubbleState.kt app/src/main/java/com/phoneassistant/KokoroPcm.kt \
  app/src/main/java/com/phoneassistant/SpeechBubbleTiming.kt app/src/main/java/com/phoneassistant/WakePhrase.kt app/src/main/java/com/phoneassistant/WakeCaptureRecovery.kt \
  app/src/main/java/com/phoneassistant/WakeStartupPolicy.kt app/src/main/java/com/phoneassistant/VoiceExchange.kt \
  app/src/main/java/com/phoneassistant/CarCommand.kt app/src/main/java/com/phoneassistant/WakeFeedback.kt app/src/main/java/com/phoneassistant/SpontaneousToyChoice.kt \
  app/src/main/java/com/phoneassistant/TimedRequest.kt app/src/main/java/com/phoneassistant/PaintingStart.kt \
  app/src/main/java/com/phoneassistant/PurchasePolicy.kt app/src/main/java/com/phoneassistant/CarVoice.kt app/src/main/java/com/phoneassistant/NavVoice.kt \
  app/src/main/java/com/phoneassistant/ClearRows.kt \
  tests/JobProgressTest.kt \
  -include-runtime -d "$tmp/job-progress.jar"
java -jar "$tmp/job-progress.jar"
