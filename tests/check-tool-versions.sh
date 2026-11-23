#!/usr/bin/env bash
# The app builds with one Kotlin version and the host tests compile with another (the npm
# kotlin-compiler package in CI). They must move together, and the Gradle wrapper must pin
# the distribution checksum, or a supply-chain check goes missing silently.
set -euo pipefail
cd "$(dirname "$0")/.."
app=$(sed -n 's/.*id("org.jetbrains.kotlin.android") version "\([^"]*\)".*/\1/p' build.gradle.kts)
ci=$(sed -n 's/.*kotlin-compiler@\([0-9][0-9.]*\).*/\1/p' .github/workflows/checks.yml | head -1)
[[ -n "$app" ]] || { echo "could not find the Kotlin plugin version in build.gradle.kts" >&2; exit 1; }
[[ "$app" == "$ci" ]] || {
    echo "Kotlin version drift: the app builds with $app but the host tests compile with ${ci:-nothing}." >&2
    echo "Update build.gradle.kts and .github/workflows/checks.yml together." >&2
    exit 1
}
grep -q '^distributionSha256Sum=' gradle/wrapper/gradle-wrapper.properties || {
    echo "gradle/wrapper/gradle-wrapper.properties is missing distributionSha256Sum" >&2
    exit 1
}
echo "tool versions agree (Kotlin $app)"
