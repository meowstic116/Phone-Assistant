# Share Pip with a small test group

**Pip Tester** (`com.phoneassistant.pip`) is a separate install from the old **Phone
Assistant** app. It installs alongside the old app without deleting anything. The new
app starts with its own empty history, settings, and permissions; turn the old assistant
and listener off before testing Pip Tester so the two don’t compete. **Pip Dev** (`.debug`)
is a separate, local-development build and should not be shared.

## 1. Create a private signing key once

On your own computer, install JDK 17 and Android SDK Platform 34 / Build-Tools (Android
Studio can install them). Keep the keystore **outside** the Git checkout. This example
uses a macOS/Linux-style shell; Windows users can also sign via Android Studio’s
**Build → Generate Signed Bundle / APK → APK** instead.

```bash
mkdir -p "$HOME/.pip-signing"
chmod 700 "$HOME/.pip-signing"
keytool -genkeypair -keystore "$HOME/.pip-signing/pip-tester.p12" \
  -storetype PKCS12 -alias pip-tester -keyalg RSA -keysize 3072 -validity 10000
```

Use a strong password when prompted. Make a protected backup of the keystore and store
the password in your password manager. Never upload the key, send it to a tester, paste
it in chat, or reuse the old public key. Use **the same private key** for all tester
updates. If you lose it, testers will normally need to uninstall before installing a
build signed with a different key, which can erase their local app data.

## 2. Build and sign

Run `./gradlew :app:assembleRelease` from a trusted checkout, or download and unzip the
**unsigned** artifact from the manually triggered **Prepare unsigned tester APK** workflow
on a trusted GitHub ref. The workflow has no signing secrets and does not publish a release.

```bash
export ANDROID_BUILD_TOOLS="$ANDROID_HOME/build-tools/34.0.0" # or your installed version
mkdir -p artifacts
./scripts/sign-tester-apk.sh app/build/outputs/apk/release/app-release-unsigned.apk \
  "$HOME/.pip-signing/pip-tester.p12" pip-tester artifacts/pip-tester-0.189.apk
```

For a downloaded artifact, substitute the path to its `app-release-unsigned.apk`.
The helper checks the package, signs the APK, verifies the signature, and prints the
**public** certificate fingerprint. Record that fingerprint so you can compare future
builds. Keep the Kotlin namespace (`com.phoneassistant`) separate from the tester’s app ID.
For each update, increment `versionCode` and `versionName` in `app/build.gradle.kts` and
sign again with the same key. There is no automatic public update check for this edition.

## 3. Smoke-test and share the signed APK only

- On a phone running Android 8.0 or newer, install the APK and make sure the launcher
  shows **Pip Tester**. Install a later build with the same key to check that Android
  accepts updates and preserves settings. Test overlay, typed chat, and any opt-in voice
  features you intend to ask friends to try.
- Send the **signed APK** directly to your friends over a trusted channel; don’t send
  the unsigned build or the keystore. If Android asks, allow installs from the app they
  use to open the file, then turn that install permission off again afterward. Don’t
  disable Play Protect or bypass an unexpected signature warning.
- Testers supply **their own AI provider API key** during setup. Don’t share yours.
  Typed chat goes to their configured provider; model-assisted accessibility features
  can send visible screen text. Overlay permission is needed for the character;
  accessibility, microphone, and notifications are optional and can be left off when
  testing basic typed chat. Test driving features only while parked.
- Ask for the Android version, phone model, app version, and steps to reproduce problems.
  Ask testers to redact API keys, chat text, and personal information from bug reports.

The old key is permanently public in repository history. Removing it from the latest
checkout does not revoke it or make old APKs safe to share as trusted builds. Do not
rewrite Git history as a substitute for rotating the signing identity.
