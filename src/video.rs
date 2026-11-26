//! 采集卡到 WebSocket 之间的那一段：一个独占设备的采集线程，把采集卡直出的
//! JPEG 原样广播给所有观看者。服务端一次都不解码。
//!
//! 设备是独占的，所以采集按需启停：有人看才开，没人看了再多采一会儿就关掉，
//! 好让别的程序（或者本地的 chrome-kvm）能用这张卡。

use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, mpsc};
use std::time::{Duration, Instant};

use anyhow::{Context, Result, bail};
use bytes::Bytes;
use nokhwa::pixel_format::RgbFormat;
use nokhwa::Camera;
use nokhwa::utils::{
    CameraFormat, CameraIndex, FrameFormat, RequestedFormat, RequestedFormatType, Resolution,
};
use tokio::sync::{Notify, broadcast, watch};
use tracing::{debug, info, warn};

use crate::config::VideoSettings;
use crate::h264::{EncodedFrame, Encoder, EncoderConfig};
use crate::nv12::{Nv12Frame, decode_jpeg_to_nv12};

/// 只缓存最近几帧。KVM 要的是最新画面，落后的客户端应该丢帧而不是拖慢别人，
/// 所以这个值故意开得很小——broadcast 的 Lagged 正好就是「丢掉旧帧」的语义。
const CHANNEL_CAPACITY: usize = 4;

/// H.264 那路的容量大得多。两个原因：帧小得多，32 帧也才几百 KB；更重要的是这边
/// `Lagged` 的代价高——丢一个 P 帧会让后面整个 GOP 全烂到下一个 IDR，所以要尽量
/// 别发生。
const H264_CHANNEL_CAPACITY: usize = 32;

/// 等编码器报出 codec 字符串最多等这么久。等不到就回落 MJPEG。
pub const CODEC_TIMEOUT: Duration = Duration::from_secs(5);

/// 最后一个观看者离开后再多采这么久。刷新页面时连接会断开重连，留个缓冲
/// 就不会把设备反复开关（MS2109 重新打开要一秒左右）。
const IDLE_GRACE: Duration = Duration::from_secs(3);

/// 编码线程连续这么多帧解不开 JPEG 才算真坏、回落 MJPEG。偶发一帧坏的（MS2109
/// 刚开流时就会吐几帧）跳过就行，不值得把整条 H.264 路掐掉。约一秒的量。
const MAX_BAD_JPEGS: u32 = 30;

const EMPTY_JPEG: [u8; 4] = [0xFF, 0xD8, 0xFF, 0xD9];

/// 采集循环为什么停下来。两者的善后完全不同：没人看就安静退出，
/// 换了配置得立刻按新设备重开，不能走「出错后等一秒」那条退避路径。
enum StopReason {
    Idle,
    Reconfigured,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum VideoCodec {
    /// 默认：日常是局域网，低带宽是出门时才开的
    #[default]
    Mjpeg,
    H264,
}

impl VideoCodec {
    /// 认不出来的一律当 mjpeg。手改配置写错一个字不该让画面彻底不出来，
    /// 而 mjpeg 是那个「总能用」的选择。
    fn parse(text: &str) -> Self {
        if text.eq_ignore_ascii_case("h264") {
            Self::H264
        } else {
            Self::Mjpeg
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Self::Mjpeg => "mjpeg",
            Self::H264 => "h264",
        }
    }
}

/// `/ws/video` 连上时要告诉客户端的东西。
///
/// 为什么 `avc_codec` 要单独等：配置说了 h264 不等于现在就能发 h264——客户端的
/// `VideoDecoder.configure` 需要 `avc1.XXXXXX`，而那串东西要等编码器真的吐出第一个
/// SPS 才知道。所以这里的三个字段合起来表达「现在到底能发什么」。
#[derive(Clone, Debug, Default)]
pub struct CodecState {
    /// 配置要的
    pub wanted: VideoCodec,
    /// 编码器就绪后才有
    pub avc_codec: Option<String>,
    /// 编码器起不来的原因。**要带给客户端显示**——静默降级比报错更难查。
    pub fallback_reason: Option<String>,
}

#[derive(Clone, Debug, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct VideoStats {
    pub capturing: bool,
    pub viewers: usize,
    /// 自己按帧间隔算的，不能用 nokhwa 报的 camera_format().frame_rate()——
    /// 那个字段在 MS2109 上报的是 1fps，和实测的 29.5fps 完全不符。
    pub fps: f32,
    /// 采集卡吐出来的码率。**这不是上线的流量**：静止画面下 MJPEG 的重复帧不会
    /// 转发、H.264 每帧只有几十字节，实际发出去的看 `sent_mbps`。
    pub mbps: f32,
    /// 真正写进 WebSocket 的码率，所有观看者之和。
    pub sent_mbps: f32,
    pub frames: u64,
    pub last_frame_bytes: usize,
    pub resolution: Option<(u32, u32)>,
    pub error: Option<String>,
}

#[derive(Default)]
struct StatsInner {
    capturing: bool,
    frames: u64,
    last_frame_bytes: usize,
    resolution: Option<(u32, u32)>,
    window: Option<Window>,
    fps: f32,
    mbps: f32,
    sent_mbps: f32,
    error: Option<String>,
}

struct Window {
    started: Instant,
    frames: u32,
    bytes: u64,
    /// 开窗时 `VideoHub::sent` 的读数，用来算这一秒发出去了多少
    sent_at_start: u64,
}

