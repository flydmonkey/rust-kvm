// 被控机配置，照搬 chrome-kvm：一台 KVM 接多台机器（或配 HDMI/USB 切换器）时，
// 每台机器的采集卡、串口、分辨率、鼠标模式往往不一样，存成几份配置一键切换。
//
// 只存「跟着被控机走」的字段。编码、码率、窄带音频取决于现在走的是哪条链路，
// 波特率记的是 CH9329 芯片自己的状态，都和切到哪台机器无关，切换时保持当前值不动。
//
// 配置存在这台电脑的浏览器里，不在服务端：服务端只有一份「当前配置」，切换就是
// 把这里存的字段合并进去再写回 /api/config。
var Profiles = (function () {
    var STORAGE_KEY = "rust-kvm-profiles";
    var MAX_PROFILES = 20;
    var MAX_NAME_LENGTH = 20;
    var FIELDS = [
        "video.device",
        "video.width",
        "video.height",
        "video.fps",
        "audio.device",
        "serial.port",
        "serial.mouseMode"
    ];

    function empty() {
        return {active: null, profiles: []};
    }

    function normalizeName(name) {
        var text = String(name == null ? "" : name).trim();
        return Array.from(text).slice(0, MAX_NAME_LENGTH).join("");
    }

    function read(object, path) {
        var parts = path.split(".");
        var value = object;
        for (var i = 0; i < parts.length; i++) {
            if (!value || typeof value !== "object") {
                return undefined;
            }
            value = value[parts[i]];
        }
        return value;
    }

    function write(object, path, value) {
        var parts = path.split(".");
        var target = object;
        for (var i = 0; i < parts.length - 1; i++) {
            if (!target[parts[i]] || typeof target[parts[i]] !== "object") {
                target[parts[i]] = {};
            }
            target = target[parts[i]];
        }
        target[parts[parts.length - 1]] = value;
    }

    // 只挑 FIELDS 里的值，数组和对象一律不收：这几项本来都是字符串或数字，
    // 收进别的东西写回服务端只会被 serde 拒掉
    function snapshot(settings) {
        var result = {};
        if (!settings || typeof settings !== "object") {
            return result;
        }
        FIELDS.forEach(function (path) {
            var value = read(settings, path);
            if (typeof value === "string" || typeof value === "number") {
                write(result, path, value);
            }
        });
        return result;
    }

    function indexOf(state, name) {
        for (var i = 0; i < state.profiles.length; i++) {
            if (state.profiles[i].name === name) {
                return i;
            }
        }
        return -1;
    }

    // 存储里的东西可能是手改坏的：丢掉坏条目和重名，active 指向不存在的配置时清空
    function normalize(raw) {
        var state = empty();
        if (!raw || typeof raw !== "object" || !Array.isArray(raw.profiles)) {
            return state;
        }
        for (var i = 0; i < raw.profiles.length && state.profiles.length < MAX_PROFILES; i++) {
            var item = raw.profiles[i];
            if (!item || typeof item !== "object") {
                continue;
            }
            var name = normalizeName(item.name);
            if (!name || indexOf(state, name) !== -1) {
                continue;
            }
            state.profiles.push({name: name, settings: snapshot(item.settings)});
        }
        var active = normalizeName(raw.active);
        state.active = active && indexOf(state, active) !== -1 ? active : null;
        return state;
    }

    function load(storage) {
        try {
            var raw = storage && storage.getItem(STORAGE_KEY);
            return raw ? normalize(JSON.parse(raw)) : empty();
        } catch (e) {
            return empty();
        }
    }

    function save(storage, state) {
        var normalized = normalize(state);
        storage.setItem(STORAGE_KEY, JSON.stringify(normalized));
        return normalized;
    }

    // 同名覆盖，新名字追加；存完这份就是当前配置
    function saveAs(state, name, settings) {
        var clean = normalizeName(name);
        if (!clean) {
            return {error: "请给配置起个名字"};
        }
        var next = normalize(state);
        var at = indexOf(next, clean);
        if (at === -1) {
            if (next.profiles.length >= MAX_PROFILES) {
                return {error: "最多存 " + MAX_PROFILES + " 份配置，先删掉一份不用的"};
            }
            next.profiles.push({name: clean, settings: snapshot(settings)});
        } else {
            next.profiles[at].settings = snapshot(settings);
        }
        next.active = clean;
        return {state: next};
    }

    function remove(state, name) {
        var next = normalize(state);
        var at = indexOf(next, name);
        if (at !== -1) {
            next.profiles.splice(at, 1);
        }
        if (next.active === name) {
            next.active = null;
        }
        return next;
    }

    // 切到某份配置：返回合并后的完整配置。配置里没有的字段沿用当前值。
    // 返回的必须是完整配置——服务端所有字段都是 serde(default)，缺一项就被重置
    function apply(state, name, current) {
        var next = normalize(state);
        var at = indexOf(next, name);
        if (at === -1) {
            return {error: "找不到配置「" + name + "」"};
        }
        var saved = next.profiles[at].settings;
        var merged = JSON.parse(JSON.stringify(current || {}));
        FIELDS.forEach(function (path) {
            var value = read(saved, path);
            if (value !== undefined) {
                write(merged, path, value);
            }
        });
        next.active = name;
        return {state: next, settings: merged};
    }

    // 在设置页点「保存」时，把改动同步进当前配置，免得切走再切回来又变回老样子
    function updateActive(state, settings) {
        var next = normalize(state);
        var at = next.active ? indexOf(next, next.active) : -1;
        if (at !== -1) {
            next.profiles[at].settings = snapshot(settings);
        }
        return next;
    }

    // 切换面板里每份配置下面那行小字，让人认得出是哪台
    function summary(settings) {
        var parts = [];
        var width = read(settings, "video.width");
        var height = read(settings, "video.height");
        if (width && height) {
            parts.push(width + "×" + height);
        }
        parts.push(read(settings, "serial.mouseMode") === "relative" ? "相对" : "绝对");
        var port = read(settings, "serial.port");
        if (port) {
            parts.push(port);
        }
        var device = read(settings, "video.device");
        if (device) {
            parts.push(device);
        }
        return parts.join(" · ");
    }

    return {
        STORAGE_KEY: STORAGE_KEY,
        MAX_PROFILES: MAX_PROFILES,
        MAX_NAME_LENGTH: MAX_NAME_LENGTH,
        FIELDS: FIELDS,
        normalizeName: normalizeName,
        snapshot: snapshot,
        normalize: normalize,
        load: load,
        save: save,
        saveAs: saveAs,
        remove: remove,
        apply: apply,
        updateActive: updateActive,
        summary: summary
    };
})();

if (typeof module !== "undefined" && module.exports) {
    module.exports = Profiles;
}
