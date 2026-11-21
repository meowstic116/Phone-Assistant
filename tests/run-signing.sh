#!/usr/bin/env bash
# Exercise output publication with stub SDK tools, without generating or using any signing key.
set -euo pipefail
cd "$(dirname "$0")/.."
tmp=$(mktemp -d)
trap 'rm -rf -- "$tmp"' EXIT
mkdir "$tmp/bin"
cat > "$tmp/bin/aapt2" <<'STUB'
#!/usr/bin/env bash
if [[ "$3" == *dev.apk ]]; then
    printf "package: name='com.phoneassistant.pip.debug' versionCode='190'\napplication-debuggable\n"
else
    printf "package: name='com.phoneassistant.pip' versionCode='190'\n"
fi
STUB
cat > "$tmp/bin/apksigner" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$1" == sign ]]; then
    if [[ "${COLLISION:-}" == directory ]]; then
        mkdir -- "$TEST_OUTPUT"
    elif [[ "${COLLISION:-}" == file ]]; then
        printf 'keep me\n' > "$TEST_OUTPUT"
    fi
    while [[ "$1" != --out ]]; do shift; done
    printf 'verified apk\n' > "$2"
else
    [[ "${@: -1}" == */signed.apk ]] || exit 1
    printf 'Number of signers: 1\nSigner #1 certificate SHA-256 digest: %s\n' "$PIP_DEV_CERT_SHA256"
fi
STUB
chmod +x "$tmp/bin/"*
touch "$tmp/dev.apk" "$tmp/tester.apk" "$tmp/dummy.p12"
export ANDROID_BUILD_TOOLS="$tmp/bin"
export PIP_DEV_SIGNING_BUNDLE='{"keystore":"dGVzdA==","password":"dummy"}'
export PIP_DEV_CERT_SHA256=$(printf 'a%.0s' {1..64})
run_signer() {
    case "$1" in
        dev) scripts/sign-pip-dev.sh "$tmp/dev.apk" "$TEST_OUTPUT" ;;
        tester) scripts/sign-tester-apk.sh "$tmp/tester.apk" "$tmp/dummy.p12" dummy "$TEST_OUTPUT" ;;
    esac
}
for stream in dev tester; do
    export TEST_OUTPUT="$tmp/$stream-new.apk"
    run_signer "$stream" >/dev/null
    grep -qx 'verified apk' "$TEST_OUTPUT"
    if run_signer "$stream" >/dev/null 2>&1; then echo 'Overwrote existing output' >&2; exit 1; fi
    for collision in directory file; do
        export TEST_OUTPUT="$tmp/$stream-$collision.apk"
        if COLLISION="$collision" run_signer "$stream" >/dev/null 2>&1; then
            echo "Unexpected signing success with a concurrent $collision" >&2; exit 1
        fi
        if [[ "$collision" == directory ]]; then
            [[ -d "$TEST_OUTPUT" && ! -e "$TEST_OUTPUT/signed.apk" ]]
        else
            grep -qx 'keep me' "$TEST_OUTPUT"
        fi
    done
done
printf 'Signing output checks passed.\n'
