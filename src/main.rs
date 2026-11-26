//! rust-kvm 服务端。
//!
//! 同一个 HTTPS 端口下按端点分工：`/ws/video` 推画面，`/ws/audio` 推声音，
//! `/ws/serial` 透传键鼠字节，`/api/*` 读状态与设备，其余路径当静态文件伺服。

mod atx;
mod audio;
mod camera;
mod certs;
mod config;
mod devices;
#[cfg(target_os = "linux")]
mod gadget_setup;
mod h264;
mod hid_emulator;
mod nv12;
mod serial_bridge;
#[cfg(windows)]
mod service;
#[cfg(target_os = "linux")]
mod systemd;
mod usb_gadget;
mod video;

use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use anyhow::{Context, Result};
use axum::Json;
use axum::Router;
use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::extract::{Query, State};
use axum::http::header;
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum_server::accept::NoDelayAcceptor;
use axum_server::tls_rustls::{RustlsAcceptor, RustlsConfig};
use futures_util::{SinkExt, StreamExt};
use tokio::sync::broadcast::error::RecvError;
use tokio::sync::watch;
use tower_http::services::ServeDir;
use tracing::{debug, info, warn};

use atx::{AtxRelay, PressError, PressKind};
use audio::{AudioHub, AudioStats};
use config::{Config, ConfigStore};
use devices::Devices;
use serial_bridge::{PortInfo, SerialHub};
use video::{VideoCodec, VideoHub, VideoStats};

/// 局域网访问走这个口，必须是 HTTPS：Keyboard Lock、剪贴板、Pointer Lock
/// 都要求 secure context。
const HTTPS_PORT: u16 = 8443;

/// 本机调试用的明文口，只绑回环。`localhost` 在浏览器里本身就算 secure
/// context，所以功能不缺，又省掉自签名证书的警告。故意不监听 0.0.0.0：
/// 否则局域网用户可能从这个口进来，然后莫名其妙地少掉一半功能。
const HTTP_PORT: u16 = 8080;

/// axum-server 0.8 起 `Handle` 带上了地址类型参数（为了同时支持 Unix socket）。
/// 我们只监听 TCP，固定成这一种，省得每处都写全。
pub(crate) type ServerHandle = axum_server::Handle<SocketAddr>;

#[derive(Clone)]
struct AppState {
    hub: Arc<VideoHub>,
    audio: Arc<AudioHub>,
    serial: Arc<SerialHub>,
    atx: Arc<AtxRelay>,
    config: Arc<ConfigStore>,
    cert_pem: Arc<Vec<u8>>,
}

#[cfg(windows)]
const USAGE: &str = "\
用法：
  rust-kvm              直接在当前窗口跑（Ctrl+C 停）
  rust-kvm install      注册成开机自启的服务（需要管理员权限）
  rust-kvm uninstall    卸载服务（需要管理员权限）
  rust-kvm start        启动服务
  rust-kvm stop         停止服务
  rust-kvm status       看服务状态和日志位置

装成服务不只是为了开机自启：服务跑在 session 0，那是唯一能枚举到物理音频设备
的地方——远程桌面会话里采集卡的音频口根本不可见。";

#[cfg(target_os = "linux")]
const USAGE: &str = "\
用法：
  rust-kvm                  直接在当前终端跑（Ctrl+C 停）
  sudo rust-kvm install     拷到 /usr/local/bin 并注册成开机自启的 systemd 服务
                            （在项目目录里跑，资源和配置留在这里）
  sudo rust-kvm uninstall   卸载服务，连同 /usr/local/bin 里那份
  sudo rust-kvm start       启动服务
  sudo rust-kvm stop        停止服务
  rust-kvm status           看服务状态和日志位置
  rust-kvm usb-gadget-setup <用户>
                            建本机 USB 口的 gadget（ARM 板上 systemd 启动前自动跑，不用手动）

服务以执行 install 的那个用户身份跑，附加 video / audio / dialout 组并允许实时
优先级——前台跑时设备权限来自登录会话，服务里没有那一层。";

#[cfg(not(any(windows, target_os = "linux")))]
const USAGE: &str = "\
用法：
  rust-kvm              直接在当前终端跑（Ctrl+C 停）";

fn main() -> Result<()> {
    match std::env::args().nth(1).as_deref() {
        None => {
            init_logging(None)?;
            block_on_server(ServerHandle::new())
        }
        Some("-h" | "--help" | "help") => {
            println!("{USAGE}");
            Ok(())
        }
        Some(command) => run_command(command),
    }
}

