#!/usr/bin/env python3
"""Offline sanity checks for a LinOx Mobile release tree."""
from pathlib import Path
import re
import struct
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
errors = []

def need(path: str):
    if not (ROOT / path).exists():
        errors.append(f"missing: {path}")

for path in [
    "settings.gradle.kts",
    "build.gradle.kts",
    "app/build.gradle.kts",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/assets/proot-aarch64-static",
    "app/src/main/java/org/linox/mobile/LinuxRuntime.kt",
    "app/src/main/java/org/linox/mobile/DistroManager.kt",
]:
    need(path)

if not errors:
    manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml")
    app = manifest.getroot().find("application")
    if app is None:
        errors.append("manifest: application missing")

    gradle = (ROOT / "app/build.gradle.kts").read_text()
    if 'versionName = "0.9.0"' not in gradle:
        errors.append("build: versionName is not 0.9.0")

    dm = (ROOT / "app/src/main/java/org/linox/mobile/DistroManager.kt").read_text()
    for distro_id in ["ubuntu2404", "debian12", "alpine322", "fedora44", "archlinux", "kali", "rocky10", "opensuse156"]:
        if f'"{distro_id}"' not in dm:
            errors.append(f"distro catalog missing: {distro_id}")

    proot = (ROOT / "app/src/main/assets/proot-aarch64-static").read_bytes()
    if len(proot) < 4096:
        errors.append("PRoot binary is suspiciously small")
    elif proot[:4] != b"\x7fELF" or proot[4] != 2 or proot[5] != 1 or struct.unpack_from("<H", proot, 18)[0] != 183:
        errors.append("PRoot is not a little-endian AArch64 ELF")

if errors:
    print("RELEASE CHECK: FAIL")
    print("\n".join(f"- {e}" for e in errors))
    sys.exit(1)

print("RELEASE CHECK: PASS")
print("• Android project structure: OK")
print("• version: 0.9.0")
print("• ARM64 PRoot ELF: OK")
print("• Linux distro catalog: OK")
