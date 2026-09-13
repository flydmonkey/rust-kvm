# 低带宽模式（H.264）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 rust-kvm 加一个可切换的 H.264 传输后端，让公网远程（经 VPN）在几 Mbps 的
链路上可用，静止桌面码率降到 MJPEG 的 1/10 以下。

**Architecture:** 编码任务作为现有 JPEG broadcast 的一个普通订阅者并排挂上，
`采集 → JPEG broadcast → 编码任务 → H.264 broadcast`。`/ws/video` 按全局配置选择发
JPEG 还是 H.264，连接建立时先发一条 Text 头说明编码。浏览器 worker 里按头选
`createImageBitmap` 或 `VideoDecoder`，往下仍是同一块 canvas，所以统计、录制、全屏
全部不动。

**Tech Stack:** Rust 2024；`zune-jpeg` 解 JPEG；Windows Media Foundation（`windows`
crate）驱动 `AMDh264Encoder` 硬件编码；浏览器端 WebCodecs `VideoDecoder`。

**设计文档：** `docs/superpowers/specs/2026-09-13-h264-low-bandwidth-design.md`

## Global Constraints

- **单个原生 exe，不依赖 ffmpeg**。不引入任何需要外部 DLL 或 C 工具链的编解码库。
- **不改 `chrome-kvm` 目录**，它只作参考。
- 配置字段一律 camelCase：同一个结构既是 `config.json` 也是 `/api/config` 的 JSON。
- 注释和提交信息用中文，说清「为什么」，不重复代码本身在说的事。
- **不做拥塞控制**，不做码率自适应。固定码率档。
- **不上 WebRTC**（理由见设计文档）。
- Windows 相关代码一律放在 `#[cfg(windows)]` 后面，非 Windows 目标退回「编码器不可用」
  并回落 MJPEG。
- 每次 cargo 命令前必须设 `$env:CARGO_TARGET_DIR = (Join-Path (Get-Location).Path "target")`。
- **服务在跑时 exe 被锁**，`cargo build` 会报「拒绝访问 (os error 5)」。先
  `.\target\release\rust-kvm.exe stop`，构建完再 `start`。
- 已探明：`AMDh264Encoder` 在位，**Microsoft H.264 软件编码器未注册**——硬件编码器
  是唯一路径，没有软件兜底。

---

## File Structure

**新建：**

| 文件 | 职责 |
| --- | --- |
| `src/nv12.rs` | 交织 YCbCr 4:4:4 → NV12。纯计算，无平台依赖，**可单元测试** |
| `src/h264.rs` | H.264 编码器。把异步 MFT 包成同步接口，Windows-only |
| `src/bin/probe-h264.rs` | 探针：枚举编码器 MFT、喂合成帧、报 SPS/PPS 和耗时 |

**修改：**

| 文件 | 改什么 |
| --- | --- |
| `Cargo.toml` | 加 `zune-jpeg`、`zune-core`、`windows`；注册探针 bin |
| `src/config.rs` | `VideoSettings` 加 `codec`、`bitrateKbps`；`AudioSettings` 加 `narrowband` |
| `src/video.rs` | 编码任务、H.264 broadcast、`Lagged` 时强制 IDR |
| `src/main.rs` | 模块声明；`/ws/video` 发编码头；配置透传 |
| `src/audio.rs` | 窄带（单声道 24kHz）降混降采样 |
| `web/video-worker.js` | 按头选解码路径；H.264 路径不丢帧 |
| `web/viewer.js` | 编码与 `decodeQueueSize` 进叠加层 |
| `web/settings.html` | 编码开关、码率档、窄带音频开关 |
| `web/settings-page.js` | 上面三项的读写 |

`src/video.rs` 目前 257 行，加进编码任务后约 400 行，仍在可控范围，不拆。
编码器本身单独成文件是因为它全是 unsafe COM 代码，和其余部分的性质完全不同。

---

## Task 1: 探针——确认编码器能用

**这一步不通，整条路不用往下走。** 系统里没有软件编码器，所以没有 B 计划。

**Files:**
- Create: `src/bin/probe-h264.rs`
- Modify: `Cargo.toml`

**Interfaces:**
- Consumes: 无
- Produces: 无（探针是独立诊断工具）。但它验证的事实决定 Task 3 怎么写：
  编码器 CLSID/名称、是否异步、能否吃系统内存 NV12、单帧耗时。

- [ ] **Step 1: 加依赖**

`Cargo.toml` 的 `[target.'cfg(windows)'.dependencies]` 段里加：

```toml
# Media Foundation 要用 COM 接口。windows-sys 是裸 vtable，做 COM 太痛苦，
# 所以这里用带包装的 windows crate。
windows = { version = "0.62.2", features = [
    "Win32_Media_MediaFoundation",
    "Win32_System_Com",
] }
```

`[dependencies]` 段里加：

```toml
# 解 MJPEG。纯 Rust，不引外部 DLL；直出 YCbCr 可以省掉色彩矩阵那一步。
zune-jpeg = "0.5.15"
zune-core = "0.5.3"
```

`[package]` 之后加：

```toml
[[bin]]
name = "probe-h264"
path = "src/bin/probe-h264.rs"
```

- [ ] **Step 2: 写探针**

`src/bin/probe-h264.rs`：

```rust
//! 探针：确认这台机器上的 H.264 编码 MFT 能不能用。
//!
//! 存在的理由：系统里没有注册 Microsoft 的 H.264 软件编码器，硬件编码器是唯一
//! 的路。整个低带宽模式都压在「它能开、能吃系统内存的 NV12、能吐出 SPS/PPS」
//! 这三件事上，所以先用五十行确认，而不是写完一整条管线再发现前提不成立。
//!
//! 跑：cargo run --bin probe-h264

use anyhow::{Context, Result, bail};

#[cfg(not(windows))]
fn main() -> Result<()> {
    bail!("这个探针只在 Windows 上有意义");
}

#[cfg(windows)]
fn main() -> Result<()> {
    windows_probe::run()
}

#[cfg(windows)]
mod windows_probe {
    use super::*;
    use std::time::Instant;
    use windows::Win32::Media::MediaFoundation::*;
    use windows::Win32::System::Com::*;
    use windows::core::{GUID, Interface};

    const WIDTH: u32 = 1920;
    const HEIGHT: u32 = 1080;
    const FPS: u32 = 30;
    const BITRATE: u32 = 4_000_000;
    const FRAMES: usize = 60;

    pub fn run() -> Result<()> {
        unsafe {
            CoInitializeEx(None, COINIT_MULTITHREADED).ok().context("CoInitializeEx")?;
            MFStartup(MF_VERSION, MFSTARTUP_FULL).context("MFStartup")?;
        }

        // 1. 枚举：看系统到底给出哪些 H.264 编码器，硬件的排在前面
        let encoders = unsafe { enumerate()? };
        println!("找到 {} 个 H.264 编码器 MFT：", encoders.len());
        for (i, name) in encoders.iter().enumerate() {
            println!("  [{i}] {name}");
        }
        if encoders.is_empty() {
            bail!("一个都没有。低带宽模式在这台机器上没有出路。");
        }

        // 2. 创建 + 配置 + 喂帧
        unsafe { encode_probe()? };

        unsafe {
            let _ = MFShutdown();
            CoUninitialize();
        }
        Ok(())
    }

    unsafe fn enumerate() -> Result<Vec<String>> {
        let output = MFT_REGISTER_TYPE_INFO {
            guidMajorType: MFMediaType_Video,
            guidSubtype: MFVideoFormat_H264,
        };
        // SORTANDFILTER 让硬件的排前面。同时要 HARDWARE 和 SYNCMFT/ASYNCMFT：
        // 只要硬件的话，万一驱动没注册就直接空手而归，而空手和「有但不能用」
        // 在诊断上是两回事。
        let mut activates: *mut Option<IMFActivate> = std::ptr::null_mut();
        let mut count = 0u32;
        unsafe {
            MFTEnumEx(
                MFT_CATEGORY_VIDEO_ENCODER,
                MFT_ENUM_FLAG_HARDWARE.0 | MFT_ENUM_FLAG_SYNCMFT.0 | MFT_ENUM_FLAG_ASYNCMFT.0
                    | MFT_ENUM_FLAG_SORTANDFILTER.0,
                None,
                Some(&output),
                &mut activates,
                &mut count,
            )
            .context("MFTEnumEx")?;
        }

        let mut names = Vec::new();
        for i in 0..count as usize {
            let activate = unsafe { (*activates.add(i)).clone() };
            if let Some(activate) = activate {
                let mut buffer = [0u16; 256];
                let mut len = 0u32;
                let ok = unsafe {
                    activate
                        .GetString(&MFT_FRIENDLY_NAME_Attribute, &mut buffer, Some(&mut len))
                        .is_ok()
                };
                names.push(if ok {
                    String::from_utf16_lossy(&buffer[..len as usize])
                } else {
                    "（无名）".to_string()
                });
            }
        }
        unsafe { CoTaskMemFree(Some(activates as *const _)) };
        Ok(names)
    }

    unsafe fn encode_probe() -> Result<()> {
        // CLSID_MSH264EncoderMFT 这类固定 CLSID 不用：直接 CoCreateInstance
        // 系统挑出来的第一个（MFTEnumEx 已按硬件优先排过序）。
        let transform: IMFTransform = unsafe { create_first_encoder()? };

        // 顺序要紧：编码器必须**先设输出类型再设输入类型**。反了的话
        // SetInputType 会以 MF_E_TRANSFORM_TYPE_NOT_SET 失败，而那个错误码
        // 完全看不出是顺序问题。
        unsafe { set_output_type(&transform)? };
        unsafe { set_input_type(&transform)? };
        unsafe { configure_low_latency(&transform)? };

        let nv12 = synthetic_nv12();
        let mut total = std::time::Duration::ZERO;
        let mut bytes = 0usize;
        let mut first_output: Option<Vec<u8>> = None;

        unsafe {
            transform.ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0).ok();
            transform.ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0).ok();
        }

        for i in 0..FRAMES {
            let started = Instant::now();
            unsafe { push_frame(&transform, &nv12, i as i64)? };
            while let Some(packet) = unsafe { pull_output(&transform)? } {
                bytes += packet.len();
                if first_output.is_none() {
                    first_output = Some(packet);
                }
            }
            total += started.elapsed();
        }

        let seconds = FRAMES as f64 / FPS as f64;
        println!();
        println!("编码 {FRAMES} 帧 {WIDTH}x{HEIGHT}：");
        println!("  单帧平均 {:.2} ms", total.as_secs_f64() * 1000.0 / FRAMES as f64);
        println!("  实际码率 {:.2} Mbps（目标 {:.1}）",
            bytes as f64 * 8.0 / seconds / 1e6, BITRATE as f64 / 1e6);

        match first_output {
            Some(packet) => {
                println!("  首包 {} 字节，前 16 字节：{:02x?}", packet.len(),
                    &packet[..packet.len().min(16)]);
                // Annex-B 起始码 00 00 00 01 后面紧跟 NAL 头；SPS 的类型是 7
                let has_sps = packet.windows(5).any(|w| {
                    w[..4] == [0, 0, 0, 1] && (w[4] & 0x1f) == 7
                });
                if has_sps {
                    println!("  含 SPS —— 前提成立，可以往下走");
                } else {
                    bail!("首包里没有 SPS。客户端配置不出解码器，这条路不通。");
                }
            }
            None => bail!("一帧都没吐出来"),
        }
        Ok(())
    }

    /// 一张有内容的假图：纯色的话编码器几乎不产码率，量不出真实开销。
    fn synthetic_nv12() -> Vec<u8> {
        let y_size = (WIDTH * HEIGHT) as usize;
        let mut buffer = vec![0u8; y_size * 3 / 2];
        for y in 0..HEIGHT as usize {
            for x in 0..WIDTH as usize {
                buffer[y * WIDTH as usize + x] = ((x * 7 + y * 13) % 256) as u8;
            }
        }
        for i in y_size..buffer.len() {
            buffer[i] = 128;
        }
        buffer
    }
}
```