pub struct VideoHub {
    config: Mutex<VideoSettings>,
    /// 设置页保存后置位，采集循环看到就收摊重开。
    reconfigured: AtomicBool,
    frames: broadcast::Sender<Bytes>,
    wake: Notify,
    capturing: AtomicBool,
    stats: Mutex<StatsInner>,
    /// 累计写进 WebSocket 的字节。由各条连接的推流循环累加——去重和 H.264 都在
    /// 采集之后，只有这里数得到真实出口流量。
    sent: AtomicU64,

    /// 编码后的帧。没有 h264 订阅者时编码任务根本不启动。
    encoded: broadcast::Sender<EncodedFrame>,
    encode_wake: Notify,
    encoding: AtomicBool,
    /// 下一帧强制 IDR。新订阅者进场、或者有人 `Lagged` 需要重新同步时置位。
    keyframe_wanted: AtomicBool,
    codec: watch::Sender<CodecState>,
    /// 采集和「实测帧率」都要独占设备。采集持有整段循环；实测只在没人采集时拿得到。
    device: Mutex<()>,
}

impl VideoHub {
    pub fn new(config: VideoSettings) -> Arc<Self> {
        let (frames, _) = broadcast::channel(CHANNEL_CAPACITY);
        let (encoded, _) = broadcast::channel(H264_CHANNEL_CAPACITY);
        let wanted = VideoCodec::parse(&config.codec);
        let hub = Arc::new(Self {
            config: Mutex::new(config),
            reconfigured: AtomicBool::new(false),
            frames,
            wake: Notify::new(),
            capturing: AtomicBool::new(false),
            stats: Mutex::new(StatsInner::default()),
            sent: AtomicU64::new(0),
            encoded,
            encode_wake: Notify::new(),
            encoding: AtomicBool::new(false),
            keyframe_wanted: AtomicBool::new(false),
            codec: watch::channel(CodecState {
                wanted,
                ..Default::default()
            })
            .0,
            device: Mutex::new(()),
        });
        tokio::spawn(supervise(hub.clone()));
        tokio::spawn(supervise_encoder(hub.clone()));
        hub
    }

    /// 配置真的变了才动采集：设置页保存时不关心哪几项改了，会把整份配置推过来，
    /// 而只改了串口的一次保存不该把画面掐断一下。
    pub fn set_config(&self, config: VideoSettings) {
        {
            let mut current = self.config.lock().expect("config 锁");
            if *current == config {
                return;
            }
            *current = config;
        }
        info!("视频配置已更新，重开采集");
        // 编码参数（编码方式、码率、分辨率）也可能变了。把 codec 状态重置成「配置
        // 说的那个、还不知道 avc 字符串」，让下一个连接重新等编码器就绪；已连着的
        // 连接会收到 watch 变更并重发一条头。
        let wanted = VideoCodec::parse(&self.config.lock().expect("config 锁").codec);
        self.codec.send_replace(CodecState {
            wanted,
            ..Default::default()
        });
        self.reconfigured.store(true, Ordering::SeqCst);
        self.wake.notify_one();
        self.encode_wake.notify_one();
    }

    /// 订阅编码后的帧。
    ///
    /// 顺带强制一个 IDR：新观看者拿不到关键帧就什么都画不出来，而 GOP 是 10 秒，
    /// 等下一个自然关键帧最坏要黑屏十秒。
    pub fn subscribe_h264(&self) -> broadcast::Receiver<EncodedFrame> {
        // 同时订阅 JPEG 那路：采集靠 frames 的 receiver_count 判断还有没有人看，
        // 只订阅 encoded 的话采集根本不会开。这个 receiver 交给编码循环持有。
        let rx = self.encoded.subscribe();
        self.request_keyframe();
        self.encode_wake.notify_one();
        rx
    }

    pub fn watch_codec(&self) -> watch::Receiver<CodecState> {
        self.codec.subscribe()
    }

    pub fn codec_state(&self) -> CodecState {
        self.codec.borrow().clone()
    }

    pub fn request_keyframe(&self) {
        self.keyframe_wanted.store(true, Ordering::SeqCst);
    }

    /// 先订阅再唤醒采集：采集线程靠 receiver_count 判断还有没有人看，
    /// 顺序反了会开起来又立刻退出。
    pub fn subscribe(&self) -> broadcast::Receiver<Bytes> {
        let rx = self.frames.subscribe();
        self.wake.notify_one();
        rx
    }

    pub fn stats(&self) -> VideoStats {
        let inner = self.stats.lock().expect("stats 锁");
        VideoStats {
            capturing: inner.capturing,
            viewers: self.frames.receiver_count(),
            fps: inner.fps,
            mbps: inner.mbps,
            sent_mbps: inner.sent_mbps,
            frames: inner.frames,
            last_frame_bytes: inner.last_frame_bytes,
            resolution: inner.resolution,
            error: inner.error.clone(),
        }
    }

    fn mark_capturing(&self, on: bool, resolution: Option<(u32, u32)>) {
        let mut inner = self.stats.lock().expect("stats 锁");
        inner.capturing = on;
        if on {
            inner.error = None;
            inner.resolution = resolution;
        } else {
            // 采集停了，窗口不会再翻页，码率得手动清零，否则会一直停在最后一个读数
            inner.fps = 0.0;
            inner.mbps = 0.0;
            inner.sent_mbps = 0.0;
        }
    }

