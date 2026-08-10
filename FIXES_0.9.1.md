# LinOx Mobile 0.9.1 — build and runtime fixes

## Build / GitHub Actions
- Upgraded Android Gradle Plugin to 8.9.2.
- Kept Java 17 and Kotlin 1.9.24.
- Pinned Gradle to 8.11.1 in CI.
- CI now installs the NDK declared by the app: `27.0.12077973`.
- CI installs Android API 35, Build Tools 35.0.0 and CMake 3.22.1.
- Removed the broken dependency on a missing `gradle-wrapper.jar`.
- Added a deterministic debug APK artifact.
- Added a post-build APK/native-library verification step.
- Added Android lint and failure diagnostics artifacts.
- Added pull-request builds and concurrency cancellation.

## Runtime / UX
- Fixed the built-in Code screen: files now live in the actual Linux bind-mounted
  `/root/workspace`, so scripts saved from the Android editor can really be run
  from Linux.
- Added path traversal protection for editor workspace paths.
- Added a reusable `LinuxRuntime.workspacePath()` API.
- Added a Linux Doctor action showing shell/network/tool health and disk usage.
- Added terminal quick commands.
- Added terminal special keys useful for interactive CLI programs: Ctrl+C,
  Ctrl+D, Tab, Escape and arrow keys.
- Terminal Clear now also clears the guest shell screen.

## Version
- `versionCode = 10`
- `versionName = 0.9.1`

## Validation
The uploaded project was inspected file-by-file, including Gradle/Kotlin/C++,
Android manifest/resources, OCI/rootfs code and the GitHub Actions workflow.
The container used for this repair does not contain an Android SDK/Gradle cache,
so a full APK build could not be executed locally here; CI is configured to
install the exact required Android toolchain.
