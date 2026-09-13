//! 声音这一段：cpal 采采集卡的 USB 音频输入，转成 Int16 LE 的裸 PCM 广播出去。
//!
//! 结构和 `video` 一模一样：一个独占设备的采集线程 + broadcast 扇出 + 按需启停。
//! 区别在于音频有个「格式」要先告诉客户端（采样率和声道数），而格式只有在采集
//! 真正打开之后才知道，所以多了一个 watch 通道让 `/ws/audio` 等到格式再开始发。
//!
//! ## 一个会咬人的前提：进程得跑在能看见物理音频设备的会话里
//!
//! 远程桌面会话里 Windows 会把物理音频端点换成「远程音频」，物理设备在那个会话
//! 里根本不被枚举——cpal 会报「一个输入设备都没有」，而设备管理器里明明是好的。
//! 视频没这个问题（摄像头不按会话隔离），所以这个坑只在音频这条路上等着。

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use anyhow::{Context, Result, bail};
use bytes::{BufMut, Bytes, BytesMut};
use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{Device, ErrorKind, SampleFormat};
use tokio::sync::{Notify, broadcast, watch};
use tracing::{info, warn};

use crate::config::AudioSettings;

/// 一块 PCM 大约 10ms，攒几十块就是几百毫秒——真落后这么多不如直接丢。
/// 音频比视频对丢弃更敏感（丢了就是一声爆音），所以比视频的 4 给得宽松些。
const CHANNEL_CAPACITY: usize = 32;

/// 最后一个听众离开后再多采这么久，免得刷新页面把设备反复开关。
const IDLE_GRACE: Duration = Duration::from_secs(3);

/// 采集线程多久检查一次「还有没有人听」。音频回调在另一个线程上跑，
/// 这个线程只是替 cpal 的 Stream 守着生命周期，醒得勤一点也不费什么。
const POLL_INTERVAL: Duration = Duration::from_millis(200);

/// 有人听却这么久一个数据块都没来，就认定流已经停摆，收摊重建。
///
/// 块是十几毫秒一个，三秒足够宽松，不会误伤正常的流；又足够短，恢复不至于
/// 让人以为坏了。
const STALL_TIMEOUT: Duration = Duration::from_secs(3);

/// `/ws/audio` 等采集把格式报上来的上限。超过就认为开不起来，回一条错误，
/// 让页面显示原因而不是干等。
const FORMAT_TIMEOUT: Duration = Duration::from_secs(5);

/// 设了这个环境变量就用测试音源代替真设备。见 `tone_loop`。
const TONE_ENV: &str = "RUST_KVM_AUDIO_TEST";

/// 采音循环为什么停下来。和 `video` 里同名的东西一个意思：没人听就安静退出，
/// 换了配置得立刻按新设备重开，不走出错退避。
enum StopReason {
    Idle,
    Reconfigured,
}

/// 客户端在收到第一块 PCM 之前必须先拿到这个，否则它不知道按什么采样率播。
#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AudioFormat {
    pub sample_rate: u32,
    pub channels: u16,
}

#[derive(Clone, Debug, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AudioStats {
    pub capturing: bool,
    pub listeners: usize,
    pub format: Option<AudioFormat>,
    pub kbps: f32,
    pub chunks: u64,
    pub error: Option<String>,
}

#[derive(Default)]
struct StatsInner {
    capturing: bool,
    chunks: u64,
    kbps: f32,
    window: Option<Window>,
    /// **致命**错误：采音没能起来或者已经停了。`await_format` 拿这个决定要不要
    /// 直接回「开不起来」，所以瞬时打嗝绝对不能进这个字段（见 `stream_error`）。
    error: Option<String>,
    /// cpal 报的瞬时流错误（欠载/溢出）。只用于显示。
    ///
    /// 和上面那个分开是踩过的：以前两者共用 `error`，而 `mark_capturing(false)`
    /// 不清它，于是一次欠载的消息会一直留到下一次采音真正启动。下一个客户端连上
    /// 来时采音还没起、格式还没发，`await_format` 先读到这条陈旧消息就直接告诉
    /// 页面「音频开不起来: A buffer underrun occurred」——声音明明是好的。
    stream_error: Option<String>,
}

struct Window {
    started: Instant,
    bytes: u64,
}

