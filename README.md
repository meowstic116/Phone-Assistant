# Phone-Assistant

A little phone-shaped character named Pip that walks around your Android screen, over
your other apps, and talks back.

## What it does

- A floating character drawn entirely in code (no sprite sheets) that walks along the
  bottom of the screen, blinks, and reacts.
- Tap it to open a chat screen; replies come from any OpenAI-compatible API.
- It knows which app you are in (via Usage Access) and can reference it. It never reads
  screen contents.
- Drag it anywhere. It lives in a foreground service with a persistent notification.

## Setup on the phone

A prebuilt debug APK is committed at `dist/phone-assistant-0.1-debug.apk` if you just want
to sideload it without building.

1. Build and install: `./gradlew installDebug` (or open in Android Studio and Run).
2. On the setup screen, grant **Display over other apps**, **Usage access**, and
   **Notifications**.
3. Paste an API key. The defaults point at Gemini's free tier
   (`https://generativelanguage.googleapis.com/v1beta/openai` with
   `gemini-3.5-flash-lite`), and a key from https://aistudio.google.com/apikey costs
   nothing and needs no card. Roughly 500 requests/day at 15/minute.
   `gemini-3.8-flash` is smarter but its free tier allows only about 20 requests/day.
   Any OpenAI-compatible endpoint works too — OpenAI, OpenRouter, Groq, or Ollama on
   your network.
4. Press **Start assistant**.

## Changing the character

- Personality, name and rules: `app/src/main/java/com/phoneassistant/Persona.kt`
- Look and animation: `app/src/main/java/com/phoneassistant/PetView.kt`
- Walk speed, idle timing, window size: `OverlayService.kt`

The API key is stored only in this app's private preferences on the device.
