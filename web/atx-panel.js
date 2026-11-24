// ATX 电源面板。按压由服务端完成（/api/atx/press 按完才返回），所以这边关掉页面、
// 断了网都不会让电源键一直按着。配置里没有继电器时连工具栏按钮都不显示。
(function () {
    const button = document.getElementById("atx");
    const panel = document.getElementById("atx-panel");
    const info = document.getElementById("atx-info");
    const releaseButton = document.getElementById("atx-release");
    const pressButtons = {
        short: document.getElementById("atx-short"),
        long: document.getElementById("atx-long"),
        reset: document.getElementById("atx-reset")
    };

    const toast = (text, level) => window.KvmToast && KvmToast(text, level);

    let atx = null;
    let armed = null;
    let armTimer = null;
    // 正在按的种类，null 表示空闲
    let pressing = null;

    async function refresh() {
        try {
            const config = await fetch("/api/config", {cache: "no-store"}).then((r) => r.json());
            atx = Atx.normalize(config.atx);
        } catch (e) {
            // 读不到就保持原样：按钮突然消失比多显示一会儿更让人困惑
        }
        render();
    }

    function render() {
        button.hidden = !atx;
        if (!atx) {
            if (!panel.hidden) {
                setOpen(false);
            }
            return;
        }
        info.textContent = `继电器 ${atx.port}，电源键第 ${atx.channel} 路` +
            (atx.resetChannel ? `，复位键第 ${atx.resetChannel} 路` : "，没接复位键");
        pressButtons.reset.hidden = !atx.resetChannel;
        Object.keys(pressButtons).forEach((kind) => {
            const press = Atx.PRESSES[kind];
            const isArmed = !!armed && armed.kind === kind;
            pressButtons[kind].textContent = isArmed ? press.confirm : press.label;
            pressButtons[kind].classList.toggle("armed", isArmed);
            pressButtons[kind].disabled = !!pressing;
        });
        releaseButton.hidden = pressing !== "long";
    }

    function disarm() {
        clearTimeout(armTimer);
        armTimer = null;
        armed = null;
    }

    function onPress(kind) {
        if (pressing) {
            return;
        }
        const result = Atx.click(armed, kind, Date.now());
        disarm();
        armed = result.armed;
        if (armed) {
            armTimer = setTimeout(() => {
                disarm();
                render();
            }, Atx.CONFIRM_MS);
        }
        render();
        if (result.run) {
            press(kind);
        }
    }

    async function press(kind) {
        const label = Atx.PRESSES[kind].label;
        pressing = kind;
        render();
        toast(kind === "long" ? "正在长按电源键（6 秒），面板里可以点「松开」提前结束" : `正在${label}…`, "warn");
        try {
            const response = await fetch("/api/atx/press", {
                method: "POST",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({kind})
            });
            if (!response.ok) {
                throw new Error(await response.text());
            }
            const result = await response.json();
            toast(result.releasedEarly ? "已提前松开电源键" : Atx.PRESSES[kind].done, "ok");
        } catch (e) {
            toast(`${label}失败：${e.message || e}`, "error");
        } finally {
            pressing = null;
            render();
        }
    }

    async function release() {
        try {
            await fetch("/api/atx/release", {method: "POST"});
        } catch (e) {
            // 松不掉也不要紧：服务端到 6 秒自己会松
            toast(`松开失败：${e.message || e}，6 秒到了会自动松开`, "error");
        }
    }

    function setOpen(open) {
        panel.hidden = !open;
        button.classList.toggle("active", open);
        if (open) {
            KvmClosePanels(panel);
            refresh();
        } else {
            disarm();
        }
    }

    Object.keys(pressButtons).forEach((kind) => {
        pressButtons[kind].addEventListener("click", () => onPress(kind));
    });
    releaseButton.addEventListener("click", release);
    button.addEventListener("click", () => setOpen(panel.hidden));
    document.getElementById("atx-close").addEventListener("click", () => setOpen(false));
    // 切换被控机会换掉继电器，没设继电器的那台要把按钮藏起来
    window.addEventListener("kvm-config-changed", refresh);
    refresh();
})();
