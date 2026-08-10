# LinOx Linux Distribution Manager v0.8

LinOx v0.8 can pull public OCI/Docker images directly from Docker Hub and materialise a native `linux/arm64` userspace into its private app storage.

Catalog:

- Ubuntu 24.04 LTS (`ubuntu:24.04`)
- Debian 12 (`debian:12`)

The manager resolves the multi-architecture manifest, selects `linux/arm64`, downloads each layer, verifies its SHA-256 digest, applies OCI whiteouts, then activates the resulting rootfs.

## Important

This is a rootless userspace. It is **not** a second Linux kernel and it does not replace Android's kernel. PRoot intercepts guest filesystem/process operations in userspace.

The implementation intentionally does not bundle a PRoot binary or a Linux rootfs in the APK. Those components are architecture-specific and should be obtained from a trusted build/release pipeline.

## Current limitations

- Public Docker Hub images only; custom OCI registries are planned.
- gzip-compressed OCI layers are supported; other compression formats are rejected for now.
- No package-manager GUI yet. Once a distro is active, use `apt` from the Linux terminal.
- Android kernels can differ in PRoot/ptrace behavior. `PROOT_NO_SECCOMP=1` is set by LinOx for compatibility.
