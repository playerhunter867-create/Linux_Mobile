#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd "$(dirname "$0")"

if ! command -v java >/dev/null 2>&1; then
  echo "Install Java first: pkg install openjdk-17 -y"
  exit 1
fi
if ! command -v gradle >/dev/null 2>&1; then
  echo "Install Gradle first: pkg install gradle -y"
  exit 1
fi

export JAVA_HOME="${JAVA_HOME:-$PREFIX/lib/jvm/java-17-openjdk}"
export GRADLE_OPTS="${GRADLE_OPTS:--Xmx2g}"

if [ -n "${ANDROID_HOME:-}" ]; then
  printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
elif [ -n "${ANDROID_SDK_ROOT:-}" ]; then
  printf 'sdk.dir=%s\n' "$ANDROID_SDK_ROOT" > local.properties
fi

gradle --version
gradle clean --no-daemon
gradle :app:assembleDebug --no-daemon --stacktrace

APK="$(find app/build/outputs/apk/debug -type f -name '*.apk' | head -n 1)"
if [ -z "$APK" ]; then
  echo "APK not found"
  exit 1
fi

mkdir -p "$HOME/storage/downloads" 2>/dev/null || true
cp "$APK" "$HOME/storage/downloads/LinOx-0.9.1-debug.apk" 2>/dev/null || true

echo
echo "=== LinOx APK READY ==="
echo "$APK"
