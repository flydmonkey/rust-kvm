// 运行：node --test "tests/web/*.test.js"
const test = require("node:test");
const assert = require("node:assert");
const path = require("node:path");
const UsbMedia = require(path.join(__dirname, "../../web/usb-media.js"));

test("大小按 1024 进位，B 不带小数", () => {
    assert.strictEqual(UsbMedia.formatSize(512), "512 B");
    assert.strictEqual(UsbMedia.formatSize(1536), "1.5 KB");
    assert.strictEqual(UsbMedia.formatSize(5046586573), "4.7 GB");
});

test("光驱只列 .iso，U 盘都列", () => {
    const images = [{name: "a.img", size: 1}, {name: "B.ISO", size: 1}, {name: "c.iso", size: 1}];
    assert.deepStrictEqual(UsbMedia.choices(images, "cdrom"), ["B.ISO", "c.iso"]);
    assert.deepStrictEqual(UsbMedia.choices(images, "disk"), ["a.img", "B.ISO", "c.iso"]);
});

test("槽位文案", () => {
    assert.strictEqual(UsbMedia.slotText("cdrom", {image: null, readOnly: true}), "空");
    assert.strictEqual(UsbMedia.slotText("cdrom", {image: "win.iso", readOnly: true}), "win.iso");
    assert.strictEqual(UsbMedia.slotText("disk", {image: "d.img", readOnly: true}), "d.img（只读）");
    assert.strictEqual(UsbMedia.slotText("disk", {image: "d.img", readOnly: false}), "d.img");
});

test("只有可用时才显示工具栏按钮", () => {
    assert.strictEqual(UsbMedia.showButton(null), false);
    assert.strictEqual(UsbMedia.showButton({available: false, reason: "x"}), false);
    assert.strictEqual(UsbMedia.showButton({available: true, slots: {}, images: []}), true);
});

test("只收 .iso 和 .img", () => {
    assert.strictEqual(UsbMedia.acceptsUpload("x.ISO"), true);
    assert.strictEqual(UsbMedia.acceptsUpload("x.img"), true);
    assert.strictEqual(UsbMedia.acceptsUpload("x.zip"), false);
});

test("不可用的原因只在 ARM 上给（x86 上 reason 是 null）", () => {
    assert.strictEqual(UsbMedia.unavailableHint(null), "");
    assert.strictEqual(UsbMedia.unavailableHint({available: false, reason: null}), "");
    assert.strictEqual(UsbMedia.unavailableHint({available: true, reason: null}), "");
    assert.strictEqual(
        UsbMedia.unavailableHint({available: false, reason: "没有 USB 设备控制器"}),
        "本机 USB 口直连不可用：没有 USB 设备控制器"
    );
});