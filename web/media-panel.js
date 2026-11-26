// 虚拟介质面板：本机 USB 口上的光驱和 U 盘。只有服务端报 available 时才出现
// （ARM 板、有 USB 设备控制器、gadget 建好了）。
(function () {
    const button = document.getElementById("media");
    const panel = document.getElementById("media-panel");
    const list = document.getElementById("media-images");
    const fileInput = document.getElementById("media-file");
    const uploadButton = document.getElementById("media-upload");
    const progress = document.getElementById("media-progress");
    const slots = {
        cdrom: {
            select: document.getElementById("media-cdrom-image"),
            mount: document.getElementById("media-cdrom-mount"),
            eject: document.getElementById("media-cdrom-eject"),
            state: document.getElementById("media-cdrom-state")
        },
        disk: {
            select: document.getElementById("media-disk-image"),
            mount: document.getElementById("media-disk-mount"),
            eject: document.getElementById("media-disk-eject"),
            state: document.getElementById("media-disk-state"),
            readOnly: document.getElementById("media-disk-ro")
        }
    };

    const toast = (text, level) => window.KvmToast && KvmToast(text, level);

    let overview = null;
    let busy = false;
    let uploading = false;

    async function refresh() {
        try {
            const response = await fetch("/api/usb", {cache: "no-store"});
            // 旧版服务没有这个接口，当成不支持
            overview = response.ok ? await response.json() : null;
        } catch (e) {
            overview = null;
        }
        render();
    }

    function render() {
        button.hidden = !UsbMedia.showButton(overview);
        if (button.hidden) {
            if (!panel.hidden) {
                setOpen(false);
            }
            return;
        }
        Object.keys(slots).forEach((name) => {
            const slot = slots[name];
            const state = overview.slots[name];
            const keep = slot.select.value;
            slot.select.replaceChildren(...UsbMedia.choices(overview.images, name)
                .map((image) => new Option(image, image)));
            if (keep && [...slot.select.options].some((option) => option.value === keep)) {
                slot.select.value = keep;
            }
            slot.state.textContent = UsbMedia.slotText(name, state);
            slot.mount.disabled = busy || !slot.select.value;
            slot.eject.disabled = busy || !state.image;
        });
        if (overview.images.length) {
            list.replaceChildren(...overview.images.map(imageRow));
        } else {
            list.textContent = "镜像目录是空的。点「上传」，或者把 .iso / .img 拷进服务端的 images/ 目录";
        }
        uploadButton.disabled = busy;
    }

    function imageRow(image) {
        const row = document.createElement("div");
        row.className = "media-image";
        const name = document.createElement("span");
        name.textContent = image.name;
        name.title = image.name;
        const size = document.createElement("span");
        size.className = "media-size";
        size.textContent = UsbMedia.formatSize(image.size);
        const remove = document.createElement("button");
        remove.type = "button";
        remove.textContent = "删除";
        remove.disabled = busy;
        remove.addEventListener("click", () => removeImage(image.name));
        row.append(name, size, remove);
        return row;
    }

    async function call(label, method, url, body, done) {
        busy = true;
        render();
        try {
            const response = await fetch(url, {
                method: method,
                headers: body ? {"Content-Type": "application/json"} : {},
                body: body ? JSON.stringify(body) : undefined
            });
            if (!response.ok) {
                throw new Error(await response.text());
            }
            overview = await response.json();
            toast(done, "ok");
        } catch (e) {
            toast(`${label}失败：${e.message || e}`, "error");
        } finally {
            busy = false;
            render();
        }
    }

    function mount(name) {
        const image = slots[name].select.value;
        const label = UsbMedia.SLOT_LABELS[name];
        const readOnly = name === "disk" && slots.disk.readOnly.checked;
        call(`挂${label}`, "POST", `/api/usb/${name}/mount`, {image, readOnly}, `已把 ${image} 挂到${label}`);
    }

    function eject(name) {
        const label = UsbMedia.SLOT_LABELS[name];
        call(`弹出${label}`, "POST", `/api/usb/${name}/eject`, {}, `已弹出${label}`);
    }

    function removeImage(name) {
        if (!confirm(`删除镜像 ${name}？`)) {
            return;
        }
        call("删除镜像", "DELETE", `/api/usb/images/${encodeURIComponent(name)}`, null, `已删除 ${name}`);
    }

    // 用 XHR 而不是 fetch：fetch 拿不到上传进度，几个 GB 的 ISO 没有进度条会以为卡死了
    function upload(file) {
        if (!UsbMedia.acceptsUpload(file.name)) {
            toast("只支持 .iso 和 .img", "error");
            fileInput.value = "";
            return;
        }
        busy = true;
        uploading = true;
        progress.value = 0;
        progress.hidden = false;
        render();
        const xhr = new XMLHttpRequest();
        xhr.open("PUT", `/api/usb/images/${encodeURIComponent(file.name)}`);
        xhr.upload.onprogress = (event) => {
            if (event.lengthComputable) {
                progress.value = event.loaded / event.total;
            }
        };
        xhr.onload = () => {
            try {
                if (xhr.status >= 200 && xhr.status < 300) {
                    overview = JSON.parse(xhr.responseText);
                    toast(`已上传 ${file.name}`, "ok");
                } else {
                    toast(`上传失败：${xhr.responseText || xhr.status}`, "error");
                }
            } finally {
                finishUpload();
            }
        };
        xhr.onerror = () => {
            toast("上传失败：连接中断", "error");
            finishUpload();
        };
        xhr.send(file);
    }

    function finishUpload() {
        busy = false;
        uploading = false;
        progress.hidden = true;
        fileInput.value = "";
        render();
    }

    function setOpen(open) {
        panel.hidden = !open;
        button.classList.toggle("active", open);
        if (open) {
            KvmClosePanels(panel);
            refresh();
        }
    }

    Object.keys(slots).forEach((name) => {
        slots[name].mount.addEventListener("click", () => mount(name));
        slots[name].eject.addEventListener("click", () => eject(name));
        slots[name].select.addEventListener("change", render);
    });
    uploadButton.addEventListener("click", () => fileInput.click());
    fileInput.addEventListener("change", () => {
        if (fileInput.files.length) {
            upload(fileInput.files[0]);
        }
    });
    button.addEventListener("click", () => setOpen(panel.hidden));
    document.getElementById("media-close").addEventListener("click", () => setOpen(false));
    // 关页面会把上传掐断，服务端只会删掉那个 .part
    window.addEventListener("beforeunload", (event) => {
        if (uploading) {
            event.preventDefault();
        }
    });
    refresh();
})();
