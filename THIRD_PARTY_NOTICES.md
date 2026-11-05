# Third-party notices

## cloudflared

- Component: `cloudflared-linux-arm64`
- Version: 2026.10.7
- Source: https://github.com/cloudflare/cloudflared/releases
- License: Apache License 2.0
- Bundled as `app/src/main/jniLibs/arm64-v8a/libcloudflared.so`

## highlight.js

- Component: `highlight.js`
- Version: 11.9.0
- Source: https://github.com/highlightjs/highlight.js
- License: BSD 3-Clause
- Bundled as `app/src/main/assets/web/highlight.min.js`

## CA bundle

- Component: `cacert.pem`
- Source: https://curl.se/ca/cacert.pem
- License: Mozilla Public License 2.0 (as distributed by curl.se)
- Bundled as `app/src/main/assets/cacert.pem`


## QEMU

- Component: QEMU system emulation (`qemu-system-aarch64`, arm64)
- Source: https://www.qemu.org
- License: GNU GPL v2
- Bundled as Android native libs (`libqemu-system-aarch64.so`, dependencies)
- Modification: SONAMEs and dependency names were prefixed to avoid Android package collisions; binaries are otherwise from Termux packages.

## Ubuntu Base sandbox assets

- Component: Ubuntu Base 24.04 arm64 rootfs, Ubuntu 6.11 kernel/initramfs
- Source: https://cdimage.ubuntu.com/ubuntu-base/ and https://ports.ubuntu.com/
- License: Ubuntu/GPL and component licenses
- Downloaded by the app at runtime; not bundled in the APK.
