LinOx Mobile 1.0.0
===================

The app downloads ARM64 OCI images at runtime instead of bundling giant rootfs
archives into the APK. This keeps the APK build small and lets users choose
which distributions to install.

Catalog:
- Ubuntu 24.04 LTS
- Debian 12
- Alpine Linux 3.23
- Fedora 44
- openSUSE Leap 15
- Rocky Linux 10
- Arch Linux ARM
- Kali Linux Rolling

PRoot:
- Settings/Distributions -> SELECT PRoot (ARM64)
- The selected binary is validated as AArch64 ELF and executed with --version
  before it replaces the active PRoot.

Network:
- Android INTERNET permission is enabled.
- LinOx rebuilds /etc/resolv.conf from Android DNS properties before launching
  Linux, with 1.1.1.1/8.8.8.8 as fallback.
- PRoot binds /dev, /proc and /sys and exposes Android /system/bin as
  /android-bin.

Terminal:
- Interactive PTY starts bash when available and falls back to /bin/sh.
- Commands are executed inside the active Linux rootfs.

Package manager:
- apt / apt-get for Debian, Ubuntu and Kali
- apk for Alpine
- dnf for Fedora/Rocky
- zypper for openSUSE
- pacman for Arch Linux ARM

IMPORTANT:
The APK does not contain every Linux rootfs or a PRoot binary. Those are large
native assets and must be selected/downloaded at runtime. This avoids a huge
APK and avoids shipping a device-specific native PRoot binary.