#[cfg(windows)]
fn run_command(command: &str) -> Result<()> {
    match command {
        "install" => service::install(),
        "uninstall" => service::uninstall(),
        "start" => service::start(),
        "stop" => service::stop(),
        "status" => service::status(),
        service::RUN_ARG => service::run_as_service(),
        other => {
            eprintln!("未知命令：{other}\n\n{USAGE}");
            std::process::exit(2);
        }
    }
}

#[cfg(target_os = "linux")]
fn run_command(command: &str) -> Result<()> {
    match command {
        "install" => systemd::install(),
        "uninstall" => systemd::uninstall(),
        "start" => systemd::start(),
        "stop" => systemd::stop(),
        "status" => systemd::status(),
        "usb-gadget-setup" => gadget_setup::run(std::env::args().nth(2).as_deref()),
        other => {
            eprintln!("未知命令：{other}\n\n{USAGE}");
            std::process::exit(2);
        }
    }
}

#[cfg(not(any(windows, target_os = "linux")))]
fn run_command(command: &str) -> Result<()> {
    anyhow::bail!("服务管理只在 Windows 和 Linux 上可用，收到命令：{command}")
}

/// 日志去处：控制台模式进 stdout，服务模式进文件（服务没有 stdout）。
pub(crate) fn init_logging(file: Option<&std::path::Path>) -> Result<()> {
    // 排查串口/采集问题时用 RUST_KVM_LOG=debug 起，能看到每一包的字节
    let level = std::env::var("RUST_KVM_LOG")
        .ok()
        .and_then(|value| value.parse().ok())
        .unwrap_or(tracing::Level::INFO);

    match file {
        Some(path) => {
            let sink = std::fs::File::create(path)
                .with_context(|| format!("建日志文件 {} 失败", path.display()))?;
            tracing_subscriber::fmt()
                .with_max_level(level)
                // 文件里不要 ANSI 转义，否则记事本打开满屏乱码
                .with_ansi(false)
                .with_writer(std::sync::Mutex::new(sink))
                .init();
        }
        None => {
            // systemd 把 stdout 接进 journal，那里的颜色转义会原样变成 `[2m` 之类的乱码
            use std::io::IsTerminal;
            tracing_subscriber::fmt()
                .with_max_level(level)
                .with_ansi(std::io::stdout().is_terminal())
                .init();
        }
    }
    info!("日志级别 {level}（改 RUST_KVM_LOG 可调）");
    Ok(())
}

/// 服务模式下 `main` 已经不是 async 了（SCM 要先握手再干活），运行时得自己建。
pub(crate) fn block_on_server(handle: ServerHandle) -> Result<()> {
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .context("建 tokio 运行时失败")?
        .block_on(run_server(handle))
}

