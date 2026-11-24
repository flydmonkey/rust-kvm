// 控制层：浏览器采键鼠事件 -> ch9329.js 编码 -> /ws/serial 透传到芯片。
//
// 责任边界和本地版（chrome-kvm）完全一样，区别只在 ch9329.js 拿到的
// writer/reader 是 SerialWsPort 给的，而不是 Web Serial 的。
(function () {
    const screenEl = document.getElementById("screen");
    const statusButton = document.getElementById("input-status");
    const statusTip = document.getElementById("input-status-tip");

    // 一格滚轮在 Chrome 里约 100px
    const WHEEL_PIXELS_PER_NOTCH = 100;

    const LATENCY_POLL_MS = 1000;

    let port = null;
    let ch = null;
    let chipInfo = null;
    let baudRate = null;
    let baudProbed = false;
    let serialLost = false;
    let pointerLockRejected = false;
    let inputBound = false;
    let connecting = false;
    let moveCount = 0;
    let settings = null;
    let portName = null;
    let mouseAbsolute = true;
    let latencyLines = [];
    // 串口被别的控制端占着（或者我们刚被接管）。这个状态和「连不上」要分开：
    // 它的解法不是重试，而是接管。
    let busy = false;
    // 自动重连的定时器。非 null 就是「已经排上了下一次尝试」
    let reconnectTimer = null;
    // 是不是正处在「掉线后自动重连」这个过程里。不能只看 serialLost：connect()
    // 一开头就把它清零，那样一次失败的尝试之后循环就自己停了。
    let reconnecting = false;
    // 最近一次连接失败的原因。重连期间要一直显示着——光一句「正在重连」的话，
    // 「为什么还没连上」这个唯一想知道的事就没了。
    let lastFailure = null;
    // 重连间隔。CH9329 拔插后要几秒才重新枚举出来，太密只是白试
    const RECONNECT_DELAY_MS = 3000;

    function websocketUrl(path) {
        const scheme = location.protocol === "https:" ? "wss:" : "ws:";
        return `${scheme}//${location.host}${path}`;
    }

    function pointerLocked() {
        return document.pointerLockElement === screenEl;
    }

    function isEditableTarget(target) {
        if (!target || !target.tagName) {
            return false;
        }
        return target.tagName === "INPUT" || target.tagName === "TEXTAREA" || target.isContentEditable;
    }

    function inToolPanel(target) {
        return !!(target && target.closest && target.closest(".tool-panel"));
    }

    // 粘贴、开机连按各自占着键盘时 >0。计数而不是布尔：两者理论上不会重叠，
    // 但真重叠了也不至于一方结束就把另一方的屏蔽提前解除
    let keyboardBusy = 0;

    function keysSuppressed() {
        return keyboardBusy > 0;
    }

    // 每次连上（含重连）加一。连按循环靠它判断「还是不是开始时那条连接」——
    // 断线重连后芯片对象换了，接着往新连接上连按不是任何人想要的
    let session = 0;

    function renderStatus(override) {
        if (override) {
            // 覆盖是调用方在说「就显示这句」，不要再追加连接提示和延迟读数——
            // 之前会拼出「正在打开串口…／正在连接…」这种重复两遍的东西
            StatusText.applyStatus(statusButton, statusTip, override);
            return;
        }

        const state = StatusText.describeSerial({
            connected: !!ch,
            serialLost: serialLost,
            info: chipInfo,
            // 这里问的是「芯片答不答话」，不是「我们等不等」。输入一律不等
            // 应答是我们主动选的，不该显示成降级；探测拿到了固件信息就说明
            // 芯片是活的。
            ackEnabled: !!chipInfo,
            baudRate: baudRate,
            baudProbed: baudProbed,
            mouseRelative: ch ? !ch.isMouseAbsolute() : false,
            pointerLocked: pointerLocked(),
            pointerLockRejected: pointerLockRejected
        });

        // describeSerial 末尾那句「点击刷新」只在连上之后才说得通：还没连的时候
        // 点这个图标是去连接，不是刷新。
        const lines = state.lines.filter((line) => line !== "点击刷新");
        if (connecting) {
            lines.push("正在连接…");
        } else if (busy) {
            // 覆盖掉 describeSerial 的措辞：它只知道「没连上」，不知道原因是有人
            // 占着，而这两者要做的事完全不同
            lines.length = 0;
            lines.push("串口被另一个客户端占用", "点击接管控制权");
        } else if (ch && !serialLost) {
            // 延迟读数只在连着时有意义，而且要跟在固件信息后面
            lines.push(...latencyLines, "点击刷新芯片状态");
        } else if (reconnectTimer) {
            // describeSerial 那句「插回 CH9329 后会自动重连」曾经是句空话——
            // 当时根本没有重连代码。现在有了，这里把「正在等下一次」说出来，
            // 免得看上去只是躺着不动。
            if (lastFailure) {
                lines.push(lastFailure);
            }
            lines.push("正在自动重连…", "或点此立即重试");
        } else {
            lines.push(portName ? `点击连接 ${portName}` : "点击连接");
        }

        // 被占用是黄的，不是红的：设备一切正常，只是控制权在别人手上。
        // 重连期间是红的：describeSerial 此刻只看到「没连上」（灰），但我们知道
        // 这是掉线之后还没接回来，不是「还没点过连接」。
        let level = state.level;
        if (busy) {
            level = "warn";
        } else if (reconnectTimer) {
            level = "error";
        }
        StatusText.applyStatus(statusButton, statusTip, {level: level, lines: lines});
        statusButton.setAttribute("aria-label", label());
    }

    function label() {
        if (busy) {
            return "接管控制权";
        }
        return ch && !serialLost ? "键鼠状态（点击刷新）" : "连接键鼠";
    }

    // ---------------------------------------------------------------------
    // 串口
    // ---------------------------------------------------------------------

    // 串口和鼠标模式由设置页定，控制页只读。以前这两样在控制页也各有一个下拉框，
    // 和设置页里的重复了：两处都能改同一件事，改哪个生效得靠记，纯属给自己找麻烦。
    // 读不到配置就按老样子靠猜，配置缺失不该成为连不上的理由。
    async function loadSettings() {
        try {
            const response = await fetch("/api/config", {cache: "no-store"});
            settings = await response.json();
        } catch (e) {
            settings = null;
            return;
        }
        if (settings.serial.mouseMode) {
            mouseAbsolute = settings.serial.mouseMode === "absolute";
        }
    }

    function baudCandidates() {
        const preferred = settings && settings.serial.baudRate;
        if (!preferred || !Ch9329.BAUD_RATES.includes(preferred)) {
            return Ch9329.BAUD_RATES;
        }
        return [preferred, ...Ch9329.BAUD_RATES.filter((rate) => rate !== preferred)];
    }

    // 定下要开哪个口。没有下拉框了，所以这里必须自己得出一个确定的答案。
    //
    // `quiet` 给自动重连用：那条路自己会把「正在等下一次」显示出来，这儿再覆盖
    // 一句「点这里重试」只会自相矛盾。
    async function resolvePort(quiet) {
        let ports = [];
        try {
            const response = await fetch("/api/serial/ports", {cache: "no-store"});
            ports = await response.json();
        } catch (e) {
            if (!quiet) {
                renderStatus({
                    level: "error",
                    lines: ["读不到串口列表", String(e.message || e), "点击重试"]
                });
            }
            return;
        }
        if (!ports.length) {
            if (!quiet) {
                renderStatus({
                    level: "error",
                    lines: ["宿主机上没有串口", "插上 CH9329 后点这里重试"]
                });
            }
            return;
        }

        // 设置页明确指定过就听它的
        const configured = settings && settings.serial.port;
        if (configured && ports.some((entry) => entry.name === configured)) {
            portName = configured;
        } else {
            // 没配过就猜：优先报得出产品名的那个，那是 USB 转串口模块（CH9329 就挂
            // 在上面），没描述的多半是主板自带的 COM 口
            const usb = ports.find((entry) => entry.description);
            portName = (usb || ports[0]).name;
        }
        renderStatus();
    }

    // 掉线后自动重连。CH9329 断电重插是设置页写完波特率之后的**必经**步骤，而键鼠
    // 本来就默认连接——插回来还要人手点一下并不合理。服务端的视频和音频也是这么
    // 守着的（出错等一秒再来），这里只是把同一条规矩补给串口。
    function scheduleReconnect() {
        // busy 是被别人接管了，**绝不能**自动抢回来：那样两个开着的页面会互相
        // 抢到没完。要抢必须是人点的。
        if (reconnectTimer || connecting || busy) {
            return;
        }
        if (!serialLost && !reconnecting) {
            return;
        }
        reconnecting = true;
        reconnectTimer = setTimeout(() => {
            reconnectTimer = null;
            if (busy) {
                reconnecting = false;
                return;
            }
            // 每次都重新问一遍串口列表：拔插之后 Windows 偶尔会重新分配口号，
            // 死盯着旧名字会永远连不上
            portName = null;
            loadSettings()
                .then(() => resolvePort(true))
                .then(() => (portName ? connect(false) : undefined))
                .catch(() => {
                    // 失败原因由 connect 自己写在图标上，这儿只负责排下一次
                })
                .finally(() => {
                    if (!ch || serialLost) {
                        scheduleReconnect();
                    }
                });
        }, RECONNECT_DELAY_MS);
        renderStatus();
    }

    // 连上了，或者人接手了。定时器必须清掉：留着的话它迟些醒来，会在人刚手动
    // 连好之后又插一脚。
    function cancelReconnect() {
        reconnecting = false;
        lastFailure = null;
        if (reconnectTimer) {
            clearTimeout(reconnectTimer);
            reconnectTimer = null;
        }
    }

    async function teardown() {
        if (ch) {
            try {
                ch.forceReleaseAllInput();
            } catch (e) {
            }
            await ch.dispose();
            ch = null;
        }
        if (port) {
            port.dispose();
            port = null;
        }
        chipInfo = null;
        baudRate = null;
        baudProbed = false;
    }

    async function connect(takeover) {
        if (connecting) {
            return;
        }
        connecting = true;
        serialLost = false;
        busy = false;
        await teardown();
        renderStatus({
            level: "idle",
            lines: [takeover ? "正在接管控制权…" : "正在打开串口…"]
        });

        if (!portName) {
            // 上次枚举没结果（服务刚起来、或者 CH9329 那会儿没插），再问一次
            await resolvePort();
        }
        if (!portName) {
            connecting = false;
            renderStatus({level: "error", lines: ["没有可用的串口", "在设置页里指定一个"]});
            return;
        }

        // 接管标志走查询串：争用在 WebSocket 升级完成那一刻就判定了，比任何
        // 握手后的消息都早
        const path = takeover ? "/ws/serial?takeover=1" : "/ws/serial";
        port = new SerialWsPort(websocketUrl(path), portName);
        port.ontransportclose = () => {
            // 桥断了（服务退出、网络掉线）。串口还在宿主机上，但我们指挥不动了
            serialLost = true;
            if (ch) {
                ch.markDisconnected();
            }
            renderStatus();
            scheduleReconnect();
        };
        port.onserviceerror = (error) => {
            // 被别人接管走的也是这条路。这时不能报「串口已中断」——设备好着，
            // 只是控制权换人了，而解法是抢回来而不是查线。
            if (port && port.lastServiceErrorCode === "busy") {
                busy = true;
                serialLost = true;
                if (ch) {
                    ch.markDisconnected();
                }
                renderStatus();
                return;
            }
            // 服务端主动报的错，基本都是串口被拔了或被别的程序抢走
            serialLost = true;
            if (ch) {
                ch.markDisconnected();
            }
            renderStatus({level: "error", lines: ["串口已中断", error.message]});
            scheduleReconnect();
        };

        try {
            // 波特率探测、ACK 降级、失败原因归纳全由 ch9329.js 原样负责。
            // 这里只是把配置里的首选值挪到候选列表最前面，让常见情况少试一次；
            // 芯片上真正写的是多少仍然只有问过才知道。
            const result = await Ch9329.connect(port, baudCandidates(), mouseAbsolute);
            ch = result.ch;
            chipInfo = result.info;
            baudRate = result.baudRate;
            baudProbed = result.probed;
            ch.onTransportError = () => {
                serialLost = true;
                renderStatus();
                scheduleReconnect();
            };
            setAckWait(false);
            cancelReconnect();
            session += 1;
        } catch (error) {
            // 被拒的原因只在服务端那条 error 里。调用方拿到的往往是随后连接断开
            // 产生的「串口桥已断开」——更晚、更没信息量，正好把真正的原因盖住，
            // 所以 SerialWsPort 专门把最后一条服务端错误留了下来。
            const refused = port && port.lastServiceErrorCode === "busy";
            const message = refused
                ? port.lastServiceError.message
                : String(error.message || error);
            await teardown();
            connecting = false;
            if (refused) {
                // 被占用不该触发自动重连，那是抢；清掉是因为人点「接管」之后
                // 我们不想还挂着一条旧的重连链
                cancelReconnect();
                busy = true;
                renderStatus();
                return;
            }
            lastFailure = message;
            renderStatus({
                level: "error",
                lines: ["连接失败", message, reconnecting ? "正在自动重连…" : "点击重试"]
            });
            return;
        }

        bindInput();
        connecting = false;
        renderStatus();
        // 键盘要接管了，焦点不能还留在刚点过的那个按钮上，否则空格和回车会被它吃掉
        if (document.activeElement && document.activeElement.blur) {
            document.activeElement.blur();
        }
    }

    // 输入包一律「只发不等」。
    //
    // CH9329 的应答只对**串口那一段**有意义，而串口就在服务端旁边（实测往返
    // 5ms）。让浏览器每发一包都跨网络等一次应答，等于拿远端的 RTT 去给近端的
    // 串口做流控：客户端在 82ms 外时，包速被钉死在 11 包/秒，一秒六十个鼠标
    // 事件有五十个被丢掉，光标是一跳一跳走的。不等应答就只剩 ch9329.js 自己
    // 的 4ms 包间隔，浏览器给多少就发多少。
    //
    // 这不是新模式：ch9329.js 连续三次收不到应答本来就会自己切到这里，我们
    // 只是明说而已。代价是发出去的包没有确认，但串口那头本来也不丢包。
    // 探测和主动查询要读回复，临时把它开回来。
    //
    // 直接改 `_ackSupported` 是为了让 ch9329.js 保持和 chrome-kvm 逐字节一致
    // （那 44KB 的协议测试才继续有效）。耦合就这一个字段名，超出这个范围就该
    // 老老实实分叉了。
    function setAckWait(enabled) {
        if (ch) {
            ch._ackSupported = enabled;
        }
    }

    async function query(run) {
        setAckWait(true);
        try {
            return await run();
        } finally {
            setAckWait(false);
        }
    }

    async function refreshInfo() {
        if (!ch || serialLost) {
            return;
        }
        chipInfo = await query(() => ch.getInfo());
        renderStatus();
    }

    // ---------------------------------------------------------------------
    // 指针锁定
    // ---------------------------------------------------------------------

    // 相对模式下位移靠光标位置差分算，本机光标一顶到屏幕边缘就不再变化，
    // 被控端光标也跟着走不动了。指针锁定把光标从边界里解放出来，并直接给
    // movementX/movementY。绝对模式要的是真实坐标，锁上反而没法用。
    function lockPointer() {
        if (!ch || ch.isMouseAbsolute() || pointerLocked()) {
            return;
        }
        let pending;
        try {
            // unadjustedMovement 关掉本机的指针加速：被控端自己还会再加一层，
            // 两层叠加会让鼠标很难控
            pending = screenEl.requestPointerLock({unadjustedMovement: true});
        } catch (e) {
            pending = null;
        }
        if (!pending || !pending.catch) {
            return;
        }
        pending.catch(() => {
            // 有的平台不支持免加速，退回普通锁定也比不锁强
            let retry;
            try {
                retry = screenEl.requestPointerLock();
            } catch (e) {
                pointerLockRejected = true;
                renderStatus();
                return;
            }
            if (retry && retry.catch) {
                retry.catch(() => {
                    pointerLockRejected = true;
                    renderStatus();
                });
            }
        });
    }

    // ---------------------------------------------------------------------
    // 事件绑定
    // ---------------------------------------------------------------------

    function bindInput() {
        if (inputBound) {
            return;
        }
        inputBound = true;
        screenEl.style.cursor = "none";

        document.addEventListener("keydown", (event) => {
            // 快捷键面板里填自定义快捷键时，按键是给输入框的，不能发给被控端
            if (isEditableTarget(event.target)) {
                return;
            }
            event.preventDefault();
            // 粘贴和开机连按期间，用户的按键会和模拟敲键的报告互相覆盖，先不转发
            if (ch && !keysSuppressed()) {
                ch.keydown(event.code);
            }
        });

        document.addEventListener("keyup", (event) => {
            if (isEditableTarget(event.target)) {
                return;
            }
            event.preventDefault();
            // 抬起照常转发：屏蔽前按下的键要是松开也被吞掉，本地状态就一直记着它按着
            if (ch) {
                ch.keyup(event.code);
            }
        });

        // 累积滚动量再按齿数下发，保留滚动快慢
        let wheelPixels = 0;
        document.addEventListener("wheel", (event) => {
            // 面板内容多时要能滚动，滚轮不能漏成被控端的滚动
            if (!ch || inToolPanel(event.target)) {
                return;
            }
            let delta = event.deltaY;
            if (event.deltaMode === 1) {
                delta *= 16;   // 行
            } else if (event.deltaMode === 2) {
                delta *= 400;  // 页
            }
            wheelPixels += delta;
            const notches = Math.trunc(wheelPixels / WHEEL_PIXELS_PER_NOTCH);
            if (notches === 0) {
                return;
            }
            wheelPixels -= notches * WHEEL_PIXELS_PER_NOTCH;
            // 浏览器 deltaY 向下为正，协议齿数向上为正
            ch.mouseScroll(-notches);
        }, {passive: true});

        // 每个事件直接下发，中间不再加一层合并。限流由 ch9329.js 的发送队列
        // 负责：它的 writeMove 只保留最新一个移动包，天然跟着串口的速度走。
        // 之前在这里按帧攒过一轮，等于把更新率钉死在 60Hz，还平白多等半帧。
        screenEl.addEventListener("mousemove", (event) => {
            if (!ch) {
                return;
            }
            moveCount += 1;
            if (pointerLocked()) {
                ch.mouseMoveBy(event.movementX, event.movementY);
            } else {
                ch.mouseMove(screenEl, event.clientX, event.clientY);
            }
        });

        screenEl.addEventListener("mousedown", (event) => {
            event.preventDefault();
            if (!ch) {
                return;
            }
            let buttons = 0x00;
            if (event.button === 0) {
                buttons = 0x01;
            } else if (event.button === 1) {
                buttons = 0x04;
            } else if (event.button === 2) {
                buttons = 0x02;
            }
            if (buttons) {
                ch.mouseButtonDown(screenEl, event.clientX, event.clientY, buttons);
            }
            lockPointer();
        });

        screenEl.addEventListener("contextmenu", (event) => event.preventDefault());

        // 用 document 捕获，避免移出画面时丢掉抬起事件
        document.addEventListener("mouseup", (event) => {
            if (ch) {
                ch.mouseButtonUp(event.button);
            }
        });

        // 锁定失败会走这里而不是抛异常，requestPointerLock 的 Promise 也可能
        // 早于它 reject，两条路都要收口
        document.addEventListener("pointerlockerror", () => {
            pointerLockRejected = true;
            renderStatus();
        });

        document.addEventListener("pointerlockchange", () => {
            if (pointerLocked()) {
                pointerLockRejected = false;
            } else if (ch) {
                // 解锁多半是按了 Esc，这时鼠标键的抬起事件收不到，先清干净。
                // 只在解锁时做：上锁时清会把用户刚按下的那个键也松掉，拖拽就断了
                ch.forceReleaseAllMouse();
                // 光标重新出现的位置和锁定前不连续，下次移动要重新取差分基准
                ch.resetRelativeOrigin();
            }
            renderStatus();
        });

        // 切走页面就松开所有键，不然被控端会卡着一个 Ctrl。服务端在 WebSocket
        // 断开时还会再补一次全释放，兜住浏览器崩了发不出来的情况。
        window.addEventListener("blur", () => {
            if (ch) {
                ch.forceReleaseAllInput();
            }
        });
        document.addEventListener("visibilitychange", () => {
            if (document.hidden && ch) {
                ch.forceReleaseAllInput();
            }
        });
    }

    // 键鼠这一路的读数。网络和串口分开报：前者是你离宿主机多远，改不了；
    // 后者是服务端到芯片那一段，几毫秒。发包数才是手感的关键——它应该跟得上
    // 移动事件数，跟不上就说明中间有东西在做流控。
    function renderLatency() {
        const moves = moveCount;
        moveCount = 0;
        if (!port || !ch || serialLost) {
            latencyLines = [];
            return;
        }
        const stats = port.takeStats();
        const network = stats.networkMs ? `${stats.networkMs.toFixed(0)} ms` : "测量中";
        const serial = stats.serialMs ? `${stats.serialMs.toFixed(1)} ms` : "—";
        latencyLines = [
            `网络 ${network}　串口 ${serial}`,
            `发包 ${stats.writes}/秒　移动事件 ${moves}/秒`
        ];
        renderStatus();
    }

    setInterval(renderLatency, LATENCY_POLL_MS);

    // 一个图标兼两件事：没连上就连，连上了就刷新芯片状态。合成一个是因为这两个
    // 动作从来不会同时可用，分两个图标的话总有一个是灰的。
    statusButton.addEventListener("click", () => {
        if (connecting) {
            return;
        }
        if (ch && !serialLost) {
            refreshInfo();
            return;
        }
        // 人接手了，自动重连那条链让位：不清的话它会在这次手动连接之后又插一脚
        const takeover = busy;
        cancelReconnect();
        // 只有明确点过的时候才抢。自动连接绝不抢：页面一打开就夺走别人的控制权，
        // 那是没人想要的行为，而且两个开着的标签页会互相抢到没完。
        connect(takeover).catch((error) => {
            renderStatus({
                level: "error",
                lines: ["连接失败", String(error.message || error), "点击重试"]
            });
        });
    });

    // ---------------------------------------------------------------------
    // 给工具栏用的动作
    // ---------------------------------------------------------------------

    // 不把 ch 本身交出去，只交出动作。ch 的生命周期归这个文件管（连接、重连、
    // 断线重建都在这儿），交出去的引用很容易变成一个已经 dispose 掉的对象，
    // 而那种错误只在用户点按钮的那一刻才暴露。
    window.KvmInput = {
        isReady: () => !!ch && !serialLost,

        releaseAll() {
            if (!ch) {
                throw new Error("键鼠未连接");
            }
            ch.forceReleaseAllInput();
        },

        async pasteText(text) {
            if (!ch || serialLost) {
                throw new Error("键鼠未连接");
            }
            // 不开 ACK：typeText 每个字符是「按下 + 抬起」两包，开了 ACK 就要
            // 跨网络等两次应答，82ms 的链路上一个字符要三分之一秒。关掉之后走
            // ch9329.js 自己的 20ms 包间隔，一个字符 40ms。
            keyboardBusy += 1;
            try {
                return await ch.typeText(text);
            } finally {
                keyboardBusy -= 1;
            }
        },

        // 快捷键面板用：按一下组合键再全部松开。codes 是 KeyboardEvent.code。
        // 返回 false 表示没发出去（未连接、换了连接、或有协议层不认识的键）。
        async tapCombo(codes, expectedSession) {
            if (!ch || serialLost) {
                return false;
            }
            if (expectedSession !== undefined && expectedSession !== session) {
                return false;
            }
            return ch.tapCombo(codes);
        },

        // 当前连接的编号，没连上是 -1
        session: () => (ch && !serialLost ? session : -1),

        // 开机连按期间屏蔽用户键盘，结束时必须配对调用 false
        holdKeyboard(on) {
            keyboardBusy = Math.max(0, keyboardBusy + (on ? 1 : -1));
        },

        isKeyboardBusy: keysSuppressed,

        // 芯片当前的键盘指示灯状态，粘贴前用来提示 Caps Lock
        refreshInfo
    };

    // 打开页面就连。原来要手点一下，但那一下没有任何信息量——串口和鼠标模式都在
    // 配置里，点与不点的结果完全一样。连不上也没关系：图标变红写明原因，再点就重试。
    //
    // 有个副作用要知道：控制页一开着就占着串口，设置页那节「读写芯片」借不到。
    // 不过从工具栏点「设置」是整页跳转，控制页卸载时串口就放了，所以那条路还是通的。
    async function autoConnect() {
        if (!portName) {
            // resolvePort 已经把原因写在图标上了（没串口 / 读不到列表）
            return;
        }
        // 不带接管：见 statusButton 的点击处理
        await connect(false);
    }

    // 先读配置再定串口：后者要用前者来决定开哪个口
    loadSettings()
        .then(resolvePort)
        .then(autoConnect)
        .catch((error) => {
            renderStatus({
                level: "error",
                lines: ["自动连接失败", String(error.message || error), "点击重试"]
            });
        });
    renderStatus();
})();
