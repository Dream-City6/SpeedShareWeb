<div align="center">

# SpeedShareWeb

把安卓手机变成高速局域网文件服务器，并通过浏览器直接访问。

[English](README.md) · [日本語](README.ja.md)

<br>

<a href="https://github.com/Dream-City6/SpeedShareWeb/releases/download/v1.1.2/SpeedShareWeb-v1.1.2.apk">
  <img src="https://img.shields.io/badge/下载-SpeedShareWeb%20v1.1.2%20APK-2ea44f?style=for-the-badge&logo=android&logoColor=white" alt="下载 SpeedShareWeb v1.1.2 APK">
</a>

<br><br>

v1.1.2 是 SpeedShareWeb 的正式稳定版本。
如需其他版本或查看更新说明，请前往 [GitHub Releases](https://github.com/Dream-City6/SpeedShareWeb/releases)。

</div>

## 为什么做这个项目

我家里有多台手机、平板和电脑，但它们属于不同的系统和设备生态。
每次传文件时，往往需要寻找兼容的软件或服务，不仅速度榨不干路由器性能、还卡、麻烦，有的还有广告。

气急眼了的我直接做了个 SpeedShareWeb。只要设备连接到同一个局域网就能直接浏览、上传、下载和管理文件，不需要依赖特定品牌、账号或云盘。


## 项目简介

SpeedShareWeb 可以让安卓设备与同一局域网内的手机、平板和电脑互相传输文件。
接收端不需要安装应用，只需在浏览器中打开 SpeedShareWeb 显示的局域网地址即可使用。

## 主要功能

- 支持手机、平板和电脑浏览器访问
- 不需要账号或云存储
- 浏览、上传、下载、移动和删除文件
- 支持单文件、多文件和 ZIP 打包下载
- 响应式列表与网格视图
- 回收站恢复、永久删除和清空
- 支持简体中文、日语和英语
- 无广告

## 截图

<table>
  <tr>
    <td align="center">
      <img src="docs/screenshots/app-home-en.jpg" width="300" alt="SpeedShareWeb 英文主页">
      <br>
      <sub>安卓应用主页</sub>
    </td>
    <td align="center">
      <img src="docs/screenshots/app-home-ja.jpg" width="300" alt="SpeedShareWeb 日文主页">
      <br>
      <sub>实时传输状态</sub>
    </td>
  </tr>
</table>

<p align="center">
  <img src="docs/screenshots/web-file-manager.png" width="100%" alt="SpeedShareWeb 浏览器文件管理器">
</p>

## 使用方法
1. 将安卓设备和另一台设备连接到同一个可信局域网。
2. 在 SpeedShareWeb 中启动服务器。
3. 在另一台设备的浏览器中打开显示的局域网地址。
4. 浏览、传输或管理文件。
5. 使用结束后停止服务器。

## 隐私与安全
SpeedShareWeb 主要在局域网内运行，不需要账号或云存储。
请仅在可信网络中使用，不要把本地 HTTP 服务直接暴露到公网。
详细信息请参阅 [PRIVACY.md](PRIVACY.md) 和 [SECURITY.md](SECURITY.md)。

## 开源许可证
本项目采用 GNU General Public License v3.0 开源许可证。详细条款请参阅 [LICENSE](LICENSE)。


## 免责声明
请仅使用 SpeedShareWeb 访问和管理你有权处理的文件及设备。本项目不提供任何形式的担保。