    /// 编码器开不起来或者中途死了。
    ///
    /// 不静默退回 MJPEG——原因要带给客户端显示。静默降级的话，人只会觉得「省带宽
    /// 那个开关没用」，而不知道为什么，这是项目里处理设备错误一贯的做法。
    fn record_fallback(&self, message: String) {
        self.codec.send_modify(|state| {
            state.avc_codec = None;
            state.fallback_reason = Some(message);
        });
    }

    fn record_error(&self, message: String) {
        let mut inner = self.stats.lock().expect("stats 锁");
        inner.capturing = false;
        inner.fps = 0.0;
        inner.mbps = 0.0;
        inner.sent_mbps = 0.0;
        inner.error = Some(message);
    }

    fn record_frame(&self, len: usize) {
        let mut inner = self.stats.lock().expect("stats 锁");
        inner.frames += 1;
        inner.last_frame_bytes = len;

        // 出口字节由各条连接自己累加，这里只在开窗和收窗时各读一次差值。借采集
        // 的节奏来分窗：采集一直在跑，哪怕一帧都没往外发，窗口也会照常翻页。
        let sent_now = self.sent.load(Ordering::Relaxed);
        let window = inner.window.get_or_insert_with(|| Window {
            started: Instant::now(),
            frames: 0,
            bytes: 0,
            sent_at_start: sent_now,
        });
        window.frames += 1;
        window.bytes += len as u64;

        // 先把统计值取出来，window 的借用到这里就结束了，下面才能改 inner
        let elapsed = window.started.elapsed();
        let frames = window.frames;
        let bytes = window.bytes;
        let sent = sent_now.saturating_sub(window.sent_at_start);

        if elapsed >= Duration::from_secs(1) {
            let secs = elapsed.as_secs_f32();
            inner.fps = frames as f32 / secs;
            inner.mbps = bytes as f32 * 8.0 / secs / 1_000_000.0;
            inner.sent_mbps = sent as f32 * 8.0 / secs / 1_000_000.0;
            inner.window = None;
        }
    }

    /// 推流循环每发出一块数据就报一次。
    pub fn record_sent(&self, len: usize) {
        self.sent.fetch_add(len as u64, Ordering::Relaxed);
    }
}

/// 守着「有人看就开采集」这件事。采集本身是阻塞的，跑在 blocking 线程上。
async fn supervise(hub: Arc<VideoHub>) {
    loop {
        hub.wake.notified().await;
        if hub.frames.receiver_count() == 0 || hub.capturing.swap(true, Ordering::SeqCst) {
            continue;
        }

        let worker = hub.clone();
        let outcome = tokio::task::spawn_blocking(move || capture_loop(&worker)).await;
        hub.capturing.store(false, Ordering::SeqCst);

        match outcome {
            Ok(Ok(StopReason::Idle)) => info!("采集已停止（没有观看者）"),
            Ok(Ok(StopReason::Reconfigured)) => {
                // 不走下面的退避：这不是故障，是用户刚点了保存，等一秒很难看
                hub.wake.notify_one();
                continue;
            }
            Ok(Err(e)) => {
                warn!("采集出错: {e:#}");
                hub.record_error(format!("{e:#}"));
            }
            Err(e) => {
                warn!("采集线程异常退出: {e}");
                hub.record_error(format!("采集线程异常退出: {e}"));
            }
        }

        // 出错时别立刻重试，否则设备拔掉之后会疯转
        if hub.frames.receiver_count() > 0 {
            tokio::time::sleep(Duration::from_secs(1)).await;
            hub.wake.notify_one();
        }
    }
}

/// 守着「有 h264 观看者就开编码」。结构和 `supervise` 一样，因为规矩一样：
/// 有人要才开，最后一个走掉就停。
async fn supervise_encoder(hub: Arc<VideoHub>) {
    loop {
        hub.encode_wake.notified().await;
        if hub.encoded.receiver_count() == 0 || hub.encoding.swap(true, Ordering::SeqCst) {
            continue;
        }

        let worker = hub.clone();
        let outcome = tokio::task::spawn_blocking(move || encode_loop(&worker)).await;
        hub.encoding.store(false, Ordering::SeqCst);
        // 编码器已经没了，那份 avc 字符串就不再代表「现在能发 h264」。留着的话
        // 下一个连接会据此宣称 h264，然后一帧都收不到——比报错难查得多。
        // 出错那两条路由 `record_fallback` 清，这里管正常退出。
        hub.codec.send_if_modified(|state| {
            let stale = state.avc_codec.take();
            stale.is_some()
        });

        match outcome {
            Ok(Ok(())) => info!("编码已停止"),
            Ok(Err(e)) => {
                warn!("编码出错: {e:#}");
                hub.record_fallback(format!("{e:#}"));
            }
            Err(e) => {
                warn!("编码线程异常退出: {e}");
                hub.record_fallback(format!("编码线程异常退出: {e}"));
            }
        }

        // 和采集一样：出错别立刻重试，否则编码器开不起来时会疯转
        if hub.encoded.receiver_count() > 0 {
            tokio::time::sleep(Duration::from_secs(1)).await;
            hub.encode_wake.notify_one();
        }
    }
}

