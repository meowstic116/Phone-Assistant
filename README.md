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

Later builds are published as GitHub **Releases** with the APK attached, rather than being
committed, so the git history does not grow by ~840 KB per build. `dist/` is gitignored for
that reason.

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

## Signing

`signing/phone-assistant.jks` is committed and used for both debug and release builds, so
every build shares one signature and updates install straight over the top. Password
`android`, alias `androiddebugkey`.

That key is public and self-signed — completely fine for sideloading on your own phone,
but never acceptable for Play Store distribution or for anything you actually want to
protect from impersonation.

## Changing the character

- Personality, name and rules: `app/src/main/java/com/phoneassistant/Persona.kt`
- Look and animation: `app/src/main/java/com/phoneassistant/PetView.kt`
- Walk speed, idle timing, window size: `OverlayService.kt`

The API key is stored only in this app's private preferences on the device.
