# LinOx Linux Runtime v0.3

LinOx uses a **rootless Linux userspace** rather than replacing the Android kernel.
The runtime is:

`Android kernel -> LinOx APK -> PRoot -> ARM64 Linux rootfs -> /bin/sh`

## Current v0.3 workflow

1. Install a native ARM64 `proot` executable through **Settings → Install PRoot**.
2. Import an ARM64 `.tar.gz` Linux rootfs through **Settings → Install Linux rootfs**.
3. Press **Test Linux**.
4. Open Terminal and run commands.

A valid rootfs must contain at least:

```text
/bin/sh
/etc/
/usr/
```

Debian officially supports arm64, and the Debian project documents pre-built ARM64 rootfs options. PRoot-based rootless Linux environments are also used by PRoot-Distro on Android. See the project documentation before redistributing third-party binaries/rootfses.

## What is not implemented yet

- interactive PTY terminal (current terminal is command-at-a-time);
- automatic OCI/Docker image pulling;
- package manager bootstrap;
- persistent Linux background services;
- GUI Linux desktop/Wayland/X11;
- sandboxing stronger than PRoot's userspace isolation.

The next milestone is an **interactive PTY shell**, followed by an Ubuntu/Debian image installer.
