# Android 原生客户端（一期）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在仓库内新建纯 Kotlin Android 客户端，手动连接现有 Windows rust-kvm，实现 MJPEG/H.264、音频、键鼠（含相对）、粘贴、接管与基础设置，并满足设计文档一期验收清单。

**Architecture:** `android/` 多模块：`app` 壳 + `core-protocol`（模型/证书/路径）+ `client`（播放与输入）+ 空 `host` 占位。协议对齐现有 HTTPS WebSocket（`/ws/video|audio|serial`）与 `/api/*`，不改 Windows Rust 服务主路径。

**Tech Stack:** Kotlin；Jetpack Compose；OkHttp；kotlinx.coroutines；MediaCodec / ImageDecoder；AudioTrack；minSdk 26；JUnit4 单元测试（JVM）。

**设计文档：** `docs/superpowers/specs/2026-09-15-android-port-design.md`

**范围说明：** 本计划**只做一期客户端**。二期 Android 宿主机（USB OTG）另开 `docs/superpowers/plans/` 计划；本仓库可建空 `host` 模块占位，但不实现采集/服务。

## Global Constraints

- 注释与提交信息用**中文**，说清「为什么」。
- 配置 JSON 字段一律 **camelCase**（与 `config.json` / `/api/config` 一致）。
- **不上 WebRTC**；不鉴权；不做 mDNS / 录制 / 统计 HUD / CH9329 芯片配置。
- 证书：**TOFU**（按 `host:port` 存证书 SHA-256 指纹）；指纹变更必须重新确认。
- minSdk **26**；不引入 NDK / 不把 Rust 编进 `.so`。
- CH9329 编码放在 `client`；宿主机只透传字节（二期）。
- 协议行为以现有 `web/` + `src/main.rs` 为准；有歧义时对照浏览器客户端。
- Windows 服务在跑时可能锁 exe；联调前确认 `:8443` 可访问。
- 每个 Task 结束必须可独立验证（单测或手工步骤），再 commit。

---

## File Structure

**新建：**

| 路径 | 职责 |
| --- | --- |
| `android/settings.gradle.kts` | 多模块声明 |
| `android/build.gradle.kts` | 根插件版本 |
| `android/gradle.properties` | AndroidX 等 |
| `android/app/` | Application、导航、连接/控制/设置 Compose 屏 |
| `android/core-protocol/` | Config 模型、WS 消息解析、路径常量、TOFU 存储接口 |
| `android/client/` | Video/Audio/Serial 会话、解码、CH9329、输入映射 |
| `android/host/` | **空占位** library（`build.gradle.kts` + 空 `HostPlaceholder.kt`） |
| `android/README.md` | 如何用 Android Studio / `gradlew` 构建与联调 |

**不修改（一期）：** `src/**`、`web/**`（只读参考）。

---

### Task 1: Gradle 多模块骨架

**Files:**
- Create: `android/settings.gradle.kts`
- Create: `android/build.gradle.kts`
- Create: `android/gradle.properties`
- Create: `android/app/build.gradle.kts`
- Create: `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/java/com/rustkvm/app/MainActivity.kt`
- Create: `android/core-protocol/build.gradle.kts`
- Create: `android/client/build.gradle.kts`
- Create: `android/host/build.gradle.kts`
- Create: `android/host/src/main/java/com/rustkvm/host/HostPlaceholder.kt`
- Create: `android/README.md`

**Interfaces:**
- Consumes: 无
- Produces: 可同步的 Gradle 工程；applicationId `com.rustkvm.app`；模块依赖 `app → client → core-protocol`，`app` 暂不依赖 `host`

- [ ] **Step 1: 写根 Gradle 与模块 build 文件**

`android/settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "rust-kvm-android"
include(":app", ":core-protocol", ":client", ":host")
```

`android/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}
```

`android/gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
```

`android/core-protocol/build.gradle.kts`（library，开 JVM unit test）:

