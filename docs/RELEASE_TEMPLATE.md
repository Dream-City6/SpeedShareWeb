# SpeedShareWeb v1.3.2

This is an official stable release of SpeedShareWeb.

SpeedShareWeb turns an Android phone into a local file server that can be accessed from a modern web browser on the same local network.

## Highlights

- Browse, upload, download and manage files from a web browser
- Preview supported images, videos, audio files and PDFs
- Create ZIP downloads and manage files with the recycle bin
- Use Quick Settings tiles, Home screen shortcuts, QR-code access and optional clipboard sync
- English, Simplified Chinese and Japanese interfaces
- No account, cloud storage or advertising

## What changed in v1.3.2

- Adds a compact desktop right-click menu and mobile long-press bottom sheet
- Uses consistent SVG action icons and keeps permanent deletion out of the quick menu
- Supports Ctrl/Command multi-selection and Shift range selection on desktop
- Allows individual files to be removed from the upload queue before transfer
- Improves keyboard navigation, focus restoration, settings reset, and reduced-motion support
- Makes large folders smoother with batched updates, off-screen rendering deferral, and thumbnail loading placeholders
- Keeps direct uploads, limited parallel transfers, queue progress, cancellation, and failed-item retry

## Installation

1. Download the APK attached to this release.
2. Verify that it was downloaded from the official SpeedShareWeb repository.
3. Allow installation from the browser or file manager when Android asks.
4. Install or update the app.

## Security notice

SpeedShareWeb uses plain HTTP and does not provide password or token authentication. Use it only on a trusted private local network. Do not expose the server through router port forwarding, and stop the server after use. Clipboard sync also uses the same local HTTP connection; do not sync passwords, verification codes, tokens or other sensitive text.

## Files

- `SpeedShareWeb-v1.3.2.apk`
- Optional checksum file: `SpeedShareWeb-v1.3.2.apk.sha256`
