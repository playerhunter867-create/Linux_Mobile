## 27. Путь: `docs/CAPABILITIES.md`

```markdown
# Capabilities

LinOx is intended to be a Linux development workspace for Android, not a replacement Android kernel.

### Linux
- rootless userspace through PRoot
- ARM64 Debian/Ubuntu rootfs support
- Bash, apt, Python, GCC and other packages once installed in the chosen rootfs
- persistent `/root` workspace

### Terminal
- native PTY session in v0.6
- streaming I/O
- Ctrl+C and command history
- xterm-256color environment

### Developer UX
- built-in file editor
- Python/Bash runners
- Linux-first project workspace

### Product direction
LinOx should differentiate from a terminal-only Android app by integrating the Linux runtime, IDE, project manager, distribution manager and mobile-friendly workspace into one product. It should not claim to replace the Android kernel or provide unrestricted root access without explicit device root.
