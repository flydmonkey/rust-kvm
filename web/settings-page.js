// 设置页：把 /api/devices 枚举到的设备摆成下拉框，存回 /api/config。
//
// 一个贯穿始终的取舍：配置里存的设备名可能**不在**当前枚举结果里（设备拔了、
// 或者配置是手写的关键词而非完整名字）。那种情况下绝不能静悄悄地把选择重置成
// 第一个设备——那等于用户一进设置页、什么都没动、一点保存就把配置改掉了。
// 所以对配置里有值但列表里没有的项，额外插一条「(当前配置，未检测到)」并选中它。
(function () {
    const SIZES = [
        [1920, 1080],
        [1600, 900],
        [1280, 720],
        [960, 540],
        [640, 360]
    ];
    const FPS = [30, 25, 20, 15, 10];
    const CODECS = [
        {value: "mjpeg", label: "MJPEG（延迟最低）"},
        {value: "h264", label: "H.264（省带宽）"}
    ];
    // 固定几档，不做自适应。理由见低带宽模式的设计文档：链路好坏在这个场景里
    // 是已知的（在家还是在外面），一个开关比一套猜带宽的算法可靠。
    const BITRATES = [1000, 2000, 4000, 8000];
    const BAUD_RATES = [9600, 115200];
    const LIVE_POLL_MS = 2000;

    const el = (id) => document.getElementById(id);

    const videoDevice = el("video-device");
    const videoSize = el("video-size");
    const videoFps = el("video-fps");
    const videoCodec = el("video-codec");
    const videoBitrate = el("video-bitrate");
    const audioDevice = el("audio-device");
    const audioEnabled = el("audio-enabled");
    const audioNarrowband = el("audio-narrowband");
    const serialPort = el("serial-port");
    const serialBaud = el("serial-baud");
    const mouseMode = el("mouse-mode");
    const workingMode = el("working-mode");
    const saveButton = el("save");
    const reloadButton = el("reload");
    const saveStatus = el("save-status");
    const chipStatus = el("chip-status");
    const chipButtons = [el("chip-read"), el("chip-baud"), el("chip-mode"), el("chip-reset")];

    let config = null;

    function fill(select, options) {
        select.innerHTML = "";
        options.forEach((option) => {
            const node = document.createElement("option");
            node.value = option.value;
            node.textContent = option.label;
            select.appendChild(node);
        });
    }

    // 选中 value；列表里没有就补一条占位项，免得当前配置被悄悄改掉
    function selectOrKeep(select, value) {
        if (!value) {
            return;
        }
        const known = [...select.options].some((option) => option.value === value);
        if (!known) {
            const node = document.createElement("option");
            node.value = value;
            node.textContent = `${value}（当前配置，未检测到）`;
            select.insertBefore(node, select.firstChild);
        }
        select.value = value;
    }

    // 设备名要按服务端那套规则匹配：配置里存的可能只是个关键词，而枚举出来的是
    // 全名（「Digital Audio Interface」对「Digital Audio Interface (USB Digital
    // Audio)」）。只认全等的话，明明正在用的设备会被标成「未检测到」。
    function selectDevice(select, hint) {
        if (!hint) {
            return;
        }
        const matched = [...select.options].find((option) => option.value.includes(hint));
        if (matched) {
            select.value = matched.value;
            return;
        }
        selectOrKeep(select, hint);
    }

    function showProblem(id, message) {
        el(id).textContent = message || "";
    }

    function renderDevices(devices) {
        fill(
            videoDevice,
            devices.video.items.map((item) => ({
                value: item.name,
                label: `${item.name}（索引 ${item.index}）`
            }))
        );
        showProblem("video-problem", devices.video.error);

        fill(
            audioDevice,
            devices.audio.items.map((item) => {
                const rate = item.sampleRate ? `${item.sampleRate} Hz` : "采样率未知";
                const channels = item.channels === 1 ? "单声道" : `${item.channels} 声道`;
                return {value: item.name, label: `${item.name}（${rate}，${channels}）`};
            })
        );
        showProblem("audio-problem", devices.audio.error);

        fill(
            serialPort,
            devices.serial.items.map((item) => ({
                value: item.name,
                label: item.description ? `${item.name} — ${item.description}` : item.name
            }))
        );
        showProblem("serial-problem", devices.serial.error);
    }

    // 码率只对 H.264 有意义。留着能点但不起作用的控件，比灰掉更让人困惑。
    function renderBitrateState() {
        videoBitrate.disabled = videoCodec.value !== "h264";
    }

    function renderConfig() {
        selectDevice(videoDevice, config.video.device);
        selectOrKeep(videoSize, `${config.video.width}x${config.video.height}`);
        selectOrKeep(videoFps, String(config.video.fps));
        selectOrKeep(videoCodec, config.video.codec);
        selectOrKeep(videoBitrate, String(config.video.bitrateKbps));
        renderBitrateState();

        selectDevice(audioDevice, config.audio.device);
        audioEnabled.checked = config.audio.enabled;
        audioNarrowband.checked = config.audio.narrowband;

        selectOrKeep(serialPort, config.serial.port);
        selectOrKeep(serialBaud, String(config.serial.baudRate));
        mouseMode.value = config.serial.mouseMode;
    }

    // **每个字段都要列全。** 服务端那边所有字段都是 `serde(default)`，漏掉一个不会
    // 报错，而是被悄悄重置成默认值——加了 codec 和 bitrateKbps 之后，这里漏写就等于
    // 「在设置页保存任何一项，都会把 H.264 关掉」。
    function collect() {
        const [width, height] = videoSize.value.split("x").map(Number);
        return {
            video: {
                device: videoDevice.value,
                width: width,
                height: height,
                fps: Number(videoFps.value),
                codec: videoCodec.value,
                bitrateKbps: Number(videoBitrate.value)
            },
            audio: {
                device: audioDevice.value,
                enabled: audioEnabled.checked,
                narrowband: audioNarrowband.checked
            },
            serial: {
                port: serialPort.value,
                baudRate: Number(serialBaud.value),
                mouseMode: mouseMode.value
            }
        };
    }

    async function load() {
        saveStatus.textContent = "";
        saveStatus.className = "";
        try {
            const [devices, current] = await Promise.all([
                fetch("/api/devices", {cache: "no-store"}).then((r) => r.json()),
                fetch("/api/config", {cache: "no-store"}).then((r) => r.json())
            ]);
            config = current;
            renderDevices(devices);
            // 顺序要紧：先铺列表再套配置，否则 selectOrKeep 插的占位项会被冲掉
            renderConfig();
        } catch (e) {
            saveStatus.textContent = `读不到设备或配置：${e.message || e}`;
            saveStatus.className = "error";
        }
    }

    async function save() {
        saveButton.disabled = true;
        saveStatus.textContent = "正在保存…";
        saveStatus.className = "";
        try {
            const response = await fetch("/api/config", {
                method: "PUT",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify(collect())
            });
            if (!response.ok) {
                throw new Error(await response.text());
            }
            config = await response.json();
            // 不写「采集已重开」：改了设备或分辨率才会重开，只改串口不会，
            // 而这里分不清用户动了哪一项
            saveStatus.textContent = "已保存（设备或分辨率有变动时采集会自动重开）";
            saveStatus.className = "ok";
        } catch (e) {
            saveStatus.textContent = `保存失败：${e.message || e}`;
            saveStatus.className = "error";
        } finally {
            saveButton.disabled = false;
        }
    }

    // 显示实际生效的值。请求的分辨率不一定拿得到——服务端按「最接近」协商，
    // 所以这一行才是真相，也是判断带宽够不够的依据。
    async function pollLive() {
        try {
            const video = await fetch("/api/video/stats", {cache: "no-store"}).then((r) => r.json());
            const resolution = video.resolution
                ? `${video.resolution[0]}x${video.resolution[1]}`
                : "未采集";
            el("video-live").textContent = video.error
                ? `实际 ${resolution}　错误：${video.error}`
                : `实际 ${resolution}　${video.fps.toFixed(1)} fps　${video.mbps.toFixed(1)} Mbps　观看者 ${video.viewers}`;
        } catch (e) {
            el("video-live").textContent = "读不到画面状态";
        }

        try {
            const audio = await fetch("/api/audio/stats", {cache: "no-store"}).then((r) => r.json());
            const format = audio.format
                ? `${audio.format.sampleRate} Hz ${audio.format.channels} 声道`
                : "未采音";
            el("audio-live").textContent = audio.error
                ? `实际 ${format}　错误：${audio.error}`
                : `实际 ${format}　${audio.kbps.toFixed(0)} kbps　听众 ${audio.listeners}`;
        } catch (e) {
            el("audio-live").textContent = "读不到声音状态";
        }
    }

    // -------------------------------------------------------------------
    // 芯片配置：临时借用串口，写完立刻还回去
    // -------------------------------------------------------------------

    function websocketUrl(path) {
        const scheme = location.protocol === "https:" ? "wss:" : "ws:";
        return `${scheme}//${location.host}${path}`;
    }

    // 探测顺序用**配置里存的**波特率打头，而不是下拉框里选的那个：下拉框是
    // 「想写成多少」，配置才是「芯片现在多半是多少」。
    function baudCandidates() {
        const preferred = config && config.serial.baudRate;
        if (!preferred || !Ch9329.BAUD_RATES.includes(preferred)) {
            return Ch9329.BAUD_RATES;
        }
        return [preferred, ...Ch9329.BAUD_RATES.filter((rate) => rate !== preferred)];
    }

    // 所有写芯片的动作外壳一样：借串口 → 确认芯片真在应答 → 干活 → 还回去。
    // 只有中间那步不同，所以把外壳抽出来。
    //
    // 借不到时会问一句要不要接管。先礼后兵，不直接抢：抢走的可能是别人正在用的
    // 控制权，而写芯片这几项（波特率、工作模式、恢复出厂）本来就是开机配一次的
    // 事，值得多问一句。
    async function withChip(action) {
        if (!serialPort.value) {
            throw new Error("请先选一个串口");
        }
        try {
            return await borrowChip(action, false);
        } catch (e) {
            if (e.serialBusy !== true) {
                throw e;
            }
            const sure = window.confirm(
                `${e.message}\n\n` +
                    "要把控制权接管过来吗？\n" +
                    "对方的键鼠会断开（被控机上不会留下按住的键），它那边可以再点回来。"
            );
            if (!sure) {
                throw e;
            }
            return await borrowChip(action, true);
        }
    }

    async function borrowChip(action, takeover) {
        const path = takeover ? "/ws/serial?takeover=1" : "/ws/serial";
        const port = new SerialWsPort(websocketUrl(path), serialPort.value);

        let connection;
        try {
            connection = await Ch9329.connect(port, baudCandidates(), true);
        } catch (e) {
            port.dispose();
            // Ch9329.connect 抛的是 chrome-kvm 那套「关掉其它标签页」的说辞——
            // 那是给 Web Serial 写的，在这儿完全不适用：口是服务端开的，抢它的
            // 只可能是另一个 /ws/serial 控制端。服务端说了原因就用服务端的。
            const error = port.lastServiceError || e;
            // 标在异常上而不是让上面去对文案：文案是给人看的，随时会改
            if (port.lastServiceErrorCode === "busy") {
                error.serialBusy = true;
            }
            throw error;
        }
        if (!connection.info) {
            await connection.ch.dispose();
            port.dispose();
            throw new Error(`CH9329 没有应答。探测结果：${Ch9329.describeAttempts(connection.attempts)}`);
        }
        try {
            return await action(connection.ch, connection);
        } finally {
            // 成败都要还：不还的话控制页就再也连不上串口了
            await connection.ch.dispose();
            port.dispose();
        }
    }

    function setChipStatus(text, level) {
        chipStatus.textContent = text;
        chipStatus.className = `live ${level || ""}`;
    }

    async function runChip(busyText, action) {
        chipButtons.forEach((button) => (button.disabled = true));
        setChipStatus(busyText);
        try {
            await action();
        } catch (e) {
            setChipStatus(`失败：${e.message || e}`, "error");
        } finally {
            chipButtons.forEach((button) => (button.disabled = false));
        }
    }

    // 芯片才是工作模式的唯一真相，所以读回来直接盖掉下拉框，而不是反过来
    // 拿本地存的值去猜芯片是什么状态。
    function readChip() {
        return runChip("正在读取芯片…", async () => {
            const result = await withChip(async (ch, connection) => {
                const cfg = await ch.getParaCfg();
                if (!cfg) {
                    throw new Error("读取参数配置失败（芯片没有回 50 字节配置）");
                }
                return {
                    version: connection.info.version,
                    mode: ch.readParaWorkingMode(cfg),
                    storedBaud: ch.readParaBaudRate(cfg),
                    // 刚才是用哪个波特率问通的。和存着的那个不一致就说明
                    // 上次写入还等着断电重插——这是最容易让人困惑的状态，
                    // 值得单独说一句。
                    liveBaud: connection.baudRate
                };
            });
            workingMode.value = String(result.mode);
            selectOrKeep(serialBaud, String(result.storedBaud));

            let text = `芯片固件 ${result.version}　工作模式 ${result.mode}　波特率 ${result.storedBaud}`;
            let level = "ok";
            if (result.liveBaud !== result.storedBaud) {
                text += `\n注意：芯片现在实际跑在 ${result.liveBaud}，`
                    + `配置里已经是 ${result.storedBaud} 了——上次写入还没断电重插。`;
                level = "warn";
            }
            setChipStatus(text, level);
        });
    }

    function writeBaudRate() {
        const target = Number(serialBaud.value);
        return runChip(`正在写入波特率 ${target}…`, async () => {
            const result = await withChip((ch) => ch.setBaudRate(target));
            if (!result.ok) {
                throw new Error(result.reason);
            }
            // 顺手记进配置：下次连接就该优先试这个值了。写在通知用户之前，
            // 免得保存失败被那句「已写入」盖过去。
            await patchConfig((next) => (next.serial.baudRate = target));
            if (result.unchanged) {
                setChipStatus(`芯片波特率已经是 ${target}，没有改动`, "ok");
                return;
            }
            setChipStatus(`已写入波特率 ${target}。请把 CH9329 断电重插后生效。`, "warn");
        });
    }

    function writeWorkingMode() {
        const target = Number(workingMode.value);
        return runChip(`正在写入工作模式 ${target}…`, async () => {
            const result = await withChip((ch) => ch.setWorkingMode(target));
            if (!result.ok) {
                throw new Error(result.reason);
            }
            if (result.unchanged) {
                setChipStatus(`芯片工作模式已经是 ${target}，没有改动`, "ok");
                return;
            }
            setChipStatus(
                `已写入工作模式 ${target}。请把 CH9329 断电重插后生效。\n` +
                "被控端可能会把它当成一台新键盘而弹出识别向导，走完或关掉即可。",
                "warn"
            );
        });
    }

    function restoreDefaults() {
        const sure = confirm(
            "这会把芯片的参数配置和字符串描述符全部恢复出厂默认：\n" +
            "波特率回到 9600，工作模式回到 0。\n\n确定继续吗？"
        );
        if (!sure) {
            return Promise.resolve();
        }
        return runChip("正在恢复出厂配置…", async () => {
            const result = await withChip((ch) => ch.restoreDefaultCfg());
            if (!result.ok) {
                const status = result.status == null ? "无应答" : `0x${result.status.toString(16)}`;
                throw new Error(`恢复出厂配置失败（${status}）`);
            }
            await patchConfig((next) => (next.serial.baudRate = 9600));
            workingMode.value = "0";
            selectOrKeep(serialBaud, "9600");
            setChipStatus(
                "已恢复出厂配置。请把 CH9329 断电重插后生效，之后波特率是 9600。",
                "warn"
            );
        });
    }

    // 只改配置里的一两项，不把页面上其它没保存的改动一起写进去
    async function patchConfig(mutate) {
        const next = JSON.parse(JSON.stringify(config));
        mutate(next);
        const response = await fetch("/api/config", {
            method: "PUT",
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify(next)
        });
        if (!response.ok) {
            throw new Error(await response.text());
        }
        config = await response.json();
    }

    fill(videoSize, SIZES.map(([w, h]) => ({value: `${w}x${h}`, label: `${w} × ${h}`})));
    fill(videoFps, FPS.map((fps) => ({value: String(fps), label: `${fps} fps`})));
    fill(videoCodec, CODECS);
    fill(
        videoBitrate,
        BITRATES.map((kbps) => ({value: String(kbps), label: `${kbps / 1000} Mbps`}))
    );
    fill(serialBaud, BAUD_RATES.map((rate) => ({value: String(rate), label: String(rate)})));

    videoCodec.addEventListener("change", renderBitrateState);
    saveButton.addEventListener("click", save);
    reloadButton.addEventListener("click", load);
    el("chip-read").addEventListener("click", readChip);
    el("chip-baud").addEventListener("click", writeBaudRate);
    el("chip-mode").addEventListener("click", writeWorkingMode);
    el("chip-reset").addEventListener("click", restoreDefaults);

    load();
    pollLive();
    setInterval(pollLive, LIVE_POLL_MS);
})();
