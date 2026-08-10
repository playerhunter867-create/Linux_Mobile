#!/usr/bin/env sh
set -eu
DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
cd "$DIR"

# This repository intentionally keeps the wrapper bootstrap tiny. CI installs
# the pinned Gradle distribution; Termux/Android users may provide `gradle`.
if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi

echo "[LinOx] Gradle 8.11.1 is required."
echo "Install it with your package manager, or use the GitHub Actions workflow."
exit 1
