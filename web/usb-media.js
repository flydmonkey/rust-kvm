// 本机 USB 口上的光驱和 U 盘：面板和设置页共用的纯函数。
var UsbMedia = (function () {
    var SLOT_LABELS = {cdrom: "光驱", disk: "U 盘"};
    var UNITS = ["B", "KB", "MB", "GB", "TB"];

    function formatSize(bytes) {
        var value = bytes;
        var unit = 0;
        while (value >= 1024 && unit < UNITS.length - 1) {
            value /= 1024;
            unit++;
        }
        return unit === 0 ? value + " B" : value.toFixed(1) + " " + UNITS[unit];
    }

    function isIso(name) {
        return /\.iso$/i.test(name);
    }

    function acceptsUpload(name) {
        return /\.(iso|img)$/i.test(name);
    }

    // 光驱只认 ISO：硬盘镜像当光盘挂上，被控机只会报「无法读取」
    function choices(images, slot) {
        return images
            .filter(function (image) {
                return slot !== "cdrom" || isIso(image.name);
            })
            .map(function (image) {
                return image.name;
            });
    }

    function slotText(slot, state) {
        if (!state || !state.image) {
            return "空";
        }
        return slot === "disk" && state.readOnly ? state.image + "（只读）" : state.image;
    }

    function showButton(overview) {
        return !!overview && overview.available === true;
    }

    function unavailableHint(overview) {
        if (!overview || overview.available || !overview.reason) {
            return "";
        }
        return "本机 USB 口直连不可用：" + overview.reason;
    }

    return {
        SLOT_LABELS: SLOT_LABELS,
        formatSize: formatSize,
        acceptsUpload: acceptsUpload,
        choices: choices,
        slotText: slotText,
        showButton: showButton,
        unavailableHint: unavailableHint
    };
})();

if (typeof module !== "undefined" && module.exports) {
    module.exports = UsbMedia;
}
