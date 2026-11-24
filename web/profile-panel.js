// 切换被控机：在设置页存好的几份配置之间切换。只有存了两份以上才显示工具栏按钮——
// 一份没有可切的，那时它只会占地方。新增、修改、删除都在设置页。
(function () {
    const button = document.getElementById("profiles");
    const panel = document.getElementById("profile-panel");
    const list = document.getElementById("profile-list");

    const toast = (text, level) => window.KvmToast && KvmToast(text, level);
    const storage = (() => {
        try {
            return window.localStorage;
        } catch (e) {
            return null;
        }
    })();

    let switching = false;

    function load() {
        return Profiles.load(storage);
    }

    function render() {
        const state = load();
        button.hidden = state.profiles.length < 2;
        if (button.hidden && !panel.hidden) {
            setOpen(false);
        }
        list.textContent = "";
        state.profiles.forEach((profile) => {
            const item = document.createElement("button");
            item.type = "button";
            item.className = "profile-item";
            item.disabled = switching;
            const current = profile.name === state.active;
            item.classList.toggle("current", current);
            const name = document.createElement("span");
            name.textContent = current ? `${profile.name}（当前）` : profile.name;
            const summary = document.createElement("span");
            summary.className = "profile-summary";
            summary.textContent = Profiles.summary(profile.settings);
            item.title = summary.textContent;
            item.append(name, summary);
            item.addEventListener("click", () => switchTo(profile.name));
            list.appendChild(item);
        });
    }

    async function switchTo(name) {
        if (switching) {
            return;
        }
        if (window.KvmInput && KvmInput.isKeyboardBusy()) {
            toast("正在粘贴或连按，等它结束后再切换", "warn");
            return;
        }
        switching = true;
        render();
        toast(`正在切换到「${name}」…`, "warn");
        try {
            const current = await fetch("/api/config", {cache: "no-store"}).then((r) => r.json());
            const result = Profiles.apply(load(), name, current);
            if (result.error) {
                throw new Error(result.error);
            }
            const response = await fetch("/api/config", {
                method: "PUT",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify(result.settings)
            });
            if (!response.ok) {
                throw new Error(await response.text());
            }
            Profiles.save(storage, result.state);
            setOpen(false);
            // 画面由服务端在配置变了之后自己重开，这边只需要把键鼠换到新串口
            const connected = window.KvmInput ? await KvmInput.reconnect() : false;
            toast(
                connected
                    ? `已切换到「${name}」`
                    : `已切换到「${name}」，键鼠没连上，点工具栏上的键鼠图标重试`,
                connected ? "ok" : "warn"
            );
        } catch (e) {
            toast(`切换失败：${e.message || e}`, "error");
        } finally {
            switching = false;
            render();
        }
    }

    function setOpen(open) {
        panel.hidden = !open;
        button.classList.toggle("active", open);
        if (open) {
            render();
            KvmClosePanels(panel);
        }
    }

    button.addEventListener("click", () => setOpen(panel.hidden));
    document.getElementById("profile-close").addEventListener("click", () => setOpen(false));
    // 设置页（另一个标签页）存了或删了配置，按钮和列表跟着变
    window.addEventListener("storage", (event) => {
        if (event.key === Profiles.STORAGE_KEY || event.key === null) {
            render();
        }
    });
    render();
})();