```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}
android {
    namespace = "com.rustkvm.protocol"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("junit:junit:4.13.2")
}
```

`android/client/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.rustkvm.client"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    api(project(":core-protocol"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
```

`android/host/build.gradle.kts`：同 library 骨架，`namespace = "com.rustkvm.host"`，无额外依赖。

`android/app/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.rustkvm.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.rustkvm.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":client"))
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
```

- [ ] **Step 2: 最小 Manifest + MainActivity + host 占位**

`android/app/src/main/AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <application
        android:allowBackup="true"
        android:label="rust-kvm"
        android:supportsRtl="true"
        android:usesCleartextTraffic="false"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|keyboardHidden">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`MainActivity.kt`：`setContent { Text("rust-kvm") }`。

`HostPlaceholder.kt`：`object HostPlaceholder`

`android/README.md`：说明用 Android Studio 打开 `android/`，或 `cd android && ./gradlew :app:assembleDebug`；联调需 Windows 宿主机 `:8443`。

- [ ] **Step 3: 生成 Wrapper 并编译**

在已装 JDK 17 + Android SDK 的环境：

```bash
cd android
gradle wrapper --gradle-version 8.9
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`，产出 `app/build/outputs/apk/debug/app-debug.apk`。

若本机无全局 `gradle`，可用 Android Studio「Open」`android/` 目录自动生成 wrapper，再跑 `gradlew`。

- [ ] **Step 4: Commit**

```bash
git add android
git commit -m "android: 多模块工程骨架（app/client/core-protocol/host 占位）"
```

---

### Task 2: Config 模型与 API JSON

**Files:**
- Create: `android/core-protocol/src/main/java/com/rustkvm/protocol/ConfigModels.kt`
- Create: `android/core-protocol/src/main/java/com/rustkvm/protocol/ProtocolPaths.kt`
- Create: `android/core-protocol/src/test/java/com/rustkvm/protocol/ConfigModelsTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `data class KvmConfig(val video: VideoSettings, val audio: AudioSettings, val serial: SerialSettings)`
  - 各 Settings 字段与 Rust `src/config.rs` camelCase 一致：`bitrateKbps`、`baudRate`、`mouseMode`、`narrowband`
  - `object ProtocolPaths`：`WS_VIDEO="/ws/video"`、`WS_AUDIO="/ws/audio"`、`WS_SERIAL="/ws/serial"`、`API_CONFIG="/api/config"`、`API_DEVICES="/api/devices"`
  - `fun parseConfig(json: String): KvmConfig` / `fun encodeConfig(config: KvmConfig): String`

- [ ] **Step 1: 写失败单测**

```kotlin
package com.rustkvm.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigModelsTest {
    @Test
    fun parsesCamelCaseConfig() {
        val json = """
        {
          "video":{"device":"USB Video","width":1920,"height":1080,"fps":30,"codec":"h264","bitrateKbps":4000},
          "audio":{"device":"Digital Audio Interface","enabled":true,"narrowband":false},
          "serial":{"port":"COM3","baudRate":115200,"mouseMode":"absolute"}
        }
        """.trimIndent()
        val c = parseConfig(json)
        assertEquals("h264", c.video.codec)
        assertEquals(4000, c.video.bitrateKbps)
        assertEquals(115200, c.serial.baudRate)
        assertEquals("absolute", c.serial.mouseMode)
    }

    @Test
    fun roundTrips() {
        val original = KvmConfig.DEFAULT
        val again = parseConfig(encodeConfig(original))
        assertEquals(original, again)
    }
}
```

- [ ] **Step 2: 跑测确认失败**

```bash
cd android && ./gradlew :core-protocol:test --tests com.rustkvm.protocol.ConfigModelsTest
```

Expected: FAIL（类/函数未定义）。

- [ ] **Step 3: 实现模型**

