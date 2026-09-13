# 低带宽模式：H.264 走现有 WebSocket

2026-09-13

## 目标

让公网远程（经 VPN）可用。当前 1080p30 MJPEG 实测 16–45 Mbps，几 Mbps 的链路根本
跑不动。目标是同画质压到 2–5 Mbps，且**基本不动的桌面接近零码率**——那是 KVM 最
常见的画面，也是 MJPEG 最吃亏的地方（每帧都付全价）。

成功判据（都用现有读数量，不另造工具）：

- 静止桌面码率降到 MJPEG 的 1/10 以下 —— 看连接信息叠加层里的 Mbps
- 端到端比 MJPEG 增加不超过 30ms —— 看叠加层里现成的「到帧→绘制」，两种编码各测
  一次做差
- 文字可读性不低于当前 MJPEG（低码率下 JPEG 对锐利边缘很差，H.264 应当更好）

## 非目标

- **不做拥塞控制**。做对很难，做错（来回振荡）比不做更糟。先给固定码率档。
- **不上 WebRTC**。见「为什么不是 WebRTC」。
- **不做鉴权**。访问路径是 VPN，认证由那一层负责，和本设计无关。

## 已探明的前提

| 事实 | 影响 |
| --- | --- |
| `AMDh264Encoder` MFT 已注册 | 编码可行 |
| **Microsoft H.264 软件编码器未注册** | 硬件编码器是唯一路径，没有软件兜底 |
| `AMD MFT MJPEG Decoder` 已注册 | 解码有硬件路可选（但本设计不用，见下） |
| 采集卡 MS2109 只有 MJPEG 可用（1080p YUY2 仅 5fps） | 必须解码后才能编码，绕不开 |
| GPU：AMD Radeon(TM) Graphics，驱动 31.0.21925 | VCN 版本足够新 |

第二条是最大的风险：编码器开不起来就没有 B 计划，所以实施第一步是探针（见
「分步实施」）。

## 为什么不是 WebRTC

计划里原本写的是 webrtc-rs。定下走 VPN 之后，WebRTC 的两块主要价值都不成立了：

- **NAT 穿透不需要**。VPN 内部主机地址可直连，不用 STUN/TURN。
- **丢包恢复不需要**。走 TCP 时字节要么到要么连接断，不存在损坏的码流，所以
  NACK/PLI/FEC 一行都不用写。

剩下的拥塞控制（GCC）确实是它的长处，但我们本来就决定 v1 不做自适应。而代价很实：
浏览器那头要换成 `<video srcObject>`，于是**现在基于 canvas 的录制、逐帧统计、
「到帧→绘制」读数全部作废**；WebRTC 的抖动缓冲又是拿延迟换平滑，对 KVM 恰是要省的
东西；再加上 SDP/ICE/DTLS/SRTP 一整套新表面。

十倍的带宽收益来自 H.264 本身，不来自 WebRTC。

TCP 的代价要说清楚：丢包时是**队头阻塞造成的延迟尖峰**（一个 RTT），不是花屏。
链路差到尖峰无法忍受时，正确的下一步才是 WebRTC，而不是现在。

## 架构

编码任务是 JPEG 广播的一个**普通订阅者**，并排挂在现有管线旁边：

```
采集 ──→ JPEG broadcast ──┬──→ /ws/video（codec=mjpeg 时直接发）
                          │
                          └──→ 编码任务 ──→ H.264 broadcast ──→ /ws/video（codec=h264 时）
                               (按需启停)
```

编码任务的生命周期和采集本身同一套规矩：有人看才开，最后一个走掉就停。直接复用
`VideoHub` 里已有的 `supervise` + `wake` + `IDLE_GRACE` 结构。

**编码是全局配置，不是每客户端选**。`config.json` 加 `video.codec`，设置页一个开关。
（曾考虑按客户端选——局域网那台走 MJPEG 拿最低延迟、VPN 那台走 H.264——但两条流
同时开着要付两份代价，而实际用法是「这段时间我在外面」，全局切一下就够。）

## 服务端

### 解码：用 `zune-jpeg`，不用 AMD 的 MFT

理由是少一半 unsafe COM 表面，并且换台机器也能跑（AMD 那个 CLSID 是厂商专属）。
1080p30 约占一成 CPU，这机器付得起。

让解码器**直出 YCbCr 平面**，不要走 RGB 往返。采集卡的 MJPEG 通常是 4:2:2，转 NV12
（4:2:0）只需把相邻两行色度取平均，再把 U/V 交织成 NV12 的第二平面。

若 `zune-jpeg` 不支持直出 YCbCr，退回 RGB→NV12（多一次转换，CPU 再涨约一成）。
这一条由探针阶段确认，不留悬念。

### 编码器：硬件 MFT，异步

硬件 MFT 是异步的，要通过 `IMFMediaEventGenerator` 处理
`METransformNeedInput` / `METransformHaveOutput`。这是本设计最大的一块实现复杂度。
对外包成一个同步接口：`encode(nv12_frame) -> Vec<EncodedFrame>`，把异步细节关在里面。

**延迟三处必须设对**，漏一个这功能就白做：

1. `CODECAPI_AVLowLatencyMode = true`。不开的话编码器先攒几帧再吐第一帧，凭空多
   50–100ms。
2. **B 帧 = 0**（`AVEncMPVDefaultBPictureCount`）。B 帧要等后续帧才能编，是结构性
   延迟，对 KVM 纯亏。
