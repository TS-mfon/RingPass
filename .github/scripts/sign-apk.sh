#!/usr/bin/env bash
set -euo pipefail
umask 077

unsigned_apk="${1:?Usage: sign-apk.sh unsigned.apk signed.apk}"
signed_apk="${2:?Usage: sign-apk.sh unsigned.apk signed.apk}"
: "${ANDROID_HOME:?Android SDK path required}"
: "${RINGPASS_SIGNING_KEYSTORE_B64:?RingPass keystore required}"
: "${RINGPASS_SIGNING_STORE_PASSWORD:?RingPass store password required}"
: "${RINGPASS_SIGNING_KEY_ALIAS:?RingPass key alias required}"
: "${RINGPASS_SIGNING_CERT_SHA256:?RingPass certificate fingerprint required}"
export RINGPASS_SIGNING_KEY_PASSWORD="${RINGPASS_SIGNING_KEY_PASSWORD:-$RINGPASS_SIGNING_STORE_PASSWORD}"

expected_fingerprint="$(printf '%s' "$RINGPASS_SIGNING_CERT_SHA256" | tr -d ':[:space:]' | tr '[:upper:]' '[:lower:]')"
if [[ ! "$expected_fingerprint" =~ ^[a-f0-9]{64}$ ]]; then
    echo 'RingPass signing certificate fingerprint must be a SHA-256 digest.' >&2
    exit 1
fi

apksigner="$(find "$ANDROID_HOME/build-tools" -mindepth 2 -maxdepth 2 -name apksigner -type f | sort -V | tail -n 1)"
if [[ ! -x "$apksigner" ]]; then
    echo 'apksigner was not found in Android SDK Build Tools.' >&2
    exit 1
fi

signing_dir="$(mktemp -d)"
trap 'rm -rf -- "$signing_dir"' EXIT
printf '%s' "$RINGPASS_SIGNING_KEYSTORE_B64" | base64 --decode > "$signing_dir/keystore"
unset RINGPASS_SIGNING_KEYSTORE_B64

"$apksigner" sign --ks "$signing_dir/keystore" \
    --ks-key-alias "$RINGPASS_SIGNING_KEY_ALIAS" \
    --ks-pass env:RINGPASS_SIGNING_STORE_PASSWORD \
    --key-pass env:RINGPASS_SIGNING_KEY_PASSWORD \
    --out "$signing_dir/RingPass-signed.apk" "$unsigned_apk"
unset RINGPASS_SIGNING_STORE_PASSWORD RINGPASS_SIGNING_KEY_PASSWORD
verification="$("$apksigner" verify --print-certs "$signing_dir/RingPass-signed.apk")"
actual_fingerprint="$(printf '%s\n' "$verification" | awk '/^Signer #1 certificate SHA-256 digest: / { print $NF }')"
if [[ "$actual_fingerprint" != "$expected_fingerprint" ]]; then
    echo 'RingPass APK signer does not match the configured certificate. The APK will not be published.' >&2
    exit 1
fi

mv -- "$signing_dir/RingPass-signed.apk" "$signed_apk"
printf 'Verified RingPass signing certificate %s\n' "$actual_fingerprint"
