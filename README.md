# LinOx Mobile 0.9

LinOx Mobile is a rootless Linux userspace for ARM64 Android.

## What it actually is

LinOx does **not** replace the Android kernel and does not claim to boot a second kernel.
Android remains the host OS. LinOx combines:

- ARM64 PRoot userspace isolation/root mapping
- persistent Linux root filesystems
- an interactive PTY terminal
- an OCI/Docker image downloader
- SHA-256 layer verification
- OCI whiteout handling
- selectable Linux distributions
- Python / Git / nano / curl / wget bootstrap
- Android ↔ Linux workspace synchronisation
- a mobile-first dark dashboard

## Linux catalog

The app can install on demand:

- Ubuntu 24.04 LTS
- Debian 12 Bookworm (slim)
- Alpine 3.22
- Fedora 44
- Arch Linux ARM64
- Kali Linux Rolling
- Rocky Linux 10
- openSUSE Leap 15.6

The root filesystems are deliberately **not bundled into the APK** because even a minimal Ubuntu userspace is tens of MB compressed and substantially larger when extracted. They are downloaded from OCI registries when the user chooses an OS.

## Typical session

```text
Linux Mobile 0.9
└── Linux Manager
    ├── Ubuntu 24.04
    ├── Debian 12
    ├── Alpine 3.22
    ├── Fedora 44
    ├── Arch Linux
    ├── Kali Rolling
    ├── Rocky 10
    └── openSUSE Leap

Terminal:
$ python hello.py
$ nano hello.py
$ python hello.py
$ git status
$ linox doctor
```

## Important limitations

- ARM64 Android is the supported architecture.
- Linux applications share the Android kernel.
- PRoot is not a VM and does not provide hardware virtualisation.
- Systemd, kernel modules, Docker-in-Docker and other kernel-dependent features are not guaranteed.
- Android battery/background restrictions can terminate long-running sessions.
- Distribution installation requires network access and can consume hundreds of MB after extraction.

## Build

Open the project in Android Studio with Android SDK 35 and NDK 27.0.12077973.

The included Gradle launcher uses `/usr/bin/env sh`, so it is not hard-coded to Termux.

## Security model

OCI layers are downloaded over HTTPS and checked against their declared SHA-256 digest before activation. Archive paths are normalised to prevent `../` traversal. The app does not request camera or microphone permissions merely to run Linux.
