//! 串口桥：服务端独占打开 COM 口，`/ws/serial` 两头透传字节。
//!
//! 这里刻意不认识 CH9329 协议——组帧、校验、ACK、波特率探测全在浏览器的
//! `ch9329.js` 里。服务端只保证两件事：口是我开的（独占、能换波特率），
//! 字节原样进出。唯一的例外是断线松键的兜底包，见 `RELEASE_FRAMES`。

use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc::{self as blocking_mpsc, RecvTimeoutError};
use std::time::{Duration, Instant};

use anyhow::{Context, Result};
use axum::extract::ws::{CloseFrame, Message, WebSocket};
use bytes::Bytes;
use futures_util::{SinkExt, StreamExt};
use serde::{Deserialize, Serialize};
use serialport::{SerialPort, SerialPortType};
use tokio::sync::{Notify, mpsc, oneshot};
use tracing::{debug, info, warn};

/// 芯片按「空闲超过 3ms 就认为一包结束」来分包（协议里的串口包间隔，默认
/// 3ms）。客户端的 `ch9329.js` 已经按 4ms 排队发，但网络抖动会把两包挤到
/// 一起到达，那样芯片会把它们粘成一包，然后越解析越乱。所以出串口之前再
/// 兜一次底，和客户端取同一个值。
const PACKET_GAP: Duration = Duration::from_millis(4);

/// 串口线程的轮询节拍：既是「等下一个写请求」的上限，也是查读缓冲的间隔。
const POLL_INTERVAL: Duration = Duration::from_millis(1);

/// 单次从串口读走的上限。CH9329 的应答帧最长十几字节，256 足够一次读干净。
const READ_CHUNK: usize = 256;

/// 把系统定时器精度提到 1ms，并且一直保持。
///
/// 不这么做的话下面 `pump` 里那个「1ms 轮询」是假的：Windows 默认的定时器
/// 粒度是 **15.6ms**，`recv_timeout(1ms)` 实际要睡满一个 tick 才醒，循环于是
/// 只有 64Hz。后果是芯片的应答最多晚 15.6ms 才被发现，而 `ch9329.js` 在等应答
/// 模式下要收到上一包的回复才发下一包——整条键鼠链路就被钉死在 64 包/秒，
/// 实测往返 15.5ms（方差极小，正是 tick 的指纹），比硬件本身慢一倍还多。
///
/// 这是治标还是治本？是治本的：真正的病根是 `serialport` 在 Windows 上用
/// `CreateFileW` 开的是**同步句柄**（没有 `FILE_FLAG_OVERLAPPED`），一个线程
/// 没法同时等通道和串口，只能轮询；而 COM 口是独占设备，也没法开第二个文件
/// 对象绕开。要彻底摆脱轮询得自己写重叠 I/O，那是另一个量级的工作。在轮询这
/// 个前提下，让轮询真的按 1ms 走就是正解。
///
/// 代价是这个进程的定时器变密（Windows 10 2004 起是按进程计的，不再影响全
/// 系统）。对一个本来就在 30fps 推流的服务来说，这点开销无所谓。
#[cfg(windows)]
fn raise_timer_resolution() {
    use std::sync::Once;
    static ONCE: Once = Once::new();
    ONCE.call_once(|| {
        // 不配对调用 timeEndPeriod：服务活着的时候就该一直是低延迟的
        let result = unsafe { windows_sys::Win32::Media::timeBeginPeriod(1) };
        if result == 0 {
            info!("系统定时器精度已提到 1ms");
        } else {
            warn!("提高定时器精度失败，键鼠往返会多出十几毫秒");
        }
    });
}

#[cfg(not(windows))]
fn raise_timer_resolution() {}

/// 控制端发来 `open` 之后等串口真正打开的上限。
const OPEN_TIMEOUT: Duration = Duration::from_secs(3);

/// 等控制权的上限。不能一上来就拒：上一个控制端断开后，它的任务还要下发全
/// 释放包、等串口线程归还句柄，前后几十到几百毫秒。刷新页面正好落在这个窗口
/// 里，秒拒会让用户以为「明明没人用却说被占了」。等不到才是真的有人在控制。
const CONTROL_WAIT: Duration = Duration::from_secs(2);

