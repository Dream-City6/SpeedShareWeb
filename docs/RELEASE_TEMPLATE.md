# SpeedShareWeb v1.4.2

This is an official stable release of SpeedShareWeb.

SpeedShareWeb turns an Android phone into a local file server that can be accessed from a modern web browser on the same local network.

## Highlights

- Browse, upload, download and manage files from a web browser
- Preview supported images, videos, audio files and PDFs
- Create ZIP downloads and manage files with the recycle bin
- Use Quick Settings tiles, Home screen shortcuts, QR-code access and optional clipboard sync
- English, Simplified Chinese and Japanese interfaces
- No account, cloud storage or advertising

## What changed in v1.4.2

- Strengthens authentication, session expiry and HTTP request validation
- Makes uploads and file replacement transactional so interrupted operations do not expose partial results
- Improves file operations, recycle-bin recovery and cancellation cleanup
- Bounds request, transfer and thumbnail work for safer long-running use
- Adds automated Android quality checks and broader regression coverage

## Installation

1. Download the APK attached to this release.
2. Verify that it was downloaded from the official SpeedShareWeb repository.
3. Allow installation from the browser or file manager when Android asks.
4. Install or update the app.

## Security notice

SpeedShareWeb uses plain HTTP. Optional password protection can restrict access but does not encrypt network traffic. Use it only on a trusted private local network, do not expose it through router port forwarding, and stop the server after use. Do not sync passwords, verification codes, tokens or other sensitive clipboard text.

## Files

- `SpeedShareWeb-v1.4.2.apk`
- Optional checksum file: `SpeedShareWeb-v1.4.2.apk.sha256`
