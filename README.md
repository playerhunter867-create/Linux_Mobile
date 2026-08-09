# LinOx shell startup patch

This archive contains **reconstructed patch files** for the startup chain:

Linux Setup → rootfs installation → active_rootfs → isLinuxReady() → PTY → PRoot → Linux shell

## What this fixes

- `isLinuxReady()` now checks:
  - `/bin/bash`
  - `/usr/bin/bash`
  - `/bin/sh`
  - `/usr/bin/sh`
- `installRootfsTarGz()` and `activateRootfs()` use the **same SharedPreferences key** (`active_rootfs`).
- Broken or stale `active_rootfs` entries are automatically removed.
- The shell used for validation is the **same shell used for startup**.
- PTY/PRoot startup is blocked until the rootfs is fully validated.
- The Linux state becomes READY immediately after installation without restarting the app.
- Corrupted rootfs installations produce a **clear setup error** instead of the misleading
  "Shell is not running. Open Linux Setup first."

## Most likely root cause in the current APK

The decompiled APK contains both:

- `isLinuxReady`
- `/rootfs/bin/sh`
- `/bin/bash`
- `active_rootfs`

This strongly suggests that the readiness check is tied to `/bin/sh`, while the runtime may
start `/bin/bash` or another shell path. If a Debian/Ubuntu rootfs has a valid shell but the
specific checked path is missing or symlinked differently, the app enters a false NOT READY state.

Apply the functions from these files into your real source tree:

- `app/src/main/java/org/linox/mobile/LinuxRuntime.kt`
- `app/src/main/java/org/linox/mobile/TerminalActivity.kt`
