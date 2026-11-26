// 运行：node --test "tests/web/*.test.js"
const test = require("node:test");
const assert = require("node:assert");
const path = require("node:path");
const CaptureModes = require(path.join(__dirname, "../../web/capture-modes.js"));

// MS2109 能力表里有的几档
const MODES = [
    "1920x1080",
    "1600x1200",
    "1280x720",
    "800x600",
    "640x480"
];

function memoryStorage() {
    const data = {};
    return {
        getItem: (key) => (key in data ? data[key] : null),
        setItem: (key, value) => {
            data[key] = String(value);
        }
    };
}

test("下拉框按能力表列档位，去重并从大到小", () => {
    assert.deepStrictEqual(
        CaptureModes.resolutionOptions(["640x480", "1920x1080", "1280x720", "1920x1080", "1600x1200", "bad"]),
        ["1920x1080", "1600x1200", "1280x720", "640x480"]
    );
    assert.deepStrictEqual(CaptureModes.resolutionOptions(null), []);
});

test("能力表里有的分辨率不标", () => {
    assert.strictEqual(CaptureModes.resolutionNote("1920x1080", MODES), "");
    assert.strictEqual(CaptureModes.resolutionNote("1280x720", MODES), "");
});

test("没有的分辨率标出最接近的一档", () => {
    assert.strictEqual(CaptureModes.resolutionNote("1600x900", MODES), "不支持，实际为 1600×1200");
    assert.strictEqual(CaptureModes.resolutionNote("960x540", MODES), "不支持，实际为 800×600");
    assert.strictEqual(CaptureModes.resolutionNote("640x360", MODES), "不支持，实际为 640×480");
});

test("还没检测、或值不像分辨率，什么都不标", () => {
    assert.strictEqual(CaptureModes.resolutionNote("1920x1080", null), "");
    assert.strictEqual(CaptureModes.resolutionNote("1920x1080", []), "");
    assert.strictEqual(CaptureModes.resolutionNote("nope", MODES), "");
});

test("实测明显不够才在帧率后面标", () => {
    const measured = {"1920x1080@60": 31, "1280x720@60": 59};
    assert.strictEqual(
        CaptureModes.frameRateNote("1920x1080", 60, measured),
        "这个分辨率下实测只有 31 fps"
    );
    assert.strictEqual(CaptureModes.frameRateNote("1280x720", 60, measured), "");
    assert.strictEqual(CaptureModes.frameRateNote("1920x1080", 30, measured), "");
});

test("检测结果按设备记住", () => {
    const storage = memoryStorage();
    CaptureModes.save(storage, "USB Video", {
        modes: ["1920x1080"],
        measured: {"1920x1080@60": 31}
    });
    assert.deepStrictEqual(CaptureModes.load(storage, "USB Video"), {
        modes: ["1920x1080"],
        measured: {"1920x1080@60": 31}
    });
    assert.strictEqual(CaptureModes.load(storage, "别的设备"), null);
    assert.strictEqual(CaptureModes.load(memoryStorage(), "USB Video"), null);
});