async fn run_server(handle: ServerHandle) -> Result<()> {
    let root = asset_root();
    let cert = certs::load_or_create(&root.join("certs"))?;
    let tls = RustlsConfig::from_pem(cert.cert_pem.clone(), cert.key_pem.clone())
        .await
        .context("加载 TLS 证书失败")?;

    let store = Arc::new(ConfigStore::load(&root.join("config.json")));
    let settings = store.get();

    let state = AppState {
        hub: VideoHub::new(settings.video),
        audio: AudioHub::new(settings.audio),
        serial: SerialHub::new(),
        atx: AtxRelay::new(),
        config: store,
        cert_pem: Arc::new(cert.cert_pem),
    };

    let app = Router::new()
        .route("/ws/video", get(ws_video))
        .route("/ws/audio", get(ws_audio))
        .route("/ws/serial", get(ws_serial))
        .route("/api/video/stats", get(video_stats))
        .route("/api/video/capabilities", get(video_capabilities))
        .route("/api/video/measure", post(video_measure))
        .route("/api/audio/stats", get(audio_stats))
        .route("/api/serial/ports", get(serial_ports))
        .route("/api/devices", get(list_devices))
        .route("/api/config", get(get_config).put(put_config))
        .route("/api/atx/press", post(atx_press))
        .route("/api/atx/release", post(atx_release))
        .route("/cert.crt", get(download_cert))
        .fallback_service(ServeDir::new(root.join("web")))
        .layer(axum::middleware::map_response(no_cache))
        .with_state(state);

    info!("本机调试：http://localhost:{HTTP_PORT}/");
    info!("局域网访问：");
    for name in &cert.names {
        info!("  https://{name}:{HTTPS_PORT}/");
    }
    info!("自签名证书首次访问要点「高级 → 继续前往」，或装一下 /cert.crt");

    // 必须关 Nagle。键鼠包只有十几字节，应答只有七字节，Nagle 会把小包攒住等
    // 前一包被确认，和对端的延迟确认一撞最坏能压出几十毫秒——正好落在最能被
    // 感知到的地方。axum-server 备了这个 acceptor，但默认不启用。
    let https = axum_server::bind(SocketAddr::from(([0, 0, 0, 0], HTTPS_PORT)))
        .handle(handle.clone())
        .acceptor(RustlsAcceptor::new(tls).acceptor(NoDelayAcceptor::new()))
        .serve(app.clone().into_make_service());
    let http = axum_server::bind(SocketAddr::from(([127, 0, 0, 1], HTTP_PORT)))
        .handle(handle.clone())
        .acceptor(NoDelayAcceptor::new())
        .serve(app.into_make_service());

    // 控制台模式下 Ctrl+C 也走优雅关闭，主要是为了让串口线程把「全释放」包发出去：
    // 直接杀进程的话，被控机上按下的键会一直卡着。Windows 服务模式下这个 task 收不到
    // 信号，停止请求由 SCM 通过同一个 handle 送进来；systemd 则是发 SIGTERM。
    tokio::spawn(async move {
        if let Some(signal) = shutdown_signal().await {
            info!("收到 {signal}，开始收尾");
            handle.graceful_shutdown(Some(std::time::Duration::from_secs(3)));
        }
    });

    tokio::try_join!(https, http).context("HTTP 服务退出")?;
    info!("已停止");
    Ok(())
}

/// 等到该停的信号，返回它的名字；等不了（注册失败）就是 `None`，那就只能被硬杀。
async fn shutdown_signal() -> Option<&'static str> {
    #[cfg(unix)]
    {
        use tokio::signal::unix::{SignalKind, signal};
        // systemctl stop 和 kill 默认发的都是它，不接的话就是直接死，键会卡着
        if let Ok(mut terminate) = signal(SignalKind::terminate()) {
            return tokio::select! {
                result = tokio::signal::ctrl_c() => result.ok().map(|()| "Ctrl+C"),
                _ = terminate.recv() => Some("SIGTERM"),
            };
        }
    }
    tokio::signal::ctrl_c().await.ok().map(|()| "Ctrl+C")
}

async fn ws_video(ws: WebSocketUpgrade, State(state): State<AppState>) -> Response {
    ws.on_upgrade(move |socket| stream_video(socket, state.hub))
}

/// 先一条 Text 头说明编码，随后每条 Binary 是一帧。
///
/// 和 `/ws/audio` 先发格式头是同一个形状，客户端的处理结构照搬。改了配置就重发
/// 一条头而不断开连接——断开会让画面闪一下，而这只是改了个设置。
async fn stream_video(socket: WebSocket, hub: Arc<VideoHub>) {
    let mut codec_rx = hub.watch_codec();
    let (mut sender, mut receiver) = socket.split();

    // 收方向必须有人读：不读的话客户端发来的 close 和 ping 不会被处理，
    // 连接看起来就永远不会断，采集也跟着一直开着。
    let mut reader = tokio::spawn(async move { while let Some(Ok(_)) = receiver.next().await {} });

    // 换编码要重新起头（h264 得从一个 IDR 开始），所以每一轮都重新订阅
    loop {
        let wanted = codec_rx.borrow_and_update().wanted;
        let state = if wanted == VideoCodec::H264 {
            // 配置说 h264 不等于现在就能发：客户端要 avc1.XXXXXX 才配得出解码器，
            // 而那串东西得等编码器吐出第一个 SPS。这里等它，等不到就回落 MJPEG。
            //
            // 注意顺序：先订阅（让编码任务启动），再等。反了会永远等不到。
            let frames = hub.subscribe_h264();
            match await_codec(&mut codec_rx).await {
                Ok(state) => {
                    if send_codec_header(&mut sender, VideoCodec::H264, &state)
                        .await
                        .is_err()
                    {
                        break;
                    }
                    if pump_h264(&mut sender, &mut reader, &hub, frames, &mut codec_rx).await.is_err() {
                        break;
                    }
                    continue;
                }
                Err(state) => state,
            }
        } else {
            hub.codec_state()
        };

        if send_codec_header(&mut sender, VideoCodec::Mjpeg, &state)
            .await
            .is_err()
        {
            break;
        }
        if pump_mjpeg(&mut sender, &mut reader, &hub, &mut codec_rx)
            .await
            .is_err()
        {
            break;
        }
    }

    reader.abort();
}

