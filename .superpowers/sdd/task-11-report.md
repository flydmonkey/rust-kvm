# Task 11 实施报告：串口会话与 CH9329 输入

## 完成内容

- 新增 `SerialControl`，按 `getConfig()` 的串口端口、波特率和鼠标模式创建 CH9329 桥接；等待 `status.open=true` 后才开放输入。
- `busy` 错误转换为 `ControlBusy` 并关闭当前会话；控制页提供“接管”对话框，以 `?takeover=1` 重建 `KvmSession`。
- 视频表面触摸按实际 letterbox 内容区域归一化至 `0..1`；绝对模式发送按下、移动、抬起，相对模式发送位移。
- 控制页接入常用 Android 键盘事件，并提供“释放按键”和 `Ctrl+Alt+Del` 简易工具栏按钮；完整输入工具栏留给 Task 12。

## 测试与验证

- TDD RED：新增测试首次运行因 `SerialControl` 和 `normalizeLetterboxedTouch` 尚不存在而按预期编译失败。
- 单元测试覆盖 open/takeover 参数、busy 回调及会话关闭、CH9329 二进制上行、letterbox 边界与坐标归一化。
- JDK 17 下执行 `.\gradlew.bat testDebugUnitTest assembleDebug --no-daemon`：`BUILD SUCCESSFUL`。
- 未连接实体串口设备，live device smoke 按任务说明跳过。
