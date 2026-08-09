# LinOx Mobile architecture

Android APK
  -> LinOx UI
  -> Android permission/storage bridges
  -> Linux userspace runtime
  -> Debian rootfs
  -> Linux applications

The Android kernel remains the kernel in normal APK mode.

A separate device/ROM project would be required to boot a LinOx kernel directly.