以下四个 `unsafe fn` 的实现在 Task 3 会挪进 `src/h264.rs` 复用，探针里先各写一遍：

```rust
    unsafe fn create_first_encoder() -> Result<IMFTransform> {
        let output = MFT_REGISTER_TYPE_INFO {
            guidMajorType: MFMediaType_Video,
            guidSubtype: MFVideoFormat_H264,
        };
        let mut activates: *mut Option<IMFActivate> = std::ptr::null_mut();
        let mut count = 0u32;
        unsafe {
            MFTEnumEx(
                MFT_CATEGORY_VIDEO_ENCODER,
                MFT_ENUM_FLAG_HARDWARE.0 | MFT_ENUM_FLAG_SYNCMFT.0 | MFT_ENUM_FLAG_ASYNCMFT.0
                    | MFT_ENUM_FLAG_SORTANDFILTER.0,
                None,
                Some(&output),
                &mut activates,
                &mut count,
            )?;
        }
        if count == 0 {
            bail!("没有可用的 H.264 编码器 MFT");
        }
        let activate = unsafe { (*activates).clone() }.context("第一个 activate 是空的")?;
        let transform: IMFTransform = unsafe { activate.ActivateObject()? };
        unsafe { CoTaskMemFree(Some(activates as *const _)) };
        Ok(transform)
    }

    unsafe fn set_output_type(transform: &IMFTransform) -> Result<()> {
        let media_type = unsafe { MFCreateMediaType()? };
        unsafe {
            media_type.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Video)?;
            media_type.SetGUID(&MF_MT_SUBTYPE, &MFVideoFormat_H264)?;
            media_type.SetUINT32(&MF_MT_AVG_BITRATE, BITRATE)?;
            // FRAME_SIZE / FRAME_RATE 都是把两个 u32 打进一个 u64（高位在前）
            media_type.SetUINT64(&MF_MT_FRAME_SIZE, pack(WIDTH, HEIGHT))?;
            media_type.SetUINT64(&MF_MT_FRAME_RATE, pack(FPS, 1))?;
            media_type.SetUINT32(&MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive.0 as u32)?;
            // Main：比 Baseline 压得好，而 B 帧是另一个开关，关掉就行
            media_type.SetUINT32(&MF_MT_MPEG2_PROFILE, eAVEncH264VProfile_Main.0 as u32)?;
            transform.SetOutputType(0, &media_type, 0).context("SetOutputType")?;
        }
        Ok(())
    }

    unsafe fn set_input_type(transform: &IMFTransform) -> Result<()> {
        let media_type = unsafe { MFCreateMediaType()? };
        unsafe {
            media_type.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Video)?;
            media_type.SetGUID(&MF_MT_SUBTYPE, &MFVideoFormat_NV12)?;
            media_type.SetUINT64(&MF_MT_FRAME_SIZE, pack(WIDTH, HEIGHT))?;
            media_type.SetUINT64(&MF_MT_FRAME_RATE, pack(FPS, 1))?;
            media_type.SetUINT32(&MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive.0 as u32)?;
            transform.SetInputType(0, &media_type, 0).context("SetInputType")?;
        }
        Ok(())
    }

    fn pack(high: u32, low: u32) -> u64 {
        ((high as u64) << 32) | low as u64
    }

    /// 延迟三处必须设对，漏一个这功能就白做。详见设计文档。
    unsafe fn configure_low_latency(transform: &IMFTransform) -> Result<()> {
        let codec: ICodecAPI = transform.cast().context("拿不到 ICodecAPI")?;
        unsafe {
            // 1. 不开的话编码器先攒几帧再吐第一帧，凭空多 50-100ms
            set_bool(&codec, &CODECAPI_AVLowLatencyMode, true)?;
            // 2. B 帧要等后续帧才能编，是结构性延迟，对 KVM 纯亏
            set_u32(&codec, &CODECAPI_AVEncMPVDefaultBPictureCount, 0)?;
            // 3. 质量优先的 VBR 在画面一变时会冲到几十 Mbps，正好炸掉 WAN 预算
            set_u32(&codec, &CODECAPI_AVEncCommonRateControlMode,
                eAVEncCommonRateControlMode_CBR.0 as u32)?;
            set_u32(&codec, &CODECAPI_AVEncCommonMeanBitRate, BITRATE)?;
            // GOP 开大，新人进场靠强制 IDR 解决，不靠短 GOP——短 GOP 是拿常态
            // 码率换偶发事件，不值
            set_u32(&codec, &CODECAPI_AVEncMPVGOPSize, FPS * 10)?;
        }
        Ok(())
    }

    unsafe fn set_bool(codec: &ICodecAPI, key: &GUID, value: bool) -> Result<()> {
        let mut variant = windows::Win32::System::Variant::VARIANT::default();
        variant.Anonymous.Anonymous.vt = windows::Win32::System::Variant::VT_BOOL;
        variant.Anonymous.Anonymous.Anonymous.boolVal =
            windows::Win32::Foundation::VARIANT_BOOL(if value { -1 } else { 0 });
        unsafe { codec.SetValue(key, &variant) }.with_context(|| format!("设 {key:?} 失败"))?;
        Ok(())
    }

    unsafe fn set_u32(codec: &ICodecAPI, key: &GUID, value: u32) -> Result<()> {
        let mut variant = windows::Win32::System::Variant::VARIANT::default();
        variant.Anonymous.Anonymous.vt = windows::Win32::System::Variant::VT_UI4;
        variant.Anonymous.Anonymous.Anonymous.ulVal = value;
        unsafe { codec.SetValue(key, &variant) }.with_context(|| format!("设 {key:?} 失败"))?;
        Ok(())
    }

    unsafe fn push_frame(transform: &IMFTransform, nv12: &[u8], index: i64) -> Result<()> {
        let buffer = unsafe { MFCreateMemoryBuffer(nv12.len() as u32)? };
        unsafe {
            let mut target = std::ptr::null_mut();
            let mut max = 0u32;
            buffer.Lock(&mut target, Some(&mut max), None)?;
            std::ptr::copy_nonoverlapping(nv12.as_ptr(), target, nv12.len());
            buffer.Unlock()?;
            buffer.SetCurrentLength(nv12.len() as u32)?;

            let sample = MFCreateSample()?;
            sample.AddBuffer(&buffer)?;
            // 100ns 为单位
            let per_frame = 10_000_000i64 / FPS as i64;
            sample.SetSampleTime(index * per_frame)?;
            sample.SetSampleDuration(per_frame)?;
            transform.ProcessInput(0, &sample, 0).context("ProcessInput")?;
        }
        Ok(())
    }

    unsafe fn pull_output(transform: &IMFTransform) -> Result<Option<Vec<u8>>> {
        let info = unsafe { transform.GetOutputStreamInfo(0)? };
        // 编码器一般自己分配样本；不自己分配的话要我们给一块
        let provides = (info.dwFlags
            & (MFT_OUTPUT_STREAM_PROVIDES_SAMPLES.0 as u32
                | MFT_OUTPUT_STREAM_CAN_PROVIDE_SAMPLES.0 as u32))
            != 0;
        let sample = if provides {
            None
        } else {
            let buffer = unsafe { MFCreateMemoryBuffer(info.cbSize)? };
            let sample = unsafe { MFCreateSample()? };
            unsafe { sample.AddBuffer(&buffer)? };
            Some(sample)
        };

        let mut output = [MFT_OUTPUT_DATA_BUFFER {
            dwStreamID: 0,
            pSample: std::mem::ManuallyDrop::new(sample),
            dwStatus: 0,
            pEvents: std::mem::ManuallyDrop::new(None),
        }];
        let mut status = 0u32;
        let result = unsafe { transform.ProcessOutput(0, &mut output, &mut status) };
        if let Err(e) = result {
            // 还没攒够，不是错误
            if e.code() == MF_E_TRANSFORM_NEED_MORE_INPUT {
                return Ok(None);
            }
            return Err(e).context("ProcessOutput");
        }

        let sample = std::mem::ManuallyDrop::take(&mut output[0].pSample)
            .context("ProcessOutput 成功但没给样本")?;
        let buffer = unsafe { sample.ConvertToContiguousBuffer()? };
        let mut data = std::ptr::null_mut();
        let mut len = 0u32;
        unsafe { buffer.Lock(&mut data, None, Some(&mut len))? };
        let bytes = unsafe { std::slice::from_raw_parts(data, len as usize) }.to_vec();
        unsafe { buffer.Unlock()? };
        Ok(Some(bytes))
    }
```

