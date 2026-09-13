// 左侧浮动工具栏：全屏（连带 Keyboard Lock）、录制、Ctrl+Alt+Del、粘贴、
// 松开所有键、设置、刷新。布局照搬 chrome-kvm。
//
// 键鼠相关的三个都只是调 KvmInput 暴露出来的动作——协议层在 ch9329.js 里早就
// 齐了（和 chrome-kvm 逐字节相同），这个文件只负责按钮、图标和文案。
(function () {
    const toolbarEl = document.getElementById("toolbar");
    const fullscreenButton = document.getElementById("fullscreen");
    const fullscreenTip = document.getElementById("fullscreen-tip");
    const recordButton = document.getElementById("record");
    const recordTip = document.getElementById("record-tip");
    const recordIcon = document.getElementById("icon-record");
    const stopIcon = document.getElementById("icon-stop");
    const pasteButton = document.getElementById("paste");
    const cadButton = document.getElementById("ctrl-alt-del");
    const releaseButton = document.getElementById("release-all");
    const settingsButton = document.getElementById("open-settings");
    const reloadButton = document.getElementById("reload");
    const collapseButton = document.getElementById("collapse");
    const collapseTip = document.getElementById("collapse-tip");
    const toastEl = document.getElementById("toast");

    // 提示条挂多久。出错的那条留久一点：那是唯一能看到原因的地方，
    // 而人往往要先愣一下才会去看。
    const TOAST_MS = 4000;
    const TOAST_ERROR_MS = 10000;

    let toastTimer = null;

    function toast(text, level) {
        toastEl.textContent = text;
        toastEl.className = `overlay ${level || ""}`;
        clearTimeout(toastTimer);
        toastTimer = setTimeout(() => {
            toastEl.textContent = "";
        }, level === "error" ? TOAST_ERROR_MS : TOAST_MS);
    }

    // ---------------------------------------------------------------------
    // 全屏 + Keyboard Lock
    // ---------------------------------------------------------------------

    // Win、Alt+Tab、Esc、Ctrl+W 这些键默认被本机的浏览器和系统吃掉，光靠
    // preventDefault 拦不住，根本到不了被控端。Keyboard Lock 能把它们截下来，
    // 但有两个硬条件：安全上下文（HTTPS 或 localhost），以及**由 JS 发起的全屏**
    // ——按 F11 进的全屏不算，所以这个按钮不能只是提示用户自己按 F11。
    let keyboardLocked = false;

    function lockSupported() {
        return !!(navigator.keyboard && navigator.keyboard.lock);
    }

    function renderFullscreen() {
        const state = StatusText.describeFullscreen({
            fullscreen: !!document.fullscreenElement,
            keyboardLocked: keyboardLocked,
            lockSupported: lockSupported()
        });
        fullscreenTip.textContent = state.lines.join("\n");
        fullscreenButton.setAttribute("aria-label", state.label);
    }

    fullscreenButton.addEventListener("click", () => {
        if (document.fullscreenElement) {
            document.exitFullscreen();
        } else {
            // 整个文档进全屏，不是只有画布：工具栏和状态叠加还要能看见
            document.documentElement.requestFullscreen().catch((e) => {
                toast(`进全屏失败：${e.message}`, "error");
            });
        }
    });

    document.addEventListener("fullscreenchange", () => {
        const full = !!document.fullscreenElement;
        // 图标靠这个类切换 enter/exit，见 index.html
        document.body.classList.toggle("is-fullscreen", full);

        if (!full) {
            if (lockSupported()) {
                navigator.keyboard.unlock();
            }
            keyboardLocked = false;
            renderFullscreen();
            return;
        }

        renderFullscreen();
        if (!lockSupported()) {
            return;
        }
        // 不传参数就是锁全部按键。Chrome 保留「长按 Esc 两秒」作为逃生口，
        // 所以短按 Esc 会照常发给被控端——正好是 BIOS 里要用的那个键。
        navigator.keyboard.lock().then(
            () => {
                keyboardLocked = true;
                renderFullscreen();
            },
            (e) => {
                keyboardLocked = false;
                renderFullscreen();
                toast(`系统快捷键没能捕获：${e.message}`, "warn");
            }
        );
    });

    // ---------------------------------------------------------------------
    // 录制
    // ---------------------------------------------------------------------

    let recorder = null;

    // 按浏览器实际支持的挑一个，不硬编码。VP9 比 VP8 同画质小不少，
    // 但不是所有版本都有，所以从好到差试一遍。
    function pickMimeType() {
        const candidates = [
            "video/webm;codecs=vp9,opus",
            "video/webm;codecs=vp8,opus",
            "video/webm"
        ];
        return candidates.find((type) => MediaRecorder.isTypeSupported(type)) || "";
    }

    function renderRecord(on) {
        recordIcon.style.display = on ? "none" : "";
        stopIcon.style.display = on ? "" : "none";
        recordButton.classList.toggle("recording", on);
        recordButton.setAttribute("aria-label", on ? "停止录制" : "录制");
        recordTip.textContent = on ? "停止录制" : "录制（声音开着就一起录进去）";
    }

    function startRecording() {
        if (!window.KvmVideo || !KvmVideo.streaming()) {
            toast("还没有画面可录", "error");
            return;
        }

        const stream = KvmVideo.startCapture();
        const audioTrack = window.KvmAudio && KvmAudio.recordingTrack();
        if (audioTrack) {
            stream.addTrack(audioTrack);
        }

        const mimeType = pickMimeType();
        try {
            recorder = new MediaRecorder(stream, mimeType ? {mimeType} : undefined);
        } catch (e) {
            KvmVideo.stopCapture();
            recorder = null;
            toast(`录制起不来：${e.message}`, "error");
            return;
        }

        const chunks = [];
        recorder.ondataavailable = (event) => {
            if (event.data && event.data.size > 0) {
                chunks.push(event.data);
            }
        };
        recorder.onstop = () => {
            KvmVideo.stopCapture();
            // 音频轨是共用的，不能在这儿 stop——它还挂在播放图上，
            // 停掉的话声音就没了
            const blob = new Blob(chunks, {type: recorder.mimeType || "video/webm"});
            const url = URL.createObjectURL(blob);
            const link = document.createElement("a");
            link.href = url;
            link.download = StatusText.recordFileName();
            link.click();
            URL.revokeObjectURL(url);
            recorder = null;
            renderRecord(false);
            toast(`已保存 ${(blob.size / 1024 / 1024).toFixed(1)} MB`, "ok");
        };

        // 给一个时间片，让 ondataavailable 每秒来一次。不给的话数据只在 stop
        // 时一次性交付，整段录像在那之前都由浏览器攒着；分片交付出来的 Blob
        // 可以由浏览器落到磁盘上，长时间录制不至于把内存堆满。
        recorder.start(1000);
        renderRecord(true);
        toast(audioTrack ? "录制中（含声音）" : "录制中（无声，声音没开）", "warn");
    }

    function stopRecording() {
        if (recorder && recorder.state !== "inactive") {
            recorder.stop();
        }
    }

    recordButton.addEventListener("click", () => {
        if (recorder) {
            stopRecording();
        } else {
            startRecording();
        }
    });

    // 录制中关页面就问一句。不在这儿抢救录像：onstop 里要组 Blob、建 URL、
    // 点链接，全是异步的，页面已经在卸载了根本跑不完——假装能存下来比不管更坏。
    // 能做的只有拦住用户，让他自己先点停止。
    window.addEventListener("beforeunload", (event) => {
        if (recorder) {
            event.preventDefault();
        }
    });

    // ---------------------------------------------------------------------
    // 键鼠动作
    // ---------------------------------------------------------------------

    function ready() {
        if (window.KvmInput && KvmInput.isReady()) {
            return true;
        }
        toast("键鼠还没连上。先点上面那个键盘图标连接", "error");
        return false;
    }

    cadButton.addEventListener("click", async () => {
        if (!ready()) {
            return;
        }
        try {
            await KvmInput.ctrlAltDel();
            toast("已发送 Ctrl+Alt+Del", "ok");
        } catch (e) {
            toast(`发送失败：${e.message}`, "error");
        }
    });

    releaseButton.addEventListener("click", () => {
        if (!ready()) {
            return;
        }
        try {
            KvmInput.releaseAll();
            toast("已松开所有按键和鼠标键", "ok");
        } catch (e) {
            toast(`失败：${e.message}`, "error");
        }
    });

    pasteButton.addEventListener("click", async () => {
        if (!ready()) {
            return;
        }
        let text;
        try {
            text = await navigator.clipboard.readText();
        } catch (e) {
            // 读剪贴板要用户授权，而且必须安全上下文。明文 http 的局域网地址
            // 进不来这一步，所以要把话说清楚，不然只会看到一个空错误。
            toast(
                "读不到剪贴板。需要允许剪贴板权限，且页面要走 https 或 localhost",
                "error"
            );
            return;
        }
        if (!text) {
            toast("剪贴板是空的", "warn");
            return;
        }

        pasteButton.disabled = true;
        // 一个字符两包、每包 20ms，先把预计耗时说出来，免得长文本看着像卡死
        toast(`正在输入 ${text.length} 个字符（约 ${Math.ceil(text.length * 0.04)} 秒）…`, "warn");
        try {
            const result = await KvmInput.pasteText(text);
            const notes = [];
            if (result.skipped > 0) {
                // 映射表只覆盖 US 键盘的可打印字符，中文之类只能跳过——
                // 靠按键模拟没法输入非 US 布局的字符，这是方式本身的限制
                notes.push(`跳过 ${result.skipped} 个无法用按键输入的字符（如中文）`);
            }
            if (result.capsToggled) {
                notes.push("顺带关掉了 Caps Lock");
            }
            toast(
                `已输入 ${result.typed} 个字符` + (notes.length ? `\n${notes.join("；")}` : ""),
                notes.length ? "warn" : "ok"
            );
        } catch (e) {
            toast(`输入失败：${e.message}`, "error");
        } finally {
            pasteButton.disabled = false;
        }
    });

    // ---------------------------------------------------------------------
    // 设置 / 刷新 / 收放
    // ---------------------------------------------------------------------

    settingsButton.addEventListener("click", () => {
        location.href = "/settings.html";
    });

    reloadButton.addEventListener("click", () => {
        location.reload();
    });

    // 不做快捷键：键盘一旦接管就全归被控机了，任何单键都可能是用户想按给对面的。
    collapseButton.addEventListener("click", () => {
        const collapsed = toolbarEl.classList.toggle("hidden");
        collapseTip.textContent = collapsed ? "展开" : "收起";
        collapseButton.setAttribute("aria-label", collapsed ? "展开工具栏" : "收起工具栏");
    });

    renderFullscreen();
    renderRecord(false);
})();