```kotlin
@Serializable
data class KvmConfig(
    val video: VideoSettings = VideoSettings(),
    val audio: AudioSettings = AudioSettings(),
    val serial: SerialSettings = SerialSettings(),
) {
    companion object {
        val DEFAULT = KvmConfig()
    }
}

@Serializable
data class VideoSettings(
    val device: String = "USB Video",
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 30,
    val codec: String = "mjpeg",
    val bitrateKbps: Int = 4000,
)

@Serializable
data class AudioSettings(
    val device: String = "Digital Audio Interface",
    val enabled: Boolean = true,
    val narrowband: Boolean = false,
)

@Serializable
data class SerialSettings(
    val port: String = "",
    val baudRate: Int = 115200,
    val mouseMode: String = "absolute",
)

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun parseConfig(text: String): KvmConfig = json.decodeFromString(text)
fun encodeConfig(config: KvmConfig): String = json.encodeToString(config)

object ProtocolPaths {
    const val WS_VIDEO = "/ws/video"
    const val WS_AUDIO = "/ws/audio"
    const val WS_SERIAL = "/ws/serial"
    const val API_CONFIG = "/api/config"
    const val API_DEVICES = "/api/devices"
}
```

- [ ] **Step 4: 跑测通过**

同 Step 2，Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add android/core-protocol
git commit -m "android: core-protocol 对齐 config.json /api/config 模型"
```

---

### Task 3: 视频 / 音频 / 串口 Text 消息解析

**Files:**
- Create: `android/core-protocol/src/main/java/com/rustkvm/protocol/WsMessages.kt`
- Create: `android/core-protocol/src/test/java/com/rustkvm/protocol/WsMessagesTest.kt`

**Interfaces:**
- Consumes: kotlinx.serialization
- Produces:
  - `sealed class VideoText`：`Codec(codec: String, avcCodec: String?)`、`Idle(skipped: Long?)`、`Unknown`
  - `fun parseVideoText(text: String): VideoText`
  - `data class AudioFormatHeader(sampleRate: Int, channels: Int, format: String)` + `parseAudioText`
  - `sealed class SerialText`：`Status(open: Boolean, baudRate: Int?)`、`Error(message: String, code: String?)`、`Pong`、`Unknown`
  - `fun parseSerialText(text: String): SerialText`
  - 视频头形状：`{"op":"codec","codec":"mjpeg"|"h264","avcCodec":?...}`；idle：`{"op":"idle","skipped":N}`
  - 音频头：`{"op":"format","sampleRate":N,"channels":N,"format":"s16le"}`
  - 串口错误占用：`code == "busy"`

- [ ] **Step 1: 写失败单测**

```kotlin
@Test
fun parsesH264CodecHeader() {
    val v = parseVideoText("""{"op":"codec","codec":"h264","avcCodec":"avc1.4D0428"}""")
    val c = v as VideoText.Codec
    assertEquals("h264", c.codec)
    assertEquals("avc1.4D0428", c.avcCodec)
}

@Test
fun parsesIdle() {
    val v = parseVideoText("""{"op":"idle","skipped":12}""") as VideoText.Idle
    assertEquals(12L, v.skipped)
}

@Test
fun parsesAudioFormat() {
    val a = parseAudioText("""{"op":"format","sampleRate":48000,"channels":2,"format":"s16le"}""")
    assertEquals(48000, a!!.sampleRate)
    assertEquals(2, a.channels)
}

