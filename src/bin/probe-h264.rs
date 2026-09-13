//! 探针：确认这台机器上的 H.264 编码 MFT 能不能用。
//!
//! 存在的理由：系统里**没有**注册 Microsoft 的 H.264 软件编码器（查过注册表），
//! 硬件编码器是唯一的路，没有兜底。整个低带宽模式都压在「它能开、能吃系统内存的
//! NV12、能吐出 SPS」这三件事上，所以先用两百行确认，而不是写完一整条管线之后
//! 才发现前提不成立。
//!
//! 跑：cargo run --bin probe-h264

use anyhow::Result;

#[cfg(not(windows))]
fn main() -> Result<()> {
    anyhow::bail!("这个探针只在 Windows 上有意义");
}

#[cfg(windows)]
fn main() -> Result<()> {
    windows_probe::run()
}

#[cfg(windows)]
mod windows_probe {
    use anyhow::{Context, Result, bail};
    use std::time::{Duration, Instant};
    use windows::Win32::Foundation::VARIANT_BOOL;
    use windows::Win32::Media::MediaFoundation::*;
    use windows::Win32::System::Com::{COINIT_MULTITHREADED, CoInitializeEx, CoTaskMemFree};
    use windows::Win32::System::Variant::{VARIANT, VT_BOOL, VT_UI4};
    use windows::core::{GUID, Interface};

    const WIDTH: u32 = 1920;
    const HEIGHT: u32 = 1080;
    const FPS: u32 = 30;
    const BITRATE: u32 = 4_000_000;
    const FRAMES: usize = 60;

    pub fn run() -> Result<()> {
        unsafe {
            CoInitializeEx(None, COINIT_MULTITHREADED)
                .ok()
                .context("CoInitializeEx")?;
            MFStartup(MF_VERSION, MFSTARTUP_FULL).context("MFStartup")?;
        }

        let result = probe();

        unsafe {
            let _ = MFShutdown();
        }
        result
    }

