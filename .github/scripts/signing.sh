#!/usr/bin/env bash
# Zonk-Core release signing, used by .github/workflows/build.yml.
#
#   signing.sh check          stop early if the signing key is missing or cannot be opened
#   signing.sh sign <apk>     re-sign <apk> with the permanent key
#
# The key lives in two GitHub secrets (ZONK_KEYSTORE_BASE64 and ZONK_KEYSTORE_PASSWORD). It only exists as a file
# while this script runs and is deleted when it ends. Nothing secret is ever printed.
set -eu

mode="${1:-}"
apk="${2:-}"

if [ -z "${ZONK_KEYSTORE_BASE64:-}" ] || [ -z "${ZONK_KEYSTORE_PASSWORD:-}" ]; then
  if [ "${GITHUB_REPOSITORY:-}" = "karolek13245/zonk-core" ]; then
    echo "::error::The signing key is missing. Add the repository secrets ZONK_KEYSTORE_BASE64 and ZONK_KEYSTORE_PASSWORD (Settings > Secrets and variables > Actions)."
    exit 1
  fi
  echo "::warning::No signing key is set, so this APK has a throwaway debug key and will not install over an older Zonk-Core."
  exit 0
fi

KS="${RUNNER_TEMP:-/tmp}/zonk-release.p12"
trap 'rm -f "$KS"' EXIT

open_key() {
  if ! printf '%s' "$ZONK_KEYSTORE_BASE64" | tr -d '[:space:]' | base64 -d > "$KS"; then
    echo "::error::ZONK_KEYSTORE_BASE64 is not valid base64. Paste the whole text of the file again."
    exit 1
  fi
  ZONK_KEYSTORE_PASSWORD="$(printf '%s' "$ZONK_KEYSTORE_PASSWORD" | tr -d '\r\n')"
  export ZONK_KEYSTORE_PASSWORD
  if ! keytool -list -keystore "$KS" -storetype PKCS12 -storepass:env ZONK_KEYSTORE_PASSWORD > /dev/null; then
    echo "::error::The signing key could not be opened. Check the secrets ZONK_KEYSTORE_BASE64 and ZONK_KEYSTORE_PASSWORD."
    exit 1
  fi
}

case "$mode" in
  check)
    open_key
    echo "The signing key is set and opens fine."
    ;;
  sign)
    if [ ! -f "$apk" ]; then
      echo "::error::APK not found: $apk"
      exit 1
    fi
    SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
    APKSIGNER="$(ls -d "$SDK"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -n 1)"
    if [ -z "$APKSIGNER" ]; then
      echo "::error::apksigner was not found in the Android SDK."
      exit 1
    fi
    open_key
    "$APKSIGNER" sign --ks "$KS" --ks-type PKCS12 --ks-key-alias zonk \
      --ks-pass env:ZONK_KEYSTORE_PASSWORD --key-pass env:ZONK_KEYSTORE_PASSWORD "$apk"
    OUT="$("$APKSIGNER" verify --print-certs "$apk")"
    DIGEST="$(printf '%s\n' "$OUT" | awk '/certificate SHA-256 digest/ {print $NF; exit}')"
    echo "Signed with the Zonk-Core key. Certificate SHA-256: $DIGEST" | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"
    ;;
  *)
    echo "usage: signing.sh check | sign <apk>" >&2
    exit 2
    ;;
esac