@Test
fun parsesSerialBusy() {
    val s = parseSerialText("""{"op":"error","message":"busy","code":"busy"}""") as SerialText.Error
    assertEquals("busy", s.code)
}
```

- [ ] **Step 2: 跑测确认失败**

```bash
./gradlew :core-protocol:test --tests com.rustkvm.protocol.WsMessagesTest
```

Expected: FAIL。

- [ ] **Step 3: 实现解析**（用 `Json.parseToJsonElement` 按 `op` 分支；未知 op → Unknown；音频非 format → 返回 null）

- [ ] **Step 4: 跑测通过**

- [ ] **Step 5: Commit**

```bash
git commit -am "android: 解析 video/audio/serial WebSocket Text 消息"
```

---

### Task 4: 服务器地址规范化 + TOFU 存储

**Files:**
- Create: `android/core-protocol/src/main/java/com/rustkvm/protocol/ServerAddress.kt`
- Create: `android/core-protocol/src/main/java/com/rustkvm/protocol/CertTrustStore.kt`
- Create: `android/core-protocol/src/test/java/com/rustkvm/protocol/ServerAddressTest.kt`
- Create: `android/core-protocol/src/test/java/com/rustkvm/protocol/CertTrustStoreTest.kt`
- Create: `android/app/.../trust/SharedPrefsCertTrustStore.kt`（实现放 app，接口在 core-protocol）

**Interfaces:**
- Consumes: 无
- Produces:
  - `fun normalizeServerUrl(input: String): HttpUrl` — 无 scheme 则加 `https://`；无端口则 `:8443`；去掉尾 `/`
  - `interface CertTrustStore { fun trustedFingerprint(hostPort: String): String?; fun trust(hostPort: String, sha256Hex: String); fun clear(hostPort: String) }`
  - `fun sha256Fingerprint(cert: java.security.cert.X509Certificate): String`（小写 hex，无冒号）

- [ ] **Step 1: 地址单测（先写后实现）**

```kotlin
@Test
fun fillsHttpsAndPort() {
    assertEquals("https://192.168.1.8:8443/", normalizeServerUrl("192.168.1.8").toString())
}

@Test
fun keepsExplicitPort() {
    assertEquals("https://host.local:9443/", normalizeServerUrl("https://host.local:9443").toString())
}
```

- [ ] **Step 2: 内存版 TrustStore 单测**

```kotlin
class InMemoryCertTrustStore : CertTrustStore {
    private val map = mutableMapOf<String, String>()
    override fun trustedFingerprint(hostPort: String) = map[hostPort]
    override fun trust(hostPort: String, sha256Hex: String) { map[hostPort] = sha256Hex.lowercase() }
    override fun clear(hostPort: String) { map.remove(hostPort) }
}

@Test
fun remembersFingerprint() {
    val store = InMemoryCertTrustStore()
    store.trust("192.168.1.8:8443", "ABCD")
    assertEquals("abcd", store.trustedFingerprint("192.168.1.8:8443"))
}
```

（`InMemoryCertTrustStore` 可放在 `src/test` 或 `src/main` 供测试；生产用 SharedPreferences 实现放 Task 6 接到 UI。）

- [ ] **Step 3: 实现 `ServerAddress` + `CertTrustStore` 接口 + `sha256Fingerprint`**

- [ ] **Step 4: 跑 `:core-protocol:test` 全绿**

- [ ] **Step 5: Commit**

```bash
git commit -am "android: 服务器地址规范化与证书 TOFU 接口"
```

---

### Task 5: OkHttp 会话（HTTPS TOFU + 三条 WS + REST）

**Files:**
- Create: `android/client/src/main/java/com/rustkvm/client/net/TofuTrustManager.kt`
- Create: `android/client/src/main/java/com/rustkvm/client/net/KvmHttpClient.kt`
- Create: `android/client/src/main/java/com/rustkvm/client/net/KvmSession.kt`
- Create: `android/client/src/test/java/com/rustkvm/client/net/TofuTrustManagerTest.kt`

