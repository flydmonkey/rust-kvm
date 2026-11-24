// 文字识别：框选画面上的一块，用 vendor/tesseract 在本机识别成文字。照搬 chrome-kvm 的
// kvm-ocr.js；区域换算、预处理、结果整理在 ocr.js，这里只管框选交互、截图和调引擎
(function () {
    const button = document.getElementById("ocr");
    const overlay = document.getElementById("ocr-overlay");
    const box = document.getElementById("ocr-box");
    const panel = document.getElementById("ocr-panel");
    const textArea = document.getElementById("ocr-text");
    const langsEl = document.getElementById("ocr-langs");
    const againButton = document.getElementById("ocr-again");
    const wholeButton = document.getElementById("ocr-whole");
    const copyButton = document.getElementById("ocr-copy");
    const closeButton = document.getElementById("ocr-close");

    const toast = (text, level) => window.KvmToast && KvmToast(text, level);
    const storage = (() => {
        try {
            return window.localStorage;
        } catch (e) {
            return null;
        }
    })();

    // 引擎连同中文语言包要占一两百 MB 内存，闲置一阵就放掉；下次再用重新加载也不到一秒
    const IDLE_MS = 3 * 60 * 1000;

    let selecting = false;
    let start = null;
    let panelWasOpen = false;
    let busy = false;
    let worker = null;
    let workerLangs = null;
    let progress = null;
    let idleTimer = null;
    // 上一次识别的截图（已预处理），改了语言要按新语言重认同一张
    let lastCanvas = null;
    let langs = Ocr.loadLangs(storage);

    function hasLivePicture() {
        return !!(window.KvmVideo && KvmVideo.streaming());
    }

    function loadScript(src) {
        return new Promise((resolve, reject) => {
            const script = document.createElement("script");
            script.src = src;
            script.onload = resolve;
            script.onerror = () => reject(new Error(`加载 ${src} 失败`));
            document.head.appendChild(script);
        });
    }

    // 引擎第一次用到才加载，平时打开控制页不多下载一个字节
    async function ensureWorker(key) {
        if (typeof Tesseract === "undefined") {
            await loadScript(Ocr.VENDOR_DIR + "tesseract.min.js");
        }
        if (!worker) {
            const options = Ocr.workerOptions(new URL(Ocr.VENDOR_DIR, location.href).href,
                Ocr.simdSupported(window.WebAssembly));
            options.logger = (message) => {
                if (progress) {
                    progress(message);
                }
            };
            worker = await Tesseract.createWorker(key, 1, options);
            workerLangs = key;
        } else if (workerLangs !== key) {
            // 已加载的语言包不会重新下载，只补新勾上的；换顺序只是重新初始化
            await worker.reinitialize(key);
            workerLangs = key;
        }
        return worker;
    }

    function disposeWorker() {
        const old = worker;
        worker = null;
        workerLangs = null;
        if (old) {
            old.terminate().catch(() => {});
        }
    }

    function scheduleIdle() {
        clearTimeout(idleTimer);
        idleTimer = setTimeout(() => {
            if (!busy) {
                disposeWorker();
            }
        }, IDLE_MS);
    }

    function closeOtherPanels() {
        KvmClosePanels(panel);
    }

    function setPanelOpen(open) {
        if (open) {
            closeOtherPanels();
            renderLangs();
        }
        panel.hidden = !open;
        button.classList.toggle("active", open || selecting);
    }

    function startSelection() {
        if (busy) {
            toast("正在识别，等这一次完成", "warn");
            return;
        }
        if (!hasLivePicture()) {
            toast("还没有画面，没法识别文字", "warn");
            return;
        }
        panelWasOpen = !panel.hidden;
        setPanelOpen(false);
        closeOtherPanels();
        // 相对模式锁着指针时看不到光标，没法框选
        if (document.pointerLockElement) {
            document.exitPointerLock();
        }
        selecting = true;
        start = null;
        box.hidden = true;
        overlay.hidden = false;
        button.classList.add("active");
        toast("在画面上拖出要识别的区域；Esc 或右键取消", "warn");
    }

    function endSelection() {
        selecting = false;
        start = null;
        overlay.hidden = true;
        box.hidden = true;
        button.classList.toggle("active", !panel.hidden);
    }

    function cancelSelection() {
        endSelection();
        toast("已取消框选");
        if (panelWasOpen) {
            setPanelOpen(true);
        }
    }

    function drawBox(a, b) {
        box.style.left = Math.min(a.x, b.x) + "px";
        box.style.top = Math.min(a.y, b.y) + "px";
        box.style.width = Math.abs(b.x - a.x) + "px";
        box.style.height = Math.abs(b.y - a.y) + "px";
        box.hidden = false;
    }

    // 框选那一刻就把画面截下来：等引擎加载完再截，画面可能已经变了。
    // region 为 null 表示整个画面。截不到返回 null
    async function capture(selection) {
        const bitmap = await KvmVideo.snapshot();
        if (!bitmap) {
            return null;
        }
        try {
            const video = {width: bitmap.width, height: bitmap.height};
            let region = {x: 0, y: 0, width: video.width, height: video.height};
            if (selection) {
                region = Ocr.selectionToVideo(selection.start, selection.end,
                    KvmVideo.element.getBoundingClientRect(), video);
                if (!region) {
                    return null;
                }
            }
            const scale = Ocr.scaleFor(region.width, region.height);
            const canvas = document.createElement("canvas");
            canvas.width = region.width * scale;
            canvas.height = region.height * scale;
            const context = canvas.getContext("2d", {willReadFrequently: true});
            context.imageSmoothingQuality = "high";
            context.drawImage(bitmap, region.x, region.y, region.width, region.height,
                0, 0, canvas.width, canvas.height);
            const image = context.getImageData(0, 0, canvas.width, canvas.height);
            Ocr.preprocess(image.data);
            context.putImageData(image, 0, 0);
            return canvas;
        } finally {
            bitmap.close();
        }
    }

    async function copyPlainText(text) {
        try {
            await navigator.clipboard.writeText(text);
            return true;
        } catch (e) {
            return false;
        }
    }

    function setControlsDisabled(disabled) {
        againButton.disabled = disabled;
        wholeButton.disabled = disabled;
        langsEl.querySelectorAll("input").forEach((input) => {
            input.disabled = disabled;
        });
    }

    async function recognize(canvas) {
        if (busy) {
            return;
        }
        busy = true;
        lastCanvas = canvas;
        setControlsDisabled(true);
        toast(Ocr.progressText(), "warn");
        progress = (message) => toast(Ocr.progressText(message.status, message.progress), "warn");
        try {
            const plan = Ocr.recognitionPlan(langs);
            let raw = (await (await ensureWorker(plan.first)).recognize(canvas)).data.text;
            if (plan.second && Ocr.hasCjk(raw)) {
                raw = (await (await ensureWorker(plan.second)).recognize(canvas)).data.text;
            }
            progress = null;
            const text = Ocr.cleanText(raw);
            textArea.value = text;
            setPanelOpen(true);
            if (!text) {
                toast("没有识别到文字。框小一点、只框住文字再试试", "warn");
                return;
            }
            const copied = await copyPlainText(text);
            const count = Ocr.charCount(text);
            toast(copied ? `识别出 ${count} 个字，已复制到剪贴板` : `识别出 ${count} 个字`, "ok");
        } catch (e) {
            progress = null;
            console.warn("文字识别失败", e);
            toast(`文字识别失败：${e && e.message ? e.message : String(e)}`, "error");
            // 引擎出过错就不再信它，下次重新建
            disposeWorker();
        } finally {
            busy = false;
            progress = null;
            setControlsDisabled(false);
            scheduleIdle();
        }
    }

    async function recognizeWhole() {
        if (busy) {
            return;
        }
        if (!hasLivePicture()) {
            toast("还没有画面，没法识别文字", "warn");
            return;
        }
        const canvas = await capture(null);
        if (!canvas) {
            toast("截不到画面，稍后再试", "error");
            return;
        }
        recognize(canvas);
    }

    async function copyResult() {
        if (!textArea.value) {
            toast("没有可复制的文字", "warn");
            return;
        }
        if (await copyPlainText(textArea.value)) {
            toast("已复制", "ok");
            return;
        }
        // 浏览器不让写剪贴板时（明文 http 的局域网地址就是这样），选中文字让用户自己按 Ctrl+C
        textArea.focus();
        textArea.select();
        toast("浏览器不让写剪贴板，文字已选中，请按 Ctrl+C 复制", "warn");
    }

    function renderLangs() {
        langsEl.textContent = "";
        Ocr.LANGUAGES.forEach((lang) => {
            const label = document.createElement("label");
            const input = document.createElement("input");
            input.type = "checkbox";
            input.value = lang.id;
            input.checked = langs.indexOf(lang.id) !== -1;
            input.disabled = busy;
            input.addEventListener("change", () => {
                const picked = Array.from(langsEl.querySelectorAll("input:checked")).map((el) => el.value);
                // 至少留一种语言，全取消掉的那一下不算
                if (!picked.length) {
                    input.checked = true;
                    return;
                }
                try {
                    langs = Ocr.saveLangs(storage, picked);
                } catch (e) {
                    langs = Ocr.normalizeLangs(picked);
                }
                if (lastCanvas) {
                    recognize(lastCanvas);
                }
            });
            label.append(input, " " + lang.label);
            langsEl.appendChild(label);
        });
    }

    function point(event) {
        return {x: event.clientX, y: event.clientY};
    }

    overlay.addEventListener("pointerdown", (event) => {
        event.preventDefault();
        if (event.pointerType === "mouse" && event.button !== 0) {
            // 右键取消，和多数截图工具一样
            if (event.button === 2) {
                cancelSelection();
            }
            return;
        }
        start = point(event);
        try {
            overlay.setPointerCapture(event.pointerId);
        } catch (e) {
            // 拿不到捕获只是拖出窗口后收不到移动，不影响框选
        }
        drawBox(start, start);
    });
    overlay.addEventListener("pointermove", (event) => {
        if (start) {
            drawBox(start, point(event));
        }
    });
    overlay.addEventListener("pointerup", async (event) => {
        if (!start) {
            return;
        }
        const selection = {start: start, end: point(event)};
        start = null;
        const tooSmall = Math.abs(selection.end.x - selection.start.x) < Ocr.MIN_SELECTION_PX ||
            Math.abs(selection.end.y - selection.start.y) < Ocr.MIN_SELECTION_PX;
        if (tooSmall) {
            box.hidden = true;
            toast("框得太小了，拖出一块包住文字的区域", "warn");
            return;
        }
        endSelection();
        const canvas = await capture(selection);
        if (!canvas) {
            toast("框选的区域不在画面上，或者截不到画面", "warn");
            return;
        }
        recognize(canvas);
    });
    overlay.addEventListener("pointercancel", () => {
        start = null;
        box.hidden = true;
    });
    // 鼠标抬起、滚轮监听都挂在 document 上，框选时不能冒泡过去发给被控端
    ["mousedown", "mouseup", "click", "contextmenu"].forEach((type) => {
        overlay.addEventListener(type, (event) => {
            event.stopPropagation();
            if (type === "contextmenu") {
                event.preventDefault();
            }
        });
    });
    overlay.addEventListener("wheel", (event) => event.stopPropagation(), {passive: true});
    // 键盘转发挂在 document 上；在 window 的捕获阶段先截住，框选期间按键一律不发
    ["keydown", "keyup"].forEach((type) => {
        window.addEventListener(type, (event) => {
            if (!selecting) {
                return;
            }
            event.preventDefault();
            event.stopPropagation();
            if (type === "keydown" && event.key === "Escape") {
                cancelSelection();
            }
        }, true);
    });

    button.addEventListener("click", () => {
        if (selecting) {
            cancelSelection();
        } else {
            startSelection();
        }
    });
    againButton.addEventListener("click", startSelection);
    wholeButton.addEventListener("click", recognizeWhole);
    copyButton.addEventListener("click", copyResult);
    closeButton.addEventListener("click", () => setPanelOpen(false));

    window.addEventListener("storage", (event) => {
        if (event.key === Ocr.STORAGE_KEY) {
            langs = Ocr.loadLangs(storage);
            if (!panel.hidden) {
                renderLangs();
            }
        }
    });
})();
