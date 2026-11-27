// 画面解码与绘制。放在 Worker 里是为了让主线程腾出来处理键鼠事件——
// 1080p30 的 JPEG 解码抖动如果压在主线程上，输入响应会跟着一起卡。

const REPORT_MS = 1000;
const RECONNECT_MS = 1000;

let canvas = null;
let context = null;
let socketUrl = null;
let socket = null;

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

// 当前编码，由服务端连上时发的那条 Text 头决定。
let codec = "mjpeg";
let fallbackReason = null;
let decoder = null;
let decoderCodec = null;
// H.264 得从一个关键帧起头，之前的帧喂进去只会报错
let seenKeyFrame = false;
// 服务端说画面静止时，累计跳过了多少重复帧；收到新帧就清空
let idleSkipped = null;
// 页面进了往返缓存。这期间不连、不重连，见 viewer.js 的 pagehide
let paused = false;

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
    if (message.type === "pause") {
        paused = true;
        if (socket) {
            socket.close();
        }
        return;
    }
    if (message.type === "resume") {
        paused = false;
        // 还在 CLOSING 的话不用管，它的 onclose 会照常排上重连
        if (!socket || socket.readyState === WebSocket.CLOSED) {
            connect();
        }
        return;
    }
    // 文字识别要一张当前画面。不能等下一帧：MJPEG 画面静止时服务端一帧都不发。
    // createImageBitmap 是复制；transferToImageBitmap 会把显示中的画布清空
    if (message.type === "snapshot") {
        const reply = (bitmap) => self.postMessage({type: "snapshot", id: message.id, bitmap: bitmap},
            bitmap ? [bitmap] : []);
        if (!canvas || !canvas.width || !canvas.height) {
            reply(null);
            return;
        }
        createImageBitmap(canvas).then(reply, () => reply(null));
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
    if (paused) {
        return;
    }
    socket = new WebSocket(socketUrl);
    socket.binaryType = "arraybuffer";

    socket.onopen = () => post({type: "state", state: "已连接"});

    socket.onclose = () => {
        // 上一条连接的 GOP 到此为止，新连接会从一个新的 IDR 重新起头
        seenKeyFrame = false;
        post({type: "state", state: "已断开，正在重连"});
        setTimeout(connect, RECONNECT_MS);
    };

    // onclose 之后一定会被调用到，这里不用重复安排重连
    socket.onerror = () => {};

    socket.onmessage = (event) => {
        // Text 有两种：编码头和「画面静止」通知。Binary 是帧。
        if (typeof event.data === "string") {
            const message = JSON.parse(event.data);
            if (message.op === "idle") {
                // MJPEG 模式下重复帧不发，所以画面不动时一帧都不会来。没有这条
                // 通知的话，收帧计数停住和「流卡死了」在页面上长得一模一样。
                idleSkipped = message.skipped;
                return;
            }
            applyHeader(message);
            return;
        }

        idleSkipped = null;
        window_.received += 1;
        window_.bytes += event.data.byteLength;

        if (codec === "h264") {
            feedH264(event.data);
            return;
        }

        // MJPEG 每帧独立，跟不上就丢最旧的，是免费的降级
        if (pendingFrame) {
            window_.dropped += 1;
        }
        // 记下到达时刻：从这里到画上去，就是本地版用 <video> 时没有的那一段
        pendingFrame = {buffer: event.data, at: performance.now()};
        pump();
    };
}

function applyHeader(header) {
    codec = header.codec;
    fallbackReason = header.fallbackReason || null;
    post({type: "codec", codec: codec, fallbackReason: fallbackReason});

    if (codec !== "h264") {
        closeDecoder();
        return;
    }
    // 同一个 codec 字符串就不重建了：换编码时服务端会重发头，但配置里只改了
    // 码率的话头是一样的，这时重建解码器会白丢掉一个 GOP
    if (decoder && decoderCodec === header.avcCodec) {
        return;
    }
    closeDecoder();
    decoderCodec = header.avcCodec;
    decoder = new VideoDecoder({
        output: drawFrame,
        error: () => {
            window_.errors += 1;
            // VideoDecoder 出错就进 closed，再也回不来，只能重建。而重建完还得等
            // 一个 IDR 才画得出东西——GOP 是 10 秒，干等意味着最长 10 秒黑屏。
            //
            // 所以顺带把连接也断掉：重连会让服务端按「新观看者」强制一个 IDR，
            // 一秒就恢复。多一次连接换手，对一个罕见错误来说很值。
            closeDecoder();
            if (socket) {
                socket.close();
            }
        }
    });
    decoder.configure({
        codec: decoderCodec,
        // 不为了流畅去缓冲。KVM 要的是手感，多一帧缓冲就是多 33ms 的隔手感。
        optimizeForLatency: true
    });
    seenKeyFrame = false;
}