**Interfaces:**
- Consumes: `CertTrustStore`、`normalizeServerUrl`、`ProtocolPaths`、`parseConfig`
- Produces:
  - `sealed class CertDecision { object Trusted; data class NeedsUserTrust(val fingerprint: String, val subject: String); data class Mismatch(val old: String, val new: String) }`
  - `class KvmHttpClient(baseUrl, trustStore, onCertDecision: suspend (CertDecision) -> Boolean)`  
    —— 自定义 `X509TrustManager`：无记录 → NeedsUserTrust；匹配 → Trusted；不匹配 → Mismatch；用户同意后 `trustStore.trust` 并重试
  - `suspend fun getConfig(): KvmConfig` / `suspend fun putConfig(config: KvmConfig)` / `suspend fun getDevicesJson(): String`
  - `class KvmSession`：`connect()` 打开 video/audio/serial 三个 WebSocket；暴露 `Flow`/`Channel`：`videoText`、`videoBinary`、`audioText`、`audioBinary`、`serialText`、`serialBinary`；`sendSerialText` / `sendSerialBinary`；断线时关闭全部
  - serial URL：控制连接用 `.../ws/serial`，接管时 `.../ws/serial?takeover=1`

- [ ] **Step 1: TrustManager 纯逻辑单测**

用自签测试证书字节或 mock：给定 store 空 / 匹配 / 不匹配三种，断言 `evaluate(chain)` 返回对应 sealed（把决策抽成不依赖 Android 的函数便于测）。

- [ ] **Step 2: 实现 `evaluateCertificate` + `TofuTrustManager` + `KvmHttpClient` REST**

- [ ] **Step 3: 实现 `KvmSession` WebSocket**

OkHttp `WebSocketListener`：Text → 对应 Channel；Binary → ByteArray Channel。`connect()` 并发建三条；任一条失败则取消其余并抛错。

- [ ] **Step 4: 手工冒烟（对照 Windows 服务）**

```bash
# 宿主机已 cargo run，记下 https://<IP>:8443
# 临时在 android 里加 instrumentation 或小 debug Activity 调用 getConfig()
```

Expected: TOFU 弹一次后 `GET /api/config` 返回 JSON；三条 WS 能连上（可用 log 打出 video codec 头）。

若尚无 UI，可在 `MainActivity` 临时按钮触发，下一 Task 再换成正式连接页。

- [ ] **Step 5: Commit**

```bash
git commit -am "android: OkHttp TOFU 客户端与三路 WebSocket 会话"
```

---

### Task 6: 连接页 + 最近服务器 + TOFU 对话框

**Files:**
- Create: `android/app/.../ui/ConnectScreen.kt`
- Create: `android/app/.../ui/CertTrustDialog.kt`
- Create: `android/app/.../data/RecentServersStore.kt`
- Create: `android/app/.../trust/SharedPrefsCertTrustStore.kt`
- Modify: `android/app/.../MainActivity.kt`（NavHost：`connect` → `control`）

**Interfaces:**
- Consumes: `normalizeServerUrl`、`KvmHttpClient`、`CertTrustStore`
- Produces: 用户确认后 `navController.navigate("control/{encodedBaseUrl}")`；Recent 列表最多 8 条

- [ ] **Step 1: SharedPrefs 实现 `CertTrustStore` + `RecentServersStore`**

- [ ] **Step 2: Compose 连接页**

- 输入框默认提示 `192.168.x.x` 或完整 URL  
- 「连接」→ normalize → 建 client；若 `NeedsUserTrust`/`Mismatch` 弹对话框展示指纹  
- 成功 → 写入 Recent → 导航控制页  

- [ ] **Step 3: 真机/模拟器联调**

对着 Windows `:8443`：首次提示信任；再次连接无提示；改宿主机证书后应 Mismatch。

- [ ] **Step 4: Commit**

```bash
git commit -am "android: 连接页、最近服务器与证书信任对话框"
```

---

### Task 7: MJPEG 显示

**Files:**
- Create: `android/client/.../video/MjpegDecoder.kt`
- Create: `android/client/.../video/VideoRenderer.kt`
- Create: `android/app/.../ui/ControlScreen.kt`（先只挂 Surface）
- Create: `android/client/src/test/.../MjpegDecoderTest.kt`（可选：对最小 JPEG 字节 decode 非 null）

