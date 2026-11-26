// 设置页给分辨率、帧率下拉框加的那句说明。不算设备、不碰 DOM。
//
// 分辨率看能力表：表里有的不标，没有的标出会退回哪一档。这边没有浏览器那种
// 「从更大的画面缩出来」，服务端按最接近的一档协商，所以只有退回这一种。
//
// 帧率不能看能力表。不少卡每一档都写着 60，1080p 实际只有 30，只能拿实测数。
// 实测不到请求的八成就标出来，数帧总有一两帧误差。
var CaptureModes = (function () {
    var STORAGE_KEY = "rust-kvm-capture-modes";
    var ENOUGH = 0.8;

    function parseSize(value) {
        var match = /^(\d+)x(\d+)$/.exec(String(value || ""));
        if (!match) {
            return null;
        }
        var size = {width: parseInt(match[1], 10), height: parseInt(match[2], 10)};
        return size.width > 0 && size.height > 0 ? size : null;
    }

    // Chrome 挑档位用的适合度：宽、高各自的相对差加起来，越小越接近
    function distance(want, have) {
        return Math.abs(have.width - want.width) / Math.max(have.width, want.width)
            + Math.abs(have.height - want.height) / Math.max(have.height, want.height);
    }

    // 没检测过、或这一档卡自己就有，返回 null。否则是退回的那一档。
    function fallback(value, modes) {
        var want = parseSize(value);
        if (!want || !modes || !modes.length) {
            return null;
        }
        if (modes.indexOf(value) !== -1) {
            return null;
        }
        var best = null;
        modes.forEach(function (mode) {
            var size = parseSize(mode);
            if (!size) {
                return;
            }
            if (!best || distance(want, size) < distance(want, best)) {
                best = size;
            }
        });
        return best ? best.width + "x" + best.height : null;
    }

    // 能力表里的档位去重、从大到小，给下拉框用
    function resolutionOptions(modes) {
        var seen = {};
        var sizes = [];
        (modes || []).forEach(function (mode) {
            var size = parseSize(mode);
            var key = size && size.width + "x" + size.height;
            if (key && !seen[key]) {
                seen[key] = true;
                sizes.push(size);
            }
        });
        sizes.sort(function (a, b) {
            return b.width * b.height - a.width * a.height || b.width - a.width;
        });
        return sizes.map(function (size) {
            return size.width + "x" + size.height;
        });
    }

    function resolutionNote(value, modes) {
        var size = fallback(value, modes);
        return size ? "不支持，实际为 " + size.replace("x", "×") : "";
    }

    function measuredKey(resolution, frameRate) {
        return resolution + "@" + frameRate;
    }

    // 够数、还没测过、或者测的不是这个档，返回空串
    function frameRateNote(resolution, frameRate, measured) {
        var value = measured && measured[measuredKey(resolution, frameRate)];
        if (typeof value !== "number" || !(frameRate > 0) || value >= frameRate * ENOUGH) {
            return "";
        }
        return "这个分辨率下实测只有 " + value + " fps";
    }

    function readAll(storage) {
        try {
            var raw = storage && storage.getItem(STORAGE_KEY);
            var all = raw ? JSON.parse(raw) : {};
            return all && typeof all === "object" ? all : {};
        } catch (e) {
            return {};
        }
    }

    function load(storage, device) {
        var entry = readAll(storage)[device];
        if (!entry || !Array.isArray(entry.modes)) {
            return null;
        }
        return {
            modes: entry.modes,
            measured: entry.measured && typeof entry.measured === "object" ? entry.measured : {}
        };
    }

    function save(storage, device, result) {
        var all = readAll(storage);
        all[device] = {
            modes: result.modes || [],
            measured: result.measured || {}
        };
        storage.setItem(STORAGE_KEY, JSON.stringify(all));
    }

    return {
        STORAGE_KEY: STORAGE_KEY,
        parseSize: parseSize,
        fallback: fallback,
        resolutionOptions: resolutionOptions,
        resolutionNote: resolutionNote,
        measuredKey: measuredKey,
        frameRateNote: frameRateNote,
        load: load,
        save: save
    };
})();

if (typeof module !== "undefined" && module.exports) {
    module.exports = CaptureModes;
}
