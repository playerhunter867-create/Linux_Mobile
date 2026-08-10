# LinOx fixed source drop-in

This ZIP contains the corrected source files for the current LinOx project snapshot.

Changes:
- Linux setup screen now clearly shows whether PRoot is installed.
- Adds a PRoot file picker because the original project has no PRoot binary bundled.
- Installing a distro automatically activates it.
- Terminal retries PTY startup after returning from Linux setup.
- Terminal no longer spams "shell not running" for every key press.
- Enter/Send submits commands such as `nano dos.py`.
- Package manager includes Python, C/C++, Node.js, Git/curl/wget, procps (`ps`), network tools and common CLI utilities.
- `INTERNET` permission is preserved.
- Existing PRoot/PTY/native architecture is not replaced.

IMPORTANT:
The current APK/project snapshot does NOT contain a PRoot executable. The app's Linux shell cannot start until an ARM64 Android-compatible PRoot binary is installed. This ZIP therefore adds the safe installer UI, but does not invent or bundle an unverified binary.

After copying these files into your existing project, build a new debug APK.