pub struct AudioHub {
    config: Mutex<AudioSettings>,
    /// 设置页保存后置位，采音循环看到就收摊重开。
    reconfigured: AtomicBool,
    chunks: broadcast::Sender<Bytes>,
    /// 采集一打开就把格式写进来，`/ws/audio` 靠它决定什么时候能发头。
    format: watch::Sender<Option<AudioFormat>>,
    wake: Notify,
    capturing: AtomicBool,
    stats: Mutex<StatsInner>,
    /// cpal 的错误回调判定流已失效时写在这儿，采音循环下一轮轮询就收摊。
    /// 不放进 `stats` 是因为它不是给人看的读数，而是控制流。
    stream_dead: Mutex<Option<String>>,
}

impl AudioHub {
    pub fn new(config: AudioSettings) -> Arc<Self> {
        let (chunks, _) = broadcast::channel(CHANNEL_CAPACITY);
        let (format, _) = watch::channel(None);
        let hub = Arc::new(Self {
            config: Mutex::new(config),
            reconfigured: AtomicBool::new(false),
            chunks,
            format,
            wake: Notify::new(),
            capturing: AtomicBool::new(false),
            stats: Mutex::new(StatsInner::default()),
            stream_dead: Mutex::new(None),
        });
        tokio::spawn(supervise(hub.clone()));
        hub
    }

    /// 只在配置真的变了时才动采集，理由同 `VideoHub::set_config`：
    /// 一次只改了串口的保存不该把声音掐断一下。
    pub fn set_config(&self, config: AudioSettings) {
        {
            let mut current = self.config.lock().expect("config 锁");
            if *current == config {
                return;
            }
            *current = config;
        }
        info!("音频配置已更新，重开采音");
        self.reconfigured.store(true, Ordering::SeqCst);
        self.wake.notify_one();
    }

    /// 先订阅再唤醒采集：采集线程靠 receiver_count 判断还有没有人听，
    /// 顺序反了会开起来又立刻退出。
    pub fn subscribe(&self) -> broadcast::Receiver<Bytes> {
        let rx = self.chunks.subscribe();
        self.wake.notify_one();
        rx
    }

    pub fn watch_format(&self) -> watch::Receiver<Option<AudioFormat>> {
        self.format.subscribe()
    }

    pub fn stats(&self) -> AudioStats {
        let inner = self.stats.lock().expect("stats 锁");
        AudioStats {
            capturing: inner.capturing,
            listeners: self.chunks.receiver_count(),
            format: *self.format.borrow(),
            kbps: inner.kbps,
            chunks: inner.chunks,
            // 页面上这一条是当普通信息行显示的，两种错都值得看见；
            // 致命的那个更重要，优先报它
            error: inner.error.clone().or_else(|| inner.stream_error.clone()),
        }
    }

    /// 把一块样本编成 Int16 LE 发出去。这个函数是在 cpal 的音频回调线程上跑的，
    /// 严格讲不该在那儿分配内存；但一秒只有一百来次、每次两千字节，
    /// 换成无锁环形缓冲的复杂度不值得。
    fn publish(&self, samples: impl Iterator<Item = i16>, count: usize) {
        let mut buf = BytesMut::with_capacity(count * 2);
        for sample in samples {
            buf.put_i16_le(sample);
        }
        let len = buf.len();
        // 没有订阅者时 send 会返回 Err，那不是错误，下面的 receiver_count 会收摊
        let _ = self.chunks.send(buf.freeze());

        let mut inner = self.stats.lock().expect("stats 锁");
        inner.chunks += 1;
        let window = inner.window.get_or_insert_with(|| Window {
            started: Instant::now(),
            bytes: 0,
        });
        window.bytes += len as u64;

        let elapsed = window.started.elapsed();
        let bytes = window.bytes;
        if elapsed >= Duration::from_secs(1) {
            inner.kbps = bytes as f32 * 8.0 / elapsed.as_secs_f32() / 1000.0;
            inner.window = None;
        }
    }

    fn mark_capturing(&self, on: bool, format: Option<AudioFormat>) {
        {
            let mut inner = self.stats.lock().expect("stats 锁");
            inner.capturing = on;
            if on {
                // 新一轮采音起来了，上一轮的两种错都作废
                inner.error = None;
                inner.stream_error = None;
            } else {
                inner.kbps = 0.0;
                inner.window = None;
            }
        }
        // 必须用 send_replace 而不是 send：后者在没有接收者时直接返回 Err 且
        // 根本不改存着的值。而「采集停了」这一刻正好就是最后一个接收者刚被丢掉
        // 的时候，用 send 的话格式永远清不掉，下一个客户端会读到上一轮的旧格式，
        // 在新采集真起来之前就按错的采样率开播。
        self.format.send_replace(if on { format } else { None });
    }

