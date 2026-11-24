// 运行：node --test "tests/web/*.test.js"
const test = require("node:test");
const assert = require("node:assert");
const path = require("node:path");
const StatusText = require(path.join(__dirname, "../../web/status-text.js"));

test("截图和录像文件名都用本地时间", () => {
    const at = new Date(2026, 10, 4, 9, 5, 7);
    assert.strictEqual(StatusText.screenshotFileName(at), "screenshot-20261104-090507.png");
    assert.strictEqual(StatusText.recordFileName(at), "20261104-090507.webm");
});

test("被控端没枚举出键盘时不显示指示灯", () => {
    assert.strictEqual(StatusText.lockLights(null), null);
    assert.strictEqual(StatusText.lockLights({usbConnected: false, capsLock: true}), null);
});

test("指示灯按 Num、Caps、Scroll 的顺序报状态", () => {
    const lights = StatusText.lockLights({usbConnected: true, numLock: true, capsLock: false, scrollLock: true});
    assert.deepStrictEqual(lights.map((light) => light.key), ["NumLock", "CapsLock", "ScrollLock"]);
    assert.deepStrictEqual(lights.map((light) => light.on), [true, false, true]);
    assert.strictEqual(lights[0].label, "Num Lock 开，点击切换");
    assert.strictEqual(lights[1].label, "Caps Lock 关，点击切换");
});
