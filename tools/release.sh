#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

# Keystore stays outside the repository checkout.
# Override with BRIEF_KEYSTORE and BRIEF_STORE_PASSWORD_FILE if needed.
keydir="${XDG_DATA_HOME:-$HOME/.local/share}/brief/signing"
keystore="${BRIEF_KEYSTORE:-$keydir/release.p12}"
passfile="${BRIEF_STORE_PASSWORD_FILE:-$keydir/password}"

if [[ ! -e "$keystore" ]]; then
  install -d -m 700 "$(dirname "$keystore")"
  (umask 077
   if [[ ! -s "$passfile" ]]; then openssl rand -base64 30 > "$passfile"; fi
   keytool -genkeypair -storetype PKCS12 -keystore "$keystore" -alias release \
     -storepass:file "$passfile" -keyalg RSA -keysize 3072 -validity 10000 \
     -dname 'CN=Brief Release, O=Brief'
  )
fi

if command -v heavy-lock >/dev/null 2>&1; then
  heavy-lock ./gradlew :app:assembleRelease
else
  ./gradlew :app:assembleRelease
fi

apktool="$(ls -1 "$ANDROID_HOME"/build-tools/*/apksigner | sort -V | tail -n1)"
version="$(sed -n "s/.*versionName '\([^']*\)'.*/\1/p" app/build.gradle | head -n1)"
out="build/dist/Brief-v${version}-arm64-v8a.apk"
mkdir -p build/dist
"$apktool" sign --ks "$keystore" --ks-pass "file:$passfile" \
  --out "$out" app/build/outputs/apk/release/app-release-unsigned.apk
"$apktool" verify --verbose --print-certs "$out"
(cd build/dist && sha256sum "$(basename "$out")" > SHA256SUMS.txt)
cat build/dist/SHA256SUMS.txt