- [ ] **Step 3: 跑探针**

```powershell
$env:CARGO_TARGET_DIR = (Join-Path (Get-Location).Path "target")
cargo run --bin probe-h264
```

期望输出形如：

```
找到 1 个 H.264 编码器 MFT：
  [0] AMDh264Encoder
编码 60 帧 1920x1080：
  单帧平均 3.50 ms
  实际码率 4.10 Mbps（目标 4.0）
  首包 xxxx 字节，前 16 字节：[00, 00, 00, 01, 67, ...]
  含 SPS —— 前提成立，可以往下走
```

**判定标准：**
- 必须打出「含 SPS」。打不出就**停下来报告**，不要继续 Task 2——设计文档里说好了
  这一步是拦路的。
- 单帧耗时应当在 10ms 以内。超过 20ms 说明它其实走了软件路径，要在报告里说明。

若 `MFTEnumEx` 只在去掉 `MFT_ENUM_FLAG_HARDWARE` 后才有结果，记下来——那意味着
硬件编码器没注册成 MFT，Task 3 的枚举标志要跟着改。

- [ ] **Step 4: 提交**

```powershell
git add Cargo.toml Cargo.lock src/bin/probe-h264.rs
git commit -m "探针：确认 H.264 编码 MFT 可用

系统里没注册 Microsoft 的 H.264 软件编码器，硬件编码器是唯一的路。整个低带宽
模式都压在「能开、能吃系统内存 NV12、能吐 SPS」这三件事上，所以先用五十行
确认，而不是写完整条管线再发现前提不成立。"
```

---

## Task 2: YCbCr → NV12 转换

纯计算，没有平台依赖，是这个特性里**唯一真正适合单元测试**的部分。先写测试。

**Files:**
- Create: `src/nv12.rs`
- Modify: `src/main.rs`（加 `mod nv12;`）

**Interfaces:**
- Consumes: 无
- Produces:
  - `pub struct Nv12Frame { pub width: u32, pub height: u32, pub data: Vec<u8> }`
  - `pub fn from_interleaved_ycbcr(src: &[u8], width: u32, height: u32, out: &mut Nv12Frame) -> anyhow::Result<()>`
  - `pub fn decode_jpeg_to_nv12(jpeg: &[u8], out: &mut Nv12Frame) -> anyhow::Result<()>`

- [ ] **Step 1: 写失败的测试**

在 `src/nv12.rs` 末尾：

```rust
#[cfg(test)]
mod tests {
    use super::*;

    /// 造一张 2x2 的交织 YCbCr：Y 各不相同，色度四个角不同值，
    /// 这样能同时验「Y 逐像素照搬」和「色度按 2x2 取平均」。
    #[test]
    fn 亮度逐像素照搬色度按方块取平均() {
        // (Y, Cb, Cr) x 4，按行优先
        let src: Vec<u8> = vec![
            10, 100, 200,   20, 108, 208,
            30, 116, 216,   40, 124, 224,
        ];
        let mut out = Nv12Frame::new(2, 2);
        from_interleaved_ycbcr(&src, 2, 2, &mut out).unwrap();

        assert_eq!(&out.data[..4], &[10, 20, 30, 40], "Y 平面应当逐像素照搬");
        // Cb 平均 = (100+108+116+124)/4 = 112，Cr = (200+208+216+224)/4 = 212
        assert_eq!(&out.data[4..6], &[112, 212], "UV 应当是 2x2 均值且 U 在前");
    }

    /// 奇数尺寸会让 2x2 方块在右边/下边越界。KVM 的分辨率基本都是偶数，
    /// 但越界读是内存安全问题，不能靠「基本上」。
    #[test]
    fn 奇数尺寸不越界() {
        let src = vec![128u8; 3 * 3 * 3];
        let mut out = Nv12Frame::new(3, 3);
        from_interleaved_ycbcr(&src, 3, 3, &mut out).unwrap();
        assert_eq!(out.data.len(), 9 + 2 * 2 * 2, "UV 平面按向上取整的半尺寸算");
    }

    #[test]
    fn 输入太短要报错而不是崩() {
        let src = vec![0u8; 5];
        let mut out = Nv12Frame::new(2, 2);
        assert!(from_interleaved_ycbcr(&src, 2, 2, &mut out).is_err());
    }

    /// 复用同一个 Nv12Frame 换尺寸时缓冲区必须跟着重算。
    /// 不重算的话新尺寸会写进旧大小的缓冲里——要么越界要么留下上一帧的残留。
    #[test]
    fn 换尺寸时缓冲重算() {
        let mut out = Nv12Frame::new(2, 2);
        let src = vec![128u8; 4 * 4 * 3];
        from_interleaved_ycbcr(&src, 4, 4, &mut out).unwrap();
        assert_eq!(out.width, 4);
        assert_eq!(out.data.len(), 16 + 8);
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

```powershell
$env:CARGO_TARGET_DIR = (Join-Path (Get-Location).Path "target")
cargo test --lib nv12
```

期望：编译失败，`cannot find struct Nv12Frame` / `cannot find function from_interleaved_ycbcr`。

（`src/main.rs` 是 binary crate，`cargo test` 会连带编译它；只要 `mod nv12;` 声明了
就能测到。）

- [ ] **Step 3: 实现**

`src/nv12.rs` 开头：

```rust
//! JPEG → NV12。编码器要的是 NV12，采集卡给的是 MJPEG，中间这一步在这儿。
//!
//! 让 zune-jpeg 直出 YCbCr 而不是 RGB，省掉的是那个色彩矩阵——这是主要收益。
//! 但它给的是**交织的 4:4:4**（每像素 3 字节，色度已升采样），不是平面，所以
//! 转 NV12 仍要自己做：Y 取每第 3 个字节，UV 按 2x2 方块取平均再交织。
//!
//! 1080p 一帧读 6.2 MB、写 3.1 MB，30fps 约 280 MB/s。对 DDR 无所谓，但这也是
//! 为什么不值得再为「省一次拷贝」去折腾硬件解码 MFT 的零拷贝路径。

