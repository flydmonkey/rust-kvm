// 画面显示方式：适应窗口、拉伸、1:1 原始像素、放大，照搬 chrome-kvm。只算画布该多大、
// 摆在哪，不碰 DOM。
//
// 和 chrome-kvm 的区别：那边是 <video> 配 object-fit，这边是画布，object-fit 靠不住，
// 所以每种方式都直接算出画布的框，让框正好等于画面内容。ch9329.js 按画布的布局框换算
// 绝对坐标，框摆对了点击就对得上，拉伸也一样。
var ViewModes = (function () {
    var STORAGE_KEY = "rust-kvm-view";
    var DEFAULT_MODE = "fit";

    var MODES = [
        {id: "fit", label: "适应窗口", hint: "等比缩放，完整显示画面"},
        {id: "fill", label: "拉伸铺满", hint: "铺满窗口，画面比例可能变形"},
        {id: "native", label: "原始像素 1:1", hint: "一个画面像素对一个屏幕像素，小字最清楚"},
        {id: "zoom150", label: "放大 1.5×", hint: "画面跟着鼠标移动", zoom: 1.5},
        {id: "zoom200", label: "放大 2×", hint: "画面跟着鼠标移动", zoom: 2},
        {id: "zoom300", label: "放大 3×", hint: "画面跟着鼠标移动", zoom: 3}
    ];

    function find(id) {
        for (var i = 0; i < MODES.length; i++) {
            if (MODES[i].id === id) {
                return MODES[i];
            }
        }
        return null;
    }

    function normalizeMode(id) {
        return find(id) ? id : DEFAULT_MODE;
    }

    function label(id) {
        return find(normalizeMode(id)).label;
    }

    // viewport / video 都是 {width, height}，dpr 是 devicePixelRatio。返回画布的 CSS 尺寸
    function layout(id, viewport, video, dpr) {
        var mode = find(normalizeMode(id));
        if (mode.id === "fill") {
            return {width: viewport.width, height: viewport.height};
        }
        var known = video && video.width > 0 && video.height > 0;
        // 还不知道画面尺寸时没法等比，先铺满，拿到尺寸会马上重摆
        if (!known) {
            return {width: viewport.width, height: viewport.height};
        }
        if (mode.id === "native") {
            var ratio = dpr > 0 ? dpr : 1;
            return {width: video.width / ratio, height: video.height / ratio};
        }
        var scale = Math.min(viewport.width / video.width, viewport.height / video.height);
        if (!mode.zoom) {
            // 浮点误差会让贴边的那一维比窗口多出一丝，变成「超出窗口、要跟着指针走」
            return {
                width: Math.min(viewport.width, video.width * scale),
                height: Math.min(viewport.height, video.height * scale)
            };
        }
        return {width: video.width * scale * mode.zoom, height: video.height * scale * mode.zoom};
    }

    // 比窗口小的方向居中；比窗口大的方向跟着指针走：指针在窗口左边缘时看到画面
    // 最左边，右边缘时看到最右边。这样指针下面正好是它在画面里对应的那一点，
    // 本机光标和被控端光标始终重合。没有指针位置时居中
    function offsetAxis(size, view, pointer) {
        if (size <= view) {
            return (view - size) / 2;
        }
        var ratio = pointer == null ? 0.5 : Math.min(1, Math.max(0, pointer / view));
        return -ratio * (size - view);
    }

    function panOffset(box, viewport, pointer) {
        return {
            left: offsetAxis(box.width, viewport.width, pointer ? pointer.x : null),
            top: offsetAxis(box.height, viewport.height, pointer ? pointer.y : null)
        };
    }

    // 画面有哪个方向超出窗口，指针移动时才需要重新摆
    function pans(box, viewport) {
        return box.width > viewport.width || box.height > viewport.height;
    }

    function load(storage) {
        try {
            return normalizeMode(storage && storage.getItem(STORAGE_KEY));
        } catch (e) {
            return DEFAULT_MODE;
        }
    }

    function save(storage, id) {
        var mode = normalizeMode(id);
        storage.setItem(STORAGE_KEY, mode);
        return mode;
    }

    return {
        STORAGE_KEY: STORAGE_KEY,
        DEFAULT_MODE: DEFAULT_MODE,
        MODES: MODES,
        normalizeMode: normalizeMode,
        label: label,
        layout: layout,
        panOffset: panOffset,
        pans: pans,
        load: load,
        save: save
    };
})();

if (typeof module !== "undefined" && module.exports) {
    module.exports = ViewModes;
}
