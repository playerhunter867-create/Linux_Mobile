# LinOx ready Kotlin files

These are the three patched Kotlin files:

app/src/main/java/org/linox/mobile/DistroActivity.kt
app/src/main/java/org/linox/mobile/PackageManagerActivity.kt
app/src/main/java/org/linox/mobile/TerminalActivity.kt

IMPORTANT:
- Back up your current three files before replacing them.
- Do not replace LinuxRuntime.kt, DistroManager.kt, PtySession.kt, TerminalView.kt,
  AndroidManifest.xml, or liblinoxpty.so.
- These files are based on the project snapshot used to prepare the patch.
  If you made additional edits to these three files after that snapshot, replacing
  them will overwrite those edits.

After copying the three files, rebuild the debug APK.
