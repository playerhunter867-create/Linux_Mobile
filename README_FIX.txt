LINUX_MOBILE — FIXED REPLACEMENT FILES

Replace these files in the repository:

1. app/src/main/java/org/linox/mobile/DistroActivity.kt
2. app/src/main/java/org/linox/mobile/LinuxRuntime.kt
3. .github/workflows/android.yml

Main fix:
- restores the LinuxRuntime API expected by DistroActivity, including hasProot()
- removes the broken/incomplete LinuxRuntime stub that caused:
  Unresolved reference: hasProot
- workflow generates Gradle Wrapper if ./gradlew is missing
- workflow uses Android NDK 27.0.12077973 via android.ndkVersion
- workflow no longer writes deprecated ndk.dir

Then push/replace the files and run GitHub Actions -> Build LinOx Mobile APK.