use anyhow::{Result, bail};
use zune_core::colorspace::ColorSpace;
use zune_core::options::DecoderOptions;
use zune_jpeg::JpegDecoder;

/// NV12：Y 平面在前，紧跟 U/V 交织的半尺寸平面。
///
/// 复用同一个实例是为了不在每帧都分配三兆——那会让分配器成为热点。
pub struct Nv12Frame {
    pub width: u32,
    pub height: u32,
    pub data: Vec<u8>,
    /// zune-jpeg 的输出缓冲，同样为了复用而留在这儿
    ycbcr: Vec<u8>,
}

impl Nv12Frame {
    pub fn new(width: u32, height: u32) -> Self {
        let mut frame = Self {
            width: 0,
            height: 0,
            data: Vec::new(),
            ycbcr: Vec::new(),
        };
        frame.resize(width, height);
        frame
    }

    fn resize(&mut self, width: u32, height: u32) {
        if self.width == width && self.height == height {
            return;
        }
        self.width = width;
        self.height = height;
        let y = (width * height) as usize;
        // 色度平面按向上取整的半尺寸，奇数分辨率也不越界
        let uv = (width.div_ceil(2) * height.div_ceil(2) * 2) as usize;
        self.data.clear();
        self.data.resize(y + uv, 0);
        self.ycbcr.clear();
        self.ycbcr.resize(y * 3, 0);
    }
}

/// 交织 YCbCr 4:4:4 → NV12。
pub fn from_interleaved_ycbcr(
    src: &[u8],
    width: u32,
    height: u32,
    out: &mut Nv12Frame,
) -> Result<()> {
    let (w, h) = (width as usize, height as usize);
    if src.len() < w * h * 3 {
        bail!("YCbCr 数据只有 {} 字节，{width}x{height} 需要 {}", src.len(), w * h * 3);
    }
    out.resize(width, height);

    let (y_plane, uv_plane) = out.data.split_at_mut(w * h);
    for i in 0..w * h {
        y_plane[i] = src[i * 3];
    }

    let half_w = width.div_ceil(2) as usize;
    for by in 0..height.div_ceil(2) as usize {
        for bx in 0..half_w {
            // 边界上的方块可能只有一两个像素，取实际存在的那些平均
            let mut cb = 0u32;
            let mut cr = 0u32;
            let mut n = 0u32;
            for dy in 0..2 {
                let y = by * 2 + dy;
                if y >= h {
                    continue;
                }
                for dx in 0..2 {
                    let x = bx * 2 + dx;
                    if x >= w {
                        continue;
                    }
                    let p = (y * w + x) * 3;
                    cb += src[p + 1] as u32;
                    cr += src[p + 2] as u32;
                    n += 1;
                }
            }
            let at = (by * half_w + bx) * 2;
            uv_plane[at] = (cb / n) as u8;
            uv_plane[at + 1] = (cr / n) as u8;
        }
    }
    Ok(())
}

/// 解一帧 MJPEG 并转成 NV12。
pub fn decode_jpeg_to_nv12(jpeg: &[u8], out: &mut Nv12Frame) -> Result<()> {
    let options = DecoderOptions::new_fast().jpeg_set_out_colorspace(ColorSpace::YCbCr);
    let mut decoder = JpegDecoder::new_with_options(jpeg, options);
    decoder.decode_headers().map_err(|e| anyhow::anyhow!("解 JPEG 头失败: {e:?}"))?;
    let (width, height) = decoder
        .dimensions()
        .ok_or_else(|| anyhow::anyhow!("JPEG 头里没有尺寸"))?;

    out.resize(width as u32, height as u32);
    // 拿出来单独借，免得和 out 的可变借用打架
    let mut ycbcr = std::mem::take(&mut out.ycbcr);
    let result = decoder.decode_into(&mut ycbcr);
    let outcome = match result {
        Ok(()) => from_interleaved_ycbcr(&ycbcr, width as u32, height as u32, out),
        Err(e) => Err(anyhow::anyhow!("解 JPEG 失败: {e:?}")),
    };
    out.ycbcr = ycbcr;
    outcome
}
```

- [ ] **Step 4: 跑测试确认通过**

```powershell
cargo test --lib nv12
```

期望：4 个测试全过。

若 `decode_into` 因为缓冲大小报错，用 `decoder.output_buffer_size()` 的返回值来
resize `ycbcr`（4:4:4 应当正好是 `w*h*3`，但以它自己报的为准更稳）。

- [ ] **Step 5: 用真实 JPEG 验一眼**

单元测试只能证明算术对，证明不了「接上真采集卡出来的图是对的」。所以落盘看一眼：

在 `src/bin/probe-h264.rs` 的 `main` 里，当 `--jpeg <路径>` 参数存在时，改为解这个
文件并把 NV12 的 Y 平面存成 PGM（灰度图，任何看图软件都能开）：

```rust
// PGM 而不是 PNG：五行就能写出来，不用引编码库。看的是几何有没有错位、
// 有没有半张图偏移——这些用灰度足够，而色度错了肉眼在灰度图上看不出来，
// 那一层靠单元测试覆盖。
fn dump_pgm(path: &str, y_plane: &[u8], width: u32, height: u32) -> Result<()> {
    use std::io::Write;
    let mut file = std::fs::File::create(path)?;
    write!(file, "P5\n{width} {height}\n255\n")?;
    file.write_all(&y_plane[..(width * height) as usize])?;
    Ok(())
}
```

先从浏览器存一帧当样本：控制页上右键画面另存，或者直接
`curl -k https://localhost:8443/api/video/stats` 旁边加个临时端点太啰嗦——更简单的
办法是在服务里已有的采集循环打个临时断点不现实，所以：用探针自己抓。给探针加
`--grab` 参数，直接用 `nokhwa` 开一次采集卡取一帧存成 `frame.jpg`，和服务端
`video.rs` 里 `pick_device` 的做法一致。

跑：

```powershell
.\target\release\rust-kvm.exe stop
cargo run --bin probe-h264 -- --grab
cargo run --bin probe-h264 -- --jpeg frame.jpg
```

然后打开生成的 `frame-y.pgm` 肉眼确认：画面正常、没有斜切错位、没有上下颠倒。

- [ ] **Step 6: 提交**

```powershell
git add src/nv12.rs src/main.rs src/bin/probe-h264.rs
git commit -m "JPEG → NV12 转换

zune-jpeg 直出 YCbCr 省掉色彩矩阵，但它给的是交织的 4:4:4 而不是平面，所以
转 NV12 仍要自己做：Y 取每第 3 个字节，UV 按 2x2 方块取平均。

边界上的方块按实际存在的像素取平均而不是假定四个都在——奇数分辨率在 KVM 上
少见，但越界读是内存安全问题，不能靠「少见」。

单元测试只能证明算术对，所以探针加了 --grab/--jpeg：抓一帧真图，把 Y 平面存成
PGM 肉眼看几何有没有错位。"
```

---

## Task 3: 编码器封装

把异步 MFT 包成同步接口，unsafe COM 代码全部关在这一个文件里。

**Files:**
- Create: `src/h264.rs`
- Modify: `src/main.rs`（加 `mod h264;`）

**Interfaces:**
- Consumes: `nv12::Nv12Frame`（Task 2）
- Produces:
  - `pub struct Encoder`
  - `pub struct EncoderConfig { pub width: u32, pub height: u32, pub fps: u32, pub bitrate_kbps: u32 }`
  - `pub struct EncodedFrame { pub data: bytes::Bytes, pub keyframe: bool }`
  - `pub fn Encoder::new(config: EncoderConfig) -> anyhow::Result<Encoder>`
  - `pub fn Encoder::encode(&mut self, frame: &Nv12Frame) -> anyhow::Result<Vec<EncodedFrame>>`
  - `pub fn Encoder::request_keyframe(&mut self)`
  - `pub fn Encoder::codec_string(&self) -> Option<String>` —— 形如 `avc1.4D401E`

- [ ] **Step 1: 写失败的测试**

MFT 要真硬件，跑在 CI 上没意义，所以只测「不碰硬件也该成立」的两件事：

