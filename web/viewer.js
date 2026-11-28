// 观看页的主线程部分：只负责把画布交给 Worker、轮询服务端状态、渲染叠加信息。
// 解码和绘制全在 Worker 里，主线程留给后面要接的键鼠事件。

const STATS_POLL_MS = 1000;

const canvas = document.getElementById("screen");
const statsEl = document.getElementById("stats");
const statusButton = document.getElementById("video-status");
const statusTip = document.getElementById("video-status-tip");
const statsButton = document.getElementById("toggle-stats");
const statsTip = document.getElementById("stats-tip");

let clientStats = null;
let serverStats = null;
let connection = "连接中";
// 服务端本来要发 h264 却回落了 MJPEG 的原因，由 Worker 从编码头里带出来
let fallbackReason = null;

// 画布尺寸要在移交给 Worker 之前定好：控制权一旦转移，就不能再改这个元素的
// width/height 了。所以先问服务端当前采集的分辨率，拿不到就按 1080p 兜底。
async function detectResolution() {
    try {
        const response = await fetch("/api/video/stats", {cache: "no-store"});
        const stats = await response.json();
        if (stats.resolution) {
            return {width: stats.resolution[0], height: stats.resolution[1]};
        }
    } catch (e) {
        // 服务端还没起来也无所谓，用兜底值先把画布建起来
    }
    return {width: 1920, height: 1080};
}

function websocketUrl(path) {
    const scheme = location.protocol === "https:" ? "wss:" : "ws:";
    return `${scheme}//${location.host}${path}`;
}

// 一位小数对 MJPEG 够用（44.7），但对 H.264 会把静止画面的码率显示成 0.0——
// 而「不动的桌面几乎不要钱」恰恰是这个功能最大的收益，不能让它看起来像没数。
function formatMbps(mbps) {
    return mbps < 1 ? mbps.toFixed(2) : mbps.toFixed(1);
}

function render() {
    const lines = [`连接      ${connection}`];

    if (serverStats) {
        const resolution = serverStats.resolution
            ? `${serverStats.resolution[0]}x${serverStats.resolution[1]}`
            : "未知";
        lines.push(
            `服务端    ${serverStats.fps.toFixed(1)} fps  ${resolution}`,
            // 采集和发送必须分开写。采集卡不管画面动不动都吐 44 Mbps，而重复帧
            // 不发、H.264 静止时每帧几十字节，真正上线的是后一个数——把两者混成
            // 一个「服务端码率」，就会得出「静止画面也要 44 Mbps」的错误结论。
            `码率      采集 ${serverStats.mbps.toFixed(1)}  发送 ${formatMbps(serverStats.sentMbps)} Mbps`,
            `观看者    ${serverStats.viewers}${serverStats.capturing ? "  采集中" : "  未采集"}`
        );
        if (serverStats.error) {
            lines.push(`服务端错误 ${serverStats.error}`);
        }
    }

    if (clientStats) {
        // 编码必须写出来：两种编码的码率差一个数量级，看读数时得知道现在是哪种
        const h264 = clientStats.codec === "h264";
        lines.push(
            `编码      ${h264 ? "H.264" : "MJPEG"}${h264 ? `  解码队列 ${clientStats.decodeQueue}` : ""}`,
            // 静止时 MJPEG 那条路一帧都不发，收帧数会停在 0。不说清楚的话，这和
            // 「流卡死了」在页面上完全一样——而那正是最容易让人不敢信这个工具的状态。
            clientStats.idleSkipped === null || clientStats.idleSkipped === undefined
                ? `本机收到  ${clientStats.received} 帧  ${formatMbps(clientStats.mbps)} Mbps`
                : `本机收到  画面静止（已省 ${clientStats.idleSkipped} 帧重复画面）`,
            // H.264 的解码在解码器里异步做，主线程掐不到表，所以那一栏不报
            h264
                ? `本机绘制  ${clientStats.drawn} 帧`
                : `本机绘制  ${clientStats.drawn} 帧  解码 ${clientStats.decodeMs.toFixed(1)} ms`,
            `到帧→绘制 ${clientStats.latencyMs.toFixed(1)} ms`
        );
        if (fallbackReason) {
            // 服务端本来要发 h264 但编码器起不来。这一条必须显示——静默退回 MJPEG
            // 的话，人只会觉得「省带宽那个开关没用」，而不知道为什么。
            lines.push(`已回落 MJPEG  ${fallbackReason}`);
        }
        // 丢帧不一定是坏事：解码跟不上到帧时故意丢掉旧帧，保证看到的是最新画面
        if (clientStats.dropped > 0 || clientStats.errors > 0) {
            lines.push(`丢帧      ${clientStats.dropped}  解码失败 ${clientStats.errors}`);
        }
    }

    statsEl.textContent = lines.join("\n");

    // 同样的信息在工具栏上还有一份浓缩版：叠加层收起来之后，那是唯一
    // 还能看出画面通不通的地方
    StatusText.applyStatus(statusButton, statusTip, StatusText.describeStream({
        connection: connection,
        serverStats: serverStats,
        clientStats: clientStats
    }));
}

// 连接信息叠加层的开关。原来这个动作藏在画面状态图标的点击里，只有 tooltip
// 提一句，等于没有——开关这种东西该有自己的按钮。
//
// 不做快捷键：键盘一旦接管就全归被控机了，任何单键都可能是用户想按给对面的。
function renderStatsToggle() {
    const hidden = statsEl.classList.contains("hidden");
    statsButton.classList.toggle("off", hidden);
    statsTip.textContent = hidden ? "显示连接信息" : "隐藏连接信息";
    statsButton.setAttribute("aria-label", hidden ? "显示连接信息" : "隐藏连接信息");
}