/// 等编码器报出 codec 字符串。
///
/// `Ok` 表示可以发 h264；`Err` 带回当时的状态（里面有回落原因），调用方照它发
/// mjpeg 头。超时也算回落——等不到就该给画面，而不是让人对着空白页。
async fn await_codec(
    codec_rx: &mut watch::Receiver<video::CodecState>,
) -> Result<video::CodecState, video::CodecState> {
    let deadline = tokio::time::Instant::now() + video::CODEC_TIMEOUT;
    loop {
        {
            let state = codec_rx.borrow_and_update();
            if state.wanted != VideoCodec::H264 || state.fallback_reason.is_some() {
                return Err(state.clone());
            }
            if state.avc_codec.is_some() {
                return Ok(state.clone());
            }
        }
        if tokio::time::timeout_at(deadline, codec_rx.changed())
            .await
            .is_err()
        {
            let mut state = codec_rx.borrow().clone();
            if state.fallback_reason.is_none() {
                state.fallback_reason = Some(format!(
                    "编码器没能在 {} 秒内就绪",
                    video::CODEC_TIMEOUT.as_secs()
                ));
            }
            return Err(state);
        }
    }
}

/// 头里报的编码由**调用方**给，不在这里从 `state` 里猜。
///
/// 曾经在这儿按 `avc_codec.is_some()` 推断，结果是：编码任务和 `set_config` 抢
/// 同一份状态，切回 mjpeg 后残留了一个 avc 字符串，于是头说 h264 而 `pump_mjpeg`
/// 发的是 JPEG——客户端拿 JPEG 去喂 `VideoDecoder`，解码器一帧就死。
///
/// 教训是结构性的：头和帧必须出自**同一个决定**。分支已经知道自己要跑哪个 pump，
/// 让它把答案带过来，就不存在两者不一致的可能。
async fn send_codec_header(
    sender: &mut futures_util::stream::SplitSink<WebSocket, Message>,
    actual: VideoCodec,
    state: &video::CodecState,
) -> Result<(), ()> {
    let mut header = serde_json::json!({"op": "codec", "codec": actual.as_str()});
    if actual == VideoCodec::H264
        && let Some(codec) = &state.avc_codec
    {
        header["avcCodec"] = serde_json::json!(codec);
    }
    if let Some(reason) = &state.fallback_reason {
        header["fallbackReason"] = serde_json::json!(reason);
    }
    sender
        .send(Message::Text(header.to_string().into()))
        .await
        .map_err(|_| ())
}

/// 配置变了就返回，让外层重发头。
/// 画面静止时隔这么久发一条「还活着」。
///
/// 两个用处。一是告诉页面「没有新帧是因为画面没变，不是卡住了」——这两种状态在
/// 线上完全一样，而分不清会让人以为坏了。二是 NAT/代理会掐掉长时间没有流量的连接，
/// MJPEG 本来是局域网用的、这个风险不高，但代价只有十几个字节。
const IDLE_NOTICE: Duration = Duration::from_secs(2);