```rust
#[cfg(test)]
mod tests {
    use super::*;

    /// codec 字符串必须从 SPS 的三个字节拼出来，不能写死——编码器实际协商出
    /// 别的 profile 时，写死的字符串会让浏览器静默解码失败，那种失败极难查。
    #[test]
    fn 从_sps_拼出_codec_字符串() {
        // Annex-B：起始码 + NAL 头(0x67 = SPS) + profile_idc, constraints, level_idc
        let packet = [0u8, 0, 0, 1, 0x67, 0x4D, 0x40, 0x1E, 0xAB, 0xCD];
        assert_eq!(codec_string_from_annexb(&packet).as_deref(), Some("avc1.4D401E"));
    }

    #[test]
    fn 没有_sps_时给不出_codec_字符串() {
        // 0x41 = 非 IDR 片，不是 SPS
        let packet = [0u8, 0, 0, 1, 0x41, 0x9A, 0x00];
        assert_eq!(codec_string_from_annexb(&packet), None);
    }

    /// 三字节起始码（00 00 01）也要认：编码器两种都可能吐。
    #[test]
    fn 认三字节起始码() {
        let packet = [0u8, 0, 1, 0x67, 0x42, 0xE0, 0x1E];
        assert_eq!(codec_string_from_annexb(&packet).as_deref(), Some("avc1.42E01E"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```powershell
cargo test --lib h264
```

期望：`cannot find function codec_string_from_annexb`。

- [ ] **Step 3: 实现 codec 字符串解析**

```rust
/// 从 Annex-B 码流里找到 SPS，拼出 WebCodecs 要的 `avc1.PPCCLL`。
///
/// 不写死：编码器实际协商出的 profile 可能和我们请求的不同，而写死会让浏览器
/// 静默解码失败——那种失败既没有错误也没有画面，极难查。三个字节就在 SPS 的
/// 最前面，取出来拼一下的事。
fn codec_string_from_annexb(packet: &[u8]) -> Option<String> {
    let mut i = 0;
    while i + 4 <= packet.len() {
        let header_len = if packet[i..].starts_with(&[0, 0, 0, 1]) {
            4
        } else if packet[i..].starts_with(&[0, 0, 1]) {
            3
        } else {
            i += 1;
            continue;
        };
        let nal = i + header_len;
        // 低 5 位是 nal_unit_type，7 = SPS
        if nal < packet.len() && (packet[nal] & 0x1f) == 7 && nal + 3 < packet.len() {
            return Some(format!(
                "avc1.{:02X}{:02X}{:02X}",
                packet[nal + 1],
                packet[nal + 2],
                packet[nal + 3]
            ));
        }
        i = nal;
    }
    None
}
```

- [ ] **Step 4: 跑测试确认通过**

```powershell
cargo test --lib h264
```

期望：3 个测试全过。

- [ ] **Step 5: 实现编码器本体**

把 Task 1 探针里那几个 `unsafe fn`（`create_first_encoder`、`set_output_type`、
`set_input_type`、`configure_low_latency`、`push_frame`、`pull_output`）搬进
`src/h264.rs`，参数从常量改成 `EncoderConfig`。文件头：

```rust
//! H.264 编码器。全部 unsafe COM 代码关在这一个文件里，对外只有一个同步接口。
//!
//! 为什么单独成文件：这里的性质和项目其余部分完全不同——别处是普通 Rust，
//! 这里每一行都要盯着 COM 的生命周期和 HRESULT。混在 video.rs 里的话，读那个
//! 文件的人得同时装着两套心智模型。
//!
//! 硬件 MFT 是异步的（通过 IMFMediaEventGenerator 报
//! METransformNeedInput / METransformHaveOutput），但对调用方没必要暴露这个：
//! 采集循环本来就是一帧一帧同步走的。所以 encode() 内部把事件循环跑完，
//! 返回这一帧产生的所有包。
//!
//! 非 Windows 目标上 Encoder::new 直接返回 Err，调用方回落 MJPEG。
```

关键实现要点（每一条都要写成代码，不是注释）：

1. **异步 MFT 必须先解锁**，否则 `ProcessInput` 会以 `MF_E_TRANSFORM_ASYNC_LOCKED`
   失败：

```rust
    // 异步 MFT 默认是锁着的，不设这个属性一上来就 MF_E_TRANSFORM_ASYNC_LOCKED，
    // 而那个错误码看不出「要先声明自己懂异步」这层意思。
    let attributes = unsafe { transform.GetAttributes()? };
    let is_async = unsafe { attributes.GetUINT32(&MF_TRANSFORM_ASYNC).unwrap_or(0) } == 1;
    if is_async {
        unsafe { attributes.SetUINT32(&MF_TRANSFORM_ASYNC_UNLOCK, 1)? };
    }
```

2. **异步时用事件驱动**，同步时用 `ProcessInput` + 循环 `ProcessOutput`：

```rust
    fn encode(&mut self, frame: &Nv12Frame) -> Result<Vec<EncodedFrame>> {
        if self.keyframe_requested {
            // 强制 IDR 是「下一帧」生效，所以在推帧之前设
            unsafe { self.force_keyframe()? };
            self.keyframe_requested = false;
        }
        if self.is_async {
            self.encode_async(frame)
        } else {
            self.encode_sync(frame)
        }
    }
```

`encode_async` 的循环：反复 `GetEvent(MF_EVENT_FLAG_NO_WAIT)`，
`METransformNeedInput` 就推这一帧（只推一次），`METransformHaveOutput` 就拉一个包；
帧推进去且没有更多 `HaveOutput` 时返回。**不能无限等**——编码器可能这一帧不产出
（攒着），所以拿不到事件就退出，包会在下一帧的循环里出来。

3. **keyframe 标记**：从 `MFSampleExtension_CleanPoint` 属性读，
   或退一步用「包里含 SPS」判断。两者都写，前者拿不到时用后者。

4. **输入类型要标满幅**（`MF_MT_VIDEO_NOMINAL_RANGE = MFNominalRange_0_255`）。
   Task 2 实测：采集卡彩条的 Y 跑到 255 和 7，不是 235/16。不标的话编码器按
   studio swing 解释、解码端再展开一次，表现是黑的更黑白的更白、颜色发飘，
   而这种毛病很难指着说出哪里不对。

5. **`Drop`**：`ProcessMessage(MFT_MESSAGE_NOTIFY_END_OF_STREAM)` +
   `MFT_MESSAGE_COMMAND_DRAIN`，然后 `MFShutdown` 由进程级别管，不在这里调
   ——多个编码器实例轮换时调了会把别人一起关掉。

6. **`MFStartup` 只能调一次**：用 `std::sync::Once` 包住。

7. **异步 MFT 只能由事件驱动**（Task 1 实测）：`ProcessOutput` 不等
   `METransformHaveOutput` 就调，会以 `E_UNEXPECTED`（0x8000FFFF）失败。事件用
   `MF_EVENT_FLAG_NO_WAIT` 轮询而不是阻塞式 `GetEvent`——事件不来时挂死比报错
   难查得多。探针里那个 `run_async` 直接搬过来即可。

- [ ] **Step 6: 用探针验证封装后的编码器**

把 Task 1 探针改成调用 `h264::Encoder`，确认重构后行为不变：

```powershell
cargo run --bin probe-h264
```

期望：输出与 Task 1 Step 3 一致（仍打出「含 SPS」，单帧耗时同量级）。
另外确认新增一行 codec 字符串，形如 `codec: avc1.4D401E`。

- [ ] **Step 7: 提交**

```powershell
git add src/h264.rs src/main.rs src/bin/probe-h264.rs
git commit -m "H.264 编码器封装：异步 MFT 包成同步接口

unsafe COM 全部关在一个文件里。硬件 MFT 是异步的（靠 METransformNeedInput /
METransformHaveOutput 事件驱动），但没必要把这个暴露给调用方——采集循环本来
就是一帧一帧同步走的。

两个不踩不知道的坑：异步 MFT 要先设 MF_TRANSFORM_ASYNC_UNLOCK，否则
ProcessInput 直接 MF_E_TRANSFORM_ASYNC_LOCKED；编码器必须先设输出类型再设
输入类型，反了会以 MF_E_TRANSFORM_TYPE_NOT_SET 失败，而那个错误码完全看不出
是顺序问题。

codec 字符串从 SPS 的三个字节拼，不写死：实际协商出的 profile 可能和请求的
不同，写死会让浏览器静默解码失败。"
```

---

## Task 4: 服务端管线接上

**Files:**
- Modify: `src/config.rs`、`src/video.rs`、`src/main.rs`

**Interfaces:**
- Consumes: `h264::{Encoder, EncoderConfig, EncodedFrame}`（Task 3）、
  `nv12::{Nv12Frame, decode_jpeg_to_nv12}`（Task 2）
- Produces:
  - `config::VideoSettings::codec: String`（`"mjpeg"` | `"h264"`）
  - `config::VideoSettings::bitrate_kbps: u32`
  - `video::VideoHub::subscribe_h264() -> broadcast::Receiver<EncodedFrame>`
  - `video::VideoHub::codec() -> VideoCodec`
  - `video::VideoHub::watch_codec() -> tokio::sync::watch::Receiver<VideoCodec>`
  - `video::VideoHub::request_keyframe()`
  - `pub enum VideoCodec { Mjpeg, H264 }`

- [ ] **Step 1: 配置加字段**

`src/config.rs` 的 `VideoSettings`：

```rust
    pub fps: u32,
    /// `"mjpeg"` 或 `"h264"`。h264 省带宽（静止画面能到十分之一），但多一层
    /// 编解码；局域网用 mjpeg 延迟最低。
    pub codec: String,
    /// 只对 h264 有意义。固定码率，不做自适应（理由见设计文档）。
    pub bitrate_kbps: u32,
