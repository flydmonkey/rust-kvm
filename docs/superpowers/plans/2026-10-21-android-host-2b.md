# Android 宿主机 2b：UVC 采集卡 MJPEG 实采转发

2026-10-21。设计见 [`2026-09-15-android-port-design.md`](../specs/2026-09-15-android-port-design.md) 二期 2b：「UVC MJPEG 实采转发 + 设备枚举/配置」。2a 见 [`2026-10-21-android-host-2a.md`](2026-10-21-android-host-2a.md)。

## 范围

| 端点 | 2b 行为（对照 `src/main.rs` / `src/video.rs`） |
| --- | --- |
| `/ws/video` | 先发 `{"op":"codec","codec":"mjpeg"}`；之后每条 Binary 一帧采集卡直出的 JPEG；逐字节相同的帧不发，静止时每 2 秒 `{"op":"idle","skipped":N}`；改配置重发头不断开。配置要 h264 时头里带 `fallbackReason` |
| `/api/devices` | `video.items` 为已授权的 UVC 设备（`index` = USB 设备路径，`name` = 产品名）；没有时给出原因 |
| `/api/video/stats` | 真实的 capturing / viewers / fps / mbps / sentMbps / frames / lastFrameBytes / resolution / error |
| `PUT /api/config` | 视频部分变了就按新配置重开采集；没变不动 |

不做：H.264（2d）、音频（2c）、热插拔通知（2c）。

## 为什么自己写 UVC

- Camera2 的外接摄像头支持取决于厂商是否打开 external camera HAL，多数手机没有。
- 现成的 UVCCamera 系库要么停更、要么只在 JitPack，且把帧转成 YUV/RGB 回调，而这里要的是采集卡直出的 MJPEG 原样转发。
- Java USB API 没有等时传输，采集卡（MS2109 等）的视频端点又多是等时的。所以只把等时 URB 的提交与收割放进 JNI（`usb_iso.c`，直接对 `UsbDeviceConnection` 的 fd 发 usbdevfs ioctl，libusb 在 Linux 上也是这么做的），其余全部是 Kotlin，可在 JVM 上单测。

## 模块

| 文件 | 职责 |
| --- | --- |
| `video/VideoSource.kt` | `VideoDevices` / `CaptureStream` 接口，采集后端可替换 |
| `video/VideoHub.kt` | 按需启停（无观看者 3s 后关）、改配置重开、出错 1s 后重试、每观看者只留最新 2 帧、统计 |
| `video/VideoStream.kt` | `/ws/video` 推流：头、去重、idle 通知、配置变更重发头 |
| `uvc/UvcDescriptors.kt` | 解析原始描述符：VC 头版本、VS 接口、MJPEG 格式/帧/帧间隔、各 alt setting 端点（含高带宽与 USB 3 companion） |
| `uvc/UvcNegotiation.kt` | probe/commit 结构（26/34/48 字节）、按配置选分辨率与帧间隔、选能装下负载的最小 alt |
| `uvc/UvcFrameAssembler.kt` | 负载头解析，按 EOF 或 FID 翻转切帧，ERR/超长/非 JPEG 整帧丢弃 |
| `uvc/UvcCaptureStream.kt` | 拉负载直到出帧，3 秒不出帧报错 |
| `uvc/UsbPayloadReaders.kt` | 等时（JNI，8 个 URB × 32 包）与 bulk 两种读取器 |
| `uvc/UvcCamera.kt` | 打开流程：claim → alt 0 → probe/commit（失败再用 26 字节重试）→ 切 alt → 读取 |
| `usb/UvcVideoDevices.kt` | 枚举 UVC 设备、授权、按 `settings.device` 选设备 |
| `cpp/usb_iso.c` | `start` / `reap` / `stop` |

权限：Android 10 起给视频类 USB 设备授权前必须已有 CAMERA 运行时权限，否则系统静默拒绝；宿主机页面点采集卡的「授权」时先申请相机权限。

## JVM 测试

- `UvcDescriptorsTest`：MS2109 形状的合成描述符（YUY2 + MJPEG、两个等时 alt、音频接口干扰）；非 UVC 设备；USB 3 companion；分辨率/帧间隔选择；alt 选择；probe 编解码。
- `UvcFrameAssemblerTest`：EOF 切帧、FID 翻转切帧、ERR 整帧丢、非 JPEG 与超长丢弃、畸形负载；`UvcCaptureStream` 拉取与超时。
- `VideoHubTest`：按需开、宽限期后关、宽限期内重连不重开、改配置重开、相同配置不重开、打开失败报告并重试、慢观看者不拖累别人、统计、关闭。
- `VideoStreamTest`：头先于帧、重复帧跳过并发 idle、h264 回落原因、保存配置重发头、stats 反映观看者、采集错误进 stats。

## 真机验收（见 `android/ACCEPTANCE.md`）

1. 采集卡授权（先相机权限、再 USB 授权）。
2. 浏览器与 Android 客户端都能看到画面；静止画面下 `/api/video/stats` 的 `sentMbps` 接近 0。
3. 设置页改分辨率/fps 后画面按新格式恢复，不断开。
4. 所有客户端断开约 3 秒后采集停止（stats `capturing=false`）。
5. 拔采集卡 → stats 报错；插回并授权后自动恢复。
