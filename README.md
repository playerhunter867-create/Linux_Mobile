# LinOx Mobile v0.9.0

LinOx is an Android application that hosts a rootless Linux userspace for development. The project is designed around a persistent PTY terminal, PRoot-based execution, and a mobile-first IDE.

## v0.9 highlights

- App now opens straight into the terminal (no dashboard in the way); a "☰ Menu" button reaches Packages/Git/Files/Settings/Distros
- First-run setup banner in the terminal if no distro is installed yet, linking straight to the installer
- apt package manager UI with toolchain presets (Python, C/C++, Node.js, Git tools)
- Workspace sync between an Android folder (Storage Access Framework) and `/root/workspace`
- Syntax highlighting in the built-in editor (Python, C-like, Shell, JS)
- Git UI: status, add, commit, push, pull, log, diff, branch, clone

## v0.8 highlights

- Real PTY terminal foundation
- ANSI/VT renderer
- PRoot Linux runtime
- OCI/Docker Hub distribution installer
- ARM64 image selection
- Per-layer SHA-256 verification
- Ubuntu 24.04 LTS and Debian 12 catalog
- Persistent active distribution
- Built-in editor workflow

## Architecture

`Android app -> PTY -> PRoot -> ARM64 Linux rootfs -> Bash/tools`

The Android kernel remains the host kernel. LinOx does not claim to virtualize or replace it.

See `docs/DISTROS.md` for the distribution manager details.

See `docs/BUILD.md` for how to build an APK (GitHub Actions or locally).
