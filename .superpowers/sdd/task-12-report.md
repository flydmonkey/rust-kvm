# Task 12 实施报告

## 完成内容

- 新增 `PasteTyper`，按 Web 端规则映射常见 US ASCII 字符；每个按下包和释放包之间固定等待 `PACKET_GAP_MS = 20`，无法映射的字符计数并跳过。
- 新增 `ReconnectPolicy`，退避序列为 500ms、1s、2s、4s、8s，最高固定 10s。
- `KvmSession` 增加断线等待信号；控制页断线后重建视频、音频和串口会话，并显示“重连中…”。
- 用户显式接管只作用于该次连接；任何自动重连均不携带 takeover，串口 busy 时停止自动重连。
- 工具条提供静音、释放按键、Ctrl+Alt+Del、粘贴、鼠标模式、断开和设置入口；断开返回连接页，设置入口使用占位页。

## 验证

- 新增 `PasteTyperTest`：验证 ASCII 按包顺序、长度、修饰位、20ms 间隔及不支持字符跳过。
- 新增 `ReconnectPolicyTest`：验证指数退避、10 秒封顶和 reset。
- `.\gradlew.bat :client:testDebugUnitTest :app:assembleDebug`：通过。
