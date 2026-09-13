// 画面解码与绘制。放在 Worker 里是为了让主线程腾出来处理键鼠事件——
// 1080p30 的 JPEG 解码抖动如果压在主线程上，输入响应会跟着一起卡。

const REPORT_MS = 1000;
const RECONNECT_MS = 1000;

let canvas = null;
let context = null;
let socketUrl = null;

// 只保留最新的一帧待解码。KVM 看的是当前画面，解码跟不上的时候补解旧帧毫无
// 意义，反而会让显示越来越滞后——所以宁可丢，也不排队。
let pendingFrame = null;
let decoding = false;

// 录制时把解码好的帧也交给主线程一份。平时是 false，一帧都不多传。
//
// 为什么要绕这一圈：画布已经 transferControlToOffscreen 给了这个 Worker，
// 主线程那个 <canvas> 元素不再被绘制，对它 captureStream() 只会得到黑屏。
// 而 OffscreenCanvas 自己没有 captureStream（只有 convertToBlob 和
// transferToImageBitmap），所以录制必须在主线程另开一块画布。
let tapping = false;

let window_ = newWindow();

function newWindow() {
    return {received: 0, drawn: 0, dropped: 0, errors: 0, bytes: 0, decodeMs: 0, latencyMs: 0};
}

self.onmessage = (event) => {
    const message = event.data;
    if (message.type === "tap") {
        tapping = message.on;
        return;
    }
    if (message.type !== "init") {
        return;
    }
    canvas = message.canvas;
    // desynchronized 让画布跳过合成器的一级排队，对延迟敏感的场景有用；
    // alpha:false 省掉一次不必要的混合。
    context = canvas.getContext("2d", {alpha: false, desynchronized: true});
    socketUrl = message.url;
    connect();
    setInterval(report, REPORT_MS);
};

function connect() {
    const socket = new WebSocket(socketUrl);
    socket.binaryType = "arraybuffer";

    socket.onopen = () => post({type: "state", state: "已连接"});

    socket.onclose = () => {
        post({type: "state", state: "已断开，正在重连"});
        setTimeout(connect, RECONNECT_MS);
    };

    // onclose 之后一定会被调用到，这里不用重复安排重连
    socket.onerror = () => {};

    socket.onmessage = (event) => {
        window_.received += 1;
        window_.bytes += event.data.byteLength;
        if (pendingFrame) {
            window_.dropped += 1;
        }
        // 记下到达时刻：从这里到画上去，就是本地版用 <video> 时没有的那一段
        pendingFrame = {buffer: event.data, at: performance.now()};
        pump();
    };
}

async function pump() {
    if (decoding || !pendingFrame) {
        return;
    }
    decoding = true;

    const frame = pendingFrame;
    pendingFrame = null;
    const startedAt = performance.now();

    try {
        const bitmap = await createImageBitmap(new Blob([frame.buffer], {type: "image/jpeg"}));
        if (canvas.width !== bitmap.width || canvas.height !== bitmap.height) {
            canvas.width = bitmap.width;
            canvas.height = bitmap.height;
        }
        context.drawImage(bitmap, 0, 0);
        // drawImage 是同步的，画完之后像素已经在画布里了，所以这时把 bitmap
        // 转移出去是安全的——转移会让这边的引用失效，但我们已经不需要它了。
        // 正因为能这样，录制不用重复解码一次。
        if (tapping) {
            self.postMessage({type: "frame", bitmap: bitmap}, [bitmap]);
        } else {
            bitmap.close();
        }
        window_.drawn += 1;
        const finishedAt = performance.now();
        window_.decodeMs += finishedAt - startedAt;
        window_.latencyMs += finishedAt - frame.at;
    } catch (e) {
        window_.errors += 1;
    }

    decoding = false;
    if (pendingFrame) {
        pump();
    }
}

function report() {
    const stats = window_;
    window_ = newWindow();
    post({
        type: "stats",
        received: stats.received,
        drawn: stats.drawn,
        dropped: stats.dropped,
        errors: stats.errors,
        mbps: (stats.bytes * 8) / 1_000_000,
        decodeMs: stats.drawn ? stats.decodeMs / stats.drawn : 0,
        latencyMs: stats.drawn ? stats.latencyMs / stats.drawn : 0
    });
}

function post(message) {
    self.postMessage(message);
}
