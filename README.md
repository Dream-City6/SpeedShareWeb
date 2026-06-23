<p align="center">
  <h1 align="center">SpeedShareWeb</h1>
  <p align="center">
    Turn an Android phone into a local file server and access it from any modern web browser.
  </p>

<p align="center">
  <a href="README.zh-CN.md">简体中文</a> ·
  <a href="README.ja.md">日本語</a>
</p>


## Why I Built This Project

I have several phones, tablets, and computers at home, but they all belong to different operating systems and device ecosystems.

Whenever I needed to transfer files between them, I had to find a compatible app or rely on an intermediate service. This was often inconvenient, and many existing solutions could not fully take advantage of the speed available on my local network.

That is why I built SpeedShareWeb.

It does not depend on a specific brand or device ecosystem. As long as the devices are connected to the same local network and have access to a modern web browser, they can browse, upload, download, and manage files directly.

I also worked on transfer performance so that the application can make better use of the available local network and router bandwidth.

I originally built this project to solve a problem I had myself. If it can also save you some time when transferring files between devices, then it has achieved its purpose.



## Screenshots

### Android app

<table>
  <tr>
    <td align="center">
      <img src="docs/screenshots/app-home-en.jpg" width="300" alt="SpeedShareWeb English home screen">
      <br>
      <sub>English interface</sub>
    </td>
    <td align="center">
      <img src="docs/screenshots/app-home-ja.jpg" width="300" alt="SpeedShareWeb Japanese home screen">
      <br>
      <sub>Japanese interface and live transfer</sub>
    </td>
  </tr>
</table>

### Browser file manager

<p align="center">
  <img src="docs/screenshots/web-file-manager.png" width="100%" alt="SpeedShareWeb browser file manager">
</p>

<p align="center">
  Browse, upload, download, organize, and restore files directly from a browser on the same local network.
</p>

### Settings

<table>
  <tr>
    <td align="center">
      <img src="docs/screenshots/settings-en-general.jpg" width="260" alt="SpeedShareWeb general settings">
      <br>
      <sub>General settings</sub>
    </td>
    <td align="center">
      <img src="docs/screenshots/settings-en-network.jpg" width="260" alt="SpeedShareWeb network settings">
      <br>
      <sub>Network and shortcuts</sub>
    </td>
    <td align="center">
      <img src="docs/screenshots/settings-ja-general.jpg" width="260" alt="SpeedShareWeb Japanese settings">
      <br>
      <sub>Japanese settings</sub>
    </td>
  </tr>
</table>



## Overview

SpeedShareWeb lets you browse and transfer files between an Android device and other devices on the same local network.

The receiving device does not need to install an app. Open the local address shown by SpeedShareWeb in a browser and start transferring files.

## Highlights

- Browser-based access from computers, tablets and phones
- No account and no cloud storage required
- Local-network file browsing, downloading and uploading
- File management from a responsive web interface
- List and grid display modes
- Optional access protection
- Designed for temporary, direct file sharing
- No advertising

## How it works

1. Connect the Android phone and the other device to the same trusted local network.
2. Start the local server in SpeedShareWeb.
3. Open the displayed local address in a browser.
4. Browse, upload, download or manage files.
5. Stop the server when file sharing is complete.


## Privacy

SpeedShareWeb is designed to operate on the local network. It does not require an account or cloud storage.

Network traffic may be visible to other devices on an untrusted network when the app is operating over plain HTTP. Use the app only on networks you trust, enable access protection when available, and stop the server after use.

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
