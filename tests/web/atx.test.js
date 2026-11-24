// 运行：node --test "tests/web/*.test.js"
const test = require("node:test");
const assert = require("node:assert");
const path = require("node:path");
const Atx = require(path.join(__dirname, "../../web/atx.js"));

test("没选串口就是不用继电器", () => {
    assert.strictEqual(Atx.normalize(null), null);
    assert.strictEqual(Atx.normalize({port: "", channel: 1}), null);
    assert.strictEqual(Atx.normalize({port: "   ", channel: 1}), null);
});

test("电源键通道不在 1–4 内按 1", () => {
    assert.deepStrictEqual(Atx.normalize({port: "COM7", channel: 9}), {port: "COM7", channel: 1, resetChannel: null});
    assert.deepStrictEqual(Atx.normalize({port: "COM7", channel: "3"}), {port: "COM7", channel: 3, resetChannel: null});
});

test("复位键通道无效或和电源键同一路时视为不接", () => {
    assert.strictEqual(Atx.normalize({port: "COM7", channel: 2, resetChannel: 2}).resetChannel, null);
    assert.strictEqual(Atx.normalize({port: "COM7", channel: 2, resetChannel: 0}).resetChannel, null);
    assert.strictEqual(Atx.normalize({port: "COM7", channel: 2, resetChannel: "3"}).resetChannel, 3);
});

test("短按点一下就执行", () => {
    assert.deepStrictEqual(Atx.click(null, "short", 0), {run: true, armed: null});
});

test("长按要在 4 秒内点两下", () => {
    const first = Atx.click(null, "long", 1000);
    assert.strictEqual(first.run, false);
    assert.deepStrictEqual(first.armed, {kind: "long", at: 1000});
    assert.strictEqual(Atx.click(first.armed, "long", 1000 + Atx.CONFIRM_MS).run, true);

    const late = Atx.click(first.armed, "long", 1001 + Atx.CONFIRM_MS);
    assert.strictEqual(late.run, false, "超时后重新计");
});

test("确认状态只对最后点的那个按钮有效", () => {
    const reset = Atx.click(null, "reset", 0);
    const long = Atx.click(reset.armed, "long", 100);
    const resetAgain = Atx.click(long.armed, "reset", 200);
    assert.strictEqual(resetAgain.run, false);
    assert.strictEqual(Atx.click(resetAgain.armed, "reset", 300).run, true);
});

test("点短按会清掉待确认的长按", () => {
    const long = Atx.click(null, "long", 0);
    const short = Atx.click(long.armed, "short", 100);
    assert.strictEqual(short.armed, null);
    assert.strictEqual(Atx.click(short.armed, "long", 200).run, false);
});