**Interfaces:**
- Consumes: `KvmSession.videoText/videoBinary`、`VideoText.Codec` / `Idle`
- Produces: `class VideoPipeline(session, surface)`：收到 codec=`mjpeg` 后，Binary → `BitmapFactory.decodeByteArray` → 画到 `Surface`；`Idle` 不刷新；codec 切换时重置状态

- [ ] **Step 1: `MjpegDecoder.decode(bytes): Bitmap?` + 单测（用 1x1 JPEG 常量字节）**

- [ ] **Step 2: `VideoPipeline` 在 IO/Default 协程消费 session；UI 用 `AndroidView(SurfaceView)`

- [ ] **Step 3: ControlScreen 进入后 `KvmSession.connect()` 并启动 pipeline

手工 Expected: 能看到被控机画面（局域网 MJPEG）。

- [ ] **Step 4: Commit**

```bash
git commit -am "android: 控制页 MJPEG 解码显示"
```

---

### Task 8: H.264（MediaCodec）

**Files:**
- Create: `android/client/.../video/H264Decoder.kt`
- Modify: `android/client/.../video/VideoPipeline.kt`

**Interfaces:**
- Consumes: `VideoText.Codec` 的 `avcCodec`（如 `avc1.4D0428`）；Annex-B Binary
- Produces: `H264Decoder`：配置 `MediaCodec.createDecoderByType("video/avc")`，`csd-0` 从流内 SPS/PPS 提取或等首个 IDR；输出到同一 `Surface`；MJPEG/H.264 切换时释放旧解码器

参考 `web/video-worker.js`：扫 Annex-B 起始码找 NAL type 5/7；H.264 路径不随意丢帧。

- [ ] **Step 1: 实现 Annex-B NAL 扫描工具 + 单测（合成带起始码的 byte 数组）**

- [ ] **Step 2: 实现 `H264Decoder`，接入 `VideoPipeline` 按 codec 分支

- [ ] **Step 3: 在设置里把 Windows 宿主机 codec 改为 h264，客户端应能出画；切回 mjpeg 仍正常

- [ ] **Step 4: Commit**

```bash
git commit -am "android: H.264 MediaCodec 硬解路径"
```

---

### Task 9: 音频播放

**Files:**
- Create: `android/client/.../audio/PcmPlayer.kt`
- Create: `android/app/.../ui/components/AudioToggle.kt`
- Modify: `ControlScreen.kt`

**Interfaces:**
- Consumes: `parseAudioText` → `AudioTrack`（`ENCODING_PCM_16BIT`，MODE_STREAM）
- Produces: `PcmPlayer`：`onFormat(header)` 重建 AudioTrack；`write(bytes)`；`muted` 时丢弃或音量 0；固定短缓冲（例如 2–4 块）防抖，不做复杂自适应

- [ ] **Step 1: 实现 `PcmPlayer`（纯逻辑可对 sampleRate/channels 断言 buffer size > 0 的单测）**

- [ ] **Step 2: 控制页静音按钮；默认尝试播；服务端 `audio.enabled=false` 时显示错误 Text 不崩溃

- [ ] **Step 3: 联调听声；开关窄带后应收到新 format 头并重建 Track

- [ ] **Step 4: Commit**

```bash
git commit -am "android: PCM 音频播放与静音"
```

---

### Task 10: CH9329 协议核心（键盘/鼠标封包）

**Files:**
- Create: `android/client/.../hid/Ch9329.kt`
- Create: `android/client/.../hid/Ch9329Maps.kt`（从 `web/ch9329.js` 移植 KEYBOARD_MAP / CONTROL_KEY_BITS）
- Create: `android/client/src/test/.../hid/Ch9329Test.kt`