/// 接管时等现任控制端退位的上限。比 `CONTROL_WAIT` 宽：现任收到退位信号后还要
/// 下发全释放包、关串口、归还句柄。
const TAKEOVER_WAIT: Duration = Duration::from_secs(5);

// ---------------------------------------------------------------------------
// 串口枚举
// ---------------------------------------------------------------------------

#[derive(Serialize)]
pub struct PortInfo {
    pub name: String,
    pub description: Option<String>,
}

pub fn list_ports() -> Vec<PortInfo> {
    let ports = match serialport::available_ports() {
        Ok(ports) => ports,
        Err(error) => {
            warn!("枚举串口失败：{error}");
            return Vec::new();
        }
    };

    ports
        .into_iter()
        .filter(|port| has_hardware(&port.port_name))
        .map(|port| {
            // USB 转串口（CH340 之类）能报出产品名，认口比 COM 号直观得多
            let description = match port.port_type {
                SerialPortType::UsbPort(usb) => usb.product.or(usb.manufacturer),
                _ => None,
            };
            PortInfo {
                name: port.port_name,
                description,
            }
        })
        .collect()
}

/// Linux 上内核不管有没有硬件，都会预先建好 `ttyS0`–`ttyS31` 这一排传统 UART 节点，
/// 不筛的话下拉框里三十多项，真正的 USB 串口被淹在中间。sysfs 的 `type` 是 0
/// （`PORT_UNKNOWN`）就是没探到芯片，那种口打开了也收发不了任何东西。
#[cfg(target_os = "linux")]
fn has_hardware(port_name: &str) -> bool {
    let Some(name) = port_name.strip_prefix("/dev/") else {
        return true;
    };
    match std::fs::read_to_string(format!("/sys/class/tty/{name}/type")) {
        Ok(kind) => kind.trim() != "0",
        // 只有 8250 这类 UART 驱动才有这个属性，USB 串口没有，一律留着
        Err(_) => true,
    }
}

#[cfg(not(target_os = "linux"))]
fn has_hardware(_port_name: &str) -> bool {
    true
}

// ---------------------------------------------------------------------------
// 已打开的串口
// ---------------------------------------------------------------------------

struct SerialLink {
    port: String,
    baud_rate: u32,
    writes: blocking_mpsc::Sender<Bytes>,
    /// 串口线程退出时会往这里发一下，`shutdown` 靠它确认句柄真的还回去了。
    finished: oneshot::Receiver<()>,
    /// 串口段最近一次往返（微秒），0 表示还没测到。
    ///
    /// 只有服务端量得准：CH9329 是严格的一问一答，而且就在串口线程眼皮底下，
    /// 发和收天然配对。浏览器那边一旦不等应答就配不上对了，所以这个数字必须
    /// 从这里报上去。
    round_trip_us: Arc<AtomicU64>,
}

impl SerialLink {
    fn open(port: &str, baud_rate: u32) -> Result<(Self, mpsc::Receiver<Bytes>)> {
        raise_timer_resolution();

        let mut handle = serialport::new(port, baud_rate)
            .timeout(POLL_INTERVAL)
            .open()
            .with_context(|| format!("打开 {port} 失败"))?;

        let (write_tx, write_rx) = blocking_mpsc::channel::<Bytes>();
        let (read_tx, read_rx) = mpsc::channel::<Bytes>(64);
        let (done_tx, done_rx) = oneshot::channel();
        let round_trip_us = Arc::new(AtomicU64::new(0));

        let name = port.to_string();
        let thread_round_trip = round_trip_us.clone();
        std::thread::Builder::new()
            .name(format!("serial-{port}"))
            .spawn(move || {
                if let Err(error) = pump(&mut *handle, write_rx, read_tx, &thread_round_trip) {
                    warn!("串口 {name} 读写中断：{error:#}");
                }
                drop(handle);
                info!("串口 {name} 已关闭");
                let _ = done_tx.send(());
            })
            .context("启动串口线程失败")?;

        info!("串口 {port} 已打开（{baud_rate} bps）");
        Ok((
            Self {
                port: port.to_string(),
                baud_rate,
                writes: write_tx,
                finished: done_rx,
                round_trip_us,
            },
            read_rx,
        ))
    }

