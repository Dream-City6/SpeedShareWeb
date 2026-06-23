# SpeedShareWeb

把安卓手机临时变成一个本地文件服务器，让电脑、平板和其他手机直接通过浏览器访问。


## 项目简介

SpeedShareWeb 用于在同一局域网内浏览和传输文件。

接收设备不需要安装客户端，只需打开应用显示的本地地址，即可通过浏览器上传、下载、浏览或管理文件。
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

## 主要特点

- 电脑、平板和手机均可通过浏览器访问
- 不需要账号，也不依赖云端存储
- 支持局域网文件浏览、上传和下载
- 提供适配手机与桌面的网页界面
- 支持列表与网格显示
- 支持可选的访问保护
- 适合临时、直接的文件传输
- 无广告

## 使用方法

1. 将安卓手机和另一台设备连接到同一个可信局域网。
2. 在 SpeedShareWeb 中启动本地服务器。
3. 在另一台设备的浏览器中打开应用显示的本地地址。
4. 浏览、上传、下载或管理文件。
5. 使用结束后关闭服务器。

## 隐私与安全

SpeedShareWeb 的设计目标是在局域网内运行，不需要账号或云端存储。

当应用使用普通 HTTP 传输时，不可信网络中的其他设备可能有机会观察网络流量。请只在可信网络中使用，启用可用的访问保护，并在传输结束后关闭服务器。

详细内容请查看 [PRIVACY.md](PRIVACY.md) 和 [SECURITY.md](SECURITY.md)。

## 下载

首个完成测试的签名 APK 将通过 GitHub Releases 发布。

请勿安装非官方第三方重新打包的 APK。

## 开源许可

项目会在检查全部第三方依赖后选择许可证。在仓库加入正式 LICENSE 文件之前，源代码仍受版权保护，暂不授予复制、修改或再发布许可。
