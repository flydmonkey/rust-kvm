# 一期验收对照清单

对照设计文档 [`docs/superpowers/specs/2026-09-15-android-port-design.md`](../../docs/superpowers/specs/2026-09-15-android-port-design.md) 一期验收 7 条。

**验收环境说明：** 本机已用 JDK 17 完成 `assembleDebug` 与 JVM 单元测试（`core-protocol`、`client`、`app`）。**未连接真实 Windows rust-kvm 宿主机做端到端联调**，下列状态以代码实现与单测为准；标「待真机联调」项需在局域网内对照 Windows `cargo run` 服务手工复验。

**联调前置：** Windows 宿主机已启动并监听 `https://<IP>:8443`；手机/平板与宿主机同一局域网；已安装 debug APK。

---

## 清单

| # | 验收项 | 状态 | 验证方式 / 备注 |
| --- | --- | --- | --- |
| 1 | 手动地址 + TOFU 后稳定进入控制页 | 代码已实现 / 待真机联调 | `ConnectScreen` + `CertTrustDialog` + `RecentServersStore`；地址规范化默认 `https` + `:8443`；首次展示 SHA-256 指纹，信任后按 `host:port` 持久化；指纹变更提示 Mismatch |
| 2 | MJPEG 与 H.264 可看；音频可听可静音 | 代码已实现 / 待真机联调 | `VideoPipeline`（`MjpegDecoder` / `H264Decoder`）；`PcmPlayer` + 工具栏静音；codec 切换随 `/ws/video` Text 头；需对照 Windows 切换 MJPEG/H.264 与 audio 开关 |
| 3 | 触摸绝对鼠标可点可拖；可切相对模式；软键盘与常用快捷操作可用 | 代码已实现 / 待真机联调 | `TouchGestureInterpreter`（点按左键、长按与双指轻点右键、双指拖动滚轮；相对模式拖动只挪光标）+ `Ch9329` 滚轮；工具栏「键盘」呼出软键盘（`RemoteKeyboard` 占位差分）+ 功能键栏粘滞修饰键（`ModifierLatch`、`Ch9329.tap`）；画面按实际比例布局，触摸坐标对齐；需真机验证 |
| 4 | 粘贴、Ctrl+Alt+Del、松开所有键可用 | 代码已实现 / 待真机联调 | `PasteTyper`（20ms 包间隔，单测覆盖）；工具栏 CAD / 全释放；需剪贴板粘贴与被控端响应验证 |
| 5 | 串口占用时可接管 | 代码已实现 / 待真机联调 | `SerialControl` 检测 `error.code=="busy"`；UI 接管 → `/ws/serial?takeover=1` 重连；需两客户端同时连接触发 busy |
| 6 | 基础设置可改地址并读写常用 `/api/config` 项 | 代码已实现 / 待真机联调 | `SettingsScreen` GET/PUT config、GET devices；video/audio/serial 常用字段；保存后返回控制页重连生效；`SettingsScreenTest` 通过 |
| 7 | 断网重连后画面与控制可恢复；键卡住时可用一键全释放 | 代码已实现 / 待真机联调 | `ReconnectPolicy` 指数退避上限 10s；断线角标「重连中」；**自动重连不携带 takeover**；工具栏全释放；需断 Wi-Fi 后恢复验证 |

---

## 手工验收步骤（建议顺序）