statsButton.addEventListener("click", () => {
    statsEl.classList.toggle("hidden");
    renderStatsToggle();
});

// 画面状态图标点一下立刻重读服务端读数。平时一秒一次，这个只是不想等那一秒
// ——比如刚在设置页改了分辨率回来，想马上确认生效了没有。
statusButton.addEventListener("click", () => {
    pollServerStats();
});

async function pollServerStats() {
    try {
        const response = await fetch("/api/video/stats", {cache: "no-store"});
        serverStats = await response.json();
    } catch (e) {
        serverStats = null;
    }
    render();
}

// 录制专用画布。不进 DOM，也不显示：它唯一的用途是给 captureStream() 当源。
//
// 显示用的那块画布已经交给 Worker 了，主线程对它 captureStream() 只能录到黑屏，
// 而 OffscreenCanvas 没有 captureStream。所以录制单独走这一块，由 Worker 把
// 解码好的帧转过来喂。不录的时候完全没有开销——Worker 那边连帧都不发。
const recordCanvas = document.createElement("canvas");
const recordContext = recordCanvas.getContext("2d", {alpha: false});
let recordTrack = null;
const snapshotWaiters = new Map();
let snapshotSeq = 0;

function onRecordFrame(bitmap) {
    if (recordCanvas.width !== bitmap.width || recordCanvas.height !== bitmap.height) {
        recordCanvas.width = bitmap.width;
        recordCanvas.height = bitmap.height;
    }
    recordContext.drawImage(bitmap, 0, 0);
    bitmap.close();
    // 手动模式：每收一帧录一帧。用自动模式的话帧的产生取决于画布有没有被
    // 合成器绘制，而这块画布根本不在 DOM 里，不能指望。
    if (recordTrack) {
        recordTrack.requestFrame();
    }
}

// 画面的像素尺寸。画布移交之后读它的 width/height 不会跟着 Worker 变，以 Worker 报的为准
let videoSize = null;

function setVideoSize(width, height) {
    videoSize = {width: width, height: height};
    window.dispatchEvent(new Event("kvm-video-resize"));
}

async function start() {
    const size = await detectResolution();
    canvas.width = size.width;
    canvas.height = size.height;
    setVideoSize(size.width, size.height);

    const offscreen = canvas.transferControlToOffscreen();
    const worker = new Worker("video-worker.js");

    worker.onmessage = (event) => {
        const message = event.data;
        if (message.type === "frame") {
            onRecordFrame(message.bitmap);
            return;
        }
        if (message.type === "snapshot") {
            const resolve = snapshotWaiters.get(message.id);
            snapshotWaiters.delete(message.id);
            if (resolve) {
                resolve(message.bitmap);
            } else if (message.bitmap) {
                message.bitmap.close();
            }
            return;
        }
        if (message.type === "size") {
            setVideoSize(message.width, message.height);
            return;
        }
        if (message.type === "stats") {
            clientStats = message;
        } else if (message.type === "state") {
            connection = message.state;
        } else if (message.type === "codec") {
            fallbackReason = message.fallbackReason;
        }
        render();
    };

    // 录制要用的两样东西都在这个闭包里：画布和那个 tap 开关。
    // captureStream(0) 是手动模式，见 onRecordFrame。
    window.KvmVideo = {
        startCapture() {
            // 尺寸必须在开流之前定好：track 的分辨率取自调用这一刻的画布大小，
            // 之后再变会让 WebM 里的分辨率和实际帧不一致。宁可用服务端报的
            // 当前分辨率，也不要等第一帧到了才发现不对。
            const current = (serverStats && serverStats.resolution) || [size.width, size.height];
            recordCanvas.width = current[0];
            recordCanvas.height = current[1];

            const stream = recordCanvas.captureStream(0);
            recordTrack = stream.getVideoTracks()[0];
            worker.postMessage({type: "tap", on: true});
            return stream;
        },
        stopCapture() {
            worker.postMessage({type: "tap", on: false});
            recordTrack = null;
        },
        // 有没有画面可录。不看画布（它在开录前根本没收到过帧），看帧是不是在流
        streaming: () => !!clientStats && clientStats.drawn > 0,
        // 画面上现在显示的那一帧，ImageBitmap，没有画面时是 null。用完要 close()
        snapshot() {
            const id = ++snapshotSeq;
            return new Promise((resolve) => {
                snapshotWaiters.set(id, resolve);
                worker.postMessage({type: "snapshot", id: id});
            });
        },
        // 显示中的画布元素，框选时按它的位置把屏幕坐标换算成画面像素
        element: canvas,
        // 画面像素尺寸 {width, height}，变了会发 kvm-video-resize
        size: () => videoSize
    };
    window.dispatchEvent(new Event("kvm-video-resize"));

    worker.postMessage(
        {type: "init", canvas: offscreen, url: websocketUrl("/ws/video")},
        [offscreen]
    );

    // 去设置页这页并不卸载，而是连同 WebSocket 一起冻进往返缓存：连接开着却没人读，
    // 服务端照样往里推，采集卡也一直算「有人在看」。连接在 Worker 里，这里只能发个
    // 消息让它关——Worker 来不及处理就被冻住的话，服务端的发送超时会把它断掉。
    window.addEventListener("pagehide", () => worker.postMessage({type: "pause"}));
    window.addEventListener("pageshow", (event) => {
        if (event.persisted) {
            worker.postMessage({type: "resume"});
        }
    });

    pollServerStats();
    setInterval(pollServerStats, STATS_POLL_MS);
}

renderStatsToggle();

start();
