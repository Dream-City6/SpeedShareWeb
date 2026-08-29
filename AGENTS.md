# SpeedShare 项目协作规则

## APK 构建边界

- 当用户在当前任务中明确要求测试或生成 APK 时，可以运行 `assembleDebug`、`packageDebug` 或其他必要的 Debug 构建任务，并可生成未签名或 Debug 签名 APK 供用户测试。
- Release APK、App Bundle、正式签名与发布仍需用户明确要求后才能执行。
- 除非用户明确要求，不要连接真实设备、自动安装 APK 或代替用户处理正式发布签名。
- 完成代码修改后应优先运行适用的单元测试、Lint 和必要的编译检查；若用户已明确要求 APK，则可在检查通过后继续生成 Debug APK。
- 生成的测试 APK 应明确标注为测试构建，不应被描述为正式发布版本。

## Windows 下载命令

- 在 Windows PowerShell 中不要直接使用可能触发网页脚本安全确认弹窗的 `Invoke-WebRequest`。
- 下载二进制文件时优先使用 `curl.exe -L`；如果必须使用 `Invoke-WebRequest`，必须添加 `-UseBasicParsing`，并确保命令不会弹出需要用户点击的交互窗口。