    /// 返回 false 表示串口线程已经不在了。
    fn write(&self, bytes: Bytes) -> bool {
        self.writes.send(bytes).is_ok()
    }

    /// 关闭并等线程真的把句柄还回去。**必须等**：串口是独占设备，探测波特率
    /// 时会立刻用新速率重开同一个口，句柄没释放就会撞上「拒绝访问」。
    async fn shutdown(self) {
        // 发送端一丢，线程下一轮 recv_timeout 就会看到 Disconnected 并退出
        drop(self.writes);
        let _ = tokio::time::timeout(Duration::from_secs(2), self.finished).await;
    }
}

/// 单线程同时管读写。
///
/// 不用「读线程 + 写线程」是因为 Windows 上的串口句柄是同步的：一个阻塞在
/// `ReadFile` 里的读会把同一句柄上的写一起挡住，`try_clone` 复制出来的句柄
/// 指向同一个文件对象，也躲不开。所以改成一个线程轮询——先看有没有要写的，
/// 再看读缓冲里有没有数据。
fn pump(
    port: &mut dyn SerialPort,
    writes: blocking_mpsc::Receiver<Bytes>,
    reads: mpsc::Sender<Bytes>,
    round_trip_us: &AtomicU64,
) -> Result<()> {
    let mut buf = [0u8; READ_CHUNK];
    let mut last_write: Option<Instant> = None;
    // 和 last_write 分开：那个是给包间隔用的、从不清空，这个一收到回复就消耗掉
    let mut awaiting_reply: Option<Instant> = None;

    loop {
        // recv_timeout 兼作读轮询的节拍：有活干就立刻干，没活就每 1ms 看一眼
        match writes.recv_timeout(POLL_INTERVAL) {
            Ok(bytes) => {
                if let Some(at) = last_write {
                    let since = at.elapsed();
                    if since < PACKET_GAP {
                        std::thread::sleep(PACKET_GAP - since);
                    }
                }
                port.write_all(&bytes).context("写串口失败")?;
                port.flush().context("刷串口失败")?;
                let at = Instant::now();
                last_write = Some(at);
                awaiting_reply = Some(at);
                debug!("TX {}", hex(&bytes));
            }
            Err(RecvTimeoutError::Timeout) => {}
            Err(RecvTimeoutError::Disconnected) => return Ok(()),
        }

        // 先问有没有数据再读：直接 read 会阻塞到超时，那段时间写请求只能干等
        if port.bytes_to_read().unwrap_or(0) == 0 {
            continue;
        }
        let read = match port.read(&mut buf) {
            Ok(0) => continue,
            Ok(read) => read,
            Err(error) if error.kind() == std::io::ErrorKind::TimedOut => continue,
            Err(error) => return Err(error).context("读串口失败"),
        };
        if let Some(at) = awaiting_reply.take() {
            round_trip_us.store(at.elapsed().as_micros() as u64, Ordering::Relaxed);
        }
        debug!("RX {}", hex(&buf[..read]));
        if reads.blocking_send(Bytes::copy_from_slice(&buf[..read])).is_err() {
            return Ok(());
        }
    }
}

fn hex(bytes: &[u8]) -> String {
    bytes
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect::<Vec<_>>()
        .join(" ")
}

// ---------------------------------------------------------------------------
// WebSocket 协议
// ---------------------------------------------------------------------------

/// 控制端发来的文本消息。二进制消息一律当作要写到串口的原始字节。
///
/// 没有单独的 `setBaud`：`open` 本身带波特率且可重复调用，服务端会先关再开，
/// 换速走的就是这一条路。`Ch9329.connect` 探测波特率时也正是反复调 `open`。
#[derive(Deserialize)]
#[serde(tag = "op", rename_all = "camelCase", rename_all_fields = "camelCase")]
enum ClientOp {
    Open { port: String, baud_rate: u32 },
    Close,
    /// 客户端量网络往返用。走这条 WebSocket 而不是另开一条，量到的才是键鼠
    /// 实际走的那条连接。
    Ping,
}