/// 解 JPEG、编 H.264、发出去。
///
/// 这个循环是 JPEG 广播的一个**普通订阅者**，并排挂在现有管线旁边——所以原来那条
/// MJPEG 路径一行都不用改，风险严格为零。
///
/// 解码和编码拆在两个线程上流水线跑。1080p 一帧解 JPEG 七到十毫秒、编码八到十毫秒，
/// 串着做是 18ms，而 60fps 的预算只有 16.7ms；拆开后吞吐只看慢的那一段。两段之间
/// 只有两块 NV12 缓冲来回倒手，交接是 rendezvous（容量 0），最多压一帧，不会攒出延迟。
///
/// 编码留在本线程（spawn_blocking 那个），不跨线程：Windows 的编码器是 COM 对象，
/// 挪不得。解码线程只碰 JPEG 和 NV12。
fn encode_loop(hub: &VideoHub) -> Result<()> {
    let config = hub.config.lock().expect("config 锁").clone();

    // 先订阅再唤醒采集，顺序和 `subscribe` 里一样的理由
    let frames = hub.subscribe();
    let (free_tx, free_rx) = mpsc::sync_channel(PIPELINE_BUFFERS);
    for _ in 0..PIPELINE_BUFFERS {
        free_tx
            .send(Nv12Frame::new(config.width, config.height))
            .expect("通道刚建好，容量够放");
    }
    let (full_tx, full_rx) = mpsc::sync_channel(0);
    let stopped = AtomicBool::new(false);
    let stopped = &stopped;

    std::thread::scope(|scope| {
        let decoder = std::thread::Builder::new()
            .name("jpeg-decode".into())
            .spawn_scoped(scope, move || {
                decode_frames(frames, config.fps, free_rx, full_tx, stopped)
            })
            .context("起解码线程失败")?;
        // 两条通道的这一头随 `encode_frames` 返回一起丢掉，解码线程下一次交接就会
        // 发现、自己退出
        let encoded = encode_frames(hub, &config, full_rx, free_tx);
        stopped.store(true, Ordering::SeqCst);
        let decoded = decoder
            .join()
            .unwrap_or_else(|_| Err(anyhow::anyhow!("解码线程 panic 了")));
        encoded.and(decoded)
    })
}

/// 两块就够流水线：解码线程填一块的时候，编码线程在编另一块。
const PIPELINE_BUFFERS: usize = 2;

/// 流水线的前半段：从 JPEG 广播收帧、按帧率节流、解成 NV12 交给编码线程。
fn decode_frames(
    mut frames: broadcast::Receiver<Bytes>,
    fps: u32,
    free: mpsc::Receiver<Nv12Frame>,
    full: mpsc::SyncSender<Nv12Frame>,
    stopped: &AtomicBool,
) -> Result<()> {
    let mut pacer = Pacer::new(fps);
    let mut bad_jpegs = 0;
    // 解坏了的那块缓冲留着下次用，不必还回去再取
    let mut spare: Option<Nv12Frame> = None;
    loop {
        let Some(jpeg) = newest_jpeg(&mut frames) else {
            return Ok(());
        };
        if stopped.load(Ordering::SeqCst) {
            return Ok(());
        }
        if !pacer.admit(Instant::now()) {
            continue;
        }
        let mut frame = match spare.take() {
            Some(frame) => frame,
            None => match free.recv() {
                Ok(frame) => frame,
                Err(_) => return Ok(()),
            },
        };
        if let Err(e) = decode_jpeg_to_nv12(&jpeg, &mut frame) {
            bad_jpegs += 1;
            if bad_jpegs >= MAX_BAD_JPEGS {
                return Err(e.context(format!("连续 {bad_jpegs} 帧 JPEG 解不开")));
            }
            debug!("跳过一帧解不开的 JPEG: {e:#}");
            spare = Some(frame);
            continue;
        }
        bad_jpegs = 0;
        if full.send(frame).is_err() {
            return Ok(());
        }
    }
}

/// 等一帧，再把已经排着的都跳过，只要最新那帧。
///
/// 编码偶尔慢一下时广播里会积几帧，按顺序一帧帧去追的话，画面就一直落后那么几帧
/// ——KVM 要的是现在的画面，不是把错过的补齐。
///
/// `Lagged` 是同一件事更严重的版本：积满了，最老的被挤掉。**这里不能补 IDR。** 丢的
/// 是*输入*，不是输出——编码器对拿到的帧依次编码，P 帧参照的是它上一次编过的那帧，
/// 和跳过多少采集帧无关，码流始终是完整的，下游照样解得出来，只是帧率低一些。曾经在
/// 这儿补 IDR，结果是个正反馈：IDR 大 → 编得更慢 → 落后更多 → 补更多 IDR。实测把
/// 30fps 压成了 2fps 的全 IDR 流。客户端丢包才需要 IDR，那是 `pump_h264` 的事。
fn newest_jpeg(frames: &mut broadcast::Receiver<Bytes>) -> Option<Bytes> {
    let mut jpeg = loop {
        match frames.blocking_recv() {
            Ok(jpeg) => break jpeg,
            Err(broadcast::error::RecvError::Lagged(skipped)) => {
                debug!("编码落后 {skipped} 帧（编码比采集慢）");
            }
            Err(broadcast::error::RecvError::Closed) => return None,
        }
    };
    loop {
        match frames.try_recv() {
            Ok(newer) => jpeg = newer,
            Err(broadcast::error::TryRecvError::Lagged(_)) => {}
            Err(_) => return Some(jpeg),
        }
    }
}

