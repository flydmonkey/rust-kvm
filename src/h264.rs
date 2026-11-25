//! H.264 编码器。对外只有一个同步接口：喂 NV12，拿 Annex-B 包。
//!
//! 按平台二选一：
//!
//! - Windows：Media Foundation 的编码 MFT，多半落到显卡的硬件编码器上。
//! - 其余平台：openh264 软件编码，源码随 crate 编进来，不依赖系统里装没装库。
//!
//! 为什么单独成文件：这里的性质和项目其余部分完全不同——别处是普通 Rust，这里
//! 每一行都要盯着 COM 的生命周期和 HRESULT（或者 openh264 的 C 接口）。混在
//! `video.rs` 里的话，读那个文件的人得同时装着两套心智模型。

use bytes::Bytes;

#[derive(Clone, Copy, Debug)]
pub struct EncoderConfig {
    pub width: u32,
    pub height: u32,
    pub fps: u32,
    pub bitrate_kbps: u32,
}

/// 一个 Annex-B 包。
#[derive(Clone, Debug)]
pub struct EncodedFrame {
    pub data: Bytes,
    /// 关键帧（IDR）。客户端必须从一个关键帧开始才画得出东西，服务端在客户端
    /// 落后之后也要靠它重新同步，所以这个标记两头都要用。
    pub keyframe: bool,
}

/// 扫 Annex-B 起始码，回调每个 NAL 的类型和它在包里的位置。
///
/// 三字节（00 00 01）和四字节（00 00 00 01）两种起始码都要认：编码器两种都
/// 可能吐，实测 AMD 的首包是四字节。
fn for_each_nal(packet: &[u8], mut f: impl FnMut(u8, usize) -> bool) {
    let mut i = 0;
    while i + 3 < packet.len() {
        let header = if packet[i..].starts_with(&[0, 0, 0, 1]) {
            4
        } else if packet[i..].starts_with(&[0, 0, 1]) {
            3
        } else {
            i += 1;
            continue;
        };
        let at = i + header;
        if at >= packet.len() {
            return;
        }
        if f(packet[at] & 0x1f, at) {
            return;
        }
        i = at;
    }
}

/// 从 Annex-B 码流里找 SPS，拼出 WebCodecs 要的 `avc1.PPCCLL`。
///
/// 不写死：编码器实际协商出的 profile 可能和我们请求的不同，而写死会让浏览器
/// 静默解码失败——既没有错误也没有画面，极难查。三个字节就在 SPS 最前面。
pub(crate) fn codec_string_from_annexb(packet: &[u8]) -> Option<String> {
    let mut found = None;
    for_each_nal(packet, |kind, at| {
        if kind == 7 && at + 3 < packet.len() {
            found = Some(format!(
                "avc1.{:02X}{:02X}{:02X}",
                packet[at + 1],
                packet[at + 2],
                packet[at + 3]
            ));
            return true;
        }
        false
    });
    found
}

#[cfg(not(windows))]
pub use openh264_encoder::Encoder;

#[cfg(windows)]
pub use windows_encoder::Encoder;
// 只有 probe-h264 用，服务端那次编译里没人引，所以单独一行并标注
#[cfg(windows)]
#[allow(unused_imports)]
pub use windows_encoder::list_encoders;

/// openh264 软件编码。
///
/// 码率控制和 Windows 那边取同一个意图——**静止趋近于零、动起来有天花板**——但手段
/// 不同：openh264 没有「恒定质量 + 上限」这种组合，所以用码率模式加跳帧。码率模式
/// 不会像 MF 的 CBR 那样填充码流：画面不动时宏块全是 skip，一帧只剩几十字节。
/// 动得太猛时它宁可丢帧也不超预算，对 WAN 来说这正是想要的取舍。
#[cfg(not(windows))]
mod openh264_encoder {
    use anyhow::{Context, Result, bail};
    use bytes::Bytes;
    use openh264::encoder::{
        BitRate, Complexity, EncoderConfig as Settings, FrameRate, FrameType, IntraFramePeriod,
        RateControlMode, UsageType, VuiConfig,
    };
    use openh264::formats::YUVSlices;
    use openh264::{OpenH264API, Timestamp};

    use super::{EncodedFrame, EncoderConfig, codec_string_from_annexb};
    use crate::nv12::Nv12Frame;