async fn pump_mjpeg(
    sender: &mut futures_util::stream::SplitSink<WebSocket, Message>,
    reader: &mut tokio::task::JoinHandle<()>,
    hub: &VideoHub,
    codec_rx: &mut watch::Receiver<video::CodecState>,
) -> Result<(), ()> {
    let mut frames = hub.subscribe();
    // 上一帧发出去的字节。**重复的帧不再发一遍**：采集卡对着静止画面吐的是逐字节
    // 相同的 JPEG（实测 6 秒 181 帧只有一个哈希），而客户端画布上已经是那一帧了，
    // 所以不发是无损的——省掉的就是整整 15.8 Mbps。
    //
    // 为什么按连接存、而不是在 hub 里存一份共用的：新观看者的 `last` 是空的，第一帧
    // 自然会发给他，不需要额外的「补一帧」逻辑。放在 hub 里反而要专门处理静止时新人
    // 进场（不处理就是黑屏），而那正是 H.264 那条路上「强制 IDR」解决的同一个问题。
    // 63 KB 的比对按连接各做一次也无所谓，一个观看者一秒不到 2 MB。
    let mut last: Option<bytes::Bytes> = None;
    let mut skipped_duplicates = 0u64;
    // **必须是绝对时刻，不能在 select 里现造 `sleep(IDLE_NOTICE)`。** 后者每轮循环
    // 都重新计时，而静止时重复帧仍在 33ms 一个地到——计时器永远归零，这条通知一次
    // 也发不出去，整个「看得出还活着」就白做了。
    let mut next_notice = tokio::time::Instant::now() + IDLE_NOTICE;
    loop {
        tokio::select! {
            _ = &mut *reader => return Err(()),
            _ = codec_rx.changed() => return Ok(()),
            // 静止时这条是唯一的出口，所以它也兼任「连接还在」的心跳
            _ = tokio::time::sleep_until(next_notice) => {
                let notice = serde_json::json!({"op": "idle", "skipped": skipped_duplicates});
                if sender.send(Message::Text(notice.to_string().into())).await.is_err() {
                    return Err(());
                }
                next_notice = tokio::time::Instant::now() + IDLE_NOTICE;
            }
            frame = frames.recv() => match frame {
                Ok(frame) => {
                    // 重复帧不重置上面那个计时器：静止多久就该照着节奏报多久
                    if last.as_ref() == Some(&frame) {
                        skipped_duplicates += 1;
                        continue;
                    }
                    last = Some(frame.clone());
                    next_notice = tokio::time::Instant::now() + IDLE_NOTICE;
                    hub.record_sent(frame.len());
                    // send 会在客户端慢的时候等着，这期间 broadcast 那头会积压
                    // 并触发下面的 Lagged，正好实现「慢客户端丢帧」。
                    if sender.send(Message::Binary(frame)).await.is_err() {
                        return Err(());
                    }
                }
                // MJPEG 每帧独立，丢最旧的是免费的降级
                Err(RecvError::Lagged(skipped)) => {
                    debug!("客户端落后，跳过 {skipped} 帧");
                }
                Err(RecvError::Closed) => return Err(()),
            },
        }
    }
}

async fn pump_h264(
    sender: &mut futures_util::stream::SplitSink<WebSocket, Message>,
    reader: &mut tokio::task::JoinHandle<()>,
    hub: &VideoHub,
    mut frames: tokio::sync::broadcast::Receiver<h264::EncodedFrame>,
    codec_rx: &mut watch::Receiver<video::CodecState>,
) -> Result<(), ()> {
    // 解码器必须从关键帧起头，否则什么都画不出来。订阅时已经请求过一个 IDR。
    let mut waiting_for_keyframe = true;
    loop {
        tokio::select! {
            _ = &mut *reader => return Err(()),
            _ = codec_rx.changed() => return Ok(()),
            frame = frames.recv() => match frame {
                Ok(packet) => {
                    if waiting_for_keyframe {
                        if !packet.keyframe {
                            continue;
                        }
                        waiting_for_keyframe = false;
                    }
                    hub.record_sent(packet.data.len());
                    if sender.send(Message::Binary(packet.data)).await.is_err() {
                        return Err(());
                    }
                }
                // **H.264 不能像 MJPEG 那样随便丢。** 丢一个 P 帧，后面整个 GOP
                // 全烂，一直烂到下一个 IDR。所以请编码器补一个，并且在它到达之前
                // 把帧全部丢掉——解码器在 IDR 上能重新同步，代价是一次可见的卡顿，
                // 而不是持续花屏。
                Err(RecvError::Lagged(skipped)) => {
                    warn!("客户端落后 {skipped} 帧，等一个关键帧再继续");
                    hub.request_keyframe();
                    waiting_for_keyframe = true;
                }
                Err(RecvError::Closed) => return Err(()),
            },
        }
    }
}

async fn video_stats(State(state): State<AppState>) -> Json<VideoStats> {
    Json(state.hub.stats())
}

#[derive(serde::Deserialize)]
struct DeviceQuery {
    #[serde(default)]
    device: String,
}

/// 能力表。Linux 上只枚举，不打断正在进行的采集。
async fn video_capabilities(Query(query): Query<DeviceQuery>) -> Json<video::CaptureCapabilities> {
    Json(video::list_capabilities(&query.device))
}

#[derive(serde::Deserialize)]
struct MeasureRequest {
    #[serde(default)]
    device: String,
    width: u32,
    height: u32,
    fps: u32,
}