    /// 采集真的停了（循环退出）。
    fn record_error(&self, message: String) {
        let mut inner = self.stats.lock().expect("stats 锁");
        inner.capturing = false;
        inner.kbps = 0.0;
        inner.error = Some(message);
    }

    /// cpal 错误回调用这个。**不能**顺手把 `capturing` 清掉：
    /// `buffer underrun or overrun` 这类是瞬时打嗝，流还在跑、字节还在发。
    /// 以前两者共用一个函数，于是偶尔一次 underrun 之后页面就永久显示
    /// 「未采音 0 kbps」，而人明明还听得到声音——最难查的那种状态不一致。
    fn record_stream_error(&self, message: String) {
        self.stats.lock().expect("stats 锁").stream_error = Some(message);
    }

    /// 流死了，采音循环该收摊重建。
    ///
    /// 从 cpal 的错误回调（另一个线程）跨到采音循环，所以只能放个标志等它来取。
    fn mark_stream_dead(&self, message: String) {
        *self.stream_dead.lock().expect("stream_dead 锁") = Some(message);
    }

    /// 取走并清空。**必须取走**：留着的话下一轮刚建好的流会被上一轮的死讯
    /// 立刻杀掉，然后一秒一次无限重建。同一类坑在 `stream_error` 上踩过一次了。
    fn take_stream_dead(&self) -> Option<String> {
        self.stream_dead.lock().expect("stream_dead 锁").take()
    }

    /// 只给 `await_format` 用：它要判断的是「采音能不能起来」，答案只能由
    /// 致命错误回答。
    fn fatal_error(&self) -> Option<String> {
        self.stats.lock().expect("stats 锁").error.clone()
    }
}

/// 守着「有人听就开采集」这件事。cpal 的 Stream 在某些平台上不是 Send，
/// 而且它一被 drop 就停流，所以整个生命周期都待在同一个阻塞线程里。
async fn supervise(hub: Arc<AudioHub>) {
    loop {
        hub.wake.notified().await;
        if hub.chunks.receiver_count() == 0 || hub.capturing.swap(true, Ordering::SeqCst) {
            continue;
        }

        let worker = hub.clone();
        let use_tone = std::env::var_os(TONE_ENV).is_some();
        let outcome = tokio::task::spawn_blocking(move || {
            if use_tone {
                tone_loop(worker)
            } else {
                capture_loop(worker)
            }
        })
        .await;
        hub.capturing.store(false, Ordering::SeqCst);
        hub.mark_capturing(false, None);

        match outcome {
            Ok(Ok(StopReason::Idle)) => info!("采音已停止（没有听众）"),
            Ok(Ok(StopReason::Reconfigured)) => {
                hub.wake.notify_one();
                continue;
            }
            Ok(Err(e)) => {
                warn!("采音出错: {e:#}");
                hub.record_error(format!("{e:#}"));
            }
            Err(e) => {
                warn!("采音线程异常退出: {e}");
                hub.record_error(format!("采音线程异常退出: {e}"));
            }
        }

        // 出错别立刻重试，否则设备拔掉之后会疯转
        if hub.chunks.receiver_count() > 0 {
            tokio::time::sleep(Duration::from_secs(1)).await;
            hub.wake.notify_one();
        }
    }
}

/// MS2109 采集卡的 HDMI 音频口自报「96000 Hz 单声道」，实际送出来的是
/// **48000 Hz 立体声的交织样本**，而且起始位置还偏了一个样本。
///
/// 证据（拿真实信号实测）：对单声道时间序列来说，相邻差 `|x[i+1]-x[i]|` 的统计量
/// 不该和下标奇偶有关。实测偶数位均值 262.8、奇数位 74.2，差 3.5 倍，样本量 23 万。
/// 周期为 2 的结构只可能来自双声道交织；更相似的那一对落在奇数位，说明帧边界从
/// 下标 1 开始。按这个解释拆出来的两路，各自平滑度 206.5 / 206.4、峰值
/// 15569 / 15564——同一段音乐的左右声道。
///
/// Linux 内核给 `0x534d:0x2109` 设 `stream_offset_adj = 2` 修的就是这个：
/// 2 字节正好一个 16 位样本。
///
/// 好在**一个字节都不用动**：交织的立体声本来就是我们的线上格式。要改的只有报出去
/// 的格式，和丢掉整条流的第一个样本。
///
/// 万一判错了，症状是左右声道对调加一个样本的错位，几乎听不出来——而不是变速。
/// 会变速的那个假设（真·96k 单声道）已经被上面的奇偶不对称排除了。
fn hdmi_stereo_quirk(reported: AudioFormat) -> Option<AudioFormat> {
    (reported.sample_rate == 96_000 && reported.channels == 1).then_some(AudioFormat {
        sample_rate: reported.sample_rate / 2,
        channels: 2,
    })
}