```

`Default` 里：

```rust
            codec: "mjpeg".to_string(),
            bitrate_kbps: 4000,
```

默认仍是 mjpeg：日常是局域网，低带宽是出门时才开的。

- [ ] **Step 2: `VideoHub` 加 H.264 广播**

`src/video.rs`：

```rust
/// H.264 的广播容量比 JPEG 那个大得多（那边是 4）。两个原因：H.264 帧小得多，
/// 32 帧也才几百 KB；更重要的是这边 `Lagged` 的代价高——丢一个 P 帧会让后面
/// 整个 GOP 全烂，所以要尽量别发生。
const H264_CHANNEL_CAPACITY: usize = 32;
```

`VideoHub` 加字段：

```rust
    /// 编码后的帧。没有 h264 订阅者时编码任务根本不启动。
    encoded: broadcast::Sender<h264::EncodedFrame>,
    /// 当前编码。`/ws/video` 拿它决定发哪一路，改了要重发一条头。
    codec: watch::Sender<VideoCodec>,
    /// 下一帧强制 IDR。新订阅者进场、或者有人 `Lagged` 时置位。
    keyframe_wanted: AtomicBool,
```

编码任务（新函数，和 `supervise` 并列）：

```rust
/// 守着「有 h264 观看者就开编码」。结构和 `supervise` 一样，因为规矩一样：
/// 有人要才开，最后一个走掉就停。
///
/// 编码任务只是 JPEG 广播的一个普通订阅者——这样原来那条路一行都不用改，
/// 风险严格为零。
async fn supervise_encoder(hub: Arc<VideoHub>) {
    loop {
        hub.encode_wake.notified().await;
        if hub.encoded.receiver_count() == 0 {
            continue;
        }
        let worker = hub.clone();
        let outcome = tokio::task::spawn_blocking(move || encode_loop(&worker)).await;
        match outcome {
            Ok(Ok(())) => info!("编码已停止（没有 h264 观看者）"),
            Ok(Err(e)) => {
                warn!("编码出错: {e:#}");
                hub.record_encoder_error(format!("{e:#}"));
            }
            Err(e) => {
                warn!("编码线程异常退出: {e}");
                hub.record_encoder_error(format!("编码线程异常退出: {e}"));
            }
        }
        // 和采集一样：出错别立刻重试，否则编码器开不起来时会疯转
        if hub.encoded.receiver_count() > 0 {
            tokio::time::sleep(Duration::from_secs(1)).await;
            hub.encode_wake.notify_one();
        }
    }
}

fn encode_loop(hub: &VideoHub) -> Result<()> {
    let config = hub.config.lock().expect("config 锁").clone();
    let mut encoder = h264::Encoder::new(h264::EncoderConfig {
        width: config.width,
        height: config.height,
        fps: config.fps,
        bitrate_kbps: config.bitrate_kbps,
    })?;
    let mut frames = hub.frames.subscribe();
    let mut nv12 = nv12::Nv12Frame::new(config.width, config.height);
    // 先来一个 IDR：第一个订阅者不拿到关键帧就什么都画不出来
    encoder.request_keyframe();

    loop {
        if hub.encoded.receiver_count() == 0 {
            return Ok(());
        }
        if hub.reconfigured.load(Ordering::SeqCst) {
            return Ok(());
        }
        if hub.keyframe_wanted.swap(false, Ordering::SeqCst) {
            encoder.request_keyframe();
        }

        let jpeg = match frames.blocking_recv() {
            Ok(jpeg) => jpeg,
            // 采集那边跑太快，我们落后了。JPEG 丢几帧无所谓（每帧独立），
            // 但要补一个 IDR：我们给下游发的是有依赖的码流，中间断了就得重新起头。
            Err(broadcast::error::RecvError::Lagged(n)) => {
                warn!("编码落后 {n} 帧，补一个关键帧");
                encoder.request_keyframe();
                continue;
            }
            Err(broadcast::error::RecvError::Closed) => return Ok(()),
        };

        nv12::decode_jpeg_to_nv12(&jpeg, &mut nv12)?;
        for packet in encoder.encode(&nv12)? {
            hub.record_encoded(packet.data.len());
            let _ = hub.encoded.send(packet);
        }
        if let Some(codec) = encoder.codec_string() {
            hub.set_codec_string(codec);
        }
    }
}
```

- [ ] **Step 3: `/ws/video` 按编码分路**

`src/main.rs` 的 `ws_video` 处理函数改成：连上先发头，然后按编码选订阅哪一路，
并监听编码变更。

```rust
/// 连上先发一条 Text 头说明编码，之后每条 Binary 是一帧。
/// 和 `/ws/audio` 先发格式头是同一个形状。
///
/// 配置改了就重发一条头并紧跟一个关键帧，不断开连接——断开的话页面会闪一下，
/// 而这只是改了个设置。
async fn video_socket(socket: WebSocket, hub: Arc<VideoHub>) {
    let (mut sender, _) = socket.split();
    let mut codec_rx = hub.watch_codec();

    loop {
        let codec = *codec_rx.borrow_and_update();
        if send_codec_header(&mut sender, &hub, codec).await.is_err() {
            return;
        }
        // 换编码要重新起头，所以每次都重新订阅
        let pumped = match codec {
            VideoCodec::Mjpeg => pump_mjpeg(&mut sender, &hub, &mut codec_rx).await,
            VideoCodec::H264 => pump_h264(&mut sender, &hub, &mut codec_rx).await,
        };
        if pumped.is_err() {
            return;
        }
    }
}
```

`pump_h264` 里 `Lagged` 的处理是**这一步最要紧的地方**：

```rust
            Err(broadcast::error::RecvError::Lagged(n)) => {
                // H.264 不能像 JPEG 那样随便丢：丢一个 P 帧，后面整个 GOP 全烂，
                // 一直烂到下一个 IDR。所以请编码器补一个，并且在它到达之前把帧
                // 全部丢掉——解码器在 IDR 上能重新同步，代价是一次可见的卡顿，
                // 而不是持续花屏。
                warn!("客户端落后 {n} 帧，等一个关键帧再继续");
                hub.request_keyframe();
                waiting_for_keyframe = true;
                continue;
            }
```

以及：

```rust
        if waiting_for_keyframe {
            if !packet.keyframe {
                continue;
            }
            waiting_for_keyframe = false;
        }