/// 流水线的后半段：编码、发包、报 codec 字符串。退出条件都在这边查，解码线程跟着
/// 通道断开退出。
///
/// 编码器按**第一帧的实际尺寸**建，不按配置。两个原因：采集卡协商到的分辨率可以和
/// 请求的不一样（`Closest`）；而且改分辨率时 `reconfigured` 会被重开的采集循环清掉，
/// 这边要是正卡在等帧，就看不见那个标志，下一帧直接是新尺寸。所以中途尺寸变了不当
/// 错误，正常退出，由 `supervise_encoder` 按新尺寸重建——走这条路它会把 codec 字符串
/// 清掉，客户端跟着重配解码器。
fn encode_frames(
    hub: &VideoHub,
    config: &VideoSettings,
    full: mpsc::Receiver<Nv12Frame>,
    free: mpsc::SyncSender<Nv12Frame>,
) -> Result<()> {
    let mut encoder: Option<(Encoder, (u32, u32))> = None;
    loop {
        if hub.encoded.receiver_count() == 0 {
            return Ok(());
        }
        if hub.reconfigured.load(Ordering::SeqCst) {
            // 采集要按新配置重开，编码器的尺寸/码率也可能跟着变，一起重建
            return Ok(());
        }
        // 编码器还没建的话第一帧本来就是 IDR，这个请求清掉也无妨
        if hub.keyframe_wanted.swap(false, Ordering::SeqCst)
            && let Some((encoder, _)) = encoder.as_mut()
        {
            encoder.request_keyframe();
        }

        let Ok(frame) = full.recv() else {
            // 解码线程退出了（出错的话它的结果由 `encode_loop` 汇报）
            return Ok(());
        };
        let size = (frame.width, frame.height);
        let encoder = match &mut encoder {
            Some((_, built)) if *built != size => {
                info!(
                    "采集分辨率从 {}x{} 变成 {}x{}，重建编码器",
                    built.0, built.1, size.0, size.1
                );
                return Ok(());
            }
            Some((encoder, _)) => encoder,
            None => {
                let built = Encoder::new(EncoderConfig {
                    width: size.0,
                    height: size.1,
                    fps: config.fps,
                    bitrate_kbps: config.bitrate_kbps,
                })?;
                info!(
                    "编码已启动 {}x{} H.264 @{} kbps，{} fps",
                    size.0, size.1, config.bitrate_kbps, config.fps
                );
                &mut encoder.insert((built, size)).0
            }
        };
        let packets = encoder.encode(&frame);
        let _ = free.send(frame);
        for packet in packets? {
            let _ = hub.encoded.send(packet);
        }
        // codec 字符串要等第一个 SPS 才知道，所以每轮都问一下，直到问出来。
        //
        // `wanted` 那个条件不是多余的：`set_config` 切到 mjpeg 时会把状态清空，但
        // 这个循环要到下一轮才看见 `reconfigured`，中间这一次会把 avc 字符串又写
        // 回去——写进一份已经不属于自己的状态里，而且之后没人再清。
        //
        // 读的结果先落成一个 bool，别把 `borrow()` 留在 `if` 的条件里：下面紧跟着
        // 对同一个 watch 的 `send_modify`（写锁），读守卫要是还活着就是自己锁自己。
        // 那到底会不会活着，取决于临时值作用域那套相当微妙的规则——这种地方不值得
        // 去赌，写明白就没有可赌的了。
        let needs_codec_string = {
            let state = hub.codec.borrow();
            state.wanted == VideoCodec::H264 && state.avc_codec.is_none()
        };
        if needs_codec_string
            && let Some(codec) = encoder.codec_string()
        {
            info!("编码器就绪，codec {codec}");
            hub.codec.send_modify(|state| {
                state.avc_codec = Some(codec);
                state.fallback_reason = None;
            });
        }
    }
}

/// 按配置的帧率给编码线程放行帧，多出来的在解码之前就丢掉。
///
/// 采集卡不一定理会请求的帧率：MS2109 接 60Hz 的源就照样吐 60fps。1080p 解 JPEG
/// 加编码单核只够 30 帧上下，不节流的话编码线程整核跑满，多的帧最后也是被
/// `Lagged` 丢掉——白算了一遍。
///
/// 放行窗口提前 0.4 个间隔：帧到达有抖动，掐着整点放行的话，源和目标同为 30fps
/// 时早到一毫秒的帧会被丢，帧率掉成 15。也不能到 0.5——源恰好是目标两倍时，中间
/// 那帧正落在半个间隔上，就一帧都丢不掉了。
struct Pacer {
    interval: Duration,
    slack: Duration,
    next: Option<Instant>,
}

impl Pacer {
    fn new(fps: u32) -> Self {
        let interval = Duration::from_secs(1) / fps.max(1);
        Self {
            interval,
            slack: interval * 2 / 5,
            next: None,
        }
    }

    fn admit(&mut self, now: Instant) -> bool {
        if let Some(next) = self.next {
            if now + self.slack < next {
                return false;
            }
            // 按节拍往后排而不是从 now 起算，长期平均才准；落后超过一拍（卡顿、
            // 编码慢）就从现在重新起拍，不去补那些已经错过的帧。
            let after = next + self.interval;
            self.next = Some(if after < now { now + self.interval } else { after });
        } else {
            self.next = Some(now + self.interval);
        }
        true
    }
}

