<div align="center">

# SpeedShareWeb

Turn an Android phone into a fast local-network file server and access it directly from a browser.

[简体中文](README.zh-CN.md) · [日本語](README.ja.md)

<br>

<a href="https://github.com/Dream-City6/SpeedShareWeb/releases/download/v1.4.1/SpeedShareWeb-v1.4.1.apk">
  <img src="https://img.shields.io/badge/Download-SpeedShareWeb%20v1.4.1%20APK-2ea44f?style=for-the-badge&logo=android&logoColor=white" alt="Download SpeedShareWeb v1.4.1 APK">
</a>

<br><br>

v1.4.1 refines the Android home and settings experience, improves trilingual layouts and repeated navigation, and fixes light/dark appearance readability across more devices.
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
- Start whole-phone sharing from one clear, full-card action
- Monitor connections, transfer speed, active tasks, and file progress in real time
- Browse, upload, download, move, and delete files
- Use common file actions from a compact desktop right-click or mobile long-press menu
- Use Ctrl/Command/Shift multi-selection on desktop and remove individual files from the upload queue before transfer
- Upload folders while preserving their directory structure
- Single-file, multi-file, and ZIP downloads
- Review recent uploads, downloads, and file-management operations
- Optional clipboard sync between the Android app and the browser page
- Responsive list and grid views with drag-and-drop file or folder upload
- Restore, permanently delete, or clear files from the recycle bin
- System, light, and dark appearance modes
- English, Simplified Chinese, and Japanese app and browser interfaces
- No advertising

## Screenshots

### Android app

<table>
  <tr>
    <td align="center">
      <img src="docs/screenshots/app-home-zh-CN.jpg" width="220" alt="SpeedShareWeb Simplified Chinese light home screen">
      <br>
      <sub>Light home · Simplified Chinese</sub>
    </td>
    <td align="center">
      <img src="docs/screenshots/app-home-ja.jpg" width="220" alt="SpeedShareWeb Japanese light home screen">
      <br>
      <sub>Light home · Japanese</sub>
    </td>
    <td align="center">
      <img src="docs/screenshots/app-home-en.jpg" width="220" alt="SpeedShareWeb English dark home screen">
      <br>
      <sub>Dark home and transfer history</sub>
    </td>
  </tr>
  <tr>
    <td align="center">
      <img src="docs/screenshots/app-running-en.jpg" width="220" alt="SpeedShareWeb live transfer dashboard">
      <br>
      <sub>Live speed, progress, and controls</sub>
    </td>
    <td align="center">
      <img src="docs/screenshots/settings-en-general.jpg" width="220" alt="SpeedShareWeb appearance and language settings">
      <br>
      <sub>Appearance, language, and defaults</sub>
    </td>
    <td align="center">
      <img src="docs/screenshots/settings-en-network.jpg" width="220" alt="SpeedShareWeb compact advanced settings">
      <br>
      <sub>Compact security and network settings</sub>
    </td>
  </tr>
</table>

### Browser file manager

<p align="center">
  <img src="docs/screenshots/web-file-manager.png" width="900" alt="SpeedShareWeb responsive browser file manager">
  <br>
  <sub>Drag-and-drop upload, live status, file management, search, and clipboard sync</sub>
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

- v1.4.1: Refined the home and settings experience, improved Chinese/Japanese/English layouts and repeated navigation, fixed light/dark readability, and refreshed the project screenshots and website showcase.
- v1.4.0: Added the new brand icon and launch screen, responsive aurora UI, one-tap whole-phone sharing, live transfer controls, light/dark appearance modes, compact settings, clickable transfer history, and a refined browser file manager.
- v1.3.4: Added compact storage-space visibility, selected-upload size feedback, browser/server capacity checks with a 256 MB system reserve, and a password-only browser sign-in page.
- v1.3.3: Added optional password protection and connection hardening, fixed parallel-upload refresh interruption, and protected complete copies during cross-storage trash and restore failures.
- v1.3.2: Added compact right-click and mobile bottom-sheet actions, consistent SVG icons, safer deletion access, removable upload entries, desktop range selection, accessibility improvements, and large-directory rendering optimizations.
- v1.3.0: Improved the browser file manager with direct local-network uploads, limited parallel uploads/downloads, clearer queue progress, retry/cancel controls, search feedback, browser-side settings, and clearer guidance for videos the browser cannot play.
- v1.2.0: Added transfer history, folder uploads with preserved directory structure, drag-and-drop support, and a redesigned browser interface.
- v1.1.0: Added clipboard sync, recycle-bin management, stronger branding, better compact layouts, and more stable Android controls.
- v0.1.0: Initial local Android file server with browser-based file browsing, upload, download, and multilingual support.