    /// 和 Windows 那边一样开到 10 秒，新人进场靠强制 IDR。
    const GOP_SECONDS: u32 = 10;

    pub struct Encoder {
        inner: openh264::encoder::Encoder,
        config: EncoderConfig,
        /// openh264 只吃三平面的 I420，而 NV12 的 U、V 是交织的，每帧拆一次。
        /// 缓冲留着复用，理由同 `Nv12Frame`。
        u: Vec<u8>,
        v: Vec<u8>,
        frames: u64,
        /// 要到真的编出一个 IDR 才清：跳帧模式下被请求的那一帧可能正好被跳掉，
        /// 只请求一次的话，新观看者就要等十秒后的下一个自然关键帧。
        keyframe_wanted: bool,
        codec_string: Option<String>,
    }

    impl Encoder {
        pub fn new(config: EncoderConfig) -> Result<Self> {
            if config.width == 0 || config.height == 0 || config.fps == 0 {
                bail!("编码参数不合法：{}x{} @{}fps", config.width, config.height, config.fps);
            }
            // I420 的色度平面是 w/2 × h/2，奇数尺寸在 openh264 的绑定里是断言失败（panic），
            // 不是错误返回，所以得在这儿先拦住
            if config.width % 2 == 1 || config.height % 2 == 1 {
                bail!("openh264 只收偶数尺寸，{}x{} 不行", config.width, config.height);
            }

            let settings = Settings::new()
                // 屏幕内容模式对文字和大片纯色更友好，KVM 的画面基本就是这些
                .usage_type(UsageType::ScreenContentRealTime)
                // 屏幕内容模式不支持这两项，留着默认的「开」它会自己关掉，但每次建编码器
                // 都往 stderr 打一行警告
                .adaptive_quantization(false)
                .background_detection(false)
                .rate_control_mode(RateControlMode::Bitrate)
                .bitrate(BitRate::from_bps(config.bitrate_kbps * 1000))
                .skip_frames(true)
                .max_frame_rate(FrameRate::from_hz(config.fps as f32))
                .intra_frame_period(IntraFramePeriod::from_num_frames(config.fps * GOP_SECONDS))
                // 软件编码，1080p30 要一帧 33ms 内做完，复杂度只能给最低档
                .complexity(Complexity::Low)
                // 采集卡的 JPEG 是 BT.601 满幅（JFIF 就是这么定义的），要在 SPS 里写明。
                // 不写的话解码端按 studio swing 解释，黑的更黑白的更白，颜色发飘
                .vui(VuiConfig::bt601().full_range(true));
            let inner =
                openh264::encoder::Encoder::with_api_config(OpenH264API::from_source(), settings)
                    .context("建 openh264 编码器失败")?;

            let chroma = (config.width / 2 * config.height / 2) as usize;
            Ok(Self {
                inner,
                config,
                u: vec![0; chroma],
                v: vec![0; chroma],
                frames: 0,
                keyframe_wanted: false,
                codec_string: None,
            })
        }

        pub fn request_keyframe(&mut self) {
            self.keyframe_wanted = true;
        }

        pub fn codec_string(&self) -> Option<String> {
            self.codec_string.clone()
        }

        pub fn encode(&mut self, frame: &Nv12Frame) -> Result<Vec<EncodedFrame>> {
            if frame.width != self.config.width || frame.height != self.config.height {
                bail!(
                    "帧尺寸 {}x{} 和编码器的 {}x{} 不符（采集分辨率变了，要重建编码器）",
                    frame.width,
                    frame.height,
                    self.config.width,
                    self.config.height
                );
            }
            // 第一帧本来就是 IDR。而且 openh264 要到第一次 encode 才真正初始化，
            // 之前调 force_intra_frame 是在对一个还没建好的编码器下指令
            if self.keyframe_wanted && self.frames > 0 {
                self.inner.force_intra_frame();
            }

            let (w, h) = (frame.width as usize, frame.height as usize);
            let (y, uv) = frame.data.split_at(w * h);
            deinterleave(uv, &mut self.u, &mut self.v);
            let source = YUVSlices::new((y, &self.u, &self.v), (w, h), (w, w / 2, w / 2));
            // 码率控制按时间戳算每帧的预算，乱给（比如全是 0）会让它算不出码率
            let timestamp = Timestamp::from_millis(self.frames * 1000 / self.config.fps as u64);
            self.frames += 1;

            let stream = self
                .inner
                .encode_at(&source, timestamp)
                .context("openh264 编码失败")?;
            let kind = stream.frame_type();
            // 被码率控制跳掉的帧没有输出。不是错误，下一帧照常
            if matches!(kind, FrameType::Skip | FrameType::Invalid) {
                return Ok(Vec::new());
            }
            let data = stream.to_vec();
            if data.is_empty() {
                return Ok(Vec::new());
            }

            let keyframe = kind == FrameType::IDR;
            if keyframe {
                self.keyframe_wanted = false;
            }
            if self.codec_string.is_none() {
                self.codec_string = codec_string_from_annexb(&data);
            }
            Ok(vec![EncodedFrame {
                data: Bytes::from(data),
                keyframe,
            }])
        }
    }

