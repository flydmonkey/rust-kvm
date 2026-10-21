# Android 宿主机 2a：串口桥 + 同协议服务

2026-10-21。设计见 [`2026-09-15-android-port-design.md`](../specs/2026-09-15-android-port-design.md) 二期 2a：「串口桥 + 协议可连，键鼠可用（验证 OTG/权限）」。

## 范围

手机经 USB OTG 接 CH9329（USB 转串口模块），在 `https://<手机IP>:8443` 上提供与 Windows 服务同形的接口，浏览器 `web/` 与 Android 客户端都能连进来控制键鼠。

| 接口 | 2a 行为 |
| --- | --- |
| `/ws/serial` | 与 `src/serial_bridge.rs` 一致：独占座位、等 2 秒、`?takeover=1` 请现任退位（等 5 秒）、`open`/`close`/`ping` ↔ `status`/`error`(`code:"busy"`)/`pong`、Binary 原样透传、出串口前 4ms 包间隔、断开时补发键盘+鼠标全释放包 |
| `/ws/video` | 接受连接但不出画面（2b 才有采集）；读到对端关闭为止 |
| `/ws/audio` | 回 `{"op":"error"}` 说明暂不支持音频，保持连接 |
| `/api/config` | GET/PUT，同一份 JSON（`KvmConfig`），存 App 私有目录 |
| `/api/devices` | 视频/音频带 `error` 说明暂不支持；串口列 USB 串口设备 |
| `/api/serial/ports` | USB 串口设备列表 `[{name, description}]` |
| `/api/video/stats`、`/api/audio/stats` | 形状与 Windows 一致，`capturing:false` + `error` |
| `/cert.crt` | 当前自签证书 PEM |
| 其他 GET | 打包进 APK 的 `web/` 静态文件，`/` → `index.html` |

不做：采集、音频、H.264（2b–2d）；鉴权（与现项目一致）。

## 选型

- **HTTP/WS**：Ktor 3 + Netty 引擎。CIO 引擎不支持 TLS，而浏览器的剪贴板等 API 只在 HTTPS 下可用。Ktor 的 `testApplication` 能在 JVM 单测里跑完整路由和 WebSocket。
- **证书**：BouncyCastle 签 RSA 自签证书，SAN 覆盖 `localhost`、`127.0.0.1` 与本机全部非回环 IPv4；IP 变了重签（同 `src/certs.rs`）。存 PKCS12（Android 不支持 JKS）。
- **USB 串口**：`usb-serial-for-android`（CH340/CP210x/FTDI/PL2303/CDC）。端口名用 `UsbDevice.deviceName`，描述用产品名。
- **形态**：同一个 APK。连接页加「本机作为宿主机」入口；宿主机跑在前台服务里（`connectedDevice` 类型），通知栏常驻。

## 模块

`host/`（Android library）：

- `serial/`：`RawSerialPort`（阻塞读写抽象）、`SerialLink`（写线程保 4ms 包间隔、读线程、串口段往返计时）、`SerialHub`（座位 + 退位信号）、`SerialSession`（WebSocket 协议循环）、`ReleaseFrames`。
- `server/`：`hostModule()` 路由（可在 `testApplication` 里跑）、`HostServer`（Netty + TLS 启停）、静态资源。
- `tls/`：自签证书生成与缓存。
- `config/`：配置文件读写（坏了退回默认值，先落盘再更新内存）。
- `usb/`：`UsbSerialPorts`（枚举、授权、打开），`HostService`（前台服务）。

`app/`：`HostScreen`（启停、访问地址、证书指纹、USB 设备与授权）。

## 验收（真机）

1. 手机 OTG 接 CH9329，打开「本机作为宿主机」→ 授权 USB → 启动，通知栏常驻，页面显示访问地址。
2. 同局域网电脑浏览器打开该地址（信任证书）→ 键鼠图标连上、波特率探测成功 → 能控制被控机。
3. 另一台 Android 客户端连该地址 → 进入控制页（无画面）→ 触摸/键盘可控；浏览器端提示被占用，可接管。
4. 控制中拔掉 CH9329 → 控制端收到「串口连接已中断」；插回后重新授权可恢复。
5. 断开控制端时被控机没有卡住的键。

## JVM 单测

- 串口协议：open/status、透传、包间隔、ping/pong、busy、接管时现任收到 busy、断开补发全释放包、`close`。
- 路由：config 读写持久化、devices/ports/stats 形状、静态文件与 `index.html`。
- 证书：SAN 覆盖、名字不变复用、名字变了重签。
- 端到端：真 Netty + TLS 起服务，用 `client` 模块的 `KvmHttpClient`/`SerialControl` 连进来完成 TOFU、打开串口、发一个按键。
