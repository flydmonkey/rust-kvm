# Android 客户端最终审查修复报告

## 2026-09-15

- C1：`SerialControl.open()` 后持续消费串口文本与二进制下行；运行期 `error`、`status(open=false)` 会进入失败态，`busy` 继续走接管对话框；串口二进制通道改为容量 64、`DROP_OLDEST`。
- C2：重连逻辑区分首次连接失败和已进入流后的断线；首次失败、串口终止及证书未信任/指纹变化均进入可返回的失败态，不再无限重连。
- I1：增加串行 HID 写队列，移动队列中只保留最新待发包；按键和鼠标按键状态变化作为普通命令排队，不会被移动洪泛淹没。
- I2/I3：绝对触摸加入 6px 拖拽死区，未拖拽抬起时在按下坐标释放；相对移动累计亚像素残差。
- I4/I5：WebSocket OkHttp 客户端启用 20 秒 ping；MJPEG `lockCanvas` 异常写入 `Log.w`，避免黑屏故障完全静默。
- `.gitignore`：已确认 `android/.gitignore` 为已跟踪文件，并包含 `**/build/` 与 `/local.properties`。
- 回归测试：新增串口运行期状态/忙碌/ACK 消费、移动包合并与按键边界、连接错误分类、拖拽死区、相对残差、ping 周期测试。
- 验证：`.\gradlew.bat :client:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug`，`BUILD SUCCESSFUL`。
- 延后项：无。
