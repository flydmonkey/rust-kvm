//! rust-kvm 服务端。
//!
//! 同一个 HTTPS 端口下按端点分工：`/ws/video` 推画面，`/ws/audio` 推声音，
//! `/ws/serial` 透传键鼠字节，`/api/*` 读状态与设备，其余路径当静态文件伺服。

mod audio;
mod certs;
mod config;
mod devices;
mod serial_bridge;
#[cfg(windows)]
mod service;
mod video;

use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::Arc;

use anyhow::{Context, Result};
use axum::Json;
use axum::Router;
use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::extract::{Query, State};
use axum::http::header;
use axum::response::{IntoResponse, Response};
use axum::routing::get;
use axum_server::accept::NoDelayAcceptor;
use axum_server::tls_rustls::{RustlsAcceptor, RustlsConfig};
use futures_util::{SinkExt, StreamExt};
use tokio::sync::broadcast::error::RecvError;
use tower_http::services::ServeDir;
use tracing::{debug, info};

use audio::{AudioHub, AudioStats};
use config::{Config, ConfigStore};
use devices::Devices;
use serial_bridge::{PortInfo, SerialHub};
use video::{VideoHub, VideoStats};

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
    config: Arc<ConfigStore>,
    cert_pem: Arc<Vec<u8>>,
}

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

#[cfg(not(windows))]
fn run_command(command: &str) -> Result<()> {
    anyhow::bail!("服务管理只在 Windows 上可用，收到命令：{command}")
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
            tracing_subscriber::fmt().with_max_level(level).init();
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
        config: store,
        cert_pem: Arc::new(cert.cert_pem),
    };

    let app = Router::new()
        .route("/ws/video", get(ws_video))
        .route("/ws/audio", get(ws_audio))
        .route("/ws/serial", get(ws_serial))
        .route("/api/video/stats", get(video_stats))
        .route("/api/audio/stats", get(audio_stats))
        .route("/api/serial/ports", get(serial_ports))
        .route("/api/devices", get(list_devices))
        .route("/api/config", get(get_config).put(put_config))
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
    // 直接杀进程的话，被控机上按下的键会一直卡着。服务模式下这个 task 收不到信号，
    // 停止请求由 SCM 通过同一个 handle 送进来。
    tokio::spawn(async move {
        if tokio::signal::ctrl_c().await.is_ok() {
            info!("收到 Ctrl+C，开始收尾");
            handle.graceful_shutdown(Some(std::time::Duration::from_secs(3)));
        }
    });

    tokio::try_join!(https, http).context("HTTP 服务退出")?;
    info!("已停止");
    Ok(())
}

async fn ws_video(ws: WebSocketUpgrade, State(state): State<AppState>) -> Response {
    ws.on_upgrade(move |socket| stream_video(socket, state.hub))
}

/// 每帧一条 Binary 消息，内容就是采集卡直出的 JPEG。
async fn stream_video(socket: WebSocket, hub: Arc<VideoHub>) {
    let mut frames = hub.subscribe();
    let (mut sender, mut receiver) = socket.split();

    // 收方向必须有人读：不读的话客户端发来的 close 和 ping 不会被处理，
    // 连接看起来就永远不会断，采集也跟着一直开着。
    let mut reader = tokio::spawn(async move { while let Some(Ok(_)) = receiver.next().await {} });

    loop {
        tokio::select! {
            _ = &mut reader => break,
            frame = frames.recv() => match frame {
                Ok(frame) => {
                    // send 会在客户端慢的时候等着，这期间 broadcast 那头会积压
                    // 并触发下面的 Lagged，正好实现「慢客户端丢帧」。
                    if sender.send(Message::Binary(frame)).await.is_err() {
                        break;
                    }
                }
                Err(RecvError::Lagged(skipped)) => {
                    debug!("客户端落后，跳过 {skipped} 帧");
                }
                Err(RecvError::Closed) => break,
            },
        }
    }

    reader.abort();
}

async fn video_stats(State(state): State<AppState>) -> Json<VideoStats> {
    Json(state.hub.stats())
}

async fn ws_audio(ws: WebSocketUpgrade, State(state): State<AppState>) -> Response {
    ws.on_upgrade(move |socket| stream_audio(socket, state.audio))
}

/// 先一条 Text 说明采样率和声道，随后每条 Binary 都是一块 Int16 LE 裸 PCM。
/// 头必须严格早于第一块 PCM，否则客户端不知道按什么采样率建 AudioContext。
async fn stream_audio(socket: WebSocket, hub: Arc<AudioHub>) {
    // 先订阅，让采集开起来；格式要等采集真的打开才知道
    let mut chunks = hub.subscribe();
    let mut format_rx = hub.watch_format();
    let (mut sender, mut receiver) = socket.split();

    // 和 /ws/video 同理：收方向必须有人读，否则 close 不会被处理，
    // 连接看起来永远不断，设备也就一直开着。
    let mut reader = tokio::spawn(async move { while let Some(Ok(_)) = receiver.next().await {} });

    let format = tokio::select! {
        _ = &mut reader => return,
        format = audio::await_format(&hub, &mut format_rx) => format,
    };
    let format = match format {
        Ok(format) => format,
        Err(message) => {
            info!("音频开不起来: {message}");
            let payload = serde_json::json!({"op": "error", "message": message});
            let _ = sender.send(Message::Text(payload.to_string().into())).await;
            reader.abort();
            return;
        }
    };

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
        reader.abort();
        return;
    }

    // 等格式期间已经积了一点 PCM，这里先丢掉：那些是「头之前」的声音，
    // 留着只会让播放起点白白多出一段延迟。
    while chunks.try_recv().is_ok() {}

    loop {
        tokio::select! {
            _ = &mut reader => break,
            chunk = chunks.recv() => match chunk {
                Ok(chunk) => {
                    if sender.send(Message::Binary(chunk)).await.is_err() {
                        break;
                    }
                }
                Err(RecvError::Lagged(skipped)) => {
                    debug!("音频客户端落后，丢掉 {skipped} 块");
                }
                Err(RecvError::Closed) => break,
            },
        }
    }

    reader.abort();
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
    Json(config): Json<Config>,
) -> Result<Json<Config>, Response> {
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
