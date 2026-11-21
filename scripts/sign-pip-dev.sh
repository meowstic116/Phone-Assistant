#!/usr/bin/env bash
# Used only by the Pip Dev workflow. Never accepts a tester or legacy APK.
set -euo pipefail
umask 077
fail() { echo "$*" >&2; exit 1; }
[[ $# == 2 && -f "$1" && ! -e "$2" && ! -L "$2" ]] || fail 'Usage: sign-pip-dev.sh UNSIGNED.apk NEW_OUTPUT.apk'
[[ -n "${PIP_DEV_SIGNING_BUNDLE:-}" ]] || fail 'Missing PIP_DEV_SIGNING_BUNDLE. See DISTRIBUTION.md.'
[[ "${PIP_DEV_CERT_SHA256:-}" =~ ^[a-f0-9]{64}$ ]] || fail 'Missing or invalid PIP_DEV_CERT_SHA256. See DISTRIBUTION.md.'
build_tools=${ANDROID_BUILD_TOOLS:?Set ANDROID_BUILD_TOOLS to the Android SDK Build-Tools directory.}
signer="$build_tools/apksigner"
aapt2="$build_tools/aapt2"
[[ -x "$signer" && -x "$aapt2" ]] || fail 'Android SDK Build-Tools are not installed.'
badging=$("$aapt2" dump badging "$1")
grep -q "^package: name='com\.phoneassistant\.pip\.debug' " <<< "$badging" &&
    grep -qx 'application-debuggable' <<< "$badging" || fail 'Expected a debuggable Pip Dev APK.'
if "$signer" verify "$1" >/dev/null 2>&1; then
    fail 'Expected unsigned Pip Dev input.'
fi

stage=$(mktemp -d "$(dirname -- "$2")/.pip-dev-sign.XXXXXXXX")
trap 'rm -rf -- "$stage"' EXIT
python3 - "$stage" <<'PY'
import base64
import json
import os
import sys
from pathlib import Path

bundle = json.loads(os.environ["PIP_DEV_SIGNING_BUNDLE"])
path = Path(sys.argv[1])
(path / "key.p12").write_bytes(base64.b64decode(bundle["keystore"], validate=True))
(path / "password").write_text(bundle["password"] + "\n")
PY
unset PIP_DEV_SIGNING_BUNDLE
signed="$stage/signed.apk"
"$signer" sign --ks "$stage/key.p12" --ks-key-alias pip-dev \
    --ks-pass "file:$stage/password" --out "$signed" "$1"
report=$("$signer" verify --verbose --print-certs "$signed")
grep -qx 'Number of signers: 1' <<< "$report" || fail 'Expected one signer.'
fingerprint=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<< "$report")
[[ "$fingerprint" == "$PIP_DEV_CERT_SHA256" ]] || fail 'Wrong Pip Dev signing key. No APK was published.'
# os.link does not treat an existing directory (or a directory symlink) as a target folder.
python3 -c 'import os, sys; os.link(sys.argv[1], sys.argv[2])' "$signed" "$2"
printf 'Verified Pip Dev certificate SHA-256: %s\n' "$fingerprint"
