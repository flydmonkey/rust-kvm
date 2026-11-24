// 工具栏上那几个状态图标的文案和颜色。只做「硬件状态 -> 说什么」的判断，
// 不碰 DOM，这样能单测——之前这段逻辑埋在页面里，改一次只能靠手点验证。
var StatusText = (function () {
    // state: {connection, serverStats, clientStats}
    //
    // 画面走 WebSocket，没有 MediaStreamTrack 可问，状态只能从「连接状态 +
    // 服务端读数 + 本机绘制计数」推出来。chrome-kvm 那个 describeVideo 问的是
    // track.readyState / track.muted，在这儿根本没有对应物，所以整个换掉了。
    function describeStream(state) {
        if (state.connection !== "已连接") {
            return {
                level: state.connection === "连接中" ? "idle" : "error",
                lines: ["画面 " + state.connection, "点击刷新读数"]
            };
        }

        var level = "ok";
        var lines = [];
        var server = state.serverStats;
        var client = state.clientStats;

        if (!server) {
            // WebSocket 通着但 HTTP 那边读不到，多半是服务正在重启
            level = "warn";
            lines.push("读不到服务端状态");
        } else {
            if (server.resolution) {
                lines.push(server.resolution[0] + "×" + server.resolution[1]
                    + "  " + server.fps.toFixed(0) + " fps  "
                    + server.mbps.toFixed(1) + " Mbps");
            }
            if (server.error) {
                level = "error";
                lines.push(server.error);
            } else if (!server.capturing) {
                // 连上了但服务端没在采：采集卡被别的程序占着，或者根本没枚举到
                level = "warn";
                lines.push("服务端未采集");
            }
        }

        // 帧确实到了浏览器，却一帧都没画出来——解码全失败，光看 fps 看不出来
        if (client && client.received > 0 && client.drawn === 0) {
            level = "error";
            lines.push("收到帧但画不出来");
        }

        lines.push("点击刷新读数");
        return {level: level, lines: lines};
    }

    // 把 level 落到图标按钮和它的 tooltip 上。三个状态按钮共用，
    // 免得每个模块各写一遍类名拼接。
    //
    // 用 classList 增删而不是整个覆盖 className：按钮上还挂着别的类
    // （比如录制按钮的 .recording），覆盖会把它们一起抹掉。
    function applyStatus(button, tooltip, state) {
        ["idle", "ok", "warn", "error"].forEach(function (level) {
            button.classList.toggle("status-" + level, level === state.level);
        });
        tooltip.textContent = state.lines.join("\n");
    }

    // state: {connected, serialLost, info, ackEnabled, baudRate, mouseRelative, pointerLocked}
    function describeSerial(state) {
        if (!state.connected) {
            return {level: "idle", lines: ["未连接串口"]};
        }
        if (state.serialLost) {
            // 接下来那句提示（在自动重连 / 可以点着连）由调用方补：只有它知道
            // 现在有没有排上下一次尝试。这儿曾经写死「插回后会自动重连」，
            // 而当时根本没有重连代码——一句纯粹的空话。
            return {level: "error", lines: ["串口已断开"]};
        }

        var level = "ok";
        var lines = [];
        var info = state.info;
        if (!info) {
            level = "error";
            lines.push("芯片无应答");
            // 端口能打开却没人应答，最常见的是芯片没供电（到被控端那根 USB
            // 没插好）或者选错了串口设备，光说「无应答」用户无从下手
            lines.push("查 CH9329 供电和选中的端口");
        } else {
            lines.push("固件 " + info.version);
            lines.push(info.usbConnected ? "USB 已枚举" : "USB 未枚举");
            if (!info.usbConnected) {
                level = "warn";
            }
            if (info.asleep) {
                lines.push("被控端已休眠");
                level = "warn";
            }
            lines.push(info.capsLock ? "Caps Lock 开" : "Caps Lock 关");
        }

        if (!state.ackEnabled) {
            lines.push("已降级为只发不等");
            // 别把已有的 error 降级成 warn
            level = level === "ok" ? "warn" : level;
        }
        if (state.baudRate) {
            // 没探测成功时这个值只是兜底用的，不能显示得像已经确认过——
            // 否则看上去是「连上了 115200 但芯片哑巴」，实际是两个波特率都没人应
            lines.push(state.baudProbed === false
                ? state.baudRate + " bps（未确认）"
                : state.baudRate + " bps");
        }
        // 相对模式没锁指针时，光标顶到屏幕边缘就走不动了，得让用户知道怎么解决
        if (state.mouseRelative) {
            if (state.pointerLocked) {
                lines.push("指针已锁定（Esc 释放）");
            } else if (state.pointerLockRejected) {
                // 被拒时不能还显示「点击画面锁定指针」，那等于让用户白点
                lines.push("指针锁定被拒绝");
                lines.push("先点一下页面取得焦点再试");
                level = level === "ok" ? "warn" : level;
            } else {
                lines.push("相对模式：点击画面锁定指针");
            }
        }

        lines.push("点击刷新");
        return {level: level, lines: lines};
    }

    // state: {fullscreen, keyboardLocked, lockSupported}
    function describeFullscreen(state) {
        if (!state.fullscreen) {
            var lines = ["全屏"];
            if (state.lockSupported) {
                // 进全屏之前就告诉用户有这个好处，不然没人会想到
                lines.push("可捕获 Win、Alt+Tab 等系统键");
            }
            return {label: "全屏", lines: lines};
        }

        var full = ["退出全屏"];
        if (state.keyboardLocked) {
            full.push("系统快捷键已捕获");
            // Chrome 留的逃生口，不说清楚用户会以为退不出去
            full.push("长按 Esc 两秒退出");
        } else if (state.lockSupported) {
            // 多半是 F11 进的全屏：键盘锁定只认 JS 发起的全屏
            full.push("系统快捷键未捕获");
        }
        return {label: "退出全屏", lines: full};
    }

    // 文件名用本地时间，方便对着操作时间找文件
    function timestamp(date) {
        var at = date || new Date();
        var pad = function (value) {
            return String(value).padStart(2, "0");
        };
        // getMonth 从 0 开始，getDate 才是几号（原来误用了 getDay，拿到的是星期几）
        return at.getFullYear()
            + pad(at.getMonth() + 1)
            + pad(at.getDate())
            + "-"
            + pad(at.getHours())
            + pad(at.getMinutes())
            + pad(at.getSeconds());
    }

    function recordFileName(date) {
        return timestamp(date) + ".webm";
    }

    function screenshotFileName(date) {
        return "screenshot-" + timestamp(date) + ".png";
    }

    // 键盘指示灯，照搬 chrome-kvm。key 是点灯时发给被控端的键
    var LOCK_LIGHTS = [
        {key: "NumLock", field: "numLock", glyph: "1", name: "Num Lock"},
        {key: "CapsLock", field: "capsLock", glyph: "A", name: "Caps Lock"},
        {key: "ScrollLock", field: "scrollLock", glyph: "⇩", name: "Scroll Lock"}
    ];

    // 被控端没枚举出这块键盘时灯的状态没有意义，返回 null 让页面把灯藏起来
    function lockLights(info) {
        if (!info || !info.usbConnected) {
            return null;
        }
        return LOCK_LIGHTS.map(function (light) {
            var on = !!info[light.field];
            return {
                key: light.key,
                glyph: light.glyph,
                on: on,
                label: light.name + (on ? " 开，点击切换" : " 关，点击切换")
            };
        });
    }

    return {
        describeStream: describeStream,
        describeSerial: describeSerial,
        describeFullscreen: describeFullscreen,
        applyStatus: applyStatus,
        recordFileName: recordFileName,
        screenshotFileName: screenshotFileName,
        LOCK_LIGHTS: LOCK_LIGHTS,
        lockLights: lockLights
    };
})();

if (typeof module !== "undefined" && module.exports) {
    module.exports = StatusText;
}