    fn probe() -> Result<()> {
        // 先看系统到底给出哪些。空手而归和「有但开不起来」在诊断上是两回事，
        // 所以枚举和创建分开报。
        let found = enumerate()?;
        println!("找到 {} 个 H.264 编码器 MFT：", found.len());
        for (i, name) in found.iter().enumerate() {
            println!("  [{i}] {name}");
        }
        if found.is_empty() {
            bail!("一个都没有。低带宽模式在这台机器上没有出路。");
        }
        println!();

        let transform = create_encoder()?;
        let is_async = unlock_if_async(&transform)?;
        println!("异步 MFT：{}", if is_async { "是" } else { "否" });

        // 顺序要紧：编码器必须**先设输出类型再设输入类型**。反了的话 SetInputType
        // 会以 MF_E_TRANSFORM_TYPE_NOT_SET 失败，而那个错误码完全看不出是顺序问题。
        set_output_type(&transform)?;
        set_input_type(&transform)?;
        configure_low_latency(&transform)?;
        println!("输入 NV12 / 输出 H.264 协商通过，低延迟参数已设");

        let nv12 = synthetic_nv12();
        println!("喂 {FRAMES} 帧 {WIDTH}x{HEIGHT} 合成画面…");
        println!();

        unsafe {
            transform
                .ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0)
                .context("NOTIFY_BEGIN_STREAMING")?;
            transform
                .ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0)
                .context("NOTIFY_START_OF_STREAM")?;
        }

        let stats = if is_async {
            run_async(&transform, &nv12)?
        } else {
            run_sync(&transform, &nv12)?
        };
        let Stats {
            encode_time,
            bytes,
            packets,
            first,
        } = stats;

        let seconds = FRAMES as f64 / FPS as f64;
        println!("单帧平均 {:.2} ms", encode_time.as_secs_f64() * 1000.0 / FRAMES as f64);
        println!("产出 {packets} 个包，共 {bytes} 字节");
        println!(
            "实际码率 {:.2} Mbps（目标 {:.1}）",
            bytes as f64 * 8.0 / seconds / 1e6,
            BITRATE as f64 / 1e6
        );

        let Some(packet) = first else {
            bail!("一个包都没吐出来");
        };
        println!(
            "首包 {} 字节，前 16 字节：{:02x?}",
            packet.len(),
            &packet[..packet.len().min(16)]
        );

        match codec_string(&packet) {
            Some(codec) => {
                println!();
                println!("含 SPS，codec 字符串 {codec} —— 前提成立，可以往下走");
                Ok(())
            }
            // 客户端 VideoDecoder.configure 需要这个字符串，也需要码流里带 SPS
            // 才能起解码器。拿不到就说明这条路走不通。
            None => bail!("首包里没有 SPS。客户端配置不出解码器，这条路不通。"),
        }
    }

    struct Stats {
        encode_time: Duration,
        bytes: usize,
        packets: usize,
        first: Option<Vec<u8>>,
    }

    /// 同步 MFT：推一帧、拉到拉不出为止。
    fn run_sync(transform: &IMFTransform, nv12: &[u8]) -> Result<Stats> {
        let mut stats = Stats {
            encode_time: Duration::ZERO,
            bytes: 0,
            packets: 0,
            first: None,
        };
        for index in 0..FRAMES {
            let started = Instant::now();
            push_frame(transform, nv12, index as i64)?;
            while let Some(packet) = pull_output(transform)? {
                take(&mut stats, packet);
            }
            stats.encode_time += started.elapsed();
        }
        unsafe {
            let _ = transform.ProcessMessage(MFT_MESSAGE_COMMAND_DRAIN, 0);
        }
        while let Some(packet) = pull_output(transform)? {
            take(&mut stats, packet);
        }
        Ok(stats)
    }

    /// 异步 MFT：**只能由事件驱动**。
    ///
    /// 直接像同步那样推一帧拉一次的话，`ProcessOutput` 会以 `E_UNEXPECTED`
    /// （0x8000FFFF「灾难性故障」）失败——这个错误码什么都没说，但原因就是
    /// 「没等 METransformHaveOutput 就来要输出」。第一版探针正是这么写的。
    ///
    /// 所以流程变成：等事件；`NeedInput` 就推一帧（推完了就发 DRAIN），
    /// `HaveOutput` 就拉一个包，`DrainComplete` 收摊。
    fn run_async(transform: &IMFTransform, nv12: &[u8]) -> Result<Stats> {
        let events: IMFMediaEventGenerator = transform.cast().context("拿不到事件生成器")?;
        let mut stats = Stats {
            encode_time: Duration::ZERO,
            bytes: 0,
            packets: 0,
            first: None,
        };
        let mut pushed = 0usize;
        let mut draining = false;
        let started = Instant::now();
        // 不用阻塞式 GetEvent：万一事件永远不来，探针就挂死在那儿，而挂死比
        // 报错难查得多。
        let deadline = started + Duration::from_secs(30);

        loop {
            if Instant::now() > deadline {
                bail!("等编码器事件超时（已推 {pushed} 帧，收到 {} 个包）", stats.packets);
            }
            let event = match unsafe { events.GetEvent(MF_EVENT_FLAG_NO_WAIT) } {
                Ok(event) => event,
                Err(e) if e.code() == MF_E_NO_EVENTS_AVAILABLE => {
                    std::thread::sleep(Duration::from_micros(200));
                    continue;
                }
                Err(e) => return Err(e).context("GetEvent"),
            };

            // 用 if 链而不是 match：这几个常量名不是大写，拿去当匹配模式时万一
            // 名字没解析成常量，就会变成「绑定任意值」的分支，静默吃掉所有事件。
            // 这里解析是对的，但那种错法不会有任何报错，不值得赌。
            let kind = MF_EVENT_TYPE(unsafe { event.GetType() }? as i32);
            if kind == METransformNeedInput {
                if pushed < FRAMES {
                    push_frame(transform, nv12, pushed as i64)?;
                    pushed += 1;
                } else if !draining {
                    // 推完了要主动收尾，否则攒在编码器里的最后几帧永远不出来
                    unsafe {
                        transform
                            .ProcessMessage(MFT_MESSAGE_NOTIFY_END_OF_STREAM, 0)
                            .context("NOTIFY_END_OF_STREAM")?;
                        transform
                            .ProcessMessage(MFT_MESSAGE_COMMAND_DRAIN, 0)
                            .context("COMMAND_DRAIN")?;
                    }
                    draining = true;
                }
            } else if kind == METransformHaveOutput {
                if let Some(packet) = pull_output(transform)? {
                    take(&mut stats, packet);
                }
            } else if kind == METransformDrainComplete {
                break;
            }
        }

        stats.encode_time = started.elapsed();
        Ok(stats)
    }

    fn take(stats: &mut Stats, packet: Vec<u8>) {
        stats.bytes += packet.len();
        stats.packets += 1;
        if stats.first.is_none() {
            stats.first = Some(packet);
        }
    }

    /// 枚举出所有能输出 H.264 的编码器 MFT，硬件的排前面。
    fn enumerate() -> Result<Vec<String>> {
        let output = MFT_REGISTER_TYPE_INFO {
            guidMajorType: MFMediaType_Video,
            guidSubtype: MFVideoFormat_H264,
        };
        // 同时要硬件和软件：只要硬件的话，万一驱动没注册成 MFT 就直接空手而归，
        // 而那时我们需要知道「软件的也没有」这个事实。SORTANDFILTER 让硬件排前面。
        let flags = MFT_ENUM_FLAG_HARDWARE
            | MFT_ENUM_FLAG_SYNCMFT
            | MFT_ENUM_FLAG_ASYNCMFT
            | MFT_ENUM_FLAG_SORTANDFILTER;

        let mut activates: *mut Option<IMFActivate> = std::ptr::null_mut();
        let mut count = 0u32;
        unsafe {
            MFTEnumEx(
                MFT_CATEGORY_VIDEO_ENCODER,
                flags,
                None,
                Some(&output),
                &mut activates,
                &mut count,
            )
            .context("MFTEnumEx")?;
        }

        let mut names = Vec::new();
        for i in 0..count as usize {
            let slot = unsafe { &*activates.add(i) };
            if let Some(activate) = slot {
                names.push(friendly_name(activate));
            }
        }
        unsafe { CoTaskMemFree(Some(activates as *const _)) };
        Ok(names)
    }

    fn friendly_name(activate: &IMFActivate) -> String {
        let mut buffer = windows::core::PWSTR::null();
        let mut len = 0u32;
        let ok = unsafe {
            activate
                .GetAllocatedString(&MFT_FRIENDLY_NAME_Attribute, &mut buffer, &mut len)
                .is_ok()
        };
        if !ok {
            return "（无名）".to_string();
        }
        let name = unsafe { buffer.to_string() }.unwrap_or_else(|_| "（名字不是合法 UTF-16）".into());
        unsafe { CoTaskMemFree(Some(buffer.as_ptr() as *const _)) };
        name
    }

    /// 取第一个（`MFTEnumEx` 已按硬件优先排过序）。
    fn create_encoder() -> Result<IMFTransform> {
        let output = MFT_REGISTER_TYPE_INFO {
            guidMajorType: MFMediaType_Video,
            guidSubtype: MFVideoFormat_H264,
        };
        let flags = MFT_ENUM_FLAG_HARDWARE
            | MFT_ENUM_FLAG_SYNCMFT
            | MFT_ENUM_FLAG_ASYNCMFT
            | MFT_ENUM_FLAG_SORTANDFILTER;

        let mut activates: *mut Option<IMFActivate> = std::ptr::null_mut();
        let mut count = 0u32;
        unsafe {
            MFTEnumEx(
                MFT_CATEGORY_VIDEO_ENCODER,
                flags,
                None,
                Some(&output),
                &mut activates,
                &mut count,
            )
            .context("MFTEnumEx")?;
        }
        if count == 0 {
            bail!("没有可用的 H.264 编码器 MFT");
        }
        let activate = unsafe { &*activates }
            .clone()
            .context("第一个 activate 是空的")?;
        let transform = unsafe { activate.ActivateObject::<IMFTransform>() }
            .context("ActivateObject 失败（编码器注册着但开不起来）")?;
        unsafe { CoTaskMemFree(Some(activates as *const _)) };
        Ok(transform)
    }

    /// 异步 MFT 默认是锁着的，不解锁一上来就 `MF_E_TRANSFORM_ASYNC_LOCKED`，
    /// 而那个错误码看不出「要先声明自己懂异步」这层意思。
    fn unlock_if_async(transform: &IMFTransform) -> Result<bool> {
        let attributes = match unsafe { transform.GetAttributes() } {
            Ok(attributes) => attributes,
            // 没有属性存储的 MFT 一定是同步的
            Err(_) => return Ok(false),
        };
        let is_async = unsafe { attributes.GetUINT32(&MF_TRANSFORM_ASYNC) }.unwrap_or(0) == 1;
        if is_async {
            unsafe { attributes.SetUINT32(&MF_TRANSFORM_ASYNC_UNLOCK, 1) }
                .context("设 MF_TRANSFORM_ASYNC_UNLOCK")?;
        }
        Ok(is_async)
    }

    /// `MF_MT_FRAME_SIZE` / `FRAME_RATE` 都是把两个 u32 打进一个 u64，高位在前。
    fn pack(high: u32, low: u32) -> u64 {
        ((high as u64) << 32) | low as u64
    }

    fn set_output_type(transform: &IMFTransform) -> Result<()> {
        let media_type = unsafe { MFCreateMediaType() }.context("MFCreateMediaType")?;
        unsafe {
            media_type.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Video)?;
            media_type.SetGUID(&MF_MT_SUBTYPE, &MFVideoFormat_H264)?;
            media_type.SetUINT32(&MF_MT_AVG_BITRATE, BITRATE)?;
            media_type.SetUINT64(&MF_MT_FRAME_SIZE, pack(WIDTH, HEIGHT))?;
            media_type.SetUINT64(&MF_MT_FRAME_RATE, pack(FPS, 1))?;
            media_type.SetUINT32(&MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive.0 as u32)?;
            // Main 比 Baseline 压得好，而 B 帧是另一个开关（下面关掉）
            media_type.SetUINT32(&MF_MT_MPEG2_PROFILE, eAVEncH264VProfile_Main.0 as u32)?;
            transform
                .SetOutputType(0, &media_type, 0)
                .context("SetOutputType")?;
        }
        Ok(())
    }

    fn set_input_type(transform: &IMFTransform) -> Result<()> {
        let media_type = unsafe { MFCreateMediaType() }.context("MFCreateMediaType")?;
        unsafe {
            media_type.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Video)?;
            media_type.SetGUID(&MF_MT_SUBTYPE, &MFVideoFormat_NV12)?;
            media_type.SetUINT64(&MF_MT_FRAME_SIZE, pack(WIDTH, HEIGHT))?;
            media_type.SetUINT64(&MF_MT_FRAME_RATE, pack(FPS, 1))?;
            media_type.SetUINT32(&MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive.0 as u32)?;
            transform
                .SetInputType(0, &media_type, 0)
                .context("SetInputType（若报 TYPE_NOT_SET，是输出类型没先设好）")?;
        }
        Ok(())
    }

    /// 延迟三处必须设对，漏一个这功能就白做。详见设计文档。
    fn configure_low_latency(transform: &IMFTransform) -> Result<()> {
        let codec: ICodecAPI = transform.cast().context("拿不到 ICodecAPI")?;

        // 1. 不开的话编码器先攒几帧再吐第一帧，凭空多 50-100ms
        set_bool(&codec, &CODECAPI_AVLowLatencyMode, true)?;
        // 2. B 帧要等后续帧才能编，是结构性延迟，对 KVM 纯亏
        set_u32(&codec, &CODECAPI_AVEncMPVDefaultBPictureCount, 0)?;
        // 3. 质量优先的 VBR 在画面一变时会冲到几十 Mbps，正好炸掉 WAN 预算
        set_u32(
            &codec,
            &CODECAPI_AVEncCommonRateControlMode,
            eAVEncCommonRateControlMode_CBR.0 as u32,
        )?;
        set_u32(&codec, &CODECAPI_AVEncCommonMeanBitRate, BITRATE)?;
        // GOP 开大：新人进场靠强制 IDR 解决，不靠短 GOP——短 GOP 是拿常态码率
        // 换偶发事件，不值
        set_u32(&codec, &CODECAPI_AVEncMPVGOPSize, FPS * 10)?;
        Ok(())
    }

    /// 这几项是**尽力而为**：驱动不认某一项时只是少一点优化，不该让整个探针失败。
    /// 但要把没设上的那项打出来——延迟那三项里任何一项没设上都会直接反映在手感上。
    fn set_bool(codec: &ICodecAPI, key: &GUID, value: bool) -> Result<()> {
        let mut variant = VARIANT::default();
        unsafe {
            let inner = &mut variant.Anonymous.Anonymous;
            inner.vt = VT_BOOL;
            // VARIANT_BOOL 的真是 -1 而不是 1，这是 OLE 的老规矩
            inner.Anonymous.boolVal = VARIANT_BOOL(if value { -1 } else { 0 });
        }
        report_set(codec, key, &variant)
    }

    fn set_u32(codec: &ICodecAPI, key: &GUID, value: u32) -> Result<()> {
        let mut variant = VARIANT::default();
        unsafe {
            let inner = &mut variant.Anonymous.Anonymous;
            inner.vt = VT_UI4;
            inner.Anonymous.ulVal = value;
        }
        report_set(codec, key, &variant)
    }

    fn report_set(codec: &ICodecAPI, key: &GUID, variant: &VARIANT) -> Result<()> {
        if let Err(e) = unsafe { codec.SetValue(key, variant) } {
            println!("  ! 参数 {key:?} 没设上：{e}");
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
        // 色度给中性灰，只是别让它是 0（那是绿色，看日志时容易误判）
        buffer[y_size..].fill(128);
        buffer
    }

    fn push_frame(transform: &IMFTransform, nv12: &[u8], index: i64) -> Result<()> {
        let buffer =
            unsafe { MFCreateMemoryBuffer(nv12.len() as u32) }.context("MFCreateMemoryBuffer")?;
        unsafe {
            let mut target = std::ptr::null_mut();
            buffer.Lock(&mut target, None, None).context("buffer.Lock")?;
            std::ptr::copy_nonoverlapping(nv12.as_ptr(), target, nv12.len());
            buffer.Unlock()?;
            buffer.SetCurrentLength(nv12.len() as u32)?;

            let sample = MFCreateSample().context("MFCreateSample")?;
            sample.AddBuffer(&buffer)?;
            // 100ns 为单位
            let per_frame = 10_000_000i64 / FPS as i64;
            sample.SetSampleTime(index * per_frame)?;
            sample.SetSampleDuration(per_frame)?;
            transform
                .ProcessInput(0, &sample, 0)
                .context("ProcessInput（若报 ASYNC_LOCKED，是没解锁异步 MFT）")?;
        }
        Ok(())
    }

    fn pull_output(transform: &IMFTransform) -> Result<Option<Vec<u8>>> {
        let info = unsafe { transform.GetOutputStreamInfo(0) }.context("GetOutputStreamInfo")?;
        // 编码器一般自己分配样本；不自己分配的话要我们给一块
        let provides = (info.dwFlags
            & (MFT_OUTPUT_STREAM_PROVIDES_SAMPLES.0 as u32
                | MFT_OUTPUT_STREAM_CAN_PROVIDE_SAMPLES.0 as u32))
            != 0;
        let supplied = if provides {
            None
        } else {
            let buffer = unsafe { MFCreateMemoryBuffer(info.cbSize.max(1)) }?;
            let sample = unsafe { MFCreateSample() }?;
            unsafe { sample.AddBuffer(&buffer) }?;
            Some(sample)
        };

        let mut output = [MFT_OUTPUT_DATA_BUFFER {
            dwStreamID: 0,
            pSample: std::mem::ManuallyDrop::new(supplied),
            dwStatus: 0,
            pEvents: std::mem::ManuallyDrop::new(None),
        }];
        let mut status = 0u32;
        let result = unsafe { transform.ProcessOutput(0, &mut output, &mut status) };

        let sample = unsafe { std::mem::ManuallyDrop::take(&mut output[0].pSample) };
        if let Err(e) = result {
            // 还没攒够，不是错误
            if e.code() == MF_E_TRANSFORM_NEED_MORE_INPUT {
                return Ok(None);
            }
            return Err(e).context("ProcessOutput");
        }
        let Some(sample) = sample else {
            return Ok(None);
        };

        let buffer = unsafe { sample.ConvertToContiguousBuffer() }?;
        let mut data = std::ptr::null_mut();
        let mut len = 0u32;
        unsafe { buffer.Lock(&mut data, None, Some(&mut len)) }?;
        let bytes = unsafe { std::slice::from_raw_parts(data, len as usize) }.to_vec();
        unsafe { buffer.Unlock() }?;
        Ok(Some(bytes))
    }

    /// 从 Annex-B 码流里找 SPS，拼出 WebCodecs 要的 `avc1.PPCCLL`。
    ///
    /// 不写死：编码器实际协商出的 profile 可能和我们请求的不同，而写死会让浏览器
    /// 静默解码失败——既没有错误也没有画面，极难查。三个字节就在 SPS 最前面。
    fn codec_string(packet: &[u8]) -> Option<String> {
        let mut i = 0;
        while i + 4 <= packet.len() {
            let header = if packet[i..].starts_with(&[0, 0, 0, 1]) {
                4
            } else if packet[i..].starts_with(&[0, 0, 1]) {
                3
            } else {
                i += 1;
                continue;
            };
            let nal = i + header;
            // 低 5 位是 nal_unit_type，7 = SPS
            if nal + 3 < packet.len() && packet[nal] & 0x1f == 7 {
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
}
