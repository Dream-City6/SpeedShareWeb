<div align="center">

# SpeedShareWeb

Turn an Android phone into a fast local-network file server and access it directly from a browser.

[简体中文](README.zh-CN.md) · [日本語](README.ja.md)

<br>

<a href="https://github.com/Dream-City6/SpeedShareWeb/releases/download/v1.3.3/SpeedShareWeb-v1.3.3.apk">
  <img src="https://img.shields.io/badge/Download-SpeedShareWeb%20v1.3.3%20APK-2ea44f?style=for-the-badge&logo=android&logoColor=white" alt="Download SpeedShareWeb v1.3.3 APK">
</a>

<br><br>

v1.3.3 adds optional password protection and server hardening, prevents parallel uploads from being interrupted by live refreshes, and makes cross-storage trash and restore operations safer.
For other versions and release notes, visit [GitHub Releases](https://github.com/Dream-City6/SpeedShareWeb/releases).

</div>

## Why I Built This Project

I have several phones, tablets, and computers at home, but they all use different operating systems and device ecosystems.

Whenever I needed to transfer files, I had to look for a compatible app or service. Many of them could not fully use the available router speed, were slow or inconvenient, and some also included advertisements.

Eventually, I got tired of it and built SpeedShareWeb myself. As long as the devices are connected to the same local network, you can browse, upload, download, and manage files directly without relying on a specific brand, account, or cloud-storage service.

## Project Overview

SpeedShareWeb lets Android devices transfer files to and from phones, tablets, and computers on the same local network.

The receiving device does not need to install an app. Simply open the local address displayed by SpeedShareWeb in a browser.

## Main Features

- Access from browsers on phones, tablets, and computers
- No account or cloud storage required
- Browse, upload, download, move, and delete files
- Use common file actions from a compact desktop right-click or mobile long-press menu
- Use Ctrl/Command/Shift multi-selection on desktop and remove individual files from the upload queue before transfer
- Upload folders while preserving their directory structure
- Single-file, multi-file, and ZIP downloads
- Review recent uploads, downloads, and file-management operations
- Optional clipboard sync between the Android app and the browser page
- Responsive list and grid views
- Restore, permanently delete, or clear files from the recycle bin
- English, Simplified Chinese, and Japanese interfaces
- No advertising

## Screenshots

<table>
  <tr>
    <td align="center">
      <img src="docs/screenshots/app-home-en.jpg" width="300" alt="SpeedShareWeb English home screen">
      <br>
      <sub>Android app home screen</sub>
    </td>
    <td align="center">
      <img src="docs/screenshots/app-home-ja.jpg" width="300" alt="SpeedShareWeb Japanese home screen">
      <br>
      <sub>Live transfer status</sub>
    </td>
  </tr>
</table>

<p align="center">
  <img src="docs/screenshots/web-file-manager.png" width="100%" alt="SpeedShareWeb browser file manager">
</p>

## How to Use

1. Connect the Android device and the other device to the same trusted local network.
2. Start the server in SpeedShareWeb.
3. Open the displayed local address in a browser on the other device.
4. Browse, transfer, or manage files.
5. Optional: enable clipboard sync in Settings if you want to send text between the phone and browser page.
6. Stop the server when finished.

## Privacy and Security

SpeedShareWeb mainly operates on the local network and does not require an account or cloud storage.

Use it only on trusted networks. Do not expose the local HTTP server directly to the public internet.

Clipboard sync also uses the same local HTTP connection. Do not sync passwords, verification codes, tokens, or other sensitive text.

For details, see [PRIVACY.md](PRIVACY.md) and [SECURITY.md](SECURITY.md).

## License

This project is licensed under the GNU General Public License v3.0. See [LICENSE](LICENSE) for details.

## Disclaimer

Use SpeedShareWeb only with files and devices that you are authorized to access and manage. This project is provided without warranty.

## Update History

- v1.3.3: Added optional password protection and connection hardening, fixed parallel-upload refresh interruption, and protected complete copies during cross-storage trash and restore failures.
- v1.3.2: Added compact right-click and mobile bottom-sheet actions, consistent SVG icons, safer deletion access, removable upload entries, desktop range selection, accessibility improvements, and large-directory rendering optimizations.
- v1.3.0: Improved the browser file manager with direct local-network uploads, limited parallel uploads/downloads, clearer queue progress, retry/cancel controls, search feedback, browser-side settings, and clearer guidance for videos the browser cannot play.
- v1.2.0: Added transfer history, folder uploads with preserved directory structure, drag-and-drop support, and a redesigned browser interface.
- v1.1.0: Added clipboard sync, recycle-bin management, stronger branding, better compact layouts, and more stable Android controls.
- v0.1.0: Initial local Android file server with browser-based file browsing, upload, download, and multilingual support.
