// 运行：node --test "tests/web/*.test.js"
const test = require("node:test");
const assert = require("node:assert");
const path = require("node:path");
const ViewModes = require(path.join(__dirname, "../../web/view-modes.js"));

const VIEW = {width: 1600, height: 900};
const VIDEO = {width: 1920, height: 1080};

function memoryStorage() {
    const data = {};
    return {
        getItem: (key) => (key in data ? data[key] : null),
        setItem: (key, value) => {
            data[key] = String(value);
        }
    };
}

test("不认识的方式按适应窗口", () => {
    assert.strictEqual(ViewModes.normalizeMode("nope"), "fit");
    assert.strictEqual(ViewModes.normalizeMode(null), "fit");
    assert.strictEqual(ViewModes.normalizeMode("zoom200"), "zoom200");
});

test("适应窗口等比缩放，框正好等于画面", () => {
    assert.deepStrictEqual(ViewModes.layout("fit", VIEW, VIDEO, 1), {width: 1600, height: 900});
    assert.deepStrictEqual(ViewModes.layout("fit", {width: 1000, height: 1000}, VIDEO, 1), {width: 1000, height: 562.5});
});

test("拉伸铺满整个窗口，不管比例", () => {
    assert.deepStrictEqual(ViewModes.layout("fill", {width: 1000, height: 1000}, VIDEO, 1), {width: 1000, height: 1000});
});

test("1:1 按设备像素比换算成 CSS 像素", () => {
    assert.deepStrictEqual(ViewModes.layout("native", VIEW, VIDEO, 1), {width: 1920, height: 1080});
    assert.deepStrictEqual(ViewModes.layout("native", VIEW, VIDEO, 1.5), {width: 1280, height: 720});
});

test("放大在适应窗口的基础上乘倍数", () => {
    assert.deepStrictEqual(ViewModes.layout("zoom200", VIEW, VIDEO, 1), {width: 3200, height: 1800});
});

test("还不知道画面尺寸时先铺满", () => {
    assert.deepStrictEqual(ViewModes.layout("native", VIEW, null, 1), VIEW);
    assert.deepStrictEqual(ViewModes.layout("zoom300", VIEW, {width: 0, height: 0}, 1), VIEW);
});

test("比窗口小的方向居中", () => {
    assert.deepStrictEqual(ViewModes.panOffset({width: 800, height: 450}, VIEW, {x: 0, y: 0}), {left: 400, top: 225});
});

test("比窗口大的方向跟着指针走，指针下就是对应的那一点", () => {
    const box = {width: 3200, height: 1800};
    assert.deepStrictEqual(ViewModes.panOffset(box, VIEW, {x: 0, y: 0}), {left: -0, top: -0});
    assert.deepStrictEqual(ViewModes.panOffset(box, VIEW, {x: 1600, y: 900}), {left: -1600, top: -900});
    const middle = ViewModes.panOffset(box, VIEW, {x: 800, y: 450});
    // 指针在窗口正中，看到的也是画面正中
    assert.strictEqual(800 - middle.left, 1600);
    assert.strictEqual(450 - middle.top, 900);
    // 没有指针位置时居中
    assert.deepStrictEqual(ViewModes.panOffset(box, VIEW, null), {left: -800, top: -450});
});

test("只有超出窗口时才需要跟着指针重摆", () => {
    assert.strictEqual(ViewModes.pans({width: 1600, height: 900}, VIEW), false);
    assert.strictEqual(ViewModes.pans({width: 1920, height: 1080}, VIEW), true);
});

test("存取显示方式", () => {
    const storage = memoryStorage();
    assert.strictEqual(ViewModes.load(storage), "fit");
    assert.strictEqual(ViewModes.save(storage, "native"), "native");
    assert.strictEqual(ViewModes.load(storage), "native");
    assert.strictEqual(ViewModes.save(storage, "bogus"), "fit");
    assert.strictEqual(ViewModes.load(null), "fit");
});
