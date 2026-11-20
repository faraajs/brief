#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

# Always run compileReleaseJavaWithJavac so Gradle rebuilds edited sources incrementally.
if command -v heavy-lock >/dev/null 2>&1; then
  heavy-lock ./gradlew :app:compileReleaseJavaWithJavac
else
  ./gradlew :app:compileReleaseJavaWithJavac
fi

android_jar="$(ls -1 "$ANDROID_HOME"/platforms/android-*/android.jar | sort -V | tail -n1)"
ort_classes="$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches" -name 'classes.jar' -path '*onnxruntime*' 2>/dev/null | head -n1 || true)"
app_classes="app/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes"
r_jar="$(find app/build/intermediates -name 'R.jar' -path '*release*' 2>/dev/null | head -n1 || true)"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

cp="$app_classes:$android_jar"
[[ -n "$r_jar" ]] && cp="$cp:$r_jar"
[[ -n "$ort_classes" ]] && cp="$cp:$ort_classes"

javac -cp "$cp" -d "$tmp" tests/SignalTest.java
java -cp "$tmp:$cp" id.eska.brief.SignalTest