/// 数一会儿真实画面。采集正开着就拒绝，免得把正在看的人掐掉。
async fn video_measure(
    State(state): State<AppState>,
    Json(request): Json<MeasureRequest>,
) -> Response {
    use axum::http::StatusCode;
    if request.width == 0 || request.height == 0 || request.fps == 0 || request.fps > 240 {
        return (StatusCode::BAD_REQUEST, "分辨率或帧率不对").into_response();
    }
    let hub = state.hub.clone();
    let device = request.device;
    let width = request.width;
    let height = request.height;
    let fps = request.fps;
    let measured = tokio::task::spawn_blocking(move || {
        video::measure_fps(&hub, &device, width, height, fps)
    })
    .await;
    match measured {
        Ok(Ok(fps)) => Json(serde_json::json!({ "fps": fps })).into_response(),
        Ok(Err(video::MeasureError::Busy)) => (
            StatusCode::CONFLICT,
            "采集卡正在使用。关掉控制页（开了常驻采集的话先关掉它），等采集停下再测这一档的帧率",
        )
            .into_response(),
        Ok(Err(video::MeasureError::Failed(error))) => {
            (StatusCode::INTERNAL_SERVER_ERROR, format!("{error:#}")).into_response()
        }
        Err(error) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            format!("实测线程异常退出: {error}"),
        )
            .into_response(),
    }
}

async fn ws_audio(ws: WebSocketUpgrade, State(state): State<AppState>) -> Response {
    ws.on_upgrade(move |socket| stream_audio(socket, state.audio))
}

/// 先一条 Text 说明采样率和声道，随后每条 Binary 都是一块 Int16 LE 裸 PCM。
/// 头必须严格早于第一块 PCM，否则客户端不知道按什么采样率建 AudioContext。
///
/// 格式中途会变（设置页开关窄带音频），所以和 `/ws/video` 一样是个循环：格式变了
/// 就**重发一条头**并重建播放端，不断开连接。只发一次头是不行的——客户端会拿
/// 48k 立体声的参数去播 24k 单声道的字节，听起来是变调加噼啪，而且没有任何报错。
async fn stream_audio(socket: WebSocket, hub: Arc<AudioHub>) {
    // 先订阅，让采集开起来；格式要等采集真的打开才知道
    let mut chunks = hub.subscribe();
    let mut format_rx = hub.watch_format();
    let (mut sender, mut receiver) = socket.split();

    // 和 /ws/video 同理：收方向必须有人读，否则 close 不会被处理，
    // 连接看起来永远不断，设备也就一直开着。
    let mut reader = tokio::spawn(async move { while let Some(Ok(_)) = receiver.next().await {} });

    // 已经发出去的格式。**采音每重起一次这个循环就会转一圈**（设备打嗝、热插拔
    // 恢复都算），而那些多数是同一个格式；重发一条一样的头会让客户端白白关掉再
    // 新建一次 AudioContext，那是一段听得见的空白。所以只在真的变了时才发。
    let mut sent: Option<audio::AudioFormat> = None;

    loop {
        let format = tokio::select! {
            _ = &mut reader => break,
            format = audio::await_format(&hub, &mut format_rx) => format,
        };
        let format = match format {
            Ok(format) => format,
            Err(message) => {
                info!("音频开不起来: {message}");
                let payload = serde_json::json!({"op": "error", "message": message});
                let _ = sender.send(Message::Text(payload.to_string().into())).await;
                break;
            }
        };

        if sent != Some(format) {
            let header = serde_json::json!({
                "op": "format",
                "sampleRate": format.sample_rate,
                "channels": format.channels,
                "format": "s16le",
            });
            if sender
                .send(Message::Text(header.to_string().into()))
                .await
                .is_err()
            {
                break;
            }
            sent = Some(format);
        }

        // 等格式期间已经积了一点 PCM，这里先丢掉。第一次是因为那些是「头之前」的
        // 声音，留着只会让播放起点白白多出一段延迟；换格式那次更要紧——积压里可能
        // 还有**旧格式**的字节，按新头去播就是一段噪音。
        drain(&mut chunks);

        if pump_audio(&mut sender, &mut reader, &mut chunks, &mut format_rx).await == PumpEnd::Done {
            break;
        }
    }

    reader.abort();
}

