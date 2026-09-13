// 把 /ws/serial 这条桥包装成 Web Serial 的 SerialPort 形状。
//
// ch9329.js 从来只用到 port 的四样东西：open({baudRate})、close()、
// readable.getReader()、writable.getWriter()。本地版喂给它的是
// navigator.serial 的真端口，服务版喂给它这个对象，协议代码一行都不用改，
// 连 Ch9329.connect 的波特率探测都能原样复用——它探测的做法正是反复
// close/open，而桥的 open 本来就带波特率、可重复调用。

const CONTROL_TIMEOUT_MS = 5000;

// 没人取的时候最多攒这么多块。输入走「只发不等」时芯片照样会回应答，而
// ch9329.js 那边没有 waiter 来收，攒着只会白占内存——串口收到的旧字节过一会
// 就没有意义了，丢最老的即可。
const RX_QUEUE_LIMIT = 64;

class SerialWsPort {
    constructor(url, portName) {
        this._url = url;
        // 服务端说「被占用」时带的 code。页面靠它决定要不要给出「接管」这个动作，
        // 而不是去匹配错误文案——文案是给人看的，随时会改。
        this.lastServiceErrorCode = null;
        this._portName = portName;
        this._socket = null;
        this._open = false;

        // 已到达但还没被 read() 取走的字节块，和正在等数据的 read() 回调。
        // 两者必然只有一个非空。
        this._rxQueue = [];
        this._rxWaiters = [];

        // 正在等应答的控制命令（open / close）
        this._pending = null;

        // 传输层断了（不是串口关了）。页面拿它去 markDisconnected
        this.ontransportclose = null;

        // 服务端主动推来的 error，多半是串口被拔了
        this.onserviceerror = null;

        // 服务端最近一条 error 的原文，即使它已经被当成某条命令的失败应答消化掉。
        // 「已有其他客户端在控制串口」这种拒绝，原因**只**在这条消息里，而调用方
        // 拿到的往往是随后连接断开产生的「串口桥已断开」——更晚、更没信息量，
        // 却正好把真正的原因盖住了。
        this.lastServiceError = null;

        // 网络往返用显式的 ping/pong 量，不去猜数据包和回复的配对关系：
        // 输入一旦「只发不等」，回复要一百毫秒后才回来，那时候最近一次发送
        // 早被后面的包覆盖了，配出来的数只是两个不相干事件的间隔。
        // 串口段的往返由服务端量好了捎在 pong 里——那一段才是真正的一问一答。
        this._pingAt = 0;
        this._networkMs = 0;
        this._serialMs = 0;
        this._writeCount = 0;

        this._reader = {
            read: () => this._read(),
            cancel: () => {
                this._finishReads();
                return Promise.resolve();
            },
            releaseLock: () => {
            }
        };
        this._writer = {
            write: (bytes) => this._write(bytes),
            releaseLock: () => {
            }
        };
    }

    // ch9329.js 和 Ch9329.connect 都靠这两个属性判断「口是不是开着」
    get readable() {
        return this._open ? {getReader: () => this._reader} : null;
    }

    get writable() {
        return this._open ? {getWriter: () => this._writer} : null;
    }

    get baudRate() {
        return this._baudRate || null;
    }

    async open(options) {
        const baudRate = options && options.baudRate;
        await this._connect();
        const status = await this._control({
            op: "open",
            port: this._portName,
            baudRate: baudRate
        });
        this._baudRate = status.baudRate;
    }

    async close() {
        if (!this._socket || this._socket.readyState !== WebSocket.OPEN) {
            this._open = false;
            this._finishReads();
            return;
        }
        try {
            await this._control({op: "close"});
        } catch (e) {
            // 桥没回话就当它已经关了：再纠缠下去也没有别的补救办法
            this._open = false;
            this._finishReads();
        }
    }

    // 页面退出时用。close() 只关串口，桥的连接还留着
    dispose() {
        const socket = this._socket;
        this._socket = null;
        this._open = false;
        this._finishReads();
        if (socket) {
            socket.onclose = null;
            socket.close();
        }
    }

    _connect() {
        if (this._socket && this._socket.readyState === WebSocket.OPEN) {
            return Promise.resolve();
        }
        return new Promise((resolve, reject) => {
            const socket = new WebSocket(this._url);
            socket.binaryType = "arraybuffer";
            this._socket = socket;
            socket.onopen = () => resolve();
            socket.onmessage = (event) => this._onMessage(event);
            socket.onclose = () => {
                // 握手期间的失败走 reject；连上之后的断开走回调
                reject(new Error("连接串口桥失败"));
                this._onSocketClose();
            };
        });
    }

