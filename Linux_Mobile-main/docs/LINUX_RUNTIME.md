# LinOx Linux runtime

LinOx uses a bundled ARM64 PRoot executable and downloads Linux userspaces from public OCI registries.

## Built-in distro catalog

- Ubuntu 24.04 LTS — `ubuntu:24.04`
- Debian 12 — `debian:12`
- Alpine 3.23 — `alpine:3.23`
- Fedora 44 — `fedora:44`
- Arch Linux ARM64 — `danhunsaker/archlinuxarm:20260517`
- Kali Linux Rolling — `kalilinux/kali-rolling:latest`
- Rocky Linux 10 — `rockylinux:10`
- openSUSE Leap 15 — `opensuse/leap:15`

PRoot-Distro documents the same OCI model and supports local rootfs archives as well as OCI registry images.

## First-run developer tools

After a distro is installed LinOx attempts to install Python 3, pip, nano, Git, curl, wget, CA certificates and Bash using that distro's package manager. A failure is reported without deleting the installed rootfs.

## Terminal

The terminal is a real PTY. Commands are passed to the selected Linux shell, so interactive programs such as `nano test.py` can use the terminal.

## Networking

PRoot uses the Android host networking path. LinOx also writes Android DNS servers into `/etc/resolv.conf` inside the selected rootfs. Network/package-manager behavior still depends on the Android device, VPN/firewall, and the selected distro.