3. 码率控制用 **CBR + 小 VBV**（`AVEncCommonRateControlMode` = CBR）。质量优先的 VBR
   在画面一变时会瞬间冲到几十 Mbps，正好把 WAN 预算炸掉。

其余：Main profile；`AVEncMPVGOPSize` 设成 300（10 秒 @30fps），靠强制 IDR 处理新人
进场而不是靠短 GOP——短 GOP 是拿常态码率换偶发事件，不值。

### 关键帧策略

新观看者必须先拿到一个 IDR，否则解码器什么也画不出来。所以**有新订阅者就强制一个
IDR**（`CODECAPI_AVEncVideoForceKeyFrame`）。多人同时进场要去重，一次 IDR 服务所有人。

### 码流格式与 codec 字符串

发 Annex-B，每个 IDR 前重复 SPS/PPS——这样客户端 `VideoDecoder.configure` 不需要
`description`，中途重连也能自己接上。

codec 字符串（`avc1.XXXXXX`）**从 SPS 的 profile_idc / constraint_flags / level_idc
三个字节直接拼出来**，不是写死。写死的话编码器实际协商出别的 profile 就会静默解码
失败，而那种失败极难查。

### 带宽不够时

现有 broadcast 的 `Lagged` 语义就是「丢最旧的帧」，正好是想要的降级：丢帧而不是堆
延迟。不额外做别的。

### 编码器起不来怎么办

回落到 MJPEG，并把原因带给客户端：头里写
`{"codec":"mjpeg","fallbackReason":"..."}`，页面在连接信息叠加层里显示。和项目里
处理设备错误的做法一致——**说出原因，而不是静默降级**。

编码器中途死掉：走 `supervise` 同一条退避路径（记错误、等一秒、重试），不静默。

## 协议

`/ws/video` 连上后先发一条 Text 头，随后每条 Binary 是一帧：

```json
{"codec": "h264", "avcCodec": "avc1.4D401E", "width": 1920, "height": 1080}
{"codec": "mjpeg"}
```

和 `/ws/audio` 先发格式头是同一个形状，客户端的处理结构可以照搬。

配置改了就**重发一条头**并紧跟一个 IDR，不断开连接。客户端任何时候收到 Text 都当
作「重新配置解码器」。

## 客户端

`video-worker.js` 里按头选两条路：`createImageBitmap`（MJPEG）或 `VideoDecoder`
（H.264）。往下都是同一块 canvas，所以**统计读数、录制、全屏、叠加层全部不动**。

`VideoDecoder` 配置带 `optimizeForLatency: true`。

**最容易踩的坑**：WebCodecs 的 `VideoFrame` **必须手动 `close()`**。它持有 GPU 内存，
不 close 会在几秒内耗尽并卡死整个页面。现有代码是 bitmap 转移给主线程，`VideoFrame`
同样可以转移，但画完必须 close——这一条要写在代码注释里，因为看代码时它一点都不
显眼。

## 窄带音频（顺带，几乎免费）

音频裸 PCM 固定 1.5 Mbps，在 5 Mbps 预算里占三成。不引入编解码器也有 4 倍可省：
**单声道 + 24 kHz**。采集卡那路音频是被控机的系统声，单声道完全够用。

设置页一个「窄带音频」开关。服务端在发布前降混降采样，客户端照头里的采样率和声道
数建 `AudioContext`——那套路径本来就是按头走的，不用改。

不上 Opus：要引 libopus（C 库），和「单原生 exe、不依赖 ffmpeg」的取向冲突。

## 分步实施

1. **探针**（`src/bin/probe-h264.rs`，沿用现有 `probe.rs` / `probe-audio.rs` 的惯例）：
   枚举编码器 MFT、创建 `AMDh264Encoder`、喂若干合成 NV12 帧，报告 SPS/PPS、实际码率、
   单帧编码耗时。**顺带确认 `zune-jpeg` 能否直出 YCbCr 平面。**
   这一步不通，整条路不用往下走。
2. JPEG → NV12 转换。验证方式是把一帧 NV12 落盘肉眼看，别指望靠码流反推。
3. 编码器封装（同步接口包住异步 MFT）+ 按需启停的编码任务。
4. 协议头 + 客户端 `VideoDecoder`。
5. 设置页：编码开关 + 码率档（1 / 2 / 4 / 8 Mbps，默认 4）。
6. 窄带音频开关。

## 验证

这项东西没法靠单元测试说「对了」——画面对不对、延迟够不够，只有真跑起来看。所以
验证点定在三处，每处都有明确的观察对象：

| 验证什么 | 怎么看 |
| --- | --- |
| 编码器能用 | 探针输出 SPS/PPS 和单帧耗时 |
| 转换对不对 | 一帧 NV12 落盘肉眼看（别靠码流反推） |
| 码率与延迟 | 叠加层的 Mbps 和「到帧→绘制」，MJPEG / H.264 各一次做差 |
| 静止画面的收益 | 桌面不动时盯 Mbps，应当趋近于零 |
| `VideoFrame` 没泄漏 | 连看五分钟，盯页面内存曲线 |
| 切编码不断流 | 设置页改编码后不刷新页面，画面应当继续 |

## 风险

1. **硬件编码器是唯一路径**，系统里没有软件兜底。AMD 的 MFT 在系统内存输入上的格式
   协商向来挑。由第 1 步拦住。
2. **异步 MFT 的事件循环**是这里唯一一块真正棘手的 Windows API 代码。包成同步接口
   以限制它的传播范围。
3. **`VideoFrame` 泄漏**会让页面在几秒内卡死，而且现象像是「解码器不行」。要在实施
   时专门盯一次五分钟内存曲线。
