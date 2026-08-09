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
