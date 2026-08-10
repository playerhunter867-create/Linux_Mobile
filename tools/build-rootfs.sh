#!/usr/bin/env bash
set -euo pipefail

# Build a Debian userspace on a Linux build host.
# This is intentionally separate from the Android app build.
# A runtime such as a compatible proot/namespace layer must be supplied
# before this rootfs can execute inside the APK.

OUT="${1:-linox-rootfs}"
ARCH="${2:-arm64}"

if [ "$(id -u)" -ne 0 ]; then
  echo "Run as root on a Debian/Ubuntu Linux build host."
  exit 1
fi

apt-get update
apt-get install -y debootstrap qemu-user-static binfmt-support

rm -rf "$OUT"
mkdir -p "$OUT"

debootstrap --arch="$ARCH" --variant=minbase bookworm "$OUT" http://deb.debian.org/debian

cat > "$OUT/etc/os-release" <<'EOF'
NAME="LinOx"
ID=linox
PRETTY_NAME="LinOx Mobile Linux userspace"
VERSION_ID="0.1"
EOF

echo "Rootfs created at $OUT"
echo "Next step: package it with a compatible Android Linux runtime."