fn capture_loop(hub: &VideoHub) -> Result<StopReason> {
    // 和实测帧率互斥。锁放在读配置之前，免得实测正开着设备时这边先把配置标志清掉。
    let _device = hub.device.lock().expect("采集设备锁");
    // 先清标志再读配置。顺序反了的话，这两步之间的一次保存会被当成「已处理」
    // 而丢掉；现在这个顺序最坏也只是多重开一次，那没有坏处。
    hub.reconfigured.store(false, Ordering::SeqCst);
    let config = hub.config.lock().expect("config 锁").clone();
    let index = pick_device(&config.device)?;

    let wanted = CameraFormat::new(
        Resolution::new(config.width, config.height),
        FrameFormat::MJPEG,
        config.fps,
    );
    let request = RequestedFormat::new::<RgbFormat>(RequestedFormatType::Closest(wanted));
    let mut camera =
        Camera::new(index, request).context("打开采集卡失败（是否被别的程序占用？）")?;

    let format = camera.camera_format();
    if format.format() != FrameFormat::MJPEG {
        bail!(
            "采集卡协商到 {:?} 而不是 MJPEG，透传方案不成立",
            format.format()
        );
    }
    let resolution = (format.width(), format.height());

    camera.open_stream().context("开流失败")?;
    hub.mark_capturing(true, Some(resolution));
    info!(
        "采集已启动 {}x{} MJPEG",
        resolution.0, resolution.1
    );

    let mut idle_since: Option<Instant> = None;
    let result = loop {
        let frame = match camera.frame_raw() {
            Ok(frame) => frame,
            Err(e) => break Err(anyhow::anyhow!(e).context("取帧失败")),
        };

        // 采集卡直出 JPEG，裁掉缓冲区的填充后原样广播（见 `jpeg_len`）。send 在没有
        // 订阅者时会返回 Err，那不是错误，下面的 receiver_count 会处理退出。
        //
        // 连 SOI 都没有的直接丢：MS2109 刚开流那几帧就是这样的垃圾，整块 4 MB
        // 缓冲区发出去浏览器也解不了。有 SOI 但结构走不通的照原样发，那种由
        // 浏览器去判断，不在这里替它丢。
        //
        // 只有 SOI+EOI 的空帧也丢。MS2109 的 1080p60 是假的：真画面只有 30 帧，每隔
        // 一帧插一个 `FFD8 FFD9` 凑数。这种帧发给浏览器白解一次，进了编码线程就是一次
        // 解码失败——还会跟流水线的节奏锁在一起，次次挑中空帧，连错 30 帧整路回落。
        if !frame.starts_with(&[0xFF, 0xD8]) {
            debug!("丢掉一帧不是 JPEG 的缓冲区（{} B）", frame.len());
        } else {
            let jpeg = &frame[..jpeg_len(&frame).unwrap_or(frame.len())];
            if jpeg == EMPTY_JPEG {
                debug!("丢掉一帧空 JPEG（只有 SOI+EOI）");
            } else {
                let _ = hub.frames.send(Bytes::copy_from_slice(jpeg));
                hub.record_frame(jpeg.len());
            }
        }

        if hub.reconfigured.load(Ordering::SeqCst) {
            break Ok(StopReason::Reconfigured);
        }

        if hub.frames.receiver_count() == 0 {
            let since = idle_since.get_or_insert_with(Instant::now);
            if since.elapsed() >= IDLE_GRACE {
                break Ok(StopReason::Idle);
            }
        } else {
            idle_since = None;
        }
    };

    let _ = camera.stop_stream();
    hub.mark_capturing(false, Some(resolution));
    result
}

/// 一帧 JPEG 的真实长度：从 SOI 按段走到 EOI。
///
/// Linux 上非裁不可：nokhwa 的 V4L2 后端把整块 mmap 缓冲区原样交出来，丢掉了内核
/// 报的 `bytesused`。缓冲区按 YUYV 最坏情况分配（1080p 是 4 MB），而 JPEG 只有
/// 八十来 KB——不裁的话每帧发 4 MB，30fps 就是 800 多 Mbps。
///
/// 不能从尾部往前找最后一个 `FFD9`：缓冲区是循环复用的，上一帧比这帧长时，它的
/// 结尾（连同那个旧 EOI）还留在后面。头部也不能直接搜，APPn 段里可以是任意字节。
/// 所以老老实实按结构走：段按长度跳过，扫描数据里 `FF` 后面跟 `00` 是转义、跟
/// `D0`–`D7` 是重启标记，其余就是下一个段。
///
/// 结构不对就返回 `None`，调用方照原样发——那是坏帧，不该由这里决定丢不丢。
fn jpeg_len(buf: &[u8]) -> Option<usize> {
    if !buf.starts_with(&[0xFF, 0xD8]) {
        return None;
    }
    let mut i = 2;
    loop {
        if *buf.get(i)? != 0xFF {
            return None;
        }
        // 段前可以垫任意个 FF
        while *buf.get(i)? == 0xFF {
            i += 1;
        }
        let marker = buf[i];
        i += 1;
        match marker {
            0xD9 => return Some(i),
            // 没有长度字段的独立标记
            0x01 | 0xD0..=0xD7 => continue,
            _ => {}
        }
        let len = u16::from_be_bytes([*buf.get(i)?, *buf.get(i + 1)?]) as usize;
        // 长度把自己那两个字节算在内，小于 2 只能是坏数据，不拦会原地打转
        if len < 2 {
            return None;
        }
        i += len;
        if marker != 0xDA {
            continue;
        }
        // SOS 之后是熵编码数据，一直到下一个真正的标记为止
        loop {
            let at = i + buf.get(i..)?.iter().position(|&b| b == 0xFF)?;
            match *buf.get(at + 1)? {
                0x00 | 0xD0..=0xD7 => i = at + 2,
                _ => {
                    i = at;
                    break;
                }
            }
        }
    }
}