#[derive(Serialize)]
#[serde(tag = "op", rename_all = "camelCase", rename_all_fields = "camelCase")]
enum ServerOp {
    Status {
        open: bool,
        port: Option<String>,
        baud_rate: Option<u32>,
    },
    Error {
        message: String,
        /// 机器可读的原因，只在客户端需要据此改变行为时才带。目前唯一的取值是
        /// `"busy"`（串口被别的控制端占着，或者自己刚被接管），页面靠它把状态
        /// 显示成「可点击接管」而不是一条干巴巴的错误。其余错误不带这个字段。
        #[serde(skip_serializing_if = "Option::is_none")]
        code: Option<&'static str>,
    },
    /// 顺带把串口段的往返捎回去。浏览器不等应答之后自己配不上对，这个数只有
    /// 服务端量得准。
    Pong {
        serial_us: Option<u64>,
    },
}

impl ServerOp {
    fn closed() -> Self {
        Self::Status {
            open: false,
            port: None,
            baud_rate: None,
        }
    }

    fn error(message: impl Into<String>) -> Self {
        Self::Error {
            message: message.into(),
            code: None,
        }
    }

    /// 串口被别的控制端占着。带上 `busy` 让页面给出「接管」这个动作。
    fn busy(message: impl Into<String>) -> Self {
        Self::Error {
            message: message.into(),
            code: Some("busy"),
        }
    }

    fn into_message(self) -> Message {
        // 这几个结构都是纯数据，序列化不会失败
        Message::Text(serde_json::to_string(&self).unwrap_or_default().into())
    }
}

pub struct SerialHub {
    /// 拿到这把锁才算持有控制权，一直持到控制端断开。
    seat: Arc<tokio::sync::Mutex<()>>,
    /// 现任控制端的退位信号。接管请求敲这个。
    ///
    /// 只用信号量是不够的：**信号量能等不能赶**，唯一的退出路径是持有者自己
    /// 放手。而持有者可能是另一台设备上、用户根本碰不到的标签页——这个真踩到
    /// 了：我这边一个自动连接的标签页占了串口一小时，另一头反复重试十几次，
    /// 每次只收到「请先关掉那个标签页」，而那个标签页他压根碰不到。
    evict: std::sync::Mutex<Option<Arc<Notify>>>,
}

/// 持有期间代表控制权。drop 时把座位和退位信号一起交还。
struct ControlSeat {
    hub: Arc<SerialHub>,
    /// 现任专属。每任一个新的，所以敲到上一任身上的信号不会误伤下一任。
    notify: Arc<Notify>,
    /// 只为了持有而持有：它在，座位就是我们的。
    _guard: tokio::sync::OwnedMutexGuard<()>,
}

impl Drop for ControlSeat {
    fn drop(&mut self) {
        // 这时 `_guard` 还没释放（字段在 Drop::drop 之后才析构），所以别人不可能
        // 已经坐进来，槽里只会是我们自己这一份。
        *self.hub.evict.lock().expect("evict 锁") = None;
    }
}

impl SerialHub {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            seat: Arc::new(tokio::sync::Mutex::new(())),
            evict: std::sync::Mutex::new(None),
        })
    }

    /// 取得控制权，拿不到返回 `None`。`takeover` 为真时先请现任退位。
    async fn claim(self: &Arc<Self>, takeover: bool) -> Option<ControlSeat> {
        let wait = if takeover {
            // 现任的 select 里有一个分支在等这个信号，收到就收摊走人。
            // 现任没在 await 也没关系：notify_one 会留一张许可给它下次 await。
            if let Some(current) = self.evict.lock().expect("evict 锁").clone() {
                info!("收到接管请求，正在请现任控制端退位");
                current.notify_one();
            }
            TAKEOVER_WAIT
        } else {
            CONTROL_WAIT
        };

        let guard = tokio::time::timeout(wait, self.seat.clone().lock_owned())
            .await
            .ok()?;

        let notify = Arc::new(Notify::new());
        *self.evict.lock().expect("evict 锁") = Some(notify.clone());
        Some(ControlSeat {
            hub: self.clone(),
            notify,
            _guard: guard,
        })
    }
}