/// 整条流开头要丢掉的样本数。
const QUIRK_SKIP: usize = 1;

/// 维持帧对齐。cpal 每次回调给的样本数不定，开头丢掉一个之后每块的奇偶都会错开，
/// 所以落单的那个样本要留到下一块开头，否则左右声道会来回互换。
///
/// 有个修不了的隐患：WASAPI 报 `DATA_DISCONTINUITY`（也就是 cpal 的 `Xrun`）时样本
/// 真的丢了，丢的个数是奇数的话，从那一刻起左右声道就一直是反的。cpal 不透出丢了
/// 多少，所以没法补偿。好在后果只是左右对调——不难受，也不影响别的。真正的办法是
/// 别让它发生：开 cpal 的 `realtime` feature 把采音线程提到实时优先级。
struct FrameAlign {
    skip: usize,
    carry: Option<i16>,
    buf: Vec<i16>,
}

impl FrameAlign {
    fn new(skip: usize) -> Self {
        Self {
            skip,
            carry: None,
            buf: Vec::new(),
        }
    }

    /// 返回对齐后的样本，长度一定是偶数（凑不齐一帧的留到下次）。
    fn align(&mut self, samples: impl Iterator<Item = i16>) -> &[i16] {
        let skip = std::mem::take(&mut self.skip);
        self.buf.clear();
        self.buf.extend(self.carry.take());
        self.buf.extend(samples.skip(skip));
        if self.buf.len() % 2 == 1 {
            self.carry = self.buf.pop();
        }
        &self.buf
    }
}

/// `align` 为 `None` 时原样发（设备自报的格式可信），否则先对齐帧边界。
fn publish_aligned(
    hub: &AudioHub,
    align: &mut Option<FrameAlign>,
    samples: impl Iterator<Item = i16>,
    count: usize,
) {
    match align {
        None => hub.publish(samples, count),
        Some(align) => {
            let aligned = align.align(samples);
            hub.publish(aligned.iter().copied(), aligned.len());
        }
    }
}

