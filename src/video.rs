//! 采集卡到 WebSocket 之间的那一段：一个独占设备的采集线程，把采集卡直出的
//! JPEG 原样广播给所有观看者。服务端一次都不解码。
//!
//! 设备是独占的，所以采集按需启停：有人看才开，没人看了再多采一会儿就关掉，
//! 好让别的程序（或者本地的 chrome-kvm）能用这张卡。

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use anyhow::{Context, Result, bail};
use bytes::Bytes;
use nokhwa::pixel_format::RgbFormat;
use nokhwa::utils::{
    ApiBackend, CameraFormat, CameraIndex, FrameFormat, RequestedFormat, RequestedFormatType,
    Resolution,
};
use nokhwa::{Camera, query};
use tokio::sync::{Notify, broadcast};
use tracing::{info, warn};

use crate::config::VideoSettings;

/// 只缓存最近几帧。KVM 要的是最新画面，落后的客户端应该丢帧而不是拖慢别人，
/// 所以这个值故意开得很小——broadcast 的 Lagged 正好就是「丢掉旧帧」的语义。
const CHANNEL_CAPACITY: usize = 4;

/// 最后一个观看者离开后再多采这么久。刷新页面时连接会断开重连，留个缓冲
/// 就不会把设备反复开关（MS2109 重新打开要一秒左右）。
const IDLE_GRACE: Duration = Duration::from_secs(3);

/// 采集循环为什么停下来。两者的善后完全不同：没人看就安静退出，
/// 换了配置得立刻按新设备重开，不能走「出错后等一秒」那条退避路径。
enum StopReason {
    Idle,
    Reconfigured,
}

#[derive(Clone, Debug, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct VideoStats {
    pub capturing: bool,
    pub viewers: usize,
    /// 自己按帧间隔算的，不能用 nokhwa 报的 camera_format().frame_rate()——
    /// 那个字段在 MS2109 上报的是 1fps，和实测的 29.5fps 完全不符。
    pub fps: f32,
    pub mbps: f32,
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
    error: Option<String>,
}

struct Window {
    started: Instant,
    frames: u32,
    bytes: u64,
}

pub struct VideoHub {
    config: Mutex<VideoSettings>,
    /// 设置页保存后置位，采集循环看到就收摊重开。
    reconfigured: AtomicBool,
    frames: broadcast::Sender<Bytes>,
    wake: Notify,
    capturing: AtomicBool,
    stats: Mutex<StatsInner>,
}

impl VideoHub {
    pub fn new(config: VideoSettings) -> Arc<Self> {
        let (frames, _) = broadcast::channel(CHANNEL_CAPACITY);
        let hub = Arc::new(Self {
            config: Mutex::new(config),
            reconfigured: AtomicBool::new(false),
            frames,
            wake: Notify::new(),
            capturing: AtomicBool::new(false),
            stats: Mutex::new(StatsInner::default()),
        });
        tokio::spawn(supervise(hub.clone()));
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
        self.reconfigured.store(true, Ordering::SeqCst);
        self.wake.notify_one();
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
            inner.fps = 0.0;
            inner.mbps = 0.0;
        }
    }

    fn record_error(&self, message: String) {
        let mut inner = self.stats.lock().expect("stats 锁");
        inner.capturing = false;
        inner.fps = 0.0;
        inner.mbps = 0.0;
        inner.error = Some(message);
    }

    fn record_frame(&self, len: usize) {
        let mut inner = self.stats.lock().expect("stats 锁");
        inner.frames += 1;
        inner.last_frame_bytes = len;

        let window = inner.window.get_or_insert_with(|| Window {
            started: Instant::now(),
            frames: 0,
            bytes: 0,
        });
        window.frames += 1;
        window.bytes += len as u64;

        // 先把统计值取出来，window 的借用到这里就结束了，下面才能改 inner
        let elapsed = window.started.elapsed();
        let frames = window.frames;
        let bytes = window.bytes;

        if elapsed >= Duration::from_secs(1) {
            let secs = elapsed.as_secs_f32();
            inner.fps = frames as f32 / secs;
            inner.mbps = bytes as f32 * 8.0 / secs / 1_000_000.0;
            inner.window = None;
        }
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

fn capture_loop(hub: &VideoHub) -> Result<StopReason> {
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

        // 采集卡直出 JPEG，原样广播。send 在没有订阅者时会返回 Err，
        // 那不是错误，下面的 receiver_count 会处理退出。
        let _ = hub.frames.send(Bytes::copy_from_slice(&frame));
        hub.record_frame(frame.len());

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

fn pick_device(hint: &str) -> Result<CameraIndex> {
    let devices = query(ApiBackend::Auto).context("枚举采集设备失败")?;
    if devices.is_empty() {
        bail!("没有找到任何视频采集设备");
    }

    let picked = devices
        .iter()
        .find(|info| !hint.is_empty() && info.human_name().contains(hint))
        .unwrap_or(&devices[0]);
    Ok(picked.index().clone())
}
