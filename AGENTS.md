# SpeedShare 项目协作规则

## APK 构建边界

- 除非用户在当前任务中明确要求，否则不要生成任何 APK，包括签名 APK 和未签名 APK。
- 默认不要运行 `assembleDebug`、`assembleRelease`、`packageDebug`、`packageRelease` 或其他 APK、App Bundle 打包任务。
- 不要安装 APK、连接设备部署，也不要代替用户处理 APK 签名。
- 完成代码修改后，默认只运行适用的单元测试、Lint 和必要的编译检查；最终 APK 由用户在 Android Studio 中自行构建、签名和真机测试。

## Windows 下载命令

- 在 Windows PowerShell 中不要直接使用可能触发网页脚本安全确认弹窗的 `Invoke-WebRequest`。
- 下载二进制文件时优先使用 `curl.exe -L`；如果必须使用 `Invoke-WebRequest`，必须添加 `-UseBasicParsing`，并确保命令不会弹出需要用户点击的交互窗口。