fn capture_loop(hub: Arc<AudioHub>) -> Result<StopReason> {
    // 先清标志再读配置，理由同 video.rs
    hub.reconfigured.store(false, Ordering::SeqCst);
    // 上一轮的死讯到这儿一律作废。不清的话新流一建好就被它立刻杀掉，然后
    // 一秒一轮无限重建——和 `stream_error` 那次陈旧消息是同一类坑。
    hub.take_stream_dead();
    let config = hub.config.lock().expect("config 锁").clone();
    if !config.enabled {
        bail!("音频已在设置里关掉了");
    }

    let device = pick_device(&config.device)?;
    let supported = device
        .default_input_config()
        .context("读音频设备的默认输入配置失败")?;

    let reported = AudioFormat {
        sample_rate: supported.sample_rate(),
        channels: supported.channels(),
    };
    let sample_format = supported.sample_format();
    let config = supported.config();

    // 设备自报的格式可能是假的，见 hdmi_stereo_quirk
    let (format, mut align) = match hdmi_stereo_quirk(reported) {
        Some(actual) => {
            info!(
                "设备自报 {} Hz {} 声道，按 {} Hz {} 声道解（MS2109 的交织立体声）",
                reported.sample_rate, reported.channels, actual.sample_rate, actual.channels
            );
            (actual, Some(FrameAlign::new(QUIRK_SKIP)))
        }
        None => (reported, None),
    };

    let error_hub = hub.clone();
    let on_error = move |e: cpal::Error| {
        error_hub.record_stream_error(format!("音频流错误: {e}"));
        // 瞬时的只有这两种：Xrun 是欠载/溢出，流还在跑；DeviceChanged 是音频
        // 路由变了、cpal 已经自己重定向好了，文档明说不用重建。**其余一律当致命**
        // ——未知错误按致命处理最坏是白重建一次流（声音断一下就回来），按瞬时
        // 处理则会永久假活：页面显示在采音、字节数不动、没有任何错误，而且永远
        // 不会自己恢复。两种猜错的代价差得太远。
        if matches!(e.kind(), ErrorKind::Xrun | ErrorKind::DeviceChanged) {
            warn!("音频流错误（瞬时）: {e}");
            return;
        }
        warn!("音频流已失效: {e}");
        error_hub.mark_stream_dead(format!("音频流已失效: {e}"));
    };

    // 声道数和采样率照设备自报的来，不在这儿重采样：客户端的 AudioContext
    // 可以直接按这个采样率建，浏览器的重采样比我手写的强。
    let data_hub = hub.clone();
    let stream = match sample_format {
        SampleFormat::I16 => device.build_input_stream(
            config,
            move |data: &[i16], _: &_| {
                publish_aligned(&data_hub, &mut align, data.iter().copied(), data.len())
            },
            on_error,
            None,
        ),
        SampleFormat::F32 => device.build_input_stream(
            config,
            move |data: &[f32], _: &_| {
                let samples = data.iter().map(|s| f32_to_i16(*s));
                publish_aligned(&data_hub, &mut align, samples, data.len())
            },
            on_error,
            None,
        ),
        SampleFormat::I32 => device.build_input_stream(
            config,
            move |data: &[i32], _: &_| {
                let samples = data.iter().map(|s| (*s >> 16) as i16);
                publish_aligned(&data_hub, &mut align, samples, data.len())
            },
            on_error,
            None,
        ),
        other => bail!("音频设备用的是 {other:?}，这儿只处理 I16 / I32 / F32"),
    };
    let stream = stream.context("建立音频输入流失败")?;
    stream.play().context("音频开流失败")?;

    hub.mark_capturing(true, Some(format));
    info!(
        "采音已启动 {} Hz {} 声道（源格式 {sample_format:?}）",
        format.sample_rate, format.channels
    );

    let mut idle_since: Option<Instant> = None;
    let mut last_chunks = hub.stats.lock().expect("stats 锁").chunks;
    let mut last_progress = Instant::now();

    let outcome = loop {
        std::thread::sleep(POLL_INTERVAL);

        if hub.reconfigured.load(Ordering::SeqCst) {
            break Ok(StopReason::Reconfigured);
        }
        if let Some(message) = hub.take_stream_dead() {
            break Err(anyhow::anyhow!(message));
        }
        if hub.chunks.receiver_count() == 0 {
            let since = idle_since.get_or_insert_with(Instant::now);
            if since.elapsed() >= IDLE_GRACE {
                break Ok(StopReason::Idle);
            }
            // 没人听的时候不采数据也正常，别让下面的停摆检查误判
            last_progress = Instant::now();
            continue;
        }
        idle_since = None;

        // 有人听却一个块都不来，说明流已经停了。这一条是兜底：WASAPI 上设备被
        // 拔掉时 cpal 不一定报错，回调可能只是再也不被调用——那正是最坏的失败
        // 形态，页面显示在采音、读数不动、永远不会自己恢复。输入流即使源是静音
        // 也会持续送缓冲，所以「三秒一个块都没有」不会误伤正常的流。
        let chunks = hub.stats.lock().expect("stats 锁").chunks;
        if chunks != last_chunks {
            last_chunks = chunks;
            last_progress = Instant::now();
        } else if last_progress.elapsed() >= STALL_TIMEOUT {
            break Err(anyhow::anyhow!(
                "音频流停摆：{} 秒没有收到数据（设备可能被拔了）",
                STALL_TIMEOUT.as_secs()
            ));
        }
    };

    // drop 就是停流，显式写出来是为了让「到这儿才停」这件事看得见
    drop(stream);
    outcome
}

fn f32_to_i16(sample: f32) -> i16 {
    (sample.clamp(-1.0, 1.0) * i16::MAX as f32) as i16
}