pub async fn handle(socket: WebSocket, hub: Arc<SerialHub>, takeover: bool) {
    let (mut sender, mut receiver) = socket.split();

    // 等不到座位说明真的有人在控制。纯观看的客户端不连这个端点，所以被挡在
    // 外面的只会是第二个想控制的人。
    let Some(seat) = hub.claim(takeover).await else {
        info!("已有控制端，拒绝新的 /ws/serial 连接（takeover={takeover}）");
        let _ = sender
            .send(
                // 只陈述事实，不写「接下来做什么」：这条会被原样显示，而同一条
                // 消息有两个去处（控制页的状态图标、设置页借串口失败的提示），
                // 那一步在两边长得不一样，由各自的页面去说。
                ServerOp::busy(if takeover {
                    "接管失败：现任控制端没有在 5 秒内退位，它可能卡住了。"
                } else {
                    "串口正被另一个控制端占用。"
                })
                .into_message(),
            )
            .await;
        // 正经关一下。不发关闭帧的话对面拿到 1006「异常断开」，发了但不带状态码
        // 是 1005，两者在 devtools 里都记成异常——而这明明是一次有明确原因的
        // 拒绝，原因已经在上面那条消息里说清楚了。
        let _ = sender
            .send(Message::Close(Some(CloseFrame {
                code: 1000,
                reason: "串口被占用".into(),
            })))
            .await;
        return;
    };
    info!("控制端已接入 /ws/serial");

    // 被别人接管时置位，用来决定收尾时说什么
    let mut preempted = false;

    let mut link: Option<SerialLink> = None;
    let mut rx: Option<mpsc::Receiver<Bytes>> = None;

    // 这里故意不主动推一条「当前是关着的」：客户端把 status 当作 open/close 的
    // 应答来配对，一条没人要的 status 会被错认成刚发出的那条命令的回复。新连上
    // 来的桥必然是关着的，本来也没有信息量。
    loop {
        tokio::select! {
            // 别的客户端要接管。不能不让：占着串口的可能是另一台设备上、用户
            // 碰不到的标签页，不让的话那台设备就永远拿不到控制权。
            () = seat.notify.notified() => {
                preempted = true;
                break;
            }

            // 串口读到的原始字节，原样丢给客户端喂 ch9329.js 的 reader
            chunk = recv_serial(&mut rx) => match chunk {
                Some(bytes) => {
                    if sender.send(Message::Binary(bytes)).await.is_err() {
                        break;
                    }
                }
                // 线程自己退了（拔线、口被别的程序抢走），告诉客户端别再发了
                None => {
                    link = None;
                    rx = None;
                    let dropped = ServerOp::error("串口连接已中断");
                    if sender.send(dropped.into_message()).await.is_err() {
                        break;
                    }
                    if sender.send(ServerOp::closed().into_message()).await.is_err() {
                        break;
                    }
                }
            },

            message = receiver.next() => {
                let Some(Ok(message)) = message else { break };
                match message {
                    Message::Binary(bytes) => {
                        match link.as_ref() {
                            Some(active) if active.write(bytes) => {}
                            Some(_) => {
                                // 写不进去了，等上面那条 None 分支去报错收尾
                            }
                            None => {
                                let error = ServerOp::error("串口未打开");
                                if sender.send(error.into_message()).await.is_err() {
                                    break;
                                }
                            }
                        }
                    }
                    Message::Text(text) => {
                        let reply = match serde_json::from_str::<ClientOp>(&text) {
                            Ok(ClientOp::Open { port, baud_rate }) => {
                                if let Some(old) = link.take() {
                                    rx = None;
                                    old.shutdown().await;
                                }
                                match open_port(port, baud_rate).await {
                                    Ok((active, reads)) => {
                                        let status = ServerOp::Status {
                                            open: true,
                                            port: Some(active.port.clone()),
                                            baud_rate: Some(active.baud_rate),
                                        };
                                        link = Some(active);
                                        rx = Some(reads);
                                        status
                                    }
                                    Err(error) => ServerOp::error(format!("{error:#}")),
                                }
                            }
                            Ok(ClientOp::Ping) => ServerOp::Pong {
                                serial_us: link.as_ref().and_then(|active| {
                                    match active.round_trip_us.load(Ordering::Relaxed) {
                                        0 => None,
                                        micros => Some(micros),
                                    }
                                }),
                            },
                            Ok(ClientOp::Close) => {
                                if let Some(old) = link.take() {
                                    rx = None;
                                    old.shutdown().await;
                                }
                                ServerOp::closed()
                            }
                            Err(error) => {
                                ServerOp::error(format!("控制消息无法解析：{error}"))
                            }
                        };
                        if sender.send(reply.into_message()).await.is_err() {
                            break;
                        }
                    }
                    Message::Close(_) => break,
                    Message::Ping(_) | Message::Pong(_) => {}
                }
            },
        }
    }

    // 先把话说出去再收串口：接管方正在等座位（上限 5 秒），而关串口要几十毫秒。
    // 被踢的一方必须知道原因，否则它只看到连接断了，会显示成「网络断了」——
    // 那是完全错误的指引。
    if preempted {
        let _ = sender
            .send(
                ServerOp::busy("控制权已被另一个客户端接管。点键鼠图标可以抢回来。")
                    .into_message(),
            )
            .await;
    }

    if let Some(active) = link {
        release_all(&active);
        active.shutdown().await;
    }
    // 显式还座位，让接管方尽早进来；不写这行也对，只是要等函数返回
    drop(seat);
    info!("控制端已离开 /ws/serial（preempted={preempted}）");
}

