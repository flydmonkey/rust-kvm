# rust-kvm Android 客户端（一期）

Gradle 多模块工程：`app`（Compose UI）、`client`（播放与输入）、`core-protocol`（模型/证书/路径）、`host`（二期占位，**app 不依赖**）。

一期目标：手动连接现有 **Windows rust-kvm** 宿主机，实现 MJPEG/H.264 视频、PCM 音频、键鼠（含相对模式）、粘贴、串口接管、基础设置与断线重连。验收清单见 [`ACCEPTANCE.md`](ACCEPTANCE.md)。

## 环境要求

| 项 | 要求 |
| --- | --- |
| JDK | **17**（必须；Gradle 8.9 / AGP 8.7 与 JDK 25 不兼容） |
| Android SDK | API 35（compileSdk）；minSdk 26 |
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
.\gradlew.bat :core-protocol:testDebugUnitTest :client:testDebugUnitTest :app:testDebugUnitTest
```

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

- **控制页：** 全屏视频 + 工具栏（静音、松开所有键、Ctrl+Alt+Del、粘贴、绝对/相对鼠标、断开、设置）。
- **设置页：** 读取 `/api/devices` 与 `/api/config`，可修改视频设备/分辨率/fps/codec/码率、音频开关与窄带、串口端口/波特率/鼠标模式；保存后返回控制页并重连生效。
- **断线：** WebSocket 断开后指数退避重连（上限约 10s），显示「重连中」；**自动重连不会自动 takeover** 串口。

## 已知限制

- **无** mDNS / 局域网扫描、**无** 录制、**无** 统计 HUD
- **无** Keyboard Lock 级系统键全捕获
- **无** CH9329 芯片级配置 UI
- **无** 鉴权；请仅在可信网络使用
- **Android 宿主机**（USB 采集卡 + CH9329）为二期，`host` 模块仅占位
- 粘贴仅映射常见 US ASCII；不支持的字符会跳过
- 部分 ROM 杀后台可能断开连接；需保持 App 前台使用

设计全文：`docs/superpowers/specs/2026-09-15-android-port-design.md`
