# LinOx Mobile 0.9

## Major upgrade

- Reworked mobile dashboard and Linux OS manager.
- Ubuntu 24.04 and Debian 12 are first-class one-tap options.
- Added Alpine, Fedora, Arch, Kali, Rocky and openSUSE catalog entries.
- Bundled ARM64 PRoot is validated automatically.
- Fixed persistence of distro rootfs selection across app restarts.
- Added safe temporary `.part` downloads with SHA-256 verification and retries.
- Added OCI whiteout handling and hardlink support.
- Added `linox`, `linox-info`, `linox-doctor` and `ll` commands inside the guest.
- Added profile setup for PATH/LANG/TERM.
- Terminal now uses the included ANSI terminal renderer instead of a plain TextView.
- Removed unnecessary camera and microphone permissions.
- Updated project docs and CI release naming.
- Added an offline release verification script.

## Reality check

This is a serious rootless Linux userspace for Android, not a second Linux kernel.
It can run ordinary command-line Linux software that works under PRoot, but kernel
modules, systemd-dependent services and other kernel/privilege-sensitive software
remain outside the scope of a rootless Android userspace.
