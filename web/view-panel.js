// 画面工具：显示方式面板和截图。
(function () {
    const screenEl = document.getElementById("screen");
    const viewButton = document.getElementById("view-mode");
    const viewTip = document.getElementById("view-mode-tip");
    const panel = document.getElementById("view-panel");
    const options = document.getElementById("view-options");
    const screenshotButton = document.getElementById("screenshot");

    const toast = (text, level) => window.KvmToast && KvmToast(text, level);
    const storage = (() => {
        try {
            return window.localStorage;
        } catch (e) {
            return null;
        }
    })();

    // ---------------------------------------------------------------------
    // 显示方式
    // ---------------------------------------------------------------------

    let mode = ViewModes.load(storage);
    let pointer = null;
    let box = null;

    function viewport() {
        return {width: window.innerWidth, height: window.innerHeight};
    }

    function videoSize() {
        return window.KvmVideo ? KvmVideo.size() : null;
    }

    function applyLayout() {
        const view = viewport();
        box = ViewModes.layout(mode, view, videoSize(), window.devicePixelRatio || 1);
        const offset = ViewModes.panOffset(box, view, pointer);
        const style = screenEl.style;
        style.width = box.width + "px";
        style.height = box.height + "px";
        style.left = offset.left + "px";
        style.top = offset.top + "px";
    }

    // 相对模式锁着指针时 clientX/Y 不再变，画面也就不跟着走，这时候放大意义不大，
    // 但不至于乱跳
    function pan(event) {
        pointer = {x: event.clientX, y: event.clientY};
        if (box && ViewModes.pans(box, viewport())) {
            applyLayout();
        }
    }

    function render() {
        const label = ViewModes.label(mode);
        viewTip.textContent = `显示方式：${label}`;
        viewButton.setAttribute("aria-label", `显示方式：${label}`);
        options.textContent = "";
        ViewModes.MODES.forEach((item) => {
            const option = document.createElement("button");
            option.type = "button";
            option.className = "profile-item";
            option.classList.toggle("current", item.id === mode);
            const name = document.createElement("span");
            name.textContent = item.id === mode ? `${item.label}（当前）` : item.label;
            const hint = document.createElement("span");
            hint.className = "profile-summary";
            hint.textContent = item.hint;
            option.append(name, hint);
            option.addEventListener("click", () => setMode(item.id));
            options.appendChild(option);
        });
    }

    function setMode(id) {
        mode = storage ? ViewModes.save(storage, id) : ViewModes.normalizeMode(id);
        applyLayout();
        render();
    }

    function setOpen(open) {
        panel.hidden = !open;
        viewButton.classList.toggle("active", open);
        if (open) {
            render();
            KvmClosePanels(panel);
        }
    }

    viewButton.addEventListener("click", () => setOpen(panel.hidden));
    document.getElementById("view-close").addEventListener("click", () => setOpen(false));
    window.addEventListener("resize", applyLayout);
    window.addEventListener("kvm-video-resize", applyLayout);
    document.addEventListener("mousemove", pan);
    // 别的标签页改了显示方式（比如同时开着两个控制页），这边跟着换
    window.addEventListener("storage", (event) => {
        if (event.key === ViewModes.STORAGE_KEY) {
            mode = ViewModes.load(storage);
            applyLayout();
            render();
        }
    });

    applyLayout();
    render();

    // ---------------------------------------------------------------------
    // 截图
    // ---------------------------------------------------------------------

    // 按采集卡的原始分辨率截，不按页面上缩放后的尺寸，小字才看得清
    async function takeScreenshot() {
        if (!window.KvmVideo || !KvmVideo.streaming()) {
            toast("没有画面可截，请先确认视频已经打开", "warn");
            return;
        }
        const bitmap = await KvmVideo.snapshot();
        if (!bitmap) {
            toast("没有画面可截，请先确认视频已经打开", "warn");
            return;
        }
        const width = bitmap.width;
        const height = bitmap.height;
        const canvas = document.createElement("canvas");
        canvas.width = width;
        canvas.height = height;
        canvas.getContext("2d").drawImage(bitmap, 0, 0);
        bitmap.close();
        const blob = await new Promise((resolve) => canvas.toBlob(resolve, "image/png"));
        if (!blob) {
            toast("截图失败：浏览器没能生成图片", "error");
            return;
        }

        const url = URL.createObjectURL(blob);
        const link = document.createElement("a");
        link.href = url;
        link.download = StatusText.screenshotFileName();
        link.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000);

        // 剪贴板只在 https 或 localhost 下可用，用不了就只下载
        let copied = false;
        try {
            if (navigator.clipboard && window.ClipboardItem) {
                await navigator.clipboard.write([new ClipboardItem({"image/png": blob})]);
                copied = true;
            }
        } catch (e) {
            console.warn("截图复制到剪贴板失败", e);
        }
        toast(copied
            ? `截图已保存（${width}×${height}），并已复制到剪贴板`
            : `截图已保存（${width}×${height}）`, "ok");
    }

    screenshotButton.addEventListener("click", () => {
        takeScreenshot().catch((e) => toast(`截图失败：${e.message || e}`, "error"));
    });
})();