1. **连接与 TOFU：** 输入 `192.168.x.x` 或完整 URL → 首次弹出指纹对话框 → 信任并进入控制页 → 断开重连无二次提示 → 修改宿主机证书后应提示指纹变更。
2. **视频：** 默认 MJPEG 有画面；设置改为 H.264 保存并返回 → 画面仍正常；切回 MJPEG。
   - MJPEG 1080p30 连看 5 分钟，在被控端开一个秒表：操作到画面的延迟不应越拖越大，App 内存不应持续上涨。
   - H.264 下快速拖动窗口：不应出现持续花屏；若出现，应在约 1 秒内恢复（logcat 里 `VideoPipeline` 会打 `Reopening video stream`）。
   - H.264 下把分辨率改成 1280×720：画面应在几秒内恢复，不黑屏。
   - 若本机解码器吃不下这路 H.264（连续 3 次解码失败且没出过一帧），底部提示「本机 H.264 解码反复失败，可在设置里把编码改成 MJPEG」；出画面后提示消失。
   - 宿主机关闭音频时底部提示「音频不可用」，画面和键鼠不受影响。
   - 若宿主机 H.264 编码器起不来，服务端会退回 MJPEG：画面正常，底部显示「H.264 不可用，已退回 MJPEG：原因」。
   - 控制页全屏（状态栏、导航栏隐藏，从边缘划出后自动收回）；放着不动屏幕不熄灭；横竖屏切换、插拔外接键盘都不应断线重连；刘海屏上画面不被挡。
3. **音频：** 有声音；点静音立刻无声（不再拖一小段尾音）；取消静音恢复，且不会先冒出一段旧声音。连听 5 分钟，声音与画面的错位不应越来越大。
4. **输入：** 触摸点击/拖拽；切换绝对/相对鼠标；软键盘输入字符。
   - 绝对模式：点桌面图标落点准确（MJPEG 与 H.264 各点一次四个角附近）；拖动窗口标题栏；长按出右键菜单，且长按前没有误触左键。
   - 双指上下划动网页能滚动，方向和手机上一致；双指轻点出右键菜单。
   - 相对模式：单指划动只挪光标、不会拖出选区；轻点是左键单击。
   - 点「键盘」呼出软键盘：连续输入字母、数字、符号不重复不丢；退格、回车可用；中文等非 US 键盘字符会被跳过。
   - 软键盘弹出时画面缩到键盘上方，键盘上方出现功能键栏（Ctrl/Alt/Shift/Win、Esc、Tab、方向键、Home/End/PgUp/PgDn/Del、F1–F12）：点 Ctrl 再在软键盘打 `c` 应为复制；Ctrl 点亮后点 Alt 再点 Del 是 Ctrl+Alt+Del；连点两下 Win 打开开始菜单；修饰键用一次后自动熄灭；收起软键盘时已点亮的修饰键也会熄灭。
5. **工具栏：** 粘贴剪贴板文本；Ctrl+Alt+Del；松开所有键（键卡住时尤其验证）。
6. **接管：** 浏览器或第二客户端占用串口 → 本端提示 busy → 点接管后键鼠恢复。
7. **设置：** 修改分辨率/fps/codec、音频窄带、串口端口等 → 保存 → 返回控制页确认生效。
   - 控制页按一次返回（或边缘返回手势）只提示「再按一次返回断开连接」，2 秒内再按一次才回到连接页；软键盘弹出时第一次返回只收起键盘。
   - 连接失败（如串口被占用后点取消）时点「断开」能回到连接页。
   - 连接页点最近服务器直接发起连接。
8. **重连：** 控制页开启时关闭手机 Wi-Fi 数秒再打开 → 出现「重连中」→ 画面与键鼠恢复；确认未自动 takeover。

---

## 已知限制（一期不做）

- 无 mDNS / 局域网自动发现（仅手动地址 + 最近 8 条）
- 无录制、无统计 HUD 叠加层
- 无 Keyboard Lock 级系统键全捕获
- 无 CH9329 芯片波特率/工作模式配置 UI
- 无鉴权（与 Windows 服务一致，假定可信局域网或 VPN）
- Android 宿主机（USB OTG）为二期，见 `host` 模块占位
- CH9329 ACK 队列为一期简化版（写后不严格等 ACK）；若特定硬件依赖 ACK 需后续收紧

---

## 构建自检（本机已执行）

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
cd android
.\gradlew.bat :app:assembleDebug :core-protocol:testDebugUnitTest :client:testDebugUnitTest :app:testDebugUnitTest
```

结果：**BUILD SUCCESSFUL**（2026-09-15）。
