# Task 13 实施报告

## 完成内容

- 新增原生 `SettingsScreen`，读取 `/api/config` 与 `/api/devices`，显示服务端枚举的视频、音频和串口设备及各类错误信息。
- 支持编辑并完整保存视频设备、宽高、帧率、编码、H.264 码率，音频设备、启用、窄带，以及串口、波特率、鼠标模式。
- 保存前校验正整数，PUT 期间禁用操作并显示加载、保存成功或失败状态。
- 设置入口携带当前服务器地址，替换原占位页；返回控制页后重新建立会话并读取新配置。
- 新增设备响应模型，JSON 解析启用 `ignoreUnknownKeys`；保留未检测到设备的文本配置，不会进入页面即被覆盖。
- 按任务说明跳过实时 PUT/热切换，采用返回控制页后重连生效。

## 测试

- `:core-protocol:testDebugUnitTest --tests com.rustkvm.protocol.ConfigModelsTest`：通过。
- `:app:testDebugUnitTest --tests com.rustkvm.app.ui.SettingsScreenTest`：通过。
- `.\gradlew.bat :app:assembleDebug :client:testDebugUnitTest`：通过。

## P1/P2 修复

- **P1**：`ControlScreen` 每次 `LaunchedEffect` 重载配置时，均从 `loadedConfig.serial.mouseMode` 同步 `absoluteMouse`（不再仅在 null 时设置），从设置页返回后鼠标模式与服务端一致。
- **P2**：`SettingsForm.toConfig()` 在 codec 为 `mjpeg` 时跳过码率正整数校验，无效值回退默认 4000 kbps。
- 新增 `mjpegSkipsBitrateValidation` 单元测试；`assembleDebug` 与全模块 `testDebugUnitTest` 通过（JDK 17）。
