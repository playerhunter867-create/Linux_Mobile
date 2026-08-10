# LinOx Mobile 0.9 — Linux Distribution Manager

The distribution manager resolves public OCI/Docker images, selects `linux/arm64`,
downloads layers over HTTPS, verifies their SHA-256 digests, applies OCI whiteouts,
and materialises a persistent rootfs.

## Catalog

- Ubuntu 24.04 LTS
- Debian 12 Bookworm slim
- Alpine 3.22
- Fedora 44
- Arch Linux ARM64
- Kali Linux Rolling
- Rocky Linux 10
- openSUSE Leap 15.6

## Why the rootfs is downloaded

A complete Linux userspace is far larger than a small Android APK. LinOx therefore
ships only the runtime needed to execute userspace and downloads the selected OS
on demand. This also makes reinstalling/updating a distribution independent of
the application package.

## Runtime model

This is rootless Linux userspace execution. Android remains the host kernel.
PRoot performs path/root mapping and process mediation in userspace.

## Reliability

- Multi-architecture manifests are filtered for `linux/arm64`.
- Layer blobs are SHA-256 verified.
- Failed downloads are retried.
- Partial downloads use `.part` files and are never activated.
- OCI whiteouts are applied before later layer entries.
- Rootfs activation happens only after `/etc` and a shell are present.