/// 设置页用来标分辨率。`error` 有内容时 `modes` 为空，页面照样能显示原因。
#[derive(Debug, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CaptureCapabilities {
    pub device: String,
    pub modes: Vec<CaptureMode>,
    pub error: Option<String>,
}

#[derive(Debug, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CaptureMode {
    pub size: String,
    pub rates: Vec<u32>,
}

pub fn list_capabilities(hint: &str) -> CaptureCapabilities {
    match list_capabilities_inner(hint) {
        Ok((device, modes)) => CaptureCapabilities {
            device,
            modes,
            error: None,
        },
        Err(error) => CaptureCapabilities {
            device: hint.to_string(),
            modes: Vec::new(),
            error: Some(format!("{error:#}")),
        },
    }
}

fn list_capabilities_inner(hint: &str) -> Result<(String, Vec<CaptureMode>)> {
    let index = pick_device(hint)?;
    let devices = crate::camera::capture_devices()?;
    let name = devices
        .iter()
        .find(|info| info.index() == &index)
        .map(|info| info.human_name())
        .unwrap_or_else(|| hint.to_string());
    let index = index.as_index().context("设备索引不是数字")?;
    let modes = crate::camera::mjpeg_modes(index)?
        .into_iter()
        .map(|mode| CaptureMode {
            size: format!("{}x{}", mode.width, mode.height),
            rates: mode.rates,
        })
        .collect();
    Ok((name, modes))
}

pub enum MeasureError {
    /// 采集线程正占着设备
    Busy,
    Failed(anyhow::Error),
}

/// 按请求的分辨率和帧率开流，数约 1.5 秒里**非空** JPEG 有多少。
///
/// 能力表会撒谎：MS2109 的 1080p60 每隔一帧是一个 4 字节空 JPEG，真画面还是 30。
/// 空帧不计入，得到的才是设置页该标的数。采集正在跑时不抢设备。
pub fn measure_fps(
    hub: &VideoHub,
    hint: &str,
    width: u32,
    height: u32,
    fps: u32,
) -> Result<u32, MeasureError> {
    let Ok(_device) = hub.device.try_lock() else {
        return Err(MeasureError::Busy);
    };
    let index = pick_device(hint).map_err(MeasureError::Failed)?;
    sample_fps(index, width, height, fps).map_err(MeasureError::Failed)
}

fn sample_fps(index: CameraIndex, width: u32, height: u32, fps: u32) -> Result<u32> {
    let wanted = CameraFormat::new(
        Resolution::new(width, height),
        FrameFormat::MJPEG,
        fps.max(1),
    );
    let mut camera = Camera::new(
        index,
        RequestedFormat::new::<RgbFormat>(RequestedFormatType::Closest(wanted)),
    )
    .context("打开采集卡失败（是否被别的程序占用？）")?;
    camera.open_stream().context("开流失败")?;

    // MS2109 刚换格式时要稳一会儿：1080p 切到 720p60 后头一秒多只出十几帧，之后才到
    // 60。所以连数两段取大的——头一段没稳也不会把一档好好的帧率标成不够。
    let started = Instant::now();
    let warmup = Duration::from_millis(500);
    let window = Duration::from_millis(1500);
    const WINDOWS: u32 = 2;
    // frame_raw 本身会堵到下一帧。过了这个点还堵着，就当卡死，别一直占着设备
    let deadline = started + warmup + window * WINDOWS + Duration::from_secs(2);
    let mut origin: Option<Instant> = None;
    let mut frames = 0u32;
    let mut best = 0u32;
    let mut done = 0u32;
    while done < WINDOWS {
        if Instant::now() > deadline {
            bail!("采集卡没有在时限内给出足够的帧");
        }
        let frame = camera.frame_raw().context("取帧失败")?;
        let now = Instant::now();
        if now.duration_since(started) < warmup {
            continue;
        }
        let since = *origin.get_or_insert(now);
        if real_jpeg(&frame) {
            frames += 1;
        }
        let elapsed = now.duration_since(since);
        if elapsed >= window {
            best = best.max((frames as f32 / elapsed.as_secs_f32()).round() as u32);
            done += 1;
            origin = None;
            frames = 0;
        }
    }
    let _ = camera.stop_stream();
    Ok(best)
}

fn real_jpeg(frame: &[u8]) -> bool {
    if !frame.starts_with(&[0xFF, 0xD8]) {
        return false;
    }
    let jpeg = &frame[..jpeg_len(frame).unwrap_or(frame.len())];
    jpeg != EMPTY_JPEG
}

fn pick_device(hint: &str) -> Result<CameraIndex> {
    let devices = crate::camera::capture_devices()?;
    if devices.is_empty() {
        bail!("没有找到任何视频采集设备");
    }

    let picked = devices
        .iter()
        .find(|info| !hint.is_empty() && info.human_name().contains(hint))
        .unwrap_or(&devices[0]);
    Ok(picked.index().clone())
}

#[cfg(test)]
mod tests {
    use std::time::{Duration, Instant};