    /// NV12 的 UV 平面（U 在前）拆成 I420 的两个平面。
    fn deinterleave(uv: &[u8], u: &mut [u8], v: &mut [u8]) {
        let pairs = uv.as_chunks::<2>().0;
        for ((pair, u), v) in pairs.iter().zip(u.iter_mut()).zip(v.iter_mut()) {
            *u = pair[0];
            *v = pair[1];
        }
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        /// U、V 拆反了画面照样出得来，只是颜色整体偏掉（蓝的变红），编码器和解码器
        /// 都不会报错——这种错只有测试拦得住。
        #[test]
        fn 交织的_uv_拆成两个平面且_u_在前() {
            let uv = [10, 20, 11, 21, 12, 22];
            let (mut u, mut v) = ([0; 3], [0; 3]);
            deinterleave(&uv, &mut u, &mut v);
            assert_eq!(u, [10, 11, 12]);
            assert_eq!(v, [20, 21, 22]);
        }

        /// 编码器本身也过一遍：第一帧必须是带 SPS 的关键帧，codec 字符串得拼得出来。
        /// 这两样缺一个，浏览器那头就配不出解码器。
        #[test]
        fn 第一帧是带_sps_的关键帧() {
            let config = EncoderConfig {
                width: 64,
                height: 48,
                fps: 30,
                bitrate_kbps: 500,
            };
            let mut encoder = Encoder::new(config).unwrap();
            let mut frame = Nv12Frame::new(64, 48);
            frame.data.fill(128);
            let packets = encoder.encode(&frame).unwrap();
            assert!(packets.first().is_some_and(|p| p.keyframe));
            assert!(encoder.codec_string().is_some_and(|c| c.starts_with("avc1.")));
        }

        #[test]
        fn 奇数尺寸直接拒绝而不是_panic() {
            let config = EncoderConfig {
                width: 63,
                height: 48,
                fps: 30,
                bitrate_kbps: 500,
            };
            assert!(Encoder::new(config).is_err());
        }
    }
}

/// Media Foundation 编码。全部 unsafe COM 代码关在这里。
///
/// 硬件 MFT 是**异步**的：不能像同步 MFT 那样「推一帧、拉一次」，必须等
/// `METransformNeedInput` 才推、等 `METransformHaveOutput` 才拉，否则 `ProcessOutput`
/// 直接报 `E_UNEXPECTED`（0x8000FFFF「灾难性故障」，一个毫无指向性的错误码）。
/// 但调用方没必要知道这些：采集循环本来就是一帧一帧同步走的，所以 `encode()` 内部
/// 把事件循环跑完再返回这一帧产生的包。
#[cfg(windows)]
mod windows_encoder {
    use std::sync::Once;
    use std::time::{Duration, Instant};

    use anyhow::{Context, Result, bail};
    use bytes::Bytes;
    use windows::Win32::Foundation::VARIANT_BOOL;
    use windows::Win32::Media::MediaFoundation::*;
    use windows::Win32::System::Com::CoTaskMemFree;
    use windows::Win32::System::Variant::{VARIANT, VT_BOOL, VT_UI4};
    use windows::core::{GUID, Interface};

    use super::{EncodedFrame, EncoderConfig, codec_string_from_annexb, for_each_nal};
    use crate::nv12::Nv12Frame;

