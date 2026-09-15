# Android 移植：原生客户端 + 同协议宿主机

2026-09-15

一期实施计划见 [`docs/superpowers/plans/2026-09-15-android-client.md`](../plans/2026-09-15-android-client.md)。

## 目标

在 Android 上落地 rust-kvm 的能力，分两期交付，但用**同一份设计**约束协议与模块边界：

1. **一期：原生 Kotlin 客户端** — 连接现有 Windows rust-kvm（或日后安卓宿主机），达到日常远程控制可用。
2. **二期：Android 宿主机** — 经 USB OTG 独占采集卡 + CH9329，对外提供与现服务**同形**的 HTTPS/WebSocket/API，浏览器与安卓客户端均可接入。

成功判据：

- 安卓客户端 ↔ Windows rust-kvm 可用（一期验收清单全部满足）。
- 安卓宿主机对外协议与 Windows 服务兼容，无需为「安卓源」开客户端特例（二期；允许先无 H.264，但路径与消息兼容）。
- Windows Rust 服务继续作为主流宿主机实现，不迁入 NDK。

## 非目标

- 不把现有 Rust `axum` 服务搬进 Android NDK / JNI。
- 一期不做：mDNS / 局域网扫描、录制、统计 HUD、Keyboard Lock 级系统键全捕获、CH9329 芯片波特率/工作模式配置。
- 不做 WebRTC；继续自定义 WebSocket 帧协议。
- 不做鉴权（与现项目一致；访问路径假定可信局域网或 VPN）。
- 不保证所有 ROM 在杀后台后仍常驻（二期靠前台服务 + 用户指引）。

## 已确认的产品决策

| 决策 | 选择 |
| --- | --- |
| 范围 | 客户端 + 宿主机都要；实现严格先客户端后宿主机 |
| 客户端形态 | 原生 Kotlin App（非 WebView 壳） |
| 一期功能档 | 对标浏览器常用能力：MJPEG + H.264、音频、键鼠（含相对）、粘贴、控制权接管、基础设置 |
| 发现宿主机 | 手动输入地址；记住最近连接 |
| 自签名证书 | 首次连接展示指纹，用户信任后按 host:port 持久化（TOFU） |
| 实现路线 | 纯 Kotlin 双端；与 Windows Rust 协议对齐、实现独立 |

## 总体架构

Windows 继续跑现有 Rust 服务。Android 侧纯 Kotlin，协议对齐现有：

- `https://<host>:8443`（局域网）/ 可选本机 HTTP
- `/ws/video`、`/ws/audio`、`/ws/serial`
- `/api/config`、`/api/devices`、stats 等

互通矩阵：

| 控制端 \\ 宿主机 | Windows Rust | Android Host（二期） |
| --- | --- | --- |
| 浏览器 `web/` | 已有 | 目标兼容 |
| Android 客户端 | 一期目标 | 二期目标 |

### 仓库布局

```
rust-kvm/
  src/ ...                 # 现有 Windows/桌面 Rust 服务（主路径不改）
  web/ ...                 # 现有浏览器客户端
  android/                 # 新建 Gradle 多模块
    app/                   # 壳：导航、连接、控制、设置、宿主机开关
    core-protocol/         # HTTP/WS 编解码、证书信任存储、config 模型、路径常量
    client/                # 播放、输入、音频、控制会话
    host/                  # 二期：USB、串口桥、本地 HTTP/WS 服务
```

### 运行时角色

| 角色 | 一期 | 二期 |
| --- | --- | --- |
| 客户端 | 手动地址 + TOFU + 三条 WS + API | 同左 |
| 宿主机 | 不做 | 前台服务 + USB + 同协议 HTTP/WS |
| Windows Rust | 主流宿主机 | 继续独立实现，协议兼容 |

## 一期：Android 客户端

### 页面与流程

1. **连接页**：输入地址（可省协议，默认 `https` + 端口 `8443`）；最近连接列表。
2. **证书 TOFU**：未信任则展示指纹/主体/有效期 →「信任并继续」；已信任直连；指纹变更须重新确认。
3. **控制页**：全屏画面 + 工具条（静音、松开所有键、Ctrl+Alt+Del、粘贴、绝对/相对鼠标、断开、进设置）。
4. **设置页（基础）**：改服务器地址；`GET /api/devices`；`GET/PUT /api/config` 常用字段（video 设备/分辨率/fps/codec、audio 开关与窄带、serial 端口与鼠标模式）。

### 流媒体

| 通道 | 行为 |
| --- | --- |
| `/ws/video` | Text 编码头（`mjpeg` / `h264`）；Binary 为 JPEG 或 Annex-B H.264。MJPEG 用 `BitmapFactory`/`ImageDecoder` 绘到 Surface；H.264 用 `MediaCodec`。处理 `{"op":"idle"}`（保持上一帧）。 |
| `/ws/audio` | Text `{sampleRate,channels,format}`；Binary Int16 LE PCM → `AudioTrack`；一期用固定短抖动缓冲即可。 |
| `/ws/serial` | Text JSON：`open`/`close`/`ping`/`status`/`error`/`pong`；Binary 为 CH9329 原始字节；支持 `?takeover=1`。 |

### 输入