    _onSocketClose() {
        this._open = false;
        this._finishReads();
        this._settle(new Error("串口桥已断开"));
        if (this.ontransportclose) {
            this.ontransportclose();
        }
    }

    _onMessage(event) {
        if (typeof event.data === "string") {
            this._onControl(JSON.parse(event.data));
            return;
        }
        const chunk = new Uint8Array(event.data);
        if (chunk.length === 0) {
            return;
        }
        const waiter = this._rxWaiters.shift();
        if (waiter) {
            waiter({value: chunk, done: false});
            return;
        }
        this._rxQueue.push(chunk);
        while (this._rxQueue.length > RX_QUEUE_LIMIT) {
            this._rxQueue.shift();
        }
    }

    _onControl(message) {
        if (message.op === "pong") {
            if (this._pingAt) {
                this._networkMs = performance.now() - this._pingAt;
                this._pingAt = 0;
            }
            this._serialMs = message.serialUs ? message.serialUs / 1000 : 0;
            return;
        }
        if (message.op === "status") {
            const wasOpen = this._open;
            this._open = !!message.open;
            if (wasOpen && !this._open) {
                // 串口关了，让 ch9329.js 的读循环收到 done 后干净退出
                this._finishReads();
            }
            this._settle(null, message);
            return;
        }
        if (message.op === "error") {
            const error = new Error(message.message || "串口桥报错");
            this.lastServiceError = error;
            this.lastServiceErrorCode = message.code || null;
            // 有命令在等就归给它，否则是服务端主动报的（拔线之类）
            if (!this._settle(error)) {
                if (this.onserviceerror) {
                    this.onserviceerror(error);
                }
            }
        }
    }

    _control(message) {
        return new Promise((resolve, reject) => {
            // 一次只允许一条控制命令在飞：open/close 都会改串口状态，
            // 并发发出去的话回来的 status 分不清是谁的
            if (this._pending) {
                reject(new Error("上一条串口命令还没完成"));
                return;
            }
            const entry = {resolve: resolve, reject: reject, timer: null};
            entry.timer = setTimeout(() => {
                if (this._pending === entry) {
                    this._pending = null;
                }
                reject(new Error("串口桥无响应"));
            }, CONTROL_TIMEOUT_MS);
            this._pending = entry;
            this._socket.send(JSON.stringify(message));
        });
    }

    // 返回是否真的有命令在等着被这条消息了结
    _settle(error, value) {
        const entry = this._pending;
        if (!entry) {
            return false;
        }
        this._pending = null;
        clearTimeout(entry.timer);
        if (error) {
            entry.reject(error);
        } else {
            entry.resolve(value);
        }
        return true;
    }

    _read() {
        const queued = this._rxQueue.shift();
        if (queued) {
            return Promise.resolve({value: queued, done: false});
        }
        if (!this._open) {
            return Promise.resolve({value: undefined, done: true});
        }
        return new Promise((resolve) => this._rxWaiters.push(resolve));
    }

    _finishReads() {
        this._rxQueue = [];
        const waiters = this._rxWaiters;
        this._rxWaiters = [];
        waiters.forEach((resolve) => resolve({value: undefined, done: true}));
    }

    _write(bytes) {
        if (!this._socket || this._socket.readyState !== WebSocket.OPEN) {
            // 必须抛：ch9329.js 靠这个异常进入 markDisconnected，
            // 否则后面每个包都会静悄悄地丢
            return Promise.reject(new Error("串口桥已断开"));
        }
        // WebSocket.send 收 ArrayBufferView 时只发视图覆盖的那段，
        // 不用自己切 buffer
        this._socket.send(bytes);
        this._writeCount += 1;
        return Promise.resolve();
    }

    // 取走这一段时间的统计并清零，顺手发下一个 ping。给状态栏每秒刷一次用。
    takeStats() {
        const stats = {
            networkMs: this._networkMs,
            serialMs: this._serialMs,
            writes: this._writeCount
        };
        this._writeCount = 0;

        if (this._socket && this._socket.readyState === WebSocket.OPEN && !this._pingAt) {
            this._pingAt = performance.now();
            this._socket.send(JSON.stringify({op: "ping"}));
        }
        return stats;
    }
}