    /// 等编码器要下一帧最多等这么久。给得比一帧（33ms）宽是为了容忍偶尔的抖动，
    /// 但不能没有上限——编码器卡住时挂死比报错难查得多。
    const INPUT_WAIT: Duration = Duration::from_millis(500);
    /// 推完一帧之后等它的输出最多等这么久。
    ///
    /// **必须等**。不等的话这一帧的包要到下一次 `encode()` 才收得到，凭空多一帧
    /// 33ms——而省这 33ms 正是整件事的重点。实测低延迟模式下一帧进一帧出，所以
    /// 等一个输出是对的；真等不到也不当错误，下一轮自然会收到。
    const OUTPUT_WAIT: Duration = Duration::from_millis(100);
    /// 轮询事件的间隔。不用阻塞式 `GetEvent`：事件永远不来时会挂死。
    const POLL: Duration = Duration::from_micros(200);
    /// GOP 开到 10 秒。新人进场靠强制 IDR 解决，不靠短 GOP——短 GOP 是拿常态码率
    /// 换偶发事件，不值。
    const GOP_SECONDS: u32 = 10;

    static MF_INIT: Once = Once::new();

    pub struct Encoder {
        transform: IMFTransform,
        codec: ICodecAPI,
        events: Option<IMFMediaEventGenerator>,
        config: EncoderConfig,
        /// 下一次 `encode()` 之前给编码器下强制 IDR 的指令
        keyframe_wanted: bool,
        /// 从第一个 SPS 拼出来之后就不变了
        codec_string: Option<String>,
        /// 样本时间戳，100ns 为单位。编码器靠它算码率，乱给会让 CBR 失准。
        next_timestamp: i64,
    }

    // COM 接口指针本身不是 Send，但这个编码器全程只在 `spawn_blocking` 出来的
    // 那一个线程上用（建、喂、丢都在同一个线程），所以跨 await 移动它是安全的。
    // 之所以要这句：`video.rs` 里编码任务的 future 得是 Send。
    unsafe impl Send for Encoder {}

    impl Encoder {
        pub fn new(config: EncoderConfig) -> Result<Self> {
            if config.width == 0 || config.height == 0 || config.fps == 0 {
                bail!("编码参数不合法：{}x{} @{}fps", config.width, config.height, config.fps);
            }
            // MFStartup 是进程级的，多开几个编码器不能各调一次；对应的 MFShutdown
            // 也就不能在 Drop 里调——那会把别的编码器一起关掉。
            let mut startup: Result<()> = Ok(());
            MF_INIT.call_once(|| {
                startup = unsafe { MFStartup(MF_VERSION, MFSTARTUP_FULL) }.context("MFStartup");
            });
            startup?;

            let transform = create_encoder()?;
            let events = unlock_if_async(&transform)?;
            // 顺序要紧：必须**先设输出类型再设输入类型**。反了的话 SetInputType 会以
            // MF_E_TRANSFORM_TYPE_NOT_SET 失败，而那个错误码完全看不出是顺序问题。
            set_output_type(&transform, &config)?;
            set_input_type(&transform, &config)?;

            let codec: ICodecAPI = transform.cast().context("拿不到 ICodecAPI")?;
            configure_low_latency(&codec, &config);

            unsafe {
                transform
                    .ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0)
                    .context("NOTIFY_BEGIN_STREAMING")?;
                transform
                    .ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0)
                    .context("NOTIFY_START_OF_STREAM")?;
            }

