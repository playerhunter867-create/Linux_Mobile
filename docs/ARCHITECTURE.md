# LinOx Mobile 0.9 architecture

```text
Android application
│
├── Dashboard
├── Terminal / PTY
├── Code editor
├── Workspace bridge
├── Package manager UI
└── Linux OS Manager
       │
       ├── OCI registry
       │      └── linux/arm64 manifest + layers
       │
       └── persistent rootfs
                │
                ▼
             PRoot
                │
                ▼
        Android host kernel
```

## Installation pipeline

1. Resolve an OCI image.
2. Obtain a registry bearer token when required.
3. Resolve a multi-architecture manifest.
4. Select `linux/arm64`.
5. Download each layer into a digest-named cache.
6. Verify the complete blob SHA-256.
7. Extract gzip, zstd or uncompressed tar layers.
8. Apply OCI whiteouts in the correct order.
9. Validate `/etc` and `/bin/sh` or `/usr/bin/sh`.
10. Atomically activate the finished rootfs.
11. Bootstrap common developer tools.
12. Start the selected shell through PRoot/PTY.

## Storage

The APK contains the application and a small ARM64 PRoot runtime.
Large distribution rootfs files are installed into private app storage on demand.
