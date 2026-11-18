# Phone-Assistant

A little phone-shaped character named Pip that walks around your Android screen, over
your other apps, and talks back.

## What it does

- A floating character drawn entirely in code (no sprite sheets) that wanders the whole
  screen, blinks, and reacts.
- Tap it to open a small input box under his feet. He stops to listen, shows animated
  thinking dots, then answers in a speech bubble that follows him around and flips below
  him near the top of the screen. The box leaves as soon as you send, or when you press
  back. He steps straight up clear of the keyboard using its measured height, not a guess,
  keeping his place across the screen instead of jumping to a corner. Replies
  come from any OpenAI-compatible API.
- A **Chat history** screen shows the whole conversation and lets you keep talking from
  there; replies still appear in his overlay bubble, which floats above the chat.
- A **Personality** screen shows the full prompt that defines him, and lets you rewrite it.
- A **Memory** screen lists the facts he keeps between conversations, and lets you add or
  delete them.
- It knows which app you are in (via Usage Access) and can reference it. It never reads
  screen contents.
- Drag it anywhere. It lives in a foreground service with a persistent notification.

## Setup on the phone

The current build is committed at `dist/phone-assistant-0.11-debug.apk` if you just want to
sideload it without building. Each new build replaces the file in `dist/`, so there is
always exactly one APK to grab — easiest thing to download from a phone.

Tagged builds are also published as GitHub Releases by
`.github/workflows/release.yml`, which is what the in-app update check looks at.

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

`signing/phone-assistant.jks` is committed and public, and signs **every** build — debug
and release alike. One signature means every build installs straight over the top, so
updates never require uninstalling and no chat history is lost.

This is a personal-use key: ideal for sideloading on your own phone, and never acceptable
for Play Store distribution or anywhere impersonation would matter.

If you ever decide to share the app, generate a private keystore, feed it to the release
workflow through repository secrets, and expect the first private-key build to need one
uninstall — Android treats a changed signature as a different app.

### Publishing a release

Bump `versionCode` and `versionName` in `app/build.gradle.kts`, then tag:

```bash
git tag v0.3
git push origin v0.3
```

`.github/workflows/release.yml` builds the APK and publishes it on a GitHub Release, so
binaries never enter git history.

## Memory

Pip keeps short notes about you between conversations. They are pulled out locally by
pattern matching — "my name is …", "I'm working on …", "I like …", "I live in …", and
anything said after "remember" — so remembering costs no extra API calls. The notes are
appended to the prompt, and the **Memory** screen shows every one of them so you can
delete a bad capture. The conversation itself is still capped at the last 24 messages.

## Changing the character

- **Personality**: the **Personality** button in the app opens the prompt in an editor and
  saves it on the device. The built-in version lives in `Persona.kt`, and **reset**
  restores it. The note about which app you are in is appended automatically and is not
  part of the editable text.
- Look and animation: `app/src/main/java/com/phoneassistant/PetView.kt`
- Walk speed, idle timing, window size: `OverlayService.kt`

The API key is stored only in this app's private preferences on the device.