/// `rx` 是 Option，select 里要一个「没串口时永远不就绪」的分支。
async fn recv_serial(rx: &mut Option<mpsc::Receiver<Bytes>>) -> Option<Bytes> {
    match rx.as_mut() {
        Some(rx) => rx.recv().await,
        None => std::future::pending().await,
    }
}

/// `SerialLink::open` 里的打开动作是阻塞的（Windows 上偶尔要等几百毫秒），
/// 不能直接压在 tokio 的 worker 线程上。
async fn open_port(port: String, baud_rate: u32) -> Result<(SerialLink, mpsc::Receiver<Bytes>)> {
    let opening = tokio::task::spawn_blocking(move || SerialLink::open(&port, baud_rate));
    tokio::time::timeout(OPEN_TIMEOUT, opening)
        .await
        .context("打开串口超时")?
        .context("打开串口的任务异常退出")?
}

/// 断线松键的兜底。
///
/// 正常路径是客户端在 `beforeunload` / `visibilitychange` 里先发
/// `forceReleaseAllInput`，但网络断了、浏览器崩了就没人发。被控机上卡住一个
/// Ctrl 恰恰是远程最修不了的故障，所以这里硬编两个「全释放」包。
///
/// 这是服务端唯一需要的一点协议知识：帧头 `57 AB` + 地址 + 命令 + 长度 +
/// 数据 + 前面所有字节的和（取低 8 位）。
fn release_all(link: &SerialLink) {
    const KEYBOARD_RELEASE: [u8; 8] = [0; 8];
    // 相对移动包：固定的 0x01 + 按键位 + dx + dy + 滚轮，五个字节一个都不能少，
    // 少一个芯片会按参数错误拒收。用相对而不是绝对，是因为绝对包必须带坐标，
    // 填 0 会把被控机的光标甩到屏幕左上角。
    const MOUSE_RELEASE: [u8; 5] = [0x01, 0x00, 0x00, 0x00, 0x00];

    for (cmd, data) in [(0x02u8, &KEYBOARD_RELEASE[..]), (0x05u8, &MOUSE_RELEASE[..])] {
        let mut frame = vec![0x57, 0xAB, 0x00, cmd, data.len() as u8];
        frame.extend_from_slice(data);
        let checksum = frame.iter().fold(0u8, |sum, byte| sum.wrapping_add(*byte));
        frame.push(checksum);
        link.write(Bytes::from(frame));
    }
    info!("控制端断开，已下发全释放包");
}