- **绝对鼠标**：触摸映射到画面内容区（letterbox 换算）→ CH9329 绝对模式。
- **相对鼠标**：触控板式滑动发相对位移；外接鼠标相对事件优先（若可得）。
- **键盘**：软键盘 + 外接键盘；常用修饰键与特殊键映射；「松开所有键」「Ctrl+Alt+Del」。
- **粘贴**：读剪贴板，按现网页思路向被控端打字（默认速率对齐 `web/`）。
- **控制权**：串口占用时提示；用户可选择接管。

CH9329 帧逻辑从 `web/ch9329.js` 移植到 Kotlin，放在 `client` 模块（宿主机只透传字节，不编码 HID）；单测覆盖编解码。

### 技术选型

- UI：Jetpack Compose
- 网络：OkHttp（HTTPS + WebSocket）；自定义 `TrustManager` 做 TOFU
- 异步：`kotlinx.coroutines`；解码/渲染与 UI 解耦
- minSdk：**26**；targetSdk 跟进当前稳定版

### 一期明确不做

录制、统计叠加层、系统键 Keyboard Lock 全捕获、CH9329 芯片级配置 UI、服务发现。

## 二期：Android 宿主机

### 定位

USB OTG 接采集卡（典型 MS2109 类 UVC MJPEG）+ CH9329（USB 串口），前台服务对外提供同形协议。

### 组件

| 组件 | 职责 |
| --- | --- |
| `HostService`（前台） | 保活、通知栏、启停采集与 HTTP 服务 |
| `UsbDeviceHub` | 权限、热插拔、绑定视频/音频/串口设备 |
| `VideoSource` | UVC MJPEG 读取；有观看者才采，无人约 3s 后停 |
| `VideoEncoder`（后置） | 先 MJPEG 原样转发；站稳后 `MediaCodec` H.264 |
| `AudioSource` | USB 音频 → Int16 PCM；窄带可在推流前重采样 |
| `SerialBridge` | USB CDC ↔ `/ws/serial`；互斥、`takeover`、全释放、ping/pong |
| `HttpServer` | 嵌入式服务（如 Ktor）挂同路径；自签证书按本机 IP 生成 |
| `ConfigStore` | 本地配置；`/api/config`；视频/音频热切换 |

`VideoSource` / `AudioSource` 以接口隔离，便于替换 UVC/UAC 实现（系统 API 不足时用开源栈）。

### 二期内部里程碑

1. **2a**：串口桥 + 协议可连，键鼠可用（验证 OTG/权限）
2. **2b**：UVC MJPEG 实采转发 + 设备枚举/配置
3. **2c**：USB 音频 + 热插拔恢复
4. **2d**：H.264 编码 + 窄带音频

### 风险

- 部分机型 OTG 供电不足：文档标明，不做软件硬扛。
- UVC/UAC 在 Android 上碎片化：接口化采集后端，优先 MJPEG 路径。
- 后台存活依赖前台服务，不做 Windows SCM 等价物。

## 错误处理

| 场景 | 行为 |
| --- | --- |
| 不可达 / TLS 失败 | 连接页明确错误；指纹变更单独提示 |
| WS 断线 | 指数退避重连（上限约 10s）；角标「重连中」；串口重连后默认不自动 takeover |
| 视频解码失败 | 丢帧计数；连续失败提示检查 codec / 改 MJPEG |
| 音频异常 | 静音，不拖垮视频 |
| 串口占用 | 等待或接管 |
| 宿主机 USB 无权限/拔出 | 通知 + API 明确错误；插回后尝试自恢复 |
| 配置热切换失败 | API 返回错误；客户端 toast，不静默成功 |

## 测试与验收

### 一期客户端测试

- `core-protocol` 单测：视频头、PCM 头、serial JSON、CH9329 帧
- 联调对象：现有 Windows `cargo run` 宿主机
- 覆盖：MJPEG、H.264、音频开/关、绝对/相对鼠标、粘贴、接管、TOFU/换证
- 真机：至少一台手机 + 一台平板（有外接键盘则加测）

### 一期验收清单

1. 手动地址 + TOFU 后稳定进入控制页
2. MJPEG 与 H.264 可看；音频可听可静音
3. 触摸绝对鼠标可点可拖；可切相对模式；软键盘与常用快捷操作可用
4. 粘贴、Ctrl+Alt+Del、松开所有键可用
5. 串口占用时可接管
6. 基础设置可改地址并读写常用 `/api/config` 项
7. 断网重连后画面与控制可恢复；键卡住时可用一键全释放

### 二期验收

浏览器与安卓客户端连接安卓宿主机时，协议行为与 Windows 服务一致（允许里程碑内先无 H.264，但消息与路径兼容）。2a–2d 各用浏览器 + 安卓客户端各验一轮；覆盖热插拔、按需启停采集、串口单控。

## 实现顺序（设计约束，非本文件的施工单）

1. 建 `android/` 多模块骨架与 `core-protocol`
2. 连接页 + TOFU + 三条 WS 打通（可先黑屏/日志）
3. MJPEG 显示 → H.264 → 音频 → 输入/CH9329 → 设置
4. 一期验收通过后，再开宿主机 2a–

详细任务拆分在设计批准后由 implementation plan 给出。
