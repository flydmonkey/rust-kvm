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
| 3 | 触摸绝对鼠标可点可拖；可切相对模式；软键盘与常用快捷操作可用 | 代码已实现 / 待真机联调 | `TouchInputModifier` + `Ch9329` 绝对/相对；工具栏切换鼠标模式；软键盘映射常用键；需真机点击桌面图标与拖拽验证 |
| 4 | 粘贴、Ctrl+Alt+Del、松开所有键可用 | 代码已实现 / 待真机联调 | `PasteTyper`（20ms 包间隔，单测覆盖）；工具栏 CAD / 全释放；需剪贴板粘贴与被控端响应验证 |
| 5 | 串口占用时可接管 | 代码已实现 / 待真机联调 | `SerialControl` 检测 `error.code=="busy"`；UI 接管 → `/ws/serial?takeover=1` 重连；需两客户端同时连接触发 busy |
| 6 | 基础设置可改地址并读写常用 `/api/config` 项 | 代码已实现 / 待真机联调 | `SettingsScreen` GET/PUT config、GET devices；video/audio/serial 常用字段；保存后返回控制页重连生效；`SettingsScreenTest` 通过 |
| 7 | 断网重连后画面与控制可恢复；键卡住时可用一键全释放 | 代码已实现 / 待真机联调 | `ReconnectPolicy` 指数退避上限 10s；断线角标「重连中」；**自动重连不携带 takeover**；工具栏全释放；需断 Wi-Fi 后恢复验证 |

---

## 手工验收步骤（建议顺序）

1. **连接与 TOFU：** 输入 `192.168.x.x` 或完整 URL → 首次弹出指纹对话框 → 信任并进入控制页 → 断开重连无二次提示 → 修改宿主机证书后应提示指纹变更。
2. **视频：** 默认 MJPEG 有画面；设置改为 H.264 保存并返回 → 画面仍正常；切回 MJPEG。
3. **音频：** 有声音；点静音无声；取消静音恢复。
4. **输入：** 触摸点击/拖拽；切换绝对/相对鼠标；软键盘输入字符。
5. **工具栏：** 粘贴剪贴板文本；Ctrl+Alt+Del；松开所有键（键卡住时尤其验证）。
6. **接管：** 浏览器或第二客户端占用串口 → 本端提示 busy → 点接管后键鼠恢复。
7. **设置：** 修改分辨率/fps/codec、音频窄带、串口端口等 → 保存 → 返回控制页确认生效。
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