    use bytes::Bytes;
    use tokio::sync::broadcast;

    use super::{Pacer, jpeg_len, newest_jpeg, real_jpeg};

    #[test]
    fn newest_jpeg_skips_queued_frames() {
        let (tx, mut rx) = broadcast::channel(4);
        for i in 0..3u8 {
            tx.send(Bytes::from(vec![i])).unwrap();
        }
        assert_eq!(newest_jpeg(&mut rx).as_deref(), Some(&[2u8][..]));

        // 积满被挤掉（Lagged）也照样拿到最新的，不报错
        for i in 10..20u8 {
            tx.send(Bytes::from(vec![i])).unwrap();
        }
        assert_eq!(newest_jpeg(&mut rx).as_deref(), Some(&[19u8][..]));

        drop(tx);
        assert_eq!(newest_jpeg(&mut rx), None);
    }

    /// 按 `source_fps` 喂 `seconds` 秒、到达时刻带 ±`jitter_ms` 的交替抖动，数放行了几帧
    fn admitted(target_fps: u32, source_fps: u32, jitter_ms: u64, seconds: u32) -> usize {
        let mut pacer = Pacer::new(target_fps);
        let start = Instant::now() + Duration::from_millis(jitter_ms);
        let period = Duration::from_secs(1) / source_fps;
        (0..source_fps * seconds)
            .filter(|&i| {
                let jitter = Duration::from_millis(jitter_ms);
                let t = start + period * i;
                pacer.admit(if i % 2 == 0 { t + jitter } else { t - jitter })
            })
            .count()
    }

    #[test]
    fn pacer_halves_a_60fps_source() {
        assert_eq!(admitted(30, 60, 0, 10), 300);
        assert_eq!(admitted(30, 60, 3, 10), 300);
    }

    #[test]
    fn pacer_keeps_every_frame_when_source_matches() {
        assert_eq!(admitted(30, 30, 0, 10), 300);
        assert_eq!(admitted(30, 30, 5, 10), 300);
    }

    #[test]
    fn pacer_passes_a_slower_source_untouched() {
        assert_eq!(admitted(30, 25, 2, 10), 250);
    }

    #[test]
    fn pacer_restarts_after_a_stall_instead_of_bursting() {
        let mut pacer = Pacer::new(30);
        let t0 = Instant::now();
        assert!(pacer.admit(t0));
        // 卡了一秒，之后 60fps 来帧：不该把错过的 30 拍一口气补放
        let resume = t0 + Duration::from_secs(1);
        let period = Duration::from_secs(1) / 60;
        let burst = (0..6).filter(|&i| pacer.admit(resume + period * i)).count();
        assert_eq!(burst, 3);
    }

    /// 拼一个结构完整的假 JPEG：APP0 段里故意放一个 FFD9，扫描数据里放转义的
    /// FF00 和一个重启标记，都不能被当成结尾。
    fn fake_jpeg() -> Vec<u8> {
        let mut jpeg = vec![0xFF, 0xD8];
        jpeg.extend([0xFF, 0xE0, 0x00, 0x04, 0xFF, 0xD9]);
        jpeg.extend([0xFF, 0xDA, 0x00, 0x03, 0x01]);
        jpeg.extend([0x12, 0xFF, 0x00, 0x34, 0xFF, 0xD0, 0x56]);
        jpeg.extend([0xFF, 0xD9]);
        jpeg
    }

    #[test]
    fn 走到真正的_eoi_为止() {
        let jpeg = fake_jpeg();
        assert_eq!(jpeg_len(&jpeg), Some(jpeg.len()));
    }

    /// 这就是 V4L2 那个缓冲区的样子：真 JPEG 后面跟着上一帧残留的尾巴，
    /// 里面还有一个旧的 EOI。从后往前找的话会把那段残留一起发出去。
    #[test]
    fn 不被缓冲区里上一帧的残留骗到() {
        let jpeg = fake_jpeg();
        let mut buffer = jpeg.clone();
        buffer.extend([0x77, 0x88, 0xFF, 0xD9, 0x00, 0x00, 0x00]);
        assert_eq!(jpeg_len(&buffer), Some(jpeg.len()));
    }

    #[test]
    fn 不是_jpeg_或者截断了就说不知道() {
        assert_eq!(jpeg_len(&[0x00, 0x01, 0x02]), None);
        let jpeg = fake_jpeg();
        assert_eq!(jpeg_len(&jpeg[..jpeg.len() - 2]), None);
    }

    /// 长度字段是 0 或 1 的坏段，不拦的话会在同一个位置无限循环。
    #[test]
    fn 坏的段长度不会卡死() {
        assert_eq!(jpeg_len(&[0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x00, 0xFF, 0xD9]), None);
        assert!(!real_jpeg(&[0xFF, 0xD8, 0xFF, 0xD9]));
        assert!(real_jpeg(&fake_jpeg()));
    }

    #[test]
    fn 能力表能列出插着的采集卡() {
        let caps = super::list_capabilities("USB Video");
        if caps.modes.is_empty() {
            return;
        }
        assert!(caps.error.is_none(), "{:?}", caps.error);
        assert!(caps.modes.iter().any(|mode| mode.size == "1920x1080"));
        assert!(caps.modes.iter().any(|mode| mode.size == "1280x720"));
        assert!(!caps.modes.iter().any(|mode| mode.size == "1600x900"));
    }
}