function closeDecoder() {
    if (decoder && decoder.state !== "closed") {
        decoder.close();
    }
    decoder = null;
    decoderCodec = null;
    seenKeyFrame = false;
}

// H.264 和 MJPEG 那条路最大的区别：**一帧都不能丢**。
//
// MJPEG 每帧独立，跟不上丢最旧的，免费。H.264 丢一个 P 帧会让后面整个 GOP 全烂
// 到下一个 IDR，所以这里每一条都喂进去。VideoDecoder 自己排队，硬件解码远快于
// 30fps，跟不上不是问题；真跟不上的话 decodeQueueSize 会涨，那是给人看的诊断
// 读数，不是要去丢帧的信号。
function feedH264(buffer) {
    if (!decoder || decoder.state !== "configured") {
        return;
    }
    const bytes = new Uint8Array(buffer);
    const key = isKeyFrame(bytes);
    if (!seenKeyFrame) {
        if (!key) {
            // 还没等到起头的关键帧。喂进去只会报错，而报错会把解码器关掉。
            return;
        }
        seenKeyFrame = true;
    }
    // 关键帧必须标成 "key"，否则解码器拿它当增量帧，从头到尾什么都不出
    const chunk = new EncodedVideoChunk({
        type: key ? "key" : "delta",
        // 拿时间戳当「到达时刻」的信道：VideoFrame 会把它原样带回来，于是不用自己
        // 维护一个到达时间的队列就能量出「到帧→绘制」。反正这个流是实时的，
        // 时间戳的绝对值对谁都没有意义。单位是微秒。
        timestamp: performance.now() * 1000,
        data: bytes
    });
    // decode 可以同步抛（码流不合法、或者状态刚变）。这是在 onmessage 里，抛出去
    // 只会变成一条无人处理的 worker 错误，还不如就地记进「解码失败」那个读数。
    try {
        decoder.decode(chunk);
    } catch (e) {
        window_.errors += 1;
    }
}

// 扫 Annex-B 起始码找 NAL 类型：5 = IDR 片，7 = SPS（编码器在每个 IDR 前重复
// SPS/PPS）。三字节和四字节起始码都要认，编码器两种都可能吐。
function isKeyFrame(bytes) {
    for (let i = 0; i + 3 < bytes.length; i += 1) {
        if (bytes[i] !== 0 || bytes[i + 1] !== 0) {
            continue;
        }
        let header;
        if (bytes[i + 2] === 0 && bytes[i + 3] === 1) {
            header = 4;
        } else if (bytes[i + 2] === 1) {
            header = 3;
        } else {
            continue;
        }
        const at = i + header;
        if (at >= bytes.length) {
            return false;
        }
        const kind = bytes[at] & 0x1f;
        if (kind === 5 || kind === 7) {
            return true;
        }
        i = at - 1;
    }
    return false;
}

// 主线程那块画布已经移交过来，它读不到这边改的尺寸，显示方式却要按画面尺寸摆，
// 所以改了就告诉它一声
function fitCanvas(width, height) {
    if (canvas.width === width && canvas.height === height) {
        return;
    }
    canvas.width = width;
    canvas.height = height;
    self.postMessage({type: "size", width: width, height: height});
}

function drawFrame(frame) {
    try {
        fitCanvas(frame.displayWidth, frame.displayHeight);
        context.drawImage(frame, 0, 0);
        window_.drawn += 1;
        // 「到帧→绘制」两种编码同一个含义，可以直接做差比较。
        //
        // 不报 decodeMs：解码是解码器在别处异步做的，这边掐不到表。硬要报个
        // 绘制耗时会显示成「解码 0.3ms」，比不报更误导。
        window_.latencyMs += performance.now() - frame.timestamp / 1000;
        if (tapping) {
            // 录制那条通路收的是 bitmap，所以转一下再给。VideoFrame 本身也能转移，
            // 但那样录制侧还得单独处理它的 close，不值得为此多一套。
            createImageBitmap(frame)
                .then((bitmap) => self.postMessage({type: "frame", bitmap: bitmap}, [bitmap]))
                .catch(() => {});
        }
    } catch (e) {
        window_.errors += 1;
    } finally {
        // **必须 close。** VideoFrame 持有 GPU 内存，不 close 会在几秒内耗尽并卡死
        // 整个页面，而现象看起来像「解码器不行」——最误导的那种失败。
        // 放在 finally 里：上面任何一步抛了也不能漏掉这一下。
        frame.close();
    }
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
        fitCanvas(bitmap.width, bitmap.height);
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
        latencyMs: stats.drawn ? stats.latencyMs / stats.drawn : 0,
        codec: codec,
        // 持续上涨才说明解码跟不上。H.264 那条路不丢帧，所以这是唯一能看出
        // 「客户端撑不住了」的读数。
        decodeQueue: decoder ? decoder.decodeQueueSize : 0,
        idleSkipped: idleSkipped
    });
}

function post(message) {
    self.postMessage(message);
}
