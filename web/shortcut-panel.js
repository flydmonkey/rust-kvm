// 快捷键面板：预设五组 + 自定义（存 localStorage）+ 开机连按。逻辑照搬 chrome-kvm
// 的 kvm-tools.js，解析和预设在 shortcuts.js，真正发包走 KvmInput.tapCombo。
(function () {
    const button = document.getElementById("shortcuts");
    const panel = document.getElementById("shortcut-panel");
    const closeButton = document.getElementById("shortcut-close");
    const groupsEl = document.getElementById("shortcut-groups");
    const repeatToggle = document.getElementById("shortcut-repeat");
    const repeatHint = document.getElementById("shortcut-repeat-hint");
    const form = document.getElementById("shortcut-form");
    const labelInput = document.getElementById("shortcut-label");
    const comboInput = document.getElementById("shortcut-combo");
    const formError = document.getElementById("shortcut-error");

    const toast = (text, level) => window.KvmToast && KvmToast(text, level);
    const storage = (() => {
        try {
            return window.localStorage;
        } catch (e) {
            return null;
        }
    })();

    // 当前的连按任务，null 表示没在连按
    let repeating = null;
    // 多步序列正在发的时候不接新的，免得两串按键交错
    let running = false;

    repeatHint.textContent =
        `打开后点按键会每 ${Shortcuts.REPEAT_INTERVAL_MS / 1000} 秒按一次，` +
        `最长 ${Shortcuts.REPEAT_MAX_MS / 1000} 秒。先打开连按再给被控端开机，就不会错过 BIOS 热键`;

    function sleep(ms) {
        return new Promise((resolve) => setTimeout(resolve, ms));
    }

    function ready() {
        if (window.KvmInput && KvmInput.isReady()) {
            return true;
        }
        toast("键鼠还没连上。先点上面那个键盘图标连接", "error");
        return false;
    }

    async function runShortcut(combo) {
        if (repeating) {
            // 连按时点任何快捷键都是想停下来，不管点的是哪个
            stopRepeat("stopped");
            return;
        }
        if (running || !ready()) {
            return;
        }
        if (KvmInput.isKeyboardBusy()) {
            toast("正在粘贴，等粘贴结束或取消后再按快捷键", "warn");
            return;
        }
        const parsed = Shortcuts.parseSequence(combo);
        if (parsed.error) {
            toast(`快捷键无效：${parsed.error}`, "error");
            return;
        }
        if (repeatToggle.checked) {
            startRepeat(parsed);
            return;
        }
        const session = KvmInput.session();
        running = true;
        try {
            for (let i = 0; i < parsed.steps.length; i++) {
                if (i > 0) {
                    await sleep(Shortcuts.STEP_GAP_MS);
                }
                if (!await KvmInput.tapCombo(parsed.steps[i], session)) {
                    toast(`快捷键没有发完：${parsed.text}`, "error");
                    return;
                }
            }
            toast(`已发送 ${parsed.text}`, "ok");
        } catch (e) {
            toast(`发送失败：${e.message}`, "error");
        } finally {
            running = false;
        }
    }

    function startRepeat(parsed) {
        const task = {
            text: parsed.text,
            steps: parsed.steps,
            session: KvmInput.session(),
            count: 0,
            started: Date.now()
        };
        repeating = task;
        KvmInput.holdKeyboard(true);
        renderRepeating();
        loop(task);
    }

    async function loop(task) {
        while (repeating === task) {
            if (Date.now() - task.started >= Shortcuts.REPEAT_MAX_MS) {
                stopRepeat("timeout");
                return;
            }
            for (let i = 0; i < task.steps.length && repeating === task; i++) {
                if (i > 0) {
                    await sleep(Shortcuts.STEP_GAP_MS);
                }
                let ok;
                try {
                    ok = await KvmInput.tapCombo(task.steps[i], task.session);
                } catch (e) {
                    ok = false;
                }
                if (!ok) {
                    if (repeating === task) {
                        stopRepeat("failed");
                    }
                    return;
                }
            }
            if (repeating !== task) {
                return;
            }
            task.count += 1;
            toast(`正在连按 ${task.text}（已按 ${task.count} 次），被控端开机自检时会认到`, "warn");
            await sleep(Shortcuts.REPEAT_INTERVAL_MS);
        }
    }

    function stopRepeat(reason) {
        const task = repeating;
        if (!task) {
            return;
        }
        repeating = null;
        KvmInput.holdKeyboard(false);
        renderRepeating();
        if (reason === "timeout") {
            toast(`连按已到 ${Shortcuts.REPEAT_MAX_MS / 1000} 秒上限，自动停止，共按了 ${task.count} 次 ${task.text}`, "warn");
        } else if (reason === "failed") {
            toast(`快捷键没有发完：${task.text}`, "error");
        } else {
            toast(`已停止连按，共按了 ${task.count} 次 ${task.text}`, "ok");
        }
    }

    function renderRepeating() {
        panel.classList.toggle("repeating", !!repeating);
    }

    function shortcutButton(item, onDelete) {
        const wrap = document.createElement("div");
        wrap.className = "shortcut-item";

        const run = document.createElement("button");
        run.type = "button";
        run.className = "shortcut-run";
        const label = document.createElement("span");
        label.className = "shortcut-label";
        label.textContent = item.label;
        const combo = document.createElement("span");
        combo.className = "shortcut-combo";
        combo.textContent = item.combo;
        run.append(label, combo);
        run.title = `${item.label}：${item.combo}`;
        run.addEventListener("click", () => runShortcut(item.combo));
        wrap.appendChild(run);

        if (onDelete) {
            const del = document.createElement("button");
            del.type = "button";
            del.className = "shortcut-delete";
            del.textContent = "×";
            del.setAttribute("aria-label", `删除 ${item.label}`);
            del.title = `删除 ${item.label}`;
            del.addEventListener("click", onDelete);
            wrap.appendChild(del);
        }
        return wrap;
    }

    function group(title, items, onDelete) {
        const section = document.createElement("section");
        section.className = "shortcut-group";
        const heading = document.createElement("h3");
        heading.textContent = title;
        section.appendChild(heading);
        const grid = document.createElement("div");
        grid.className = "shortcut-grid";
        items.forEach((item, index) => {
            grid.appendChild(shortcutButton(item, onDelete && (() => onDelete(index))));
        });
        section.appendChild(grid);
        return section;
    }

    function loadCustom() {
        return Shortcuts.loadCustom(storage);
    }

    function removeCustom(index) {
        const list = loadCustom();
        list.splice(index, 1);
        try {
            Shortcuts.saveCustom(storage, list);
        } catch (e) {
            toast(`保存失败：${e.message}`, "error");
        }
        render();
    }

    function render() {
        groupsEl.textContent = "";
        Shortcuts.PRESETS.forEach((preset) => {
            groupsEl.appendChild(group(preset.title, preset.items));
        });
        const custom = loadCustom();
        if (custom.length) {
            groupsEl.appendChild(group("自定义", custom, removeCustom));
        }
    }

    form.addEventListener("submit", (event) => {
        event.preventDefault();
        formError.textContent = "";
        const list = loadCustom();
        if (list.length >= Shortcuts.MAX_CUSTOM) {
            formError.textContent = `最多保存 ${Shortcuts.MAX_CUSTOM} 个自定义快捷键`;
            return;
        }
        const custom = Shortcuts.makeCustom(labelInput.value, comboInput.value);
        if (custom.error) {
            formError.textContent = custom.error;
            return;
        }
        list.push(custom);
        try {
            Shortcuts.saveCustom(storage, list);
        } catch (e) {
            formError.textContent = `保存失败：${e.message}`;
            return;
        }
        labelInput.value = "";
        comboInput.value = "";
        render();
    });

    // 另一个标签页改了自定义列表，这边跟着刷新
    window.addEventListener("storage", (event) => {
        if (event.key === Shortcuts.STORAGE_KEY || event.key === null) {
            render();
        }
    });

    function setOpen(open) {
        panel.hidden = !open;
        button.classList.toggle("active", open);
        if (open) {
            render();
            const ocrPanel = document.getElementById("ocr-panel");
            if (ocrPanel) {
                ocrPanel.hidden = true;
                document.getElementById("ocr").classList.remove("active");
            }
        }
    }

    button.addEventListener("click", () => setOpen(panel.hidden));
    closeButton.addEventListener("click", () => setOpen(false));

    // 关掉连按开关等于叫停
    repeatToggle.addEventListener("change", () => {
        if (!repeatToggle.checked) {
            stopRepeat("stopped");
        }
    });
    document.getElementById("shortcut-stop").addEventListener("click", () => stopRepeat("stopped"));
})();
