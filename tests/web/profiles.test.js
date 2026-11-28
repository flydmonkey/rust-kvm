// 运行：node --test "tests/web/*.test.js"
const test = require("node:test");
const assert = require("node:assert");
const path = require("node:path");
const Profiles = require(path.join(__dirname, "../../web/profiles.js"));

function memoryStorage(initial) {
    const data = Object.assign({}, initial);
    return {
        data,
        getItem: (key) => (Object.prototype.hasOwnProperty.call(data, key) ? data[key] : null),
        setItem: (key, value) => {
            data[key] = String(value);
        }
    };
}

function config(overrides) {
    const base = {
        video: {device: "USB Video", width: 1920, height: 1080, fps: 30, codec: "h264", bitrateKbps: 4000},
        audio: {device: "Digital Audio Interface", enabled: true, narrowband: false},
        serial: {port: "COM3", baudRate: 115200, mouseMode: "absolute"}
    };
    return Object.assign(base, overrides || {});
}

test("snapshot 只收跟着被控机走的字段", () => {
    const shot = Profiles.snapshot(config());
    assert.deepStrictEqual(shot, {
        video: {device: "USB Video", width: 1920, height: 1080, fps: 30},
        audio: {device: "Digital Audio Interface"},
        serial: {port: "COM3", mouseMode: "absolute"}
    });
});

test("snapshot 是深拷贝，之后改原配置不影响快照", () => {
    const source = config();
    const shot = Profiles.snapshot(source);
    source.video.width = 640;
    assert.strictEqual(shot.video.width, 1920);
});

test("apply 合并进当前配置，编码、码率、窄带、波特率保持当前值", () => {
    const saved = Profiles.saveAs(Profiles.normalize(null), "办公室", config({
        video: {device: "Cam B", width: 1280, height: 720, fps: 25, codec: "mjpeg", bitrateKbps: 1000},
        audio: {device: "Mic B", enabled: false, narrowband: true},
        serial: {port: "COM7", baudRate: 9600, mouseMode: "relative"}
    })).state;
    const current = config();
    const result = Profiles.apply(saved, "办公室", current);
    assert.deepStrictEqual(result.settings, {
        video: {device: "Cam B", width: 1280, height: 720, fps: 25, codec: "h264", bitrateKbps: 4000},
        audio: {device: "Mic B", enabled: true, narrowband: false},
        serial: {port: "COM7", baudRate: 115200, mouseMode: "relative", scrollDirection: "normal"},
        atx: null
    });
    assert.strictEqual(result.state.active, "办公室");
    assert.strictEqual(current.video.device, "USB Video", "不能改动传入的当前配置");
});

test("滚轮方向跟着配置走", () => {
    const state = Profiles.saveAs(Profiles.normalize(null), "Mac", config({
        serial: {port: "COM3", baudRate: 115200, mouseMode: "absolute", scrollDirection: "reverse"}
    })).state;
    assert.strictEqual(state.profiles[0].settings.serial.scrollDirection, "reverse");
    const result = Profiles.apply(state, "Mac", config());
    assert.strictEqual(result.settings.serial.scrollDirection, "reverse");
    assert.strictEqual(Profiles.summary(state.profiles[0].settings), "1920×1080 · 绝对 · 滚轮反向 · COM3 · USB Video");
});

test("切到没存滚轮方向的旧配置时回到标准，不沿用上一台的反向", () => {
    const old = {active: null, profiles: [{name: "旧", settings: {serial: {port: "COM3"}}}]};
    const current = config({serial: {port: "COM5", baudRate: 115200, mouseMode: "absolute", scrollDirection: "reverse"}});
    const result = Profiles.apply(old, "旧", current);
    assert.strictEqual(result.settings.serial.scrollDirection, "normal");
});

test("继电器跟着配置走", () => {
    const relay = {port: "COM9", channel: 2, resetChannel: 3};
    const state = Profiles.saveAs(Profiles.normalize(null), "A", config({atx: relay})).state;
    assert.deepStrictEqual(state.profiles[0].settings.atx, relay);
    const result = Profiles.apply(state, "A", config({atx: null}));
    assert.deepStrictEqual(result.settings.atx, relay);
    assert.notStrictEqual(result.settings.atx, relay, "要拷贝，不能共用对象");
});

