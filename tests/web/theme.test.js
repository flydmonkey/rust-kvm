// 运行：node --test "tests/web/*.test.js"
const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const path = require("node:path");
const WEB = path.join(__dirname, "../../web");
const Theme = require(path.join(WEB, "theme.js"));

test("normalize 只认三种选择，其余按跟随系统", () => {
    assert.strictEqual(Theme.normalize("light"), "light");
    assert.strictEqual(Theme.normalize("dark"), "dark");
    assert.strictEqual(Theme.normalize("blue"), "auto");
    assert.strictEqual(Theme.normalize(null), "auto");
});

test("resolve：跟随系统时看系统偏好，拿不到偏好按深色", () => {
    assert.strictEqual(Theme.resolve("auto", true), "light");
    assert.strictEqual(Theme.resolve("auto", false), "dark");
    assert.strictEqual(Theme.resolve("light", false), "light");
    assert.strictEqual(Theme.resolve("dark", true), "dark");
});

test("两个页面都在 <head> 里同步加载主题，赶在绘制之前", () => {
    for (const page of ["index.html", "settings.html"]) {
        const html = fs.readFileSync(path.join(WEB, page), "utf8");
        const head = html.slice(0, html.indexOf("</head>"));
        assert.match(head, /<link rel="stylesheet" href="theme.css">/, page);
        assert.match(head, /<script src="theme.js"><\/script>/, page);
    }
});

test("浅色和深色定义了同一组变量，页面用到的变量都有定义", () => {
    const css = fs.readFileSync(path.join(WEB, "theme.css"), "utf8");
    const blocks = css.split(':root[data-theme="light"]');
    const names = (text) => new Set([...text.matchAll(/(--[\w-]+)\s*:/g)].map((m) => m[1]));
    const dark = names(blocks[0]);
    const light = names(blocks[1]);
    assert.deepStrictEqual([...light].sort(), [...dark].sort());

    for (const page of ["index.html", "settings.html"]) {
        const html = fs.readFileSync(path.join(WEB, page), "utf8");
        for (const match of html.matchAll(/var\((--[\w-]+)\)/g)) {
            assert.ok(dark.has(match[1]), `${page} 用了未定义的 ${match[1]}`);
        }
    }
});
