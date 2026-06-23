<h1 align="center">Speed Share Web</h1>

<p align="center">
  Turn an Android phone into a private local file server and access it from any modern web browser.
</p>

<p align="center">
  <a href="README.zh-CN.md">简体中文</a> ·
  <a href="README.ja.md">日本語</a>
</p>>

> This repository is currently being prepared for its first public release. Screenshots, signed APKs and complete build instructions will be added before the repository becomes public.

## Overview

SpeedShareWeb lets you browse and transfer files between an Android device and other devices on the same local network.

The receiving device does not need to install an app. Open the local address shown by SpeedShareWeb in a browser and start transferring files.

## Highlights

- Browser-based access from computers, tablets and phones
- No account and no cloud storage required
- Local-network file browsing, downloading and uploading
- File management from a responsive web interface
- List and grid display modes
- Designed for temporary, direct file sharing
- No advertising

## How it works

1. Connect the Android phone and the other device to the same trusted local network.
2. Start the local server in SpeedShareWeb.
3. Open the displayed local address in a browser.
4. Browse, upload, download or manage files.
5. Stop the server when file sharing is complete.

## Screenshots

Screenshots will be added before the first public release.

<!--
Recommended layout:
| Android home | Browser file list | Grid view |
|---|---|---|
| image | image | image |
-->

## Privacy

SpeedShareWeb is designed to operate on the local network. It does not require an account or cloud storage.

The app uses plain HTTP and does not provide password or token authentication. Other devices on the same local network may be able to access the server. Use it only on a trusted private network, do not expose the port through router port forwarding, do not transfer sensitive files over public Wi-Fi, and stop the server after use.

See [PRIVACY.md](PRIVACY.md) for details.

## Security

Do not expose the local server directly to the public internet. Security issues should not be posted publicly before they are reviewed.

See [SECURITY.md](SECURITY.md).

## Download

The first signed APK will be published on the GitHub Releases page after release testing is complete.

Do not install APK files distributed by unofficial third parties.

## Build from source

Detailed build instructions will be added after the Android project structure and dependency versions are finalized.

Expected requirements:

- Android Studio
- Android SDK
- JDK compatible with the project Gradle version

## Roadmap

- Complete English, Simplified Chinese and Japanese interfaces
- Add screenshots and a short demonstration video
- Publish reproducible build instructions
- Publish the first signed APK
- Improve security and network-status explanations
- Expand automated testing

## Contributing

Bug reports, feature suggestions, translations and pull requests are welcome after the first public release.

Read [CONTRIBUTING.md](CONTRIBUTING.md) before contributing.

## License

A license will be selected after all third-party dependencies have been reviewed. Until a license file is added, the source code remains copyrighted and no reuse permission is granted.

## Disclaimer

Use SpeedShareWeb only with files and devices you are authorized to access. The project is provided without warranty.

Developed by Alex
