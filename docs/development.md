# Developing Pip

## Building

You need JDK 17 and Android SDK Platform 34 (Android Studio installs both). Then:

```bash
./gradlew installDebug        # installs the local **Pip Dev** app
./gradlew :app:assembleRelease  # unsigned tester APK, to be signed per DISTRIBUTION.md
```

The first build downloads the pinned, checksum-verified Kokoro runtime from npm (see
`app/build.gradle.kts`); the phone downloads voice models separately only if requested.

## Running the checks

CI (`.github/workflows/checks.yml`) runs everything below on every push and pull request.
Run locally what you touched:

| Check | Command | Needs |
| --- | --- | --- |
| Build | `./gradlew :app:assembleDebug :app:assembleRelease` | JDK 17, Android SDK |
| Lint | `./gradlew :app:lint` | JDK 17, Android SDK |
| Screen builds | `./gradlew :app:testDebugUnitTest` | JDK 17, Android SDK, network for Robolectric's android-all jars |
| Host regression tests | `tests/run-job-progress.sh` | `kotlinc` 1.9.x on PATH, JRE 17+ |
| Model archive handling | `tests/run-kokoro-files.sh` | `kotlinc`, JRE, and the Ant jar (found automatically; see script) |
| Signing scripts | `tests/run-signing.sh` | bash |
| Settings layout guard | `tests/run-settings-layout.sh` | bash |
| Tool versions agree | `tests/check-tool-versions.sh` | bash |

The easiest way to get `kotlinc` without installing a full Kotlin distribution is the npm
package CI uses:

```bash
npm install --ignore-scripts --prefix "$TMPDIR/kotlin" kotlin-compiler@1.9.24
export PATH="$TMPDIR/kotlin/node_modules/kotlin-compiler/bin:$PATH"
```

### The host test harness

Most of the app's rules are deliberately Android-free Kotlin — request parsing
(`JobProgress`), surface detection policy, voice protocol checks, the rolling chat window
(`ChatWindow`), site matching for the vault (`SiteHost`), and more. `tests/run-job-progress.sh`
compiles those files together with `tests/JobProgressTest.kt` and runs its `main()`, which
is a long list of `check(...)` assertions. It needs no emulator, so it runs on every PR.

Two rules keep it honest:

1. A file may join the harness only if it has no Android imports (no `android.*`, no
   `org.json`). The pure rules are split into their own files exactly so they can run here.
2. When you add a pure-logic file that the tests should cover, add it to the compile list
   in `tests/run-job-progress.sh` (and to `tests/run-kokoro-files.sh` only if it is part of
   that test). A file that is not listed is not tested.

`tests/run-settings-layout.sh` is a text-level guard for `PipSettingsActivity`, whose
screen is assembled in code; the real render of that screen (and the other screens) is
covered by `app/src/test/java/com/phoneassistant/ScreensRenderTest.kt` under Robolectric.

## Tool versions

The app builds with the Kotlin version in `build.gradle.kts`; the host tests compile with
the npm `kotlin-compiler` pin in `checks.yml`. They must move together —
`tests/check-tool-versions.sh` fails the build if they drift, and also checks that
`gradle/wrapper/gradle-wrapper.properties` pins `distributionSha256Sum`. Dependabot
(`.github/dependabot.yml`) proposes Gradle and GitHub Actions updates weekly.

## Code map

- `OverlayService.kt` owns the overlay windows and the character's state machine. It is
  large on purpose (one place to look), with self-contained panels split into extension
  files beside it — `OverlayServiceHud.kt` (the floating diagnostics readout) is the
  pattern: state stays with the service, the windows move out. Keep splitting along those
  lines when touching a cluster.
- `PetView.kt` draws the character; `Prop.kt` is the toy base class; `Toys.kt` is the only
  way a toy arrives. See [toys](toys.md).
- `Screen.kt` + `ImeWatcher.kt` are the accessibility layer: reading the app in front and
  acting on it. `Agent.kt` runs one phone job as read-act-verify steps.
- `AssistantBrain.kt` talks to any OpenAI-compatible endpoint. The API key is sealed with
  the Android Keystore (`pip_brain` alias) before it reaches preferences; the logins
  vault (`Vault.kt`, `pip_vault` alias) uses the same pattern. Both are excluded from
  backup (`app/src/main/res/xml/backup_rules.xml`).
- `Diag.kt` keeps in-memory diagnostics for the Diagnostics screen, including swallowed
  exceptions — if you swallow one to keep the overlay alive, record it there.

## Distribution

Signing and tester sharing are documented in [DISTRIBUTION.md](../DISTRIBUTION.md). The
`Pip Dev` pre-release stream is published by the `pip-dev.yml` workflow from `main` only,
and never replaces an existing release asset.