**Interfaces:**
- Consumes: 写回调 `(ByteArray) -> Unit`；可选读回调
- Produces:
  - `class Ch9329(absoluteMouse: Boolean, write: (ByteArray) -> Unit)`
  - `keydown/keyup(key: String)`（Code 如 `KeyA`）、`releaseAllKeys()`
  - `mouseMoveAbsolute(nx: Float, ny: Float, buttons: Int)` — 归一化 0..1 → 0..4095
  - `mouseMoveRelative(dx: Int, dy: Int, buttons: Int)`
  - `ctrlAltDelete()`
  - 帧头校验和：与 JS `toUnit8Array` 一致（HEAD+ADDR+CMD+LEN+DATA 低 8 位）
  - 固定释放包：与 JS `keyboardReleasePacket` 一致

移植策略：先移植**封包与键表**（可单测）；ACK 队列可简化为「写后不严格等 ACK」的一期版本，但保留读缓冲接口以便日后收紧。若联调发现芯片依赖 ACK，再按 JS `_queue` 补全（仍在本 Task 内修到键鼠可用）。

- [ ] **Step 1: 单测校验和与绝对鼠标坐标**

```kotlin
@Test
fun absoluteCoordsClamp() {
    // nx=1, ny=0 → x=4095,y=0 的封包 CMD=0x04
    val frames = mutableListOf<ByteArray>()
    val ch = Ch9329(absoluteMouse = true) { frames += it }
    ch.mouseMoveAbsolute(1f, 0f, 0)
    val pkt = frames.last()
    assertEquals(0x57.toByte(), pkt[0])
    assertEquals(0xAB.toByte(), pkt[1])
    assertEquals(0x04.toByte(), pkt[3]) // absolute cmd
}

@Test
fun releaseAllKeysPacket() {
    val frames = mutableListOf<ByteArray>()
    Ch9329(true) { frames += it }.releaseAllKeys()
    assertEquals(14, frames.single().size)
    assertEquals(0x0C.toByte(), frames.single().last())
}
```

- [ ] **Step 2: 跑测失败 → 实现 → 通过**

- [ ] **Step 3: Commit**

```bash
git commit -am "android: 移植 CH9329 键鼠封包（可单测）"
```

---

### Task 11: 串口会话 + 触摸/键盘输入接到 CH9329

**Files:**
- Create: `android/client/.../hid/SerialControl.kt`
- Create: `android/app/.../ui/input/TouchInputModifier.kt`
- Modify: `ControlScreen.kt`

**Interfaces:**
- Consumes: `KvmSession` serial 通道、`KvmConfig.serial`、`Ch9329`
- Produces:
  - `SerialControl`：连上后发 `{"op":"open","port":"...","baudRate":115200}`；等 `status.open==true`；Binary 下行喂 Ch9329 reader（若启用 ACK）；上行 Ch9329 write → `sendSerialBinary`
  - 若 `error.code=="busy"` → 向上抛/回调 `ControlBusy`，UI 提供「接管」→ 重连 `?takeover=1`
  - 触摸：在视频 content 矩形内把坐标归一化 0..1（letterbox）；绝对模式 move/down/up；相对模式用位移
  - 软键盘：`KeyEvent` → `KeyA` 等映射；工具条按钮调 `releaseAllKeys` / `ctrlAltDelete`

- [ ] **Step 1: 实现 `SerialControl.open(takeover: Boolean)` 状态机**

- [ ] **Step 2: ControlScreen 叠加触摸；根据 config `mouseMode` 选绝对/相对（工具条可本地切换，默认跟 config）

- [ ] **Step 3: 联调：点击被控桌面图标；接管流程走通

- [ ] **Step 4: Commit**

```bash
git commit -am "android: 串口控制权与触摸键鼠映射"
```

---

### Task 12: 粘贴、工具条、断线重连

**Files:**
- Create: `android/client/.../hid/PasteTyper.kt`
- Create: `android/client/.../net/ReconnectPolicy.kt`
- Modify: `ControlScreen.kt`、`KvmSession.kt`

