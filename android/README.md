# rust-kvm Android 客户端（一期）与宿主机（二期 2a）

Gradle 多模块工程：`app`（Compose UI）、`client`（播放与输入）、`core-protocol`（模型/证书/路径）、`host`（Android 宿主机：HTTPS/WebSocket 服务、USB 串口、前台服务）。

一期目标：手动连接现有 **Windows rust-kvm** 宿主机，实现 MJPEG/H.264 视频、PCM 音频、键鼠（含相对模式）、粘贴、串口接管、基础设置与断线重连。验收清单见 [`ACCEPTANCE.md`](ACCEPTANCE.md)。

## 环境要求

| 项 | 要求 |
| --- | --- |
| JDK | **17**（必须；Gradle 8.9 / AGP 8.7 与 JDK 25 不兼容） |
| Android SDK | API 35（compileSdk）；minSdk 26 |
| NDK / CMake | NDK 27.0.12077973、CMake 3.22.1（`host` 模块的采集卡等时传输） |
| 设备 | Android 8.0+ 真机或模拟器（远程控制建议真机） |

### JDK 17

构建前设置 `JAVA_HOME` 指向 JDK 17，**勿用** Android Studio 自带 JBR 25 或系统默认 JDK 25：

```powershell
# Windows PowerShell 示例
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
java -version   # 应显示 17.x
```

Linux/macOS：`export JAVA_HOME=/path/to/jdk-17`

### Android SDK

- 安装 Android SDK Platform 35 与 Build-Tools（Android Studio → SDK Manager）
- 设置 `ANDROID_HOME`（或 `ANDROID_SDK_ROOT`），或在 `android/local.properties` 写入：

```properties
sdk.dir=C\:\\Users\\YourName\\AppData\\Local\\Android\\Sdk
```

## 构建

### Android Studio

打开 **`android/`** 目录（非仓库根目录）。File → Settings → Build → Gradle → Gradle JDK 选 **JDK 17**。

### 命令行

**Windows：**

```powershell
cd android
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
.\gradlew.bat :app:assembleDebug
```

**Linux / macOS：**

```bash
cd android
export JAVA_HOME=/path/to/jdk-17
./gradlew :app:assembleDebug
```

产出：`app/build/outputs/apk/debug/app-debug.apk`

运行单元测试：

```powershell
.\gradlew.bat :core-protocol:testDebugUnitTest :client:testDebugUnitTest :host:testDebugUnitTest :app:testDebugUnitTest
```

`host` 模块构建时会把仓库根目录的 `web/` 复制进 assets，所以宿主机页面和 Windows 版用的是同一套前端。

## 安装

USB 调试已开启且 `adb devices` 可见设备时：

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

覆盖安装用 `-r`；首次安装去掉 `-r` 亦可。

## 连接 Windows 宿主机

1. 在 Windows 上启动 rust-kvm 服务（例如 `cargo run`），确认监听 **`https://<Windows局域网IP>:8443`**。
2. 手机与 Windows 电脑处于**同一局域网**（或经 VPN 可达）。
3. 打开 App → 连接页输入地址：
   - 可只填 IP：`192.168.1.8`（自动补 `https://` 与 `:8443`）
   - 或完整 URL：`https://192.168.1.8:8443`
4. 首次连接会弹出 **TOFU 证书信任** 对话框（见下节），确认后进入全屏控制页。
5. 成功连接会写入「最近服务器」列表（最多 8 条），下次可点选快速连接。

防火墙：确保 Windows 放行入站 **8443/TCP**。

## TOFU 证书信任

Windows rust-kvm 使用**自签名 HTTPS 证书**。客户端采用 **Trust On First Use（TOFU）**：

- **首次**连接某 `host:port`：展示证书 **SHA-256 指纹**、主体、有效期；用户点「信任并继续」后，指纹写入本地（SharedPreferences），同地址后续连接不再询问。
- **已信任**且指纹未变：直接连接。
- **指纹变更**（例如重装服务、换证）：提示 Mismatch，需用户重新确认；旧信任不会静默接受新证书。

按 `host:port` 分别存储；无集中 CA、无账号鉴权（与浏览器客户端策略一致）。

清除某主机信任：目前需清除 App 数据或卸载重装（一期无设置项）。

## 控制页与设置

