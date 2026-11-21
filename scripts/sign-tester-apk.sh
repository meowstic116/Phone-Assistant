#!/usr/bin/env bash
# Run locally. Passwords are prompted for by apksigner, never passed as arguments.
set -euo pipefail

if [[ $# != 4 ]]; then
    echo "Usage: $0 UNSIGNED.apk PRIVATE_KEYSTORE KEY_ALIAS OUTPUT.apk" >&2
    exit 2
fi
input=$1
keystore=$2
alias=$3
output=$4
fail() { printf '%s\n' "$*" >&2; exit 1; }
[[ -f "$input" && -f "$keystore" ]] || fail 'The input APK and private keystore must both exist.'
[[ "$output" == *.apk && ! -e "$output" && ! -L "$output" ]] || fail 'Choose a new output .apk path.'

# Set ANDROID_BUILD_TOOLS to an SDK build-tools directory if these aren't on PATH.
if [[ -n "${ANDROID_BUILD_TOOLS:-}" ]]; then
    export PATH="$ANDROID_BUILD_TOOLS:$PATH"
fi
command -v aapt2 >/dev/null || fail 'Install Android SDK Build-Tools and set ANDROID_BUILD_TOOLS.'
command -v apksigner >/dev/null || fail 'Install Android SDK Build-Tools and set ANDROID_BUILD_TOOLS.'

command -v python3 >/dev/null || fail 'Install Python 3 to publish the verified APK safely.'
badging=$(aapt2 dump badging "$input")
if ! grep -qF "package: name='com.phoneassistant.pip'" <<< "$badging" ||
    grep -q '^application-debuggable' <<< "$badging"; then
    fail 'Expected the non-debuggable Pip Tester APK, not a legacy or development build.'
fi
if apksigner verify "$input" >/dev/null 2>&1; then
    fail 'Expected an unsigned APK. Use the original assembleRelease output.'
fi

# Stage and verify before creating the requested output; never overwrite an older release.
stage=$(mktemp -d "$(dirname -- "$output")/.pip-sign.XXXXXXXX")
trap 'rm -rf -- "$stage"' EXIT
signed="$stage/signed.apk"
apksigner sign --ks "$keystore" --ks-key-alias "$alias" --out "$signed" "$input"
certs=$(apksigner verify --verbose --print-certs "$signed")
if ! grep -qx 'Number of signers: 1' <<< "$certs"; then
    fail 'Expected exactly one signing certificate.'
fi
if grep -Eiq '(^Signer #1 certificate DN:.*CN=Android Debug|cb3ecf88e83faa707c656d6ae2cf6a4125e0dc677664e9e2606f78ad880d039b)' <<< "$certs"; then
    fail 'Do not distribute APKs signed with Android debug keys or the old public Pip key.'
fi
# Create the exact requested filename atomically, even if another process used it during signing.
python3 -c 'import os, sys; os.link(sys.argv[1], sys.argv[2])' "$signed" "$output"
printf '%s\n\nReady to share: %s\n' "$certs" "$output"