#[derive(PartialEq, Eq)]
enum PumpEnd {
    /// 连接没了，收摊
    Done,
    /// 格式变了，回去重发头
    FormatChanged,
}

async fn pump_audio(
    sender: &mut futures_util::stream::SplitSink<WebSocket, Message>,
    reader: &mut tokio::task::JoinHandle<()>,
    chunks: &mut tokio::sync::broadcast::Receiver<bytes::Bytes>,
    format_rx: &mut watch::Receiver<Option<audio::AudioFormat>>,
) -> PumpEnd {
    loop {
        tokio::select! {
            _ = &mut *reader => return PumpEnd::Done,
            _ = format_rx.changed() => return PumpEnd::FormatChanged,
            chunk = chunks.recv() => match chunk {
                Ok(chunk) => {
                    if sender.send(Message::Binary(chunk)).await.is_err() {
                        return PumpEnd::Done;
                    }
                }
                // 音频丢块就是一声爆音，但落后了更不能追——追只会让延迟一直挂着
                Err(RecvError::Lagged(skipped)) => {
                    debug!("音频客户端落后，丢掉 {skipped} 块");
                }
                Err(RecvError::Closed) => return PumpEnd::Done,
            },
        }
    }
}

/// 丢到真的空了为止。
///
/// `Lagged` 不代表空——它只是说「中间丢了一些」，后面还有。拿 `is_ok()` 当条件的话
/// 会在这儿提前收手，而这个函数存在的意义正是「一个旧字节都不留」。
fn drain(chunks: &mut tokio::sync::broadcast::Receiver<bytes::Bytes>) {
    use tokio::sync::broadcast::error::TryRecvError;
    loop {
        match chunks.try_recv() {
            Ok(_) | Err(TryRecvError::Lagged(_)) => continue,
            Err(_) => return,
        }
    }
}

async fn audio_stats(State(state): State<AppState>) -> Json<AudioStats> {
    Json(state.audio.stats())
}

/// 键鼠桥。协议帧由浏览器里的 `ch9329.js` 组好，这里只管开口和透传字节。
///
/// `?takeover=1` 表示「把控制权从现任手里抢过来」。放在查询串里而不是握手后的
/// 某条消息里，是因为争用在升级完成的那一刻就要判定——真要做成消息，服务端就得
/// 先让所有人连进来、再挑一个，多一种中间状态。
async fn ws_serial(
    ws: WebSocketUpgrade,
    Query(params): Query<SerialParams>,
    State(state): State<AppState>,
) -> Response {
    let takeover = params.takeover();
    ws.on_upgrade(move |socket| serial_bridge::handle(socket, state.serial, takeover))
}

#[derive(serde::Deserialize)]
struct SerialParams {
    /// 缺省就是不抢。自动连接走的正是这条路：页面一打开就抢别人的控制权，
    /// 那是没人想要的行为。
    ///
    /// 故意收字符串而不是 `bool`：`Query` 背后的 serde_urlencoded 对 `bool` 只认
    /// `"true"`/`"false"`，`?takeover=1` 会让**整个升级请求变成 400**，而客户端那头
    /// 只能看到一个没有任何说明的 1006 断开。这个坑真踩到了，查了一会儿才发现
    /// 问题出在参数解析上而不是接管逻辑里。
    takeover: Option<String>,
}

impl SerialParams {
    fn takeover(&self) -> bool {
        // 空串是为了 `?takeover` 这种只给名字不给值的写法
        matches!(self.takeover.as_deref(), Some("1" | "true" | "on" | ""))
    }
}

/// 所有响应都带 `Cache-Control: no-cache`。
///
/// `ServeDir` 默认什么缓存头都不发，浏览器于是按启发式规则自己决定能存多久，
/// 结果是改完 js 刷新页面**还是旧的**——而且没有任何迹象表明发生了什么，
/// 只会看到刚修好的 bug 又出现。这个坑真踩到了：改完 serial-ws.js 之后
/// 页面跑的仍是旧版，排查了半天才发现问题不在代码里。
///
/// `no-cache` 不是「不缓存」，是「每次都回来问一句」。ServeDir 照样发 ETag，
/// 文件没变就是一个 304，几乎不花什么代价。
async fn no_cache(mut response: Response) -> Response {
    response.headers_mut().insert(
        header::CACHE_CONTROL,
        header::HeaderValue::from_static("no-cache"),
    );
    response
}