- **控制页：** 全屏视频 + 工具栏（静音、键盘、松开所有键、Ctrl+Alt+Del、粘贴、绝对/相对鼠标、断开、设置）。
- **触摸手势：** 单指点按为左键；长按或双指轻点为右键；双指上下划动为滚轮（内容跟手指走）；绝对模式单指拖动为按住左键拖拽，相对模式单指拖动只挪光标。
- **软键盘：** 点「键盘」呼出。只能输入 US 键盘上有的字符（和粘贴一样逐字模拟按键），外接键盘的组合键照常直通。
- **功能键栏：** 软键盘弹出时出现在其上方，提供 Ctrl/Alt/Shift/Win、Esc、Tab、方向键、Home/End/PgUp/PgDn/Del、F1–F12。修饰键是粘滞的：点一下点亮，作用于下一个键（功能键栏或软键盘）后自动熄灭；对已点亮的修饰键再点一次则单独发出它（例如连点两下 Win 打开开始菜单）。
- **全屏：** 控制页隐藏系统栏并保持屏幕常亮；连按两次返回才断开，避免拖鼠标时误触边缘返回手势；H.264 编码器不可用时服务端退回 MJPEG，画面底部会提示原因。
- **设置页：** 读取 `/api/devices` 与 `/api/config`，可修改视频设备/分辨率/fps/codec/码率、音频开关与窄带、串口端口/波特率/鼠标模式；保存后返回控制页并重连生效。
- **断线：** WebSocket 断开后指数退避重连（上限约 10s），显示「重连中」；**自动重连不会自动 takeover** 串口。

## 本机作为宿主机（二期：键鼠 + 采集卡画面）

一部 Android 手机（或电视盒子）用 OTG 接上 HDMI 采集卡和 CH9329，就能代替 Windows 电脑当宿主机：看目标机的画面、控制它的键鼠。目前**没有音频，也没有 H.264**（设置里选 H.264 会自动用 MJPEG，并在画面上提示原因）。

1. 手机用 OTG 接采集卡（UVC MJPEG，MS2109 一类）和 CH9329（CH340 / CP210x / FTDI 等常见 USB 串口芯片都支持）。两样同时接需要**带外接供电的 OTG Hub**，手机口直接供电通常带不动采集卡。
2. App 连接页底部点「本机作为宿主机」。
3. 设备列表里逐个点「授权」。采集卡会先要**相机权限**（系统规定：没有相机权限不允许给 USB 视频设备授权），再弹 USB 授权框。
4. 点「启动宿主机」。Android 13+ 会先请求通知权限，状态栏出现常驻通知（服务以前台服务运行，锁屏后继续工作）。
5. 页面显示访问地址（`https://<手机局域网IP>:8443`）和证书指纹。用另一台设备的浏览器或本 App 的客户端模式连接，核对指纹后信任。

宿主机实现的协议和 Windows 版一致：`/ws/serial`（含接管、忙碌拒绝、断开时发送松键帧、4ms 包间隔）、`/ws/video`（采集卡直出的 MJPEG 原样转发，重复帧不发，有人看才开采集、没人看 3 秒后关）、`/api/config`（改视频设置会立即按新格式重开采集）、`/api/devices`、`/api/video/stats`、`/api/serial/ports`、`/cert.crt` 和静态页面。`/ws/audio` 返回「音频不可用」。

采集卡驱动是自带的（不依赖系统的 Camera2 外接摄像头支持）：Kotlin 负责 UVC 协商和拼帧，等时传输由一小段 JNI（`host/src/main/cpp/usb_iso.c`）完成，所以构建需要 **NDK 27.0.12077973 和 CMake 3.22.1**（Android Studio 的 SDK Manager → SDK Tools 里装，或 `sdkmanager "ndk;27.0.12077973" "cmake;3.22.1"`）。证书为本机生成的自签名证书，存在 App 私有目录里，局域网 IP 变化时自动重签（指纹会变，客户端需要重新确认）。

停止：页面上点「停止宿主机」，或点通知里的「停止」。

## 已知限制

- **无** mDNS / 局域网扫描、**无** 录制、**无** 统计 HUD
- **无** Keyboard Lock 级系统键全捕获
- **无** CH9329 芯片级配置 UI
- **无** 鉴权；请仅在可信网络使用
- **Android 宿主机**目前只有键鼠和 MJPEG 画面；音频（2c）和 H.264（2d）尚未实现；只支持输出 MJPEG 的 UVC 采集卡
- 粘贴仅映射常见 US ASCII；不支持的字符会跳过
- 部分 ROM 杀后台可能断开连接；需保持 App 前台使用

设计全文：`docs/superpowers/specs/2026-09-15-android-port-design.md`