            Ok(Self {
                transform,
                codec,
                events,
                config,
                // 第一帧必须是关键帧，否则第一个观看者什么都画不出来
                keyframe_wanted: true,
                codec_string: None,
                next_timestamp: 0,
            })
        }

        /// 下一帧编成 IDR。新观看者进场、或者有人落后需要重新同步时调。
        pub fn request_keyframe(&mut self) {
            self.keyframe_wanted = true;
        }

        pub fn codec_string(&self) -> Option<String> {
            self.codec_string.clone()
        }

        pub fn encode(&mut self, frame: &Nv12Frame) -> Result<Vec<EncodedFrame>> {
            if frame.width != self.config.width || frame.height != self.config.height {
                bail!(
                    "帧尺寸 {}x{} 和编码器的 {}x{} 不符（采集分辨率变了，要重建编码器）",
                    frame.width,
                    frame.height,
                    self.config.width,
                    self.config.height
                );
            }
            if self.keyframe_wanted {
                // 强制 IDR 是对「下一帧」生效的，所以必须在推帧之前设
                set_u32(&self.codec, &CODECAPI_AVEncVideoForceKeyFrame, 1);
                self.keyframe_wanted = false;
            }

            let mut packets = Vec::new();
            if self.events.is_some() {
                self.encode_async(frame, &mut packets)?;
            } else {
                self.encode_sync(frame, &mut packets)?;
            }
            for packet in &packets {
                if self.codec_string.is_none() {
                    self.codec_string = codec_string_from_annexb(&packet.data);
                }
            }
            Ok(packets)
        }

        fn encode_sync(&mut self, frame: &Nv12Frame, out: &mut Vec<EncodedFrame>) -> Result<()> {
            self.push(frame)?;
            while let Some(packet) = pull_output(&self.transform)? {
                out.push(packet);
            }
            Ok(())
        }

        fn encode_async(&mut self, frame: &Nv12Frame, out: &mut Vec<EncodedFrame>) -> Result<()> {
            // 等它要，才能给。这一段就是把异步关在里面的全部内容。
            let deadline = Instant::now() + INPUT_WAIT;
            loop {
                if Instant::now() > deadline {
                    bail!("等编码器要下一帧等了 {:?} 没等到", INPUT_WAIT);
                }
                match self.next_event()? {
                    None => std::thread::sleep(POLL),
                    Some(kind) if kind == METransformNeedInput => {
                        self.push(frame)?;
                        break;
                    }
                    Some(kind) if kind == METransformHaveOutput => {
                        if let Some(packet) = pull_output(&self.transform)? {
                            out.push(packet);
                        }
                    }
                    Some(_) => {}
                }
            }

            // 等这一帧的输出，等到了就把顺带就绪的也一起收掉
            let deadline = Instant::now() + OUTPUT_WAIT;
            let want = out.len() + 1;
            while out.len() < want {
                if Instant::now() > deadline {
                    // 这一帧没产出。不是错误——包会在下一轮收到。
                    break;
                }
                match self.next_event()? {
                    None => std::thread::sleep(POLL),
                    Some(kind) if kind == METransformHaveOutput => {
                        if let Some(packet) = pull_output(&self.transform)? {
                            out.push(packet);
                        }
                    }
                    Some(_) => {}
                }
            }
            Ok(())
        }

        /// 用 if/match-guard 而不是直接拿常量当模式：`METransformNeedInput` 这些名字
        /// 不是大写，当模式时万一没解析成常量就会变成「绑定任意值」的分支，静默吃掉
        /// 所有事件，而且不会有任何报错。
        fn next_event(&self) -> Result<Option<MF_EVENT_TYPE>> {
            let Some(events) = &self.events else {
                return Ok(None);
            };
            match unsafe { events.GetEvent(MF_EVENT_FLAG_NO_WAIT) } {
                Ok(event) => {
                    let kind = unsafe { event.GetType() }.context("event.GetType")?;
                    Ok(Some(MF_EVENT_TYPE(kind as i32)))
                }
                Err(e) if e.code() == MF_E_NO_EVENTS_AVAILABLE => Ok(None),
                Err(e) => Err(e).context("GetEvent"),
            }
        }

        fn push(&mut self, frame: &Nv12Frame) -> Result<()> {
            let per_frame = 10_000_000i64 / self.config.fps as i64;
            push_frame(&self.transform, &frame.data, self.next_timestamp, per_frame)?;
            self.next_timestamp += per_frame;
            Ok(())
        }
    }

    impl Drop for Encoder {
        fn drop(&mut self) {
            // 告诉编码器别再等输入了，好让它放掉占着的硬件资源。攒在里面的包不去
            // 收——这时候没人要了。
            //
            // 不调 MFShutdown：它是进程级的，会把别的编码器一起关掉。
            unsafe {
                let _ = self.transform.ProcessMessage(MFT_MESSAGE_NOTIFY_END_OF_STREAM, 0);
                let _ = self.transform.ProcessMessage(MFT_MESSAGE_COMMAND_DRAIN, 0);
                let _ = self.transform.ProcessMessage(MFT_MESSAGE_NOTIFY_END_STREAMING, 0);
            }
        }
    }

    /// 列出所有能输出 H.264 的编码器 MFT 的名字。
    ///
    /// 只有 `probe-h264` 用（它靠 `#[path]` 把这个文件包进去，所以对服务端那次编译
    /// 来说是死代码，但它并不是真的没人用）。
    #[allow(dead_code)]
    pub fn list_encoders() -> Result<Vec<String>> {
        with_activates(|activates, count| {
            let mut names = Vec::new();
            for i in 0..count as usize {
                if let Some(activate) = unsafe { &*activates.add(i) } {
                    names.push(friendly_name(activate));
                }
            }
            Ok(names)
        })
    }

    /// `MFTEnumEx` 的结果要 `CoTaskMemFree`，而中间任何一步出错都不能漏掉那一下，
    /// 所以统一从这里进出。
    fn with_activates<T>(
        f: impl FnOnce(*mut Option<IMFActivate>, u32) -> Result<T>,
    ) -> Result<T> {
        let output = MFT_REGISTER_TYPE_INFO {
            guidMajorType: MFMediaType_Video,
            guidSubtype: MFVideoFormat_H264,
        };
        // 硬件和软件都要：硬件的排前面（SORTANDFILTER），但软件的是兜底。之前
        // 按注册表推断「没有软件编码器」是错的，枚举才靠得住。
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
        let outcome = f(activates, count);
        unsafe { CoTaskMemFree(Some(activates as *const _)) };
        outcome
    }

    /// 同上，只有探针在用。
    #[allow(dead_code)]
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
        let name =
            unsafe { buffer.to_string() }.unwrap_or_else(|_| "（名字不是合法 UTF-16）".into());
        unsafe { CoTaskMemFree(Some(buffer.as_ptr() as *const _)) };
        name
    }

    fn create_encoder() -> Result<IMFTransform> {
        with_activates(|activates, count| {
            if count == 0 {
                bail!("没有可用的 H.264 编码器 MFT");
            }
            // 取第一个：MFTEnumEx 已按硬件优先排过序
            let activate = unsafe { &*activates }
                .clone()
                .context("第一个 activate 是空的")?;
            unsafe { activate.ActivateObject::<IMFTransform>() }
                .context("ActivateObject 失败（编码器注册着但开不起来）")
        })
    }

    /// 异步 MFT 默认锁着，不解锁的话 `ProcessInput` 一上来就
    /// `MF_E_TRANSFORM_ASYNC_LOCKED`——那个错误码看不出「要先声明自己懂异步」
    /// 这层意思。
    ///
    /// 返回事件生成器；返回 `None` 表示这个 MFT 是同步的。
    fn unlock_if_async(transform: &IMFTransform) -> Result<Option<IMFMediaEventGenerator>> {
        // 没有属性存储的 MFT 一定是同步的
        let Ok(attributes) = (unsafe { transform.GetAttributes() }) else {
            return Ok(None);
        };
        if unsafe { attributes.GetUINT32(&MF_TRANSFORM_ASYNC) }.unwrap_or(0) != 1 {
            return Ok(None);
        }
        unsafe { attributes.SetUINT32(&MF_TRANSFORM_ASYNC_UNLOCK, 1) }
            .context("设 MF_TRANSFORM_ASYNC_UNLOCK")?;
        Ok(Some(transform.cast().context("异步 MFT 却拿不到事件生成器")?))
    }

    /// `MF_MT_FRAME_SIZE` / `FRAME_RATE` 都是把两个 u32 打进一个 u64，高位在前。
    fn pack(high: u32, low: u32) -> u64 {
        ((high as u64) << 32) | low as u64
    }

    fn set_output_type(transform: &IMFTransform, config: &EncoderConfig) -> Result<()> {
        let media_type = unsafe { MFCreateMediaType() }.context("MFCreateMediaType")?;
        unsafe {
            media_type.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Video)?;
            media_type.SetGUID(&MF_MT_SUBTYPE, &MFVideoFormat_H264)?;
            media_type.SetUINT32(&MF_MT_AVG_BITRATE, config.bitrate_kbps * 1000)?;
            media_type.SetUINT64(&MF_MT_FRAME_SIZE, pack(config.width, config.height))?;
            media_type.SetUINT64(&MF_MT_FRAME_RATE, pack(config.fps, 1))?;
            media_type.SetUINT32(&MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive.0 as u32)?;
            // Main 比 Baseline 压得好，而 B 帧是另一个开关（下面关掉）
            media_type.SetUINT32(&MF_MT_MPEG2_PROFILE, eAVEncH264VProfile_Main.0 as u32)?;
            transform
                .SetOutputType(0, &media_type, 0)
                .context("SetOutputType")?;
        }
        Ok(())
    }

    fn set_input_type(transform: &IMFTransform, config: &EncoderConfig) -> Result<()> {
        let media_type = unsafe { MFCreateMediaType() }.context("MFCreateMediaType")?;
        unsafe {
            media_type.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Video)?;
            media_type.SetGUID(&MF_MT_SUBTYPE, &MFVideoFormat_NV12)?;
            media_type.SetUINT64(&MF_MT_FRAME_SIZE, pack(config.width, config.height))?;
            media_type.SetUINT64(&MF_MT_FRAME_RATE, pack(config.fps, 1))?;
            media_type.SetUINT32(&MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive.0 as u32)?;
            // 采集卡的 JPEG 是满幅（实测彩条 Y 跑到 255 和 7，不是 235/16）。不说明
            // 的话编码器按 studio swing 解释、解码端再展开一次——表现是黑的更黑白的
            // 更白、颜色发飘，而这种毛病很难指着说出哪里不对。
            media_type.SetUINT32(&MF_MT_VIDEO_NOMINAL_RANGE, MFNominalRange_0_255.0 as u32)?;
            transform
                .SetInputType(0, &media_type, 0)
                .context("SetInputType（若报 TYPE_NOT_SET，是输出类型没先设好）")?;
        }
        Ok(())
    }

    /// 延迟三处必须设对，漏一个这功能就白做。详见设计文档。
    ///
    /// 这些是**尽力而为**：驱动不认某一项时只是少一点优化，不该让整个编码器开不
    /// 起来。`set_u32` / `set_bool` 里失败只记日志。
    fn configure_low_latency(codec: &ICodecAPI, config: &EncoderConfig) {
        // 1. 不开的话编码器先攒几帧再吐第一帧，凭空多 50-100ms
        set_bool(codec, &CODECAPI_AVLowLatencyMode, true);
        // 2. B 帧要等后续帧才能编，是结构性延迟，对 KVM 纯亏
        set_u32(codec, &CODECAPI_AVEncMPVDefaultBPictureCount, 0);
        // 3. 码率控制：**恒定质量 + 硬上限**，不是 CBR。
        //
        //    这一条起初写的是 CBR，理由是「质量优先的 VBR 一变就冲到几十 Mbps，
        //    炸掉 WAN 预算」。实测发现 CBR 有个更糟的毛病：**它会把码率填满**。
        //    对着完全静止的画面也照样跑 3.93 Mbps，而「不动的桌面几乎不要钱」正是
        //    这个功能最大的收益（KVM 的画面大部分时间不动）。
        //
        //    而那个「冲到几十 Mbps」的顾虑，正解本来就是 `MaxBitRate` 这个上限，
        //    不是 CBR。两者一起给：质量恒定、静止趋近于零、动起来有天花板。
        set_u32(
            codec,
            &CODECAPI_AVEncCommonRateControlMode,
            eAVEncCommonRateControlMode_Quality.0 as u32,
        );
        // 0-100。70 对文字足够清楚，再往上收益很小而码率涨得快。
        set_u32(codec, &CODECAPI_AVEncCommonQuality, 70);
        set_u32(codec, &CODECAPI_AVEncCommonMaxBitRate, config.bitrate_kbps * 1000);
        // 有些编码器只看 Mean 不看 Max，两个都给上
        set_u32(codec, &CODECAPI_AVEncCommonMeanBitRate, config.bitrate_kbps * 1000);
        set_u32(codec, &CODECAPI_AVEncMPVGOPSize, config.fps * GOP_SECONDS);
    }

    fn set_bool(codec: &ICodecAPI, key: &GUID, value: bool) {
        let mut variant = VARIANT::default();
        unsafe {
            let inner = &mut variant.Anonymous.Anonymous;
            inner.vt = VT_BOOL;
            // VARIANT_BOOL 的真是 -1 而不是 1，这是 OLE 的老规矩
            inner.Anonymous.boolVal = VARIANT_BOOL(if value { -1 } else { 0 });
        }
        apply(codec, key, &variant);
    }

    fn set_u32(codec: &ICodecAPI, key: &GUID, value: u32) {
        let mut variant = VARIANT::default();
        unsafe {
            let inner = &mut variant.Anonymous.Anonymous;
            inner.vt = VT_UI4;
            inner.Anonymous.ulVal = value;
        }
        apply(codec, key, &variant);
    }

    fn apply(codec: &ICodecAPI, key: &GUID, variant: &VARIANT) {
        if let Err(e) = unsafe { codec.SetValue(key, variant) } {
            tracing::warn!("编码器参数 {key:?} 没设上: {e}");
        }
    }

    fn push_frame(
        transform: &IMFTransform,
        nv12: &[u8],
        timestamp: i64,
        duration: i64,
    ) -> Result<()> {
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
            sample.SetSampleTime(timestamp)?;
            sample.SetSampleDuration(duration)?;
            transform
                .ProcessInput(0, &sample, 0)
                .context("ProcessInput（若报 ASYNC_LOCKED，是没解锁异步 MFT）")?;
        }
        Ok(())
    }

    fn pull_output(transform: &IMFTransform) -> Result<Option<EncodedFrame>> {
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

        // 无论成败都要把样本取回来释放，否则每次调用漏一个 COM 引用
        let sample = unsafe { std::mem::ManuallyDrop::take(&mut output[0].pSample) };
        let _ = unsafe { std::mem::ManuallyDrop::take(&mut output[0].pEvents) };
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

        // CleanPoint 是编码器自己说的，比我们扫 NAL 类型可靠。拿不到（有些 MFT
        // 不设这个属性）再退回去扫。
        let clean_point = unsafe { sample.GetUINT32(&MFSampleExtension_CleanPoint) }.ok();

        let buffer = unsafe { sample.ConvertToContiguousBuffer() }?;
        let mut data = std::ptr::null_mut();
        let mut len = 0u32;
        unsafe { buffer.Lock(&mut data, None, Some(&mut len)) }?;
        let bytes = unsafe { std::slice::from_raw_parts(data, len as usize) }.to_vec();
        unsafe { buffer.Unlock() }?;

        let keyframe = match clean_point {
            Some(flag) => flag == 1,
            None => has_keyframe_nal(&bytes),
        };
        Ok(Some(EncodedFrame {
            data: Bytes::from(bytes),
            keyframe,
        }))
    }

    /// 5 = IDR 片，7 = SPS（编码器在每个 IDR 前重复 SPS/PPS）。
    fn has_keyframe_nal(packet: &[u8]) -> bool {
        let mut found = false;
        for_each_nal(packet, |kind, _| {
            if kind == 5 || kind == 7 {
                found = true;
            }
            found
        });
        found
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn idr_片算关键帧非_idr_不算() {
            assert!(has_keyframe_nal(&[0, 0, 0, 1, 0x65, 0x88, 0x00, 0x11]));
            assert!(!has_keyframe_nal(&[0, 0, 0, 1, 0x41, 0x9A, 0x00, 0x11]));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// codec 字符串必须从 SPS 的三个字节拼出来，不能写死——编码器实际协商出别的
    /// profile 时，写死的字符串会让浏览器静默解码失败，那种失败极难查。
    #[test]
    fn 从_sps_拼出_codec_字符串() {
        // 起始码 + NAL 头(0x67 = SPS) + profile_idc, constraints, level_idc
        let packet = [0u8, 0, 0, 1, 0x67, 0x4D, 0x40, 0x1E, 0xAB, 0xCD];
        assert_eq!(codec_string_from_annexb(&packet).as_deref(), Some("avc1.4D401E"));
    }

    #[test]
    fn 没有_sps_时给不出_codec_字符串() {
        // 0x41 = 非 IDR 片，不是 SPS
        let packet = [0u8, 0, 0, 1, 0x41, 0x9A, 0x00, 0x11];
        assert_eq!(codec_string_from_annexb(&packet), None);
    }

    /// 三字节起始码也要认：编码器两种都可能吐。
    #[test]
    fn 认三字节起始码() {
        let packet = [0u8, 0, 1, 0x67, 0x42, 0xE0, 0x1E, 0x00];
        assert_eq!(codec_string_from_annexb(&packet).as_deref(), Some("avc1.42E01E"));
    }

    /// 实测 AMD 编码器的首包是「AUD 在前、SPS 在后」，所以不能只看第一个 NAL。
    #[test]
    fn 跳过前面的_aud_找到后面的_sps() {
        let packet = [
            0u8, 0, 0, 1, 0x09, 0x10, // AUD
            0, 0, 0, 1, 0x67, 0x4D, 0x04, 0x28, 0xD9, 0x40, // SPS
        ];
        assert_eq!(codec_string_from_annexb(&packet).as_deref(), Some("avc1.4D0428"));
    }
}