**Interfaces:**
- Consumes: `Ch9329`、剪贴板
- Produces:
  - `PasteTyper`：按字符/按键序列发送，默认间隔对齐 web（若 web 为 ~30–50ms/键，常量写死并注释来源文件行）
  - `ReconnectPolicy`：指数退避，初始 500ms，上限 10_000ms；重连成功后**不**自动 takeover；UI 角标「重连中」
  - 工具条：静音、松开所有键、CAD、粘贴、鼠标模式、断开、进设置

- [ ] **Step 1: `PasteTyper` 单测（收集写入的键序列长度/顺序，对 ASCII 样本）**

- [ ] **Step 2: 实现重连；断 Wi-Fi 再恢复，画面与串口可恢复；键卡住可一键释放

- [ ] **Step 3: Commit**

```bash
git commit -am "android: 粘贴、工具条与 WebSocket 重连"
```

---

### Task 13: 基础设置页

**Files:**
- Create: `android/app/.../ui/SettingsScreen.kt`
- Modify: 导航

**Interfaces:**
- Consumes: `KvmHttpClient.getConfig/putConfig/getDevicesJson`
- Produces: 可编辑 video（device 文本或下拉、width/height/fps、codec、bitrateKbps）、audio（enabled、narrowband）、serial（port、baudRate、mouseMode）；保存 PUT；返回控制页后视频/音频应按服务端热切换（客户端跟新 codec 头）

设备列表 JSON 结构以 `/api/devices` 实返回为准（解析用 `ignoreUnknownKeys`）。

- [ ] **Step 1: 设置 UI + 保存**

- [ ] **Step 2: 联调改 codec / 窄带，控制页自动适应

- [ ] **Step 3: Commit**

```bash
git commit -am "android: 基础设置页读写 /api/config"
```

---

### Task 14: 一期验收对照与 README

**Files:**
- Modify: `android/README.md`
- Create: `android/ACCEPTANCE.md`（勾选清单，复制设计文档一期 7 条）

**Interfaces:**
- Consumes: 无
- Produces: 文档化手工验收步骤与已知限制（无录制、无 mDNS 等）

- [ ] **Step 1: 按设计文档一期验收清单 1–7 逐项手工打勾，失败项修到过**

- [ ] **Step 2: 更新 README（构建、安装、连 Windows 宿主机、TOFU 说明）**

- [ ] **Step 3: Commit**

```bash
git commit -am "android: 一期验收说明与 README"
```

---

### Task 15（收尾，非宿主机实现）: host 模块保持空 + 计划回指

**Files:**
- Modify: `android/host/.../HostPlaceholder.kt`（注释指向二期计划将新增路径）
- Modify: `docs/superpowers/specs/2026-09-15-android-port-design.md`（可选：加一句「一期计划见 plans/…」——仅当需要交叉链接）

- [ ] **Step 1: 确认 `host` 未被 `app` 依赖，避免误用**

- [ ] **Step 2: Commit（若有文档交叉链接）**

```bash
git commit -am "android: 标明宿主机二期另案，host 模块仅占位"
```

---

## Spec Coverage（自检）

| 规格要求 | Task |
| --- | --- |
| 原生 Kotlin 客户端 | 1–14 |
| 手动地址 + Recent | 6 |
| TOFU 证书 | 4–6 |
| MJPEG + H.264 | 7–8 |
| 音频 + 静音 | 9 |
| 绝对/相对鼠标、键盘、CAD、全释放 | 10–12 |
| 粘贴 | 12 |
| 串口 busy / takeover | 11 |
| 基础设置 /api/config | 13 |
| 断线重连、不自动 takeover | 12 |
| 同形协议、不改 Rust 主路径 | 全局 |
| 二期宿主机实现 | **不在本计划**（另开计划） |
| 空 host 占位 | 1、15 |

## Placeholder / 一致性自检

- 无 TBD；CH9329 ACK 若阻塞联调，在 Task 10 内补齐而非外挂「以后再说」。
- 路径常量统一 `ProtocolPaths`；config 字段名与 Rust camelCase 一致。
- `KvmSession` / `SerialControl` 命名在 Task 5/11 前后一致。