```

头的内容：

```rust
#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct CodecHeader {
    /// `"mjpeg"` 或 `"h264"`
    codec: &'static str,
    /// 只有 h264 有：WebCodecs `VideoDecoder.configure` 要的 `avc1.XXXXXX`
    #[serde(skip_serializing_if = "Option::is_none")]
    avc_codec: Option<String>,
    /// 编码器起不来时说明原因，页面直接显示。静默降级比报错更难查。
    #[serde(skip_serializing_if = "Option::is_none")]
    fallback_reason: Option<String>,
}
```

**编码器起不来就回落 MJPEG**：配置说 h264 但 `codec_string()` 一直是 `None` 且
统计里有编码器错误时，头里发 `codec: "mjpeg"` 加 `fallbackReason`。

- [ ] **Step 4: 构建并跑起来**

```powershell
$env:CARGO_TARGET_DIR = (Join-Path (Get-Location).Path "target")
.\target\release\rust-kvm.exe stop
cargo clippy --release
cargo build --release
.\target\release\rust-kvm.exe start
```

期望：clippy 零警告，构建成功，服务起来。

- [ ] **Step 5: 用 curl 确认两种编码的头**

配置默认 mjpeg，所以先确认它没坏：

```powershell
curl.exe -k https://localhost:8443/api/config
```

期望 `video` 里有 `"codec":"mjpeg"` 和 `"bitrateKbps":4000`。

改成 h264 再看日志：

```powershell
$body = (curl.exe -sk https://localhost:8443/api/config | ConvertFrom-Json)
$body.video.codec = "h264"
$body | ConvertTo-Json -Depth 5 | curl.exe -sk -X POST -H "Content-Type: application/json" -d "@-" https://localhost:8443/api/config
Start-Sleep -Seconds 3
Get-Content rust-kvm.log -Tail 20
```

期望日志里出现编码器启动，没有 panic。

- [ ] **Step 6: 提交**

```powershell
git add src/config.rs src/video.rs src/main.rs
git commit -m "服务端接上 H.264：编码任务并排挂在 JPEG 广播旁边

编码任务是 JPEG 广播的一个普通订阅者，有 h264 观看者才启动，最后一个走掉就停
——和采集本身「有人看才开」是同一套规矩。原来那条 MJPEG 路径一行都没改，所以
风险严格为零。

最要紧的一处差别：H.264 不能像 MJPEG 那样随便丢帧。MJPEG 每帧独立，所以到处
都能「丢最旧的」；H.264 丢一个 P 帧会让后面整个 GOP 全烂到下一个 IDR。所以
Lagged 时改成「请编码器补一个关键帧 + 在它到达前把帧全丢掉」，解码器在 IDR 上
重新同步，代价是一次可见卡顿而不是持续花屏。

编码器起不来就回落 MJPEG，并把原因写进头里让页面显示——静默降级比报错更难查。"
```

---

## Task 5: 浏览器端解码

**Files:**
- Modify: `web/video-worker.js`、`web/viewer.js`

**Interfaces:**
- Consumes: Task 4 的 Text 头 `{codec, avcCodec, fallbackReason}`
- Produces: worker 往主线程 post 的 stats 里多两个字段：
  `codec`（字符串）、`decodeQueue`（数字）

- [ ] **Step 1: worker 里处理 Text 头**

`web/video-worker.js` 的 `socket.onmessage` 改成先分辨类型：

```js
    socket.onmessage = (event) => {
        // Text 是编码头，Binary 是帧。服务端在连上时发一条头，改配置时再发一条
        // ——所以任何时候收到 Text 都当作「重新配置解码器」。
        if (typeof event.data === "string") {
            applyHeader(JSON.parse(event.data));
            return;
        }
        window_.received += 1;
        window_.bytes += event.data.byteLength;
        if (codec === "h264") {
            feedH264(event.data);
        } else {
            // MJPEG 每帧独立，所以「只留最新一帧、忙就丢」是免费的降级
            if (pendingFrame) {
                window_.dropped += 1;
            }
            pendingFrame = {buffer: event.data, at: performance.now()};
            pump();
        }
    };
```

- [ ] **Step 2: H.264 解码路径**

```js
// H.264 解码。和 MJPEG 那条路最大的区别：**一帧都不能丢**。
//
// MJPEG 每帧独立，跟不上就丢最旧的，免费。H.264 丢一个 P 帧会让后面整个 GOP
// 全烂到下一个 IDR，所以这里每一条都喂进去。VideoDecoder 自己排队，硬件解码
// 远快于 30fps，跟不上不是问题；真跟不上的话 decodeQueueSize 会涨，那是诊断
// 读数而不是要去丢帧的信号。
let decoder = null;
let decoderCodec = null;

function applyHeader(header) {
    codec = header.codec;
    fallbackReason = header.fallbackReason || null;
    if (codec !== "h264") {
        closeDecoder();
        post({type: "codec", codec: codec, fallbackReason: fallbackReason});
        return;
    }
    if (decoder && decoderCodec === header.avcCodec) {
        return;
    }
    closeDecoder();
    decoderCodec = header.avcCodec;
    decoder = new VideoDecoder({
        output: drawFrame,
        error: (e) => {
            window_.errors += 1;
            // 解码器一旦进 closed 状态就再也回不来，只能重建。等下一个关键帧
            // 自然会重建，所以这里只记一下。
            closeDecoder();
        }
    });
    decoder.configure({
        codec: decoderCodec,
        // 不为了流畅去缓冲。KVM 要的是手感，多一帧缓冲就是多 33ms 的隔手感。
        optimizeForLatency: true
    });
    post({type: "codec", codec: codec, fallbackReason: fallbackReason});
}

function closeDecoder() {
    if (decoder && decoder.state !== "closed") {
        decoder.close();
    }
    decoder = null;
    decoderCodec = null;
}

function feedH264(buffer) {
    if (!decoder || decoder.state !== "configured") {
        return;
    }
    const bytes = new Uint8Array(buffer);
    // 关键帧要标成 "key"，否则解码器会拿它当增量帧，从头到尾什么都不出。
    // 靠 NAL 类型判断：5 = IDR 片，7 = SPS（编码器在每个 IDR 前重复 SPS/PPS）。
    const type = isKeyFrame(bytes) ? "key" : "delta";
    if (!seenKeyFrame) {
        if (type !== "key") {
            return;   // 还没等到起头的关键帧，喂进去只会报错
        }
        seenKeyFrame = true;
    }
    decoder.decode(new EncodedVideoChunk({
        type: type,
        timestamp: performance.now() * 1000,   // 微秒
        data: bytes
    }));
}

function isKeyFrame(bytes) {
    for (let i = 0; i + 4 < bytes.length; i += 1) {
        const four = bytes[i] === 0 && bytes[i + 1] === 0 && bytes[i + 2] === 0
            && bytes[i + 3] === 1;
        const three = bytes[i] === 0 && bytes[i + 1] === 0 && bytes[i + 2] === 1;
        if (!four && !three) {
            continue;
        }
        const nal = bytes[i + (four ? 4 : 3)] & 0x1f;
        if (nal === 5 || nal === 7) {
            return true;
        }
        i += four ? 3 : 2;
    }
    return false;
}

function drawFrame(frame) {
    try {
        if (canvas.width !== frame.displayWidth || canvas.height !== frame.displayHeight) {
            canvas.width = frame.displayWidth;
            canvas.height = frame.displayHeight;
        }
        context.drawImage(frame, 0, 0);
        window_.drawn += 1;
        if (tapping) {
            // 录制那块画布要的是能转移的东西，VideoFrame 不能直接转移给
            // 已有的录制通路，所以转成 bitmap 再给
            createImageBitmap(frame).then((bitmap) => {
                self.postMessage({type: "frame", bitmap: bitmap}, [bitmap]);
            }).catch(() => {});
        }
    } finally {
        // **必须 close。** VideoFrame 持有 GPU 内存，不 close 会在几秒内耗尽
        // 并卡死整个页面，而现象看起来像「解码器不行」——最误导的那种。
        // 放在 finally 里：上面任何一步抛了也不能漏掉这一下。
        frame.close();
    }
}
```

`report()` 里加两个字段：

```js
        codec: codec,
        decodeQueue: decoder ? decoder.decodeQueueSize : 0,
```

- [ ] **Step 3: `viewer.js` 里显示出来**

`web/viewer.js` 的 `render()` 里，客户端读数那几行之后加：

```js
    // 编码写出来：两种编码的延迟和码率差一个数量级，看读数时必须知道现在是哪种
    if (clientStats && clientStats.codec) {
        lines.push(clientStats.codec === "h264"
            ? `编码 H.264　解码队列 ${clientStats.decodeQueue}`
            : "编码 MJPEG");
    }
    if (fallbackReason) {
        // 服务端本来要发 h264 但编码器起不来。这一条必须显示——静默退回 MJPEG
        // 的话，人只会觉得「省带宽那个开关没用」，而不知道为什么。
        lines.push("已回落 MJPEG：" + fallbackReason);
    }
```

- [ ] **Step 4: 浏览器里验**

```powershell
.\target\release\rust-kvm.exe start
```

用 IDE 浏览器打开 `http://localhost:8080/`，然后逐项确认：

1. 默认（mjpeg）画面正常，叠加层写「编码 MJPEG」。
2. 把配置改成 h264（`/api/config` POST，同 Task 4 Step 5），**不刷新页面**，画面
   应当继续，叠加层变成「编码 H.264　解码队列 0」。
3. 读数对比：记下两种编码各自的 Mbps。h264 应当显著低于 mjpeg。
4. **桌面静止时**盯 h264 的 Mbps，应当趋近于零——这是这个特性的主要收益，
   看不到就说明哪里不对。
5. CDP 里查 `window.__errs` 之类的错误收集为空（沿用之前验证的做法）。
6. 录制一段，确认文件能播（H.264 路径下录制走的是 `createImageBitmap(frame)`，
   和 MJPEG 那条路不同，必须单独验）。

- [ ] **Step 5: 盯五分钟内存**

`VideoFrame` 泄漏的现象是「几秒到几十秒后页面卡死」，而且看起来像解码器的问题。
所以专门盯一次：

```
CDP: Runtime.evaluate → performance.memory.usedJSHeapSize，每 30 秒一次，连 5 分钟
```

期望：曲线平稳，不单调上升。

- [ ] **Step 6: 提交**

```powershell
git add web/video-worker.js web/viewer.js
git commit -m "浏览器端 H.264 解码

按连接上那条 Text 头选解码路径，往下仍是同一块 canvas，所以统计、录制、全屏
一行都没动。

和 MJPEG 那条路最大的区别是**一帧都不能丢**：MJPEG 每帧独立，跟不上丢最旧的
是免费的；H.264 丢一个 P 帧会让后面整个 GOP 全烂。所以每一条都喂给
VideoDecoder，它自己排队，decodeQueueSize 当诊断读数放进叠加层。

VideoFrame 必须手动 close，放在 finally 里。不 close 会在几秒内耗尽 GPU 内存
并卡死页面，而现象看起来像「解码器不行」——最误导的那种失败。"
```

---

## Task 6: 设置页开关 + 窄带音频

**Files:**
- Modify: `web/settings.html`、`web/settings-page.js`、`src/config.rs`、`src/audio.rs`

**Interfaces:**
- Consumes: `config::VideoSettings::{codec, bitrate_kbps}`（Task 4）
- Produces: `config::AudioSettings::narrowband: bool`

- [ ] **Step 1: 音频配置加窄带**

`src/config.rs` 的 `AudioSettings`：

```rust
    /// 单声道 + 24kHz。裸 PCM 固定 1.5 Mbps，在几 Mbps 的链路上占比太大，
    /// 开了就是四分之一。采集卡那路音频是被控机的系统声，单声道够用。
    pub narrowband: bool,
```

`Default` 里 `narrowband: false`。

- [ ] **Step 2: `audio.rs` 里降混降采样**

在 `publish_aligned` 之前插一层。24000 正好是 48000 的一半，所以重采样就是隔一帧
取一个——不需要滤波器，而这个简化只在「正好二整倍」时成立，要写明：

```rust
/// 降混降采样到单声道 24kHz。
///
/// 只处理 48kHz→24kHz 这一种：正好二整倍，隔一帧取一个就行，不需要低通滤波器。
/// 采集卡固定 48kHz，所以够用；哪天源不是 48k 了，这里必须改成真的重采样，
/// 否则会有镜像失真。
fn narrow(samples: &[i16], channels: u16) -> Vec<i16> {
    let step = channels as usize * 2;
    let mut out = Vec::with_capacity(samples.len() / step);
    for frame in samples.chunks_exact(step) {
        // 两个声道取平均，再隔一帧取一个
        let left = frame[0] as i32;
        let right = if channels >= 2 { frame[1] as i32 } else { left };
        out.push(((left + right) / 2) as i16);
    }
    out
}
```

格式头里报的采样率和声道数要跟着变（客户端本来就是照头建 `AudioContext` 的，
所以客户端不用改）：

```rust
    let published = if config.narrowband {
        AudioFormat { sample_rate: format.sample_rate / 2, channels: 1 }
    } else {
        format
    };
    hub.mark_capturing(true, Some(published));
```

**只在源是 48kHz 时才允许开**，否则报错而不是悄悄出怪声：

```rust
    if config.narrowband && format.sample_rate != 48_000 {
        bail!("窄带只支持 48kHz 源，当前是 {} Hz", format.sample_rate);
    }
```

- [ ] **Step 3: 设置页加三个控件**

`web/settings.html` 的画面那一节里，`fps` 之后：

```html
            <label>编码
                <select id="video-codec">
                    <option value="mjpeg">MJPEG（局域网，延迟最低）</option>
                    <option value="h264">H.264（省带宽，适合远程）</option>
                </select>
            </label>
            <label>H.264 码率
                <select id="video-bitrate">
                    <option value="1000">1 Mbps</option>
                    <option value="2000">2 Mbps</option>
                    <option value="4000">4 Mbps</option>
                    <option value="8000">8 Mbps</option>
                </select>
            </label>
            <p class="hint">H.264 把静止画面压到接近零码率，代价是多一层编解码
                （约 10ms）和宿主机一成 CPU。<strong>局域网建议用 MJPEG。</strong></p>
```

声音那一节里：

```html
            <label><input type="checkbox" id="audio-narrowband"> 窄带（单声道 24kHz）</label>
            <p class="hint">裸 PCM 固定 1.5 Mbps，远程时占比太大；开了是四分之一。
                被控机的系统声用单声道听不出区别。</p>
```

- [ ] **Step 4: `settings-page.js` 读写这三项**

在现有的读配置那一段里加：

```js
    videoCodec.value = config.video.codec;
    videoBitrate.value = String(config.video.bitrateKbps);
    audioNarrowband.checked = config.audio.narrowband;
```

保存那一段里加：

```js
        video: {
            device: videoDevice.value,
            width: Number(width),
            height: Number(height),
            fps: Number(videoFps.value),
            codec: videoCodec.value,
            bitrateKbps: Number(videoBitrate.value)
        },
```

以及 audio 里的 `narrowband: audioNarrowband.checked`。

- [ ] **Step 5: 端到端验证**

```powershell
.\target\release\rust-kvm.exe stop
cargo clippy --release
cargo build --release
.\target\release\rust-kvm.exe start
```

浏览器打开设置页，逐项确认：

1. 三个控件显示的是当前配置值（不是默认值）。
2. 编码改成 H.264 保存 → 回控制页，画面正常，叠加层写「编码 H.264」。
3. 码率改成 1 Mbps 保存 → 叠加层的 Mbps 应当跟着降到 1 附近。
4. 窄带打开保存 → 控制页声音仍然正常，叠加层的音频那行显示 24000 Hz 单声道，
   kbps 从约 1536 降到约 384。
5. 全部改回默认（mjpeg / 4000 / 窄带关）→ 一切如初。

- [ ] **Step 6: 文档**

`README.md` 里加一节「低带宽模式」，内容照设计文档的核心结论写：为什么不是
WebRTC、H.264 不能随便丢帧这个差别、`VideoFrame` 必须 close、默认仍是 MJPEG
及其理由。

计划文档 `.cursor/plans/go_kvm_服务化_0ef6b66c.plan.md` 里把 `optional-h264`
标成 completed，并补上实测数字（两种编码的 Mbps、静止画面的 Mbps、延迟差）。

- [ ] **Step 7: 提交**

```powershell
git add web/settings.html web/settings-page.js src/config.rs src/audio.rs README.md
git commit -m "设置页：编码开关、码率档、窄带音频

默认仍是 MJPEG：日常是局域网，低带宽是出门时才开的。

窄带音频只处理 48kHz→24kHz 这一种情况，因为正好二整倍，隔一帧取一个就行，
不需要低通滤波器。源不是 48k 时直接报错而不是悄悄出怪声——那个简化只在二整倍
时成立，换了采样率会有镜像失真。"
```

---

## Self-Review

**Spec coverage：**

| 设计文档里的要求 | 对应任务 |
| --- | --- |
| 编码任务并排挂在 JPEG 广播旁 | Task 4 Step 2 |
| 全局配置而非每客户端 | Task 4 Step 1、Task 6 Step 3 |
| `zune-jpeg` 直出 YCbCr、交织 4:4:4 → NV12 | Task 2 |
| 延迟三处（LowLatency / B=0 / CBR） | Task 1 Step 2 `configure_low_latency`、Task 3 Step 5 |
| GOP 开大 + 强制 IDR | Task 1 Step 2、Task 4 Step 2 |
| codec 字符串从 SPS 拼 | Task 3 Step 1–4 |
| Annex-B、每个 IDR 前重复 SPS/PPS | Task 1 Step 2（判 SPS）、Task 5 Step 2（判关键帧） |
| **H.264 不能随便丢帧**（服务端补 IDR、客户端不丢） | Task 4 Step 3、Task 5 Step 2 |
| 编码器起不来回落 MJPEG 并说明原因 | Task 4 Step 3、Task 5 Step 3 |
| 协议头形状 | Task 4 Step 3 |
| `VideoFrame` 必须 close | Task 5 Step 2、Step 5 |
| 窄带音频 | Task 6 Step 1–2 |
| 验证表六项 | Task 1 Step 3、Task 2 Step 5、Task 5 Step 4–5、Task 6 Step 5 |
| 非 Windows 回落 | Global Constraints、Task 3 文件头 |

无遗漏。

**类型一致性检查：** `Nv12Frame`（Task 2 定义 → Task 3/4 使用）、
`EncodedFrame.data`/`.keyframe`（Task 3 定义 → Task 4 使用）、
`EncoderConfig.bitrate_kbps`（Task 3 定义 → Task 4 传入，配置字段同名）、
`VideoCodec`（Task 4 定义并自用）、头字段 `avcCodec`/`fallbackReason`
（Task 4 序列化 camelCase → Task 5 同名读取）。一致。

**已知的风险点，实施时必须停下来报告而不是硬扛：**

1. Task 1 打不出「含 SPS」——前提不成立，整个特性没有出路，立刻报告。
2. Task 3 的异步事件循环如果在 `GetEvent` 上卡住，不要加 sleep 硬绕。那说明
   `MF_TRANSFORM_ASYNC_UNLOCK` 或 `NOTIFY_BEGIN_STREAMING` 的顺序不对。
3. Task 5 如果内存单调上升，一定是某条路径漏了 `frame.close()`——找漏的那条，
   不要靠 `gc()`。