/// 远程浏览器没有宿主机的 Web Serial 权限，选口只能靠服务端枚举。
async fn serial_ports() -> Json<Vec<PortInfo>> {
    Json(serial_bridge::list_ports())
}

/// 每次请求都重新枚举，不缓存：设备是会被插拔的，而设置页上那个列表过期了
/// 就直接导致选不到设备。
async fn list_devices() -> Json<Devices> {
    Json(devices::enumerate())
}

async fn get_config(State(state): State<AppState>) -> Json<Config> {
    Json(state.config.get())
}

/// 保存后立刻热切换。两个 hub 自己判断配置是否真的变了，没变就不动采集，
/// 所以只改了串口的一次保存不会把画面和声音掐一下。
async fn put_config(
    State(state): State<AppState>,
    Json(mut config): Json<Config>,
) -> Result<Json<Config>, Response> {
    config.atx = atx::normalize(config.atx);
    // 同一个口打不开两次，存进去只会让电源键和键鼠互相抢；更怕的是用户以为接好了
    if config
        .atx
        .as_ref()
        .is_some_and(|relay| relay.port == config.serial.port)
    {
        return Err((
            axum::http::StatusCode::BAD_REQUEST,
            "继电器和 CH9329 不能是同一个串口",
        )
            .into_response());
    }
    state.config.save(config.clone()).map_err(|e| {
        (
            axum::http::StatusCode::INTERNAL_SERVER_ERROR,
            format!("{e:#}"),
        )
            .into_response()
    })?;

    state.hub.set_config(config.video.clone());
    state.audio.set_config(config.audio.clone());
    // 串口不在这儿热切换：口是由浏览器通过 /ws/serial 的 open 打开的，
    // 配置只是给页面一个默认值，下次连接自然生效。
    Ok(Json(config))
}

#[derive(serde::Deserialize)]
struct PressRequest {
    kind: PressKind,
}

/// 按完才返回，长按要等 6 秒。请求中途断开不影响按压本身，断开指令照样会发
async fn atx_press(State(state): State<AppState>, Json(request): Json<PressRequest>) -> Response {
    use axum::http::StatusCode;
    let Some(settings) = state.config.get().atx else {
        return (StatusCode::CONFLICT, "没有设置继电器，在设置页「ATX 电源」里选").into_response();
    };
    match state.atx.press(&settings, request.kind).await {
        Ok(released_early) => {
            Json(serde_json::json!({ "releasedEarly": released_early })).into_response()
        }
        Err(PressError::Busy) => {
            (StatusCode::CONFLICT, "继电器正按着，等这一下结束").into_response()
        }
        Err(PressError::NoResetChannel) => {
            (StatusCode::BAD_REQUEST, "没有设置复位键通道").into_response()
        }
        Err(PressError::Failed(error)) => {
            (StatusCode::INTERNAL_SERVER_ERROR, format!("{error:#}")).into_response()
        }
    }
}

async fn atx_release(State(state): State<AppState>) -> Json<serde_json::Value> {
    Json(serde_json::json!({ "released": state.atx.release() }))
}

/// 方便把证书装进客户端的信任区，装了之后就不用每次点「继续前往」。
async fn download_cert(State(state): State<AppState>) -> Response {
    (
        [
            (header::CONTENT_TYPE, "application/x-x509-ca-cert"),
            (
                header::CONTENT_DISPOSITION,
                "attachment; filename=\"rust-kvm.crt\"",
            ),
        ],
        state.cert_pem.as_ref().clone(),
    )
        .into_response()
}

/// 资源目录（`web/`、`certs/`、`config.json`）。
///
/// 从 exe 所在目录往上找第一个含 `web/` 的目录。「往上找」而不是「就看旁边」，
/// 是因为 `cargo run` 时 exe 在 `target/debug/` 里，资源在两层之上。
///
/// **不能依赖当前工作目录**：服务是被 SCM 拉起来的，工作目录是
/// `C:\Windows\System32`，那里既没有 `web/` 也不该往里写证书和日志。
/// systemd 默认的工作目录是 `/`，同理。
/// 只有实在找不到时才退回 `.`，那种情况下多半是 exe 被单独拷走了。
pub(crate) fn asset_root() -> PathBuf {
    if let Ok(exe) = std::env::current_exe() {
        for dir in exe.ancestors().skip(1).take(4) {
            if dir.join("web").is_dir() {
                return dir.to_path_buf();
            }
        }
    }
    PathBuf::from(".")
}
