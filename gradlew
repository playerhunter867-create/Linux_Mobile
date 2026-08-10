#!/usr/bin/env sh
set -eu
DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
cd "$DIR"
if [ ! -f "$DIR/gradle/wrapper/gradle-wrapper.jar" ]; then
  if ! command -v gradle >/dev/null 2>&1; then
    echo "[LinOx] Gradle is required. Run: pkg update && pkg install openjdk-17 gradle -y"
    exit 1
  fi
  echo "[LinOx] Generating Gradle 8.9 wrapper..."
  gradle wrapper --gradle-version 8.9
  exec "$DIR/gradlew" "$@"
fi
# If a real wrapper is present but this bootstrap script is still here, use system Gradle.
exec gradle "$@"
