#!/usr/bin/env bash
# Run once on the repository owner's computer, never in CI.
set -euo pipefail
umask 077

repo=${1:-meowstic116/Phone-Assistant}
backup="$HOME/.pip-signing/pip-dev"
key="$backup/pip-dev.p12"
password_file="$backup/password"
secret=PIP_DEV_SIGNING_BUNDLE
pin=PIP_DEV_CERT_SHA256

for tool in gh keytool openssl python3; do
    command -v "$tool" >/dev/null || { echo "Install $tool before running this script." >&2; exit 1; }
done

# Don't rotate a live key. Stop on permission errors instead of assuming the secret is absent.
secrets=$(gh secret list --repo "$repo")
if grep -Eq "^$secret([[:space:]]|$)" <<< "$secrets"; then
    echo "Pip Dev signing is already configured for $repo. Nothing was changed."
    exit 0
fi
variables=$(gh variable list --repo "$repo")
existing_pin=$(awk -v name="$pin" '$1 == name { print $2 }' <<< "$variables")
if [[ ! -f "$key" && ! -f "$password_file" && -z "$existing_pin" ]]; then
    mkdir -p -- "$HOME/.pip-signing"
    chmod 700 -- "$HOME/.pip-signing"
    mkdir -- "$backup"
    openssl rand -hex 32 > "$password_file"
    keytool -genkeypair -keystore "$key" -storetype PKCS12 -alias pip-dev \
        -storepass:file "$password_file" -keyalg RSA -keysize 3072 -validity 10000 \
        -dname 'CN=Pip Dev'
fi
if [[ ! -f "$key" || ! -f "$password_file" ]]; then
    echo "Restore both the Pip Dev keystore and its password from your backup before proceeding." >&2
    exit 1
fi
fingerprint=$(keytool -exportcert -keystore "$key" -alias pip-dev -storepass:file "$password_file" |
    openssl dgst -sha256 | awk '{ print tolower($NF) }')
[[ "$fingerprint" =~ ^[a-f0-9]{64}$ ]] || { echo 'Could not read the signing certificate.' >&2; exit 1; }
if [[ -n "$existing_pin" ]]; then
    if [[ "$existing_pin" != "$fingerprint" ]]; then
        echo "The local key differs from the pinned Pip Dev certificate. Restore the original key." >&2
        exit 1
    fi
else
    gh variable set "$pin" --repo "$repo" --body "$fingerprint"
fi

# Pipe the private material directly to gh; don't print it or put it in shell arguments.
python3 - "$key" "$password_file" <<'PY' | gh secret set "$secret" --repo "$repo"
import base64
import json
import sys
from pathlib import Path

print(json.dumps({"keystore": base64.b64encode(Path(sys.argv[1]).read_bytes()).decode("ascii"),
                  "password": Path(sys.argv[2]).read_text().strip()}))
PY
printf 'Pip Dev signing configured for %s.\nCertificate SHA-256: %s\n' "$repo" "$fingerprint"
printf 'Back up the keystore and password securely. Local files: %s\n' "$backup"
