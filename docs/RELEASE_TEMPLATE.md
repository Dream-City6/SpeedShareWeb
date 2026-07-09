# SpeedShareWeb v1.2.0

This is an official stable release of SpeedShareWeb.

SpeedShareWeb turns an Android phone into a local file server that can be accessed from a modern web browser on the same local network.

## Highlights

- Browse, upload, download and manage files from a web browser
- Preview supported images, videos, audio files and PDFs
- Create ZIP downloads and manage files with the recycle bin
- Use Quick Settings tiles, Home screen shortcuts, QR-code access and optional clipboard sync
- English, Simplified Chinese and Japanese interfaces
- No account, cloud storage or advertising

## What changed in v1.2.0

- Added transfer history to the Android app and browser page
- Added folder uploads with preserved directory structure and drag-and-drop support
- Redesigned browser navigation, upload controls, file cards and management dialogs
- Improved Android quick actions and compact-screen layouts
- Hardened upload path validation and concurrent history updates

## Installation

1. Download the APK attached to this release.
2. Verify that it was downloaded from the official SpeedShareWeb repository.
3. Allow installation from the browser or file manager when Android asks.
4. Install or update the app.

## Security notice

SpeedShareWeb uses plain HTTP and does not provide password or token authentication. Use it only on a trusted private local network. Do not expose the server through router port forwarding, and stop the server after use. Clipboard sync also uses the same local HTTP connection; do not sync passwords, verification codes, tokens or other sensitive text.

## Files

- `SpeedShareWeb-v1.2.0.apk`
- Optional checksum file: `SpeedShareWeb-v1.2.0.apk.sha256`
