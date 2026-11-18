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

**Debug builds** use `signing/phone-assistant.jks`, which is committed and public. That is
what lets local builds install over the top without uninstalling. It is a sideloading
convenience, never a distribution key.

**Release builds** use your own private keystore. It is not in this repo, so cloning the
project never exposes it. Create it once, on your own machine:

```bash
keytool -genkeypair -v -keystore phone-assistant-release.jks \
  -alias phoneassistant -keyalg RSA -keysize 4096 -validity 10000

# base64 it for the GitHub secret (works on macOS and Linux)
base64 phone-assistant-release.jks | tr -d '\n' > release-base64.txt
```

Then add four secrets under **Settings → Secrets and variables → Actions**:

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | contents of `release-base64.txt` |
| `RELEASE_KEYSTORE_PASSWORD` | the store password you typed |
| `RELEASE_KEY_ALIAS` | `phoneassistant` |
| `RELEASE_KEY_PASSWORD` | the key password (same as the store password if you just pressed enter) |

Back up the `.jks` file somewhere safe. If you lose it you can never update an installed
release again — only ship a new package name.

### Publishing a release

Bump `versionCode` and `versionName` in `app/build.gradle.kts`, then tag:

```bash
git tag v0.3
git push origin v0.3
```

`.github/workflows/release.yml` builds, signs and publishes a GitHub Release with the APK
attached.

### One catch

Debug and release builds have different signatures, so Android treats them as different
apps. Installing the first release build over an installed debug build needs a single
uninstall; after that, releases update seamlessly over each other.

Running `assembleRelease` without the env vars still works, but falls back to the public
debug key and prints a warning — useful for testing, useless for distribution.

## Changing the character

- Personality, name and rules: `app/src/main/java/com/phoneassistant/Persona.kt`
- Look and animation: `app/src/main/java/com/phoneassistant/PetView.kt`
- Walk speed, idle timing, window size: `OverlayService.kt`

The API key is stored only in this app's private preferences on the device.
