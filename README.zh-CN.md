<div align="center">

# SpeedShareWeb

把安卓手机变成高速局域网文件服务器，并通过浏览器直接访问。

[English](README.md) · [日本語](README.ja.md)

<br>

<a href="https://github.com/Dream-City6/SpeedShareWeb/releases/download/v1.4.1/SpeedShareWeb-v1.4.1.apk">
  <img src="https://img.shields.io/badge/下载-SpeedShareWeb%20v1.4.1%20APK-2ea44f?style=for-the-badge&logo=android&logoColor=white" alt="下载 SpeedShareWeb v1.4.1 APK">
</a>

<br><br>

v1.4.1 进一步优化安卓主页与设置体验，改善三语排版和重复切换流畅度，并修复更多设备上的浅色/深色可读性问题。
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
- 点击整张卡片即可一键分享整个手机
- 实时查看连接数、传输速度、活动任务和文件进度
- 浏览、上传、下载、移动和删除文件
- 通过电脑右键或手机长按菜单快速执行常用文件操作
- 电脑端支持 Ctrl/Command/Shift 多选，上传前可从队列中移除单个文件
- 上传文件夹并保留原有目录结构
- 支持单文件、多文件和 ZIP 打包下载
- 查看最近的上传、下载和文件管理记录
- 可选手机与浏览器页面剪贴板同步
- 响应式列表与网格视图，支持拖放文件或文件夹上传
- 回收站恢复、永久删除和清空
- 支持跟随系统、浅色和深色外观
- 安卓应用和网页均支持简体中文、日语和英语
- 无广告

## 截图

### 安卓应用

<table>
  <tr>
    <td align="center">
      <img src="docs/screenshots/app-home-zh-CN.jpg" width="220" alt="SpeedShareWeb 简体中文浅色主页">
      <br>
      <sub>浅色主页 · 简体中文</sub>
    </td>
    <td align="center">
      <img src="docs/screenshots/app-home-ja.jpg" width="220" alt="SpeedShareWeb 日语浅色主页">
      <br>
      <sub>浅色主页 · 日语</sub>
    </td>
    <td align="center">
      <img src="docs/screenshots/app-home-en.jpg" width="220" alt="SpeedShareWeb 英语深色主页">
      <br>
      <sub>深色主页与传输历史</sub>
    </td>
  </tr>
  <tr>
    <td align="center">
      <img src="docs/screenshots/app-running-en.jpg" width="220" alt="SpeedShareWeb 实时传输控制面板">
      <br>
      <sub>实时速度、进度与传输控制</sub>
    </td>
    <td align="center">
      <img src="docs/screenshots/settings-en-general.jpg" width="220" alt="SpeedShareWeb 外观与语言设置">
      <br>
      <sub>外观、语言与默认行为</sub>
    </td>
    <td align="center">
      <img src="docs/screenshots/settings-en-network.jpg" width="220" alt="SpeedShareWeb 紧凑高级设置">
      <br>
      <sub>紧凑的安全与网络设置</sub>
    </td>
  </tr>
</table>

### 网页文件管理器

<p align="center">
  <img src="docs/screenshots/web-file-manager.png" width="900" alt="SpeedShareWeb 响应式网页文件管理器">
  <br>
  <sub>拖放上传、实时状态、文件管理、搜索与剪贴板同步</sub>
</p>

## 使用方法
1. 将安卓设备和另一台设备连接到同一个可信局域网。
2. 在 SpeedShareWeb 中启动服务器。
3. 在另一台设备的浏览器中打开显示的局域网地址。
4. 浏览、传输或管理文件。
5. 如需同步文字，可在设置中开启剪贴板同步。
6. 使用结束后停止服务器。

## 隐私与安全
SpeedShareWeb 主要在局域网内运行，不需要账号或云存储。
请仅在可信网络中使用，不要把本地 HTTP 服务直接暴露到公网。
剪贴板同步同样使用局域网普通 HTTP，请不要同步密码、验证码、Token 或其他敏感文本。
详细信息请参阅 [PRIVACY.md](PRIVACY.md) 和 [SECURITY.md](SECURITY.md)。

## 开源许可证
本项目采用 GNU General Public License v3.0 开源许可证。详细条款请参阅 [LICENSE](LICENSE)。


## 免责声明
请仅使用 SpeedShareWeb 访问和管理你有权处理的文件及设备。本项目不提供任何形式的担保。

## 更新履历

- v1.4.1：优化主页与设置操作，改善中日英三语排版和重复切换流畅度，修复浅色/深色可读性，并更新项目截图和网站展示。
- v1.4.0：新增品牌图标与启动画面、响应式极光界面、一键分享整个手机、实时传输控制、浅色/深色外观、紧凑设置、可点击传输历史与新版网页文件管理器。
- v1.3.4：新增紧凑的存储空间显示、待上传文件总大小、带 256 MB 系统预留的双重容量检查，以及只需填写密码的浏览器登录页。
- v1.3.3：新增可选访问密码与连接保护，修复并行上传被刷新中断的问题，并在跨存储回收站和恢复失败时优先保留完整副本。
- v1.3.2：新增电脑右键与手机底部操作面板，统一 SVG 图标，收紧危险操作入口，支持上传队列单项移除、桌面连续多选、无障碍操作和大目录渲染优化。
- v1.3.0：优化网页端文件管理体验，改回局域网直传，加入受限并发上传/下载、队列进度、取消/重试、搜索反馈、网页端设置，以及浏览器无法播放视频时的清晰提示。
- v1.2.0：新增传输历史、保留目录结构的文件夹上传、拖放支持，并重新设计网页端界面。
- v1.1.0：新增剪贴板同步、回收站管理、统一品牌、紧凑布局优化，以及更稳定的 Android 控制体验。
- v0.1.0：初始版本，实现 Android 本地文件服务器、浏览器文件浏览、上传、下载和多语言支持。