/// 测试音源：不碰硬件，按墙上时钟生成一段 440Hz 正弦。
///
/// 存在的理由是把两个问题分开验证：「音频设备能不能打开」和「这条管线对不对」。
/// 远程桌面会话里前者直接就是不行，没有这个开关的话，客户端那一整套环形缓冲、
/// 抖动缓冲、欠载处理就得等到部署问题解决之后才第一次运行——真出问题时也分不清
/// 是设备的事还是代码的事。
fn tone_loop(hub: Arc<AudioHub>) -> Result<StopReason> {
    const RATE: u32 = 48_000;
    const CHANNELS: u16 = 2;
    const CHUNK: Duration = Duration::from_millis(10);
    const TONE_HZ: f32 = 440.0;
    /// 别开满，出声就行，免得谁一点开关就被吓一跳
    const AMPLITUDE: f32 = 0.15;

    let frames = (RATE as u128 * CHUNK.as_millis() / 1000) as usize;
    hub.mark_capturing(
        true,
        Some(AudioFormat {
            sample_rate: RATE,
            channels: CHANNELS,
        }),
    );
    info!("采音用的是测试音源（{TONE_ENV} 已设），{TONE_HZ} Hz 正弦");

    let mut phase = 0.0f32;
    let step = std::f32::consts::TAU * TONE_HZ / RATE as f32;
    // 用累积的绝对时刻而不是每轮 sleep(10ms)：Windows 默认的定时器精度是
    // 15.6ms，按相对时长睡会让音源只跑到真实速度的六成，客户端那头就会一直欠载。
    // 盯着绝对时刻的话，睡过头的那次下一轮就不睡，自己追回来。
    let mut next = Instant::now();
    let mut idle_since: Option<Instant> = None;

    loop {
        next += CHUNK;
        let now = Instant::now();
        if next > now {
            std::thread::sleep(next - now);
        }

        let mut samples = Vec::with_capacity(frames * CHANNELS as usize);
        for _ in 0..frames {
            let value = (phase.sin() * AMPLITUDE * i16::MAX as f32) as i16;
            phase = (phase + step) % std::f32::consts::TAU;
            for _ in 0..CHANNELS {
                samples.push(value);
            }
        }
        let count = samples.len();
        hub.publish(samples.into_iter(), count);

        if hub.reconfigured.load(Ordering::SeqCst) {
            return Ok(StopReason::Reconfigured);
        }
        if hub.chunks.receiver_count() == 0 {
            let since = idle_since.get_or_insert_with(Instant::now);
            if since.elapsed() >= IDLE_GRACE {
                return Ok(StopReason::Idle);
            }
        } else {
            idle_since = None;
        }
    }
}

fn pick_device(hint: &str) -> Result<Device> {
    let host = cpal::default_host();
    let devices: Vec<Device> = host
        .input_devices()
        .context("枚举音频输入设备失败")?
        .collect();

    if devices.is_empty() {
        // 这个提示是有来历的：远程桌面会话里物理音频端点会被换成「远程音频」，
        // 设备管理器里一切正常，但进程一个输入设备都枚举不到。
        bail!(
            "没有找到任何音频输入设备。\
             如果服务是在远程桌面会话里启动的，物理音频端点在那个会话里不可见，\
             需要改到控制台会话或 Windows 服务里跑"
        );
    }

    // 0.18 起 cpal 没有 name()，设备名走 Display
    if !hint.is_empty()
        && let Some(device) = devices.iter().find(|d| d.to_string().contains(hint))
    {
        return Ok(device.clone());
    }

    if let Some(device) = host.default_input_device() {
        warn!("没找到名字含「{hint}」的音频设备，退回默认输入设备 {device}");
        return Ok(device);
    }
    warn!("没找到名字含「{hint}」的音频设备，退回第一个 {}", devices[0]);
    Ok(devices[0].clone())
}

/// `/ws/audio` 在发第一块 PCM 之前要等格式确定。返回 `Err` 里带的是给页面看的
/// 原因（设备开不起来时就是那条 RDP 提示）。
pub async fn await_format(
    hub: &AudioHub,
    format_rx: &mut watch::Receiver<Option<AudioFormat>>,
) -> Result<AudioFormat, String> {
    let deadline = tokio::time::Instant::now() + FORMAT_TIMEOUT;
    loop {
        if let Some(format) = *format_rx.borrow_and_update() {
            return Ok(format);
        }
        if let Some(message) = hub.fatal_error() {
            return Err(message);
        }
        if tokio::time::timeout_at(deadline, format_rx.changed())
            .await
            .is_err()
        {
            return Err(hub
                .fatal_error()
                .unwrap_or_else(|| "音频采集没能在 5 秒内启动".to_string()));
        }
    }
}
