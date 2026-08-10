# Building LinOx Mobile

## GitHub Actions — recommended

The repository intentionally does not depend on a checked-in Gradle Wrapper JAR.
The workflow installs **Gradle 8.11.1**, Android SDK 35, NDK `27.0.12077973`,
and CMake `3.22.1`, then builds and lints the APK.

1. Put the contents of this folder at the root of your GitHub repository.
2. Push to `main`, or open **Actions → Build LinOx Mobile APK → Run workflow**.
3. When the workflow is green, download the artifact:
   `LinOx-Mobile-0.9.0-debug`.
4. The artifact contains `LinOx-Mobile-0.9.0-debug.apk`.

No signing secrets are required for the debug build.

## Why the toolchain is pinned

This project uses Android Gradle Plugin 8.9.2, which supports API 35 and
NDK `27.0.12077973`. Gradle 8.11.1 is the compatible Gradle line for AGP 8.9.x.
Pinning these versions prevents GitHub runners from silently changing the
build environment.

## Local / Termux

Install Java 17, an Android SDK with API 35, NDK `27.0.12077973`, CMake 3.22.1,
and Gradle 8.11.1 (or a compatible 8.9.x toolchain). Then:

```bash
gradle clean
gradle :app:assembleDebug
```

For Termux, use `./build-termux.sh`.

## Release builds

The current workflow intentionally produces a debug APK so it is immediately
installable without a private signing key. A distributable release APK should
be signed with your own keystore in a separate release workflow.
