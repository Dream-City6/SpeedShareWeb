# SpeedShareWeb v1.3.4

This is an official stable release of SpeedShareWeb.

SpeedShareWeb turns an Android phone into a local file server that can be accessed from a modern web browser on the same local network.

## Highlights

- Browse, upload, download and manage files from a web browser
- Preview supported images, videos, audio files and PDFs
- Create ZIP downloads and manage files with the recycle bin
- Use Quick Settings tiles, Home screen shortcuts, QR-code access and optional clipboard sync
- English, Simplified Chinese and Japanese interfaces
- No account, cloud storage or advertising

## What changed in v1.3.4

- Shows available and total storage in the Android app and browser header
- Shows the combined size of selected files before upload
- Checks capacity in the browser and verifies it again on the server
- Reserves 256 MB for Android so uploads cannot fill the device completely
- Coordinates concurrent upload reservations and returns a clear low-space error
- Replaces the browser username/password prompt with a password-only sign-in page, temporary sharing sessions and a sign-out action

## Installation

1. Download the APK attached to this release.
2. Verify that it was downloaded from the official SpeedShareWeb repository.
3. Allow installation from the browser or file manager when Android asks.
4. Install or update the app.

## Security notice

SpeedShareWeb uses plain HTTP. Optional password protection can restrict access but does not encrypt network traffic. Use it only on a trusted private local network, do not expose it through router port forwarding, and stop the server after use. Do not sync passwords, verification codes, tokens or other sensitive clipboard text.

## Files

- `SpeedShareWeb-v1.3.4.apk`
- Optional checksum file: `SpeedShareWeb-v1.3.4.apk.sha256`
