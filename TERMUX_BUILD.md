# LinOx 0.9.0 — build in Termux

This project is prepared for a normal Android/Gradle build. It does **not** bundle Ubuntu/Debian rootfs images into the APK; those are downloaded by LinOx at runtime through the distro manager. PRoot is bundled at `app/src/main/assets/proot-aarch64-static`.

## Termux setup

```sh
pkg update && pkg upgrade -y
pkg install openjdk-17 gradle git unzip -y
termux-setup-storage
```

Install/configure an Android SDK for Termux, then set `ANDROID_HOME` (or `ANDROID_SDK_ROOT`). The project uses compile/target SDK 35 and NDK 27.0.12077973. If your Termux SDK tools cannot build SDK 35, install the matching Android 35 platform/build-tools or use the project's CI workflow instead.

## Build

```sh
cd Linux_Mobile-main
chmod +x gradlew build-termux.sh
./build-termux.sh
```

APK output:
`app/build/outputs/apk/debug/app-debug.apk`

The first run creates a Gradle 8.9 wrapper if one is not already present.
