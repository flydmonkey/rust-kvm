// 声音这一路：从 /ws/audio 收裸 PCM，交给 AudioWorklet 播。
//
// 必须由用户点一下才能开。浏览器的自动播放策略会把没有用户手势的页面里的
// AudioContext 挂起，绕不过去，所以这里是个开关按钮而不是自动连上。顺带也符合
// 实际需要：不听声音的时候连都不连，服务端那头的采集也就不会打开。
(function () {
    // 抖动缓冲。采集时钟和播放时钟不同源，加上网络抖动，水位必须留够余量。
    // 100ms 是个起点：远程链路上撑不住的话，worklet 会每欠载一次按 GROW_MS
    // 往上抬，自己找到能稳住的水位，所以这里不用为最差的链路预留。
    const TARGET_MS = 100;
    const GROW_MS = 40;
    const MAX_MS = 400;

    const RECONNECT_MS = 1500;
    // 设备开不起来（比如服务跑在远程桌面会话里）时不用急着重试，
    // 那种故障要人去改配置，一秒一次只是刷日志。
    const RETRY_AFTER_ERROR_MS = 8000;
    const STATS_POLL_MS = 1000;

    const toggleEl = document.getElementById("audio-toggle");
    const statusTip = document.getElementById("audio-status");
    const iconOn = document.getElementById("icon-audio-on");
    const iconOff = document.getElementById("icon-audio-off");

    // 默认开。真正能不能出声还要看自动播放策略，见 resumeOnGesture。
    let enabled = true;
    let socket = null;
    let context = null;
    let node = null;
    // 录制用的第二个出口。一直挂着而不是录的时候才连：多一个目标节点的开销
    // 等于零，而「开录的那一刻轨已经准备好」省掉了一段时序麻烦。
    let recordDestination = null;
    let retryTimer = null;
    // 等用户手势解锁自动播放的那两个监听器，拆的时候一起撤掉
    let gestureAbort = null;
    // 只有节点建好之后才开始喂：在那之前到的 PCM 直接丢，宁可开头缺一小段，
    // 也不要把加载 worklet 的那段时间变成永久的播放延迟。
    let feeding = false;

    let format = null;
    let lastError = null;
    let workletStats = null;
    let serverStats = null;
    let bytesThisSecond = 0;
    let kbps = 0;

    function websocketUrl(path) {
        const scheme = location.protocol === "https:" ? "wss:" : "ws:";
        return `${scheme}//${location.host}${path}`;
    }

    function show(level, lines) {
        StatusText.applyStatus(toggleEl, statusTip, {level: level, lines: lines});
    }

    function render() {
        iconOn.style.display = enabled ? "" : "none";
        iconOff.style.display = enabled ? "none" : "";
        toggleEl.setAttribute("aria-label", enabled ? "关闭声音" : "打开声音");

        if (!enabled) {
            show("idle", ["声音已关闭", "点击打开"]);
            return;
        }
        if (lastError) {
            show("error", [lastError, "点击关闭"]);
            return;
        }
        if (!format) {
            show("warn", ["正在连接声音…"]);
            return;
        }
        // 自动播放被浏览器拦着。这个状态一定要说出来，否则就是「图标绿着、
        // 读数在涨、人听不到声」——最容易让人以为是采集卡坏了。
        if (context && context.state === "suspended") {
            show("warn", ["等待页面交互后出声", "点一下画面即可"]);
            return;
        }

        const lines = [
            `${format.sampleRate} Hz ${format.channels === 1 ? "单声道" : "立体声"}　${kbps.toFixed(0)} kbps`
        ];
        if (workletStats) {
            // 目标水位会随欠载自己往上爬，所以两个数都要显示：
            // 目标明显高于 100ms 就说明这条链路抖得厉害
            lines.push(
                `缓冲 ${workletStats.bufferedMs.toFixed(0)} / 目标 ${workletStats.targetMs.toFixed(0)} ms${workletStats.playing ? "" : "　攒水位中"}`
            );
            // 欠载是网络或时钟跟不上，重同步是缓冲涨过上限被裁——
            // 前者听着是断续，后者是偶尔一下咔哒，原因不同所以分开显示
            if (workletStats.underruns > 0 || workletStats.resyncs > 0) {
                lines.push(`欠载 ${workletStats.underruns}　重同步 ${workletStats.resyncs}`);
            }
        }
        if (serverStats && serverStats.error) {
            lines.push(`服务端 ${serverStats.error}`);
        }
        lines.push("点击关闭");
        show("ok", lines);
    }

    async function buildPlayer(incoming) {
        // AudioContext 直接按采集的采样率建，这样 worklet 收到的样本率就等于
        // 上下文率，一点重采样都不用做。真和硬件不符时浏览器会在输出端重采样，
        // 那是它的活儿，比我手写的强。
        const ctx = new AudioContext({
            sampleRate: incoming.sampleRate,
            latencyHint: "interactive"
        });
        await ctx.audioWorklet.addModule("audio-worklet.js");

        const player = new AudioWorkletNode(ctx, "pcm-player", {
            numberOfInputs: 0,
            numberOfOutputs: 1,
            outputChannelCount: [incoming.channels],
            processorOptions: {
                channels: incoming.channels,
                targetFrames: Math.round((incoming.sampleRate * TARGET_MS) / 1000),
                growFrames: Math.round((incoming.sampleRate * GROW_MS) / 1000),
                maxFrames: Math.round((incoming.sampleRate * MAX_MS) / 1000)
            }
        });
        player.port.onmessage = (event) => {
            workletStats = event.data;
            render();
        };
        player.connect(ctx.destination);
        recordDestination = ctx.createMediaStreamDestination();
        player.connect(recordDestination);

        context = ctx;
        node = player;

        // 自动播放策略：没有用户手势时新建的 AudioContext 是挂起的。声音改成默认
        // 打开之后，这条路就成了常态路径，而它有两个坑：
        //
        // 1. **不能 `await ctx.resume()`**。被策略拦住时 Chrome 让这个 promise 一直
        //    悬着，buildPlayer 就永远不返回，页面显示在播、实际一点声音没有。
        //    原来这里就是 await，能跑是因为那时必定有刚点过开关的那次手势。
        // 2. **挂起期间不能喂数据**。worklet 的 process 不跑，喂进去的 PCM 只会在
        //    它的消息队列里堆着，1536 kbps 堆五分钟就是五十多兆。
        //
        // 所以挂起与否统一由状态变化驱动，而不是只在这儿判一次：上下文中途也可能
        // 被挂起（系统休眠、浏览器省电），那时同样该停止喂数据并等下一次交互。
        ctx.onstatechange = () => {
            feeding = ctx.state === "running";
            if (feeding) {
                disarmGesture();
            } else {
                armGesture(ctx);
            }
            render();
        };
        feeding = ctx.state === "running";
        if (!feeding) {
            // 有权限的话这一下就起来了（statechange 会把 feeding 打开）
            tryResume(ctx);
            armGesture(ctx);
        }
    }

    // Chrome 被自动播放策略拦住时让 resume() 的 promise 一直悬着，而 Safari 和
    // Firefox 是直接 reject。两种都不该往上冒：拦住不是错误，等手势就是了。
    function tryResume(ctx) {
        ctx.resume().catch(() => {
        });
    }

    // 默认开声音就会撞上自动播放策略。守着第一次交互即可——用户迟早要点一下画面
    // 去操作被控机，那一下就够了，不用专门再点声音开关。
    function armGesture(ctx) {
        if (gestureAbort) {
            return;
        }
        gestureAbort = new AbortController();
        const options = {once: true, signal: gestureAbort.signal};
        ["pointerdown", "keydown"].forEach((name) => {
            window.addEventListener(name, () => tryResume(ctx), options);
        });
    }

    function disarmGesture() {
        if (gestureAbort) {
            gestureAbort.abort();
            gestureAbort = null;
        }
    }

    function onText(payload) {
        let message;
        try {
            message = JSON.parse(payload);
        } catch (e) {
            return;
        }

        if (message.op === "error") {
            lastError = message.message;
            render();
            // 服务端已经说清楚原因了，它不会自己好，慢点重试
            teardown();
            scheduleRetry(RETRY_AFTER_ERROR_MS);
            return;
        }
        if (message.op === "format") {
            lastError = null;
            format = {sampleRate: message.sampleRate, channels: message.channels};
            render();
            buildPlayer(format).catch((e) => {
                lastError = `播放端建不起来：${e.message}`;
                render();
                teardown();
                scheduleRetry(RETRY_AFTER_ERROR_MS);
            });
        }
    }

    function onBinary(buffer) {
        bytesThisSecond += buffer.byteLength;
        if (!feeding || !node) {
            return;
        }
        // 转移所有权而不是拷贝，主线程这边之后也用不到它了
        const pcm = new Int16Array(buffer);
        node.port.postMessage(pcm, [pcm.buffer]);
    }

    function connect() {
        const ws = new WebSocket(websocketUrl("/ws/audio"));
        ws.binaryType = "arraybuffer";
        socket = ws;

        ws.onmessage = (event) => {
            if (typeof event.data === "string") {
                onText(event.data);
            } else {
                onBinary(event.data);
            }
        };
        ws.onclose = () => {
            if (socket !== ws) {
                return;
            }
            teardown();
            if (enabled) {
                render();
                scheduleRetry(RECONNECT_MS);
            }
        };
        ws.onerror = () => ws.close();
    }

    function scheduleRetry(delay) {
        clearTimeout(retryTimer);
        if (!enabled) {
            return;
        }
        retryTimer = setTimeout(() => {
            if (enabled) {
                connect();
            }
        }, delay);
    }

    // 只拆播放侧，不动 enabled：重连后会照样重新搭起来
    function teardown() {
        feeding = false;
        format = null;
        workletStats = null;

        if (socket) {
            const ws = socket;
            socket = null;
            ws.onclose = null;
            ws.onmessage = null;
            ws.close();
        }
        if (node) {
            node.port.onmessage = null;
            node.disconnect();
            node = null;
        }
        recordDestination = null;
        disarmGesture();
        if (context) {
            context.onstatechange = null;
            context.close();
            context = null;
        }
    }

    toggleEl.addEventListener("click", () => {
        enabled = !enabled;
        clearTimeout(retryTimer);
        lastError = null;
        if (enabled) {
            connect();
        } else {
            teardown();
            serverStats = null;
        }
        render();
    });

    // 录制那边要问一句「现在有声音可录吗」。声音没开就返回 null，录成无声视频，
    // 而不是拦着不让录。
    //
    // 有个已知的粗糙处：声音重连会重建 AudioContext，录制中途碰上的话这条轨就
    // 结束了，视频照录但后半段没声。真要修得让录制去监听重连事件重挂一次轨，
    // 不值得——重连本身就已经是听得出来的事故了。
    window.KvmAudio = {
        recordingTrack: () =>
            recordDestination ? recordDestination.stream.getAudioTracks()[0] : null
    };

    setInterval(async () => {
        kbps = (bytesThisSecond * 8) / 1000;
        bytesThisSecond = 0;

        if (enabled) {
            try {
                const response = await fetch("/api/audio/stats", {cache: "no-store"});
                serverStats = await response.json();
            } catch (e) {
                serverStats = null;
            }
        }
        render();
    }, STATS_POLL_MS);

    // 默认就连。出不出声由自动播放策略决定，用户不用再点一下开关。
    connect();
    render();
})();