test("切到没存继电器的旧配置时清空继电器，不沿用上一台的", () => {
    const old = {active: null, profiles: [{name: "旧", settings: {serial: {port: "COM3"}}}]};
    const result = Profiles.apply(old, "旧", config({atx: {port: "COM9", channel: 1, resetChannel: null}}));
    assert.strictEqual(result.settings.atx, null);
});

test("summary 带上继电器", () => {
    assert.strictEqual(
        Profiles.summary({atx: {port: "COM9", channel: 2, resetChannel: null}}),
        "绝对 · 电源 COM9 第 2 路"
    );
});

test("apply 找不到配置时报错", () => {
    assert.ok(Profiles.apply(Profiles.normalize(null), "不存在", config()).error);
});

test("saveAs 同名覆盖、空名字报错、超过上限报错", () => {
    let state = Profiles.saveAs(Profiles.normalize(null), "  A  ", config()).state;
    assert.strictEqual(state.profiles[0].name, "A");
    state = Profiles.saveAs(state, "A", config({serial: {port: "COM9", mouseMode: "absolute"}})).state;
    assert.strictEqual(state.profiles.length, 1);
    assert.strictEqual(state.profiles[0].settings.serial.port, "COM9");

    assert.ok(Profiles.saveAs(state, "   ", config()).error);

    for (let i = 0; i < Profiles.MAX_PROFILES - 1; i++) {
        state = Profiles.saveAs(state, `机器${i}`, config()).state;
    }
    assert.strictEqual(state.profiles.length, Profiles.MAX_PROFILES);
    assert.ok(Profiles.saveAs(state, "再来一台", config()).error);
});

test("名字截到上限长度，按字符而不是按 UTF-16 单元", () => {
    const long = "😀".repeat(30);
    assert.strictEqual(Array.from(Profiles.normalizeName(long)).length, Profiles.MAX_NAME_LENGTH);
});

test("remove 删掉当前配置时清空 active", () => {
    let state = Profiles.saveAs(Profiles.normalize(null), "A", config()).state;
    state = Profiles.saveAs(state, "B", config()).state;
    state = Profiles.remove(state, "B");
    assert.deepStrictEqual(state.profiles.map((p) => p.name), ["A"]);
    assert.strictEqual(state.active, null);
});

test("updateActive 只更新当前那份", () => {
    let state = Profiles.saveAs(Profiles.normalize(null), "A", config()).state;
    state = Profiles.saveAs(state, "B", config()).state;
    state = Profiles.updateActive(state, config({serial: {port: "COM5", mouseMode: "relative"}}));
    assert.strictEqual(state.profiles[0].settings.serial.port, "COM3");
    assert.strictEqual(state.profiles[1].settings.serial.port, "COM5");
});

test("load 容忍坏数据：坏 JSON、坏条目、重名、悬空的 active", () => {
    assert.deepStrictEqual(Profiles.load(memoryStorage({[Profiles.STORAGE_KEY]: "{oops"})), {active: null, profiles: []});
    const state = Profiles.load(memoryStorage({
        [Profiles.STORAGE_KEY]: JSON.stringify({
            active: "幽灵",
            profiles: [null, {name: ""}, {name: "A", settings: {video: {width: "x", fps: [1]}}}, {name: "A"}]
        })
    }));
    assert.strictEqual(state.active, null);
    assert.deepStrictEqual(state.profiles, [{name: "A", settings: {video: {width: "x"}}}]);
});

test("save 再 load 能原样读回", () => {
    const storage = memoryStorage();
    const state = Profiles.saveAs(Profiles.normalize(null), "A", config()).state;
    Profiles.save(storage, state);
    assert.deepStrictEqual(Profiles.load(storage), state);
});

test("summary 让人认得出是哪台", () => {
    assert.strictEqual(
        Profiles.summary(Profiles.snapshot(config())),
        "1920×1080 · 绝对 · COM3 · USB Video"
    );
    assert.strictEqual(Profiles.summary({}), "绝对");
});
