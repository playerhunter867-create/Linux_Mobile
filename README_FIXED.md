# LinOx Mobile 1.0.0 — fixed build/runtime drop-in

This is a complete replacement project snapshot based on the supplied Linux_Mobile project.

## Included
- ARM64 PRoot v5.3.0 supplied by the user, bundled as `app/src/main/assets/proot-aarch64-static`.
- Automatic first-run extraction/validation of bundled PRoot.
- Optional replacement with another ARM64 PRoot from the file picker.
- Ubuntu 24.04 LTS plus Debian 13, Alpine 3.22, Fedora 42, Arch Linux, Kali Rolling, Rocky Linux 10 and openSUSE Leap download/activation.
- OCI/Docker Hub ARM64 selection and SHA-256 verification.
- gzip and zstd OCI layer extraction.
- Persistent distro selection.
- Real interactive PTY terminal through PRoot.
- DNS setup and Android host network reuse for Linux userspace.
- Visible Linux setup screen and Ubuntu quick-install button.
- MainActivity is the launcher, not the raw terminal.
- Android Gradle workflow installs SDK/NDK/CMake and does not use deprecated `ndk.dir`.
- Gradle debug build command remains `:app:assembleDebug`.

## Important
The APK contains PRoot, but Linux rootfs images are intentionally downloaded on demand because shipping all distributions inside the APK would make it enormous. Use **INSTALL UBUNTU 24.04 LTS** for the first Linux environment, then add other distributions as desired.

The supplied PRoot is AArch64, so it is intended for ARM64 Android devices.
