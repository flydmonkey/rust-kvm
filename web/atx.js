// ATX 电源，照搬 chrome-kvm 的 atx-relay.js。继电器插在宿主机上，指令由服务端发
// （/api/atx/press），这里只剩配置规则、按钮文案和「点两下才执行」的确认逻辑。
var Atx = (function () {
    var CHANNELS = [1, 2, 3, 4];
    var CONFIRM_MS = 4000;

    // 长按会强制断电、复位会直接重启，都丢数据，所以要连点两下；短按开机或让系统
    // 正常关机，点一下就按
    var PRESSES = {
        short: {label: "按一下电源键", done: "已按一下电源键", confirm: null},
        long: {label: "长按强制关机", done: "已长按电源键", confirm: "再点一次确认强制关机"},
        reset: {label: "按一下复位键", done: "已按一下复位键", confirm: "再点一次确认重启"}
    };

    function normalizeChannel(value) {
        var n = typeof value === "number" ? value : parseInt(value, 10);
        return CHANNELS.indexOf(n) === -1 ? 1 : n;
    }

    // 和服务端 atx::normalize 同一套规则：没选串口就是不用；复位键可以不接，
    // 接了也不能和电源键同一路
    function normalize(atx) {
        if (!atx || typeof atx !== "object" || !String(atx.port || "").trim()) {
            return null;
        }
        var channel = normalizeChannel(atx.channel);
        var reset = typeof atx.resetChannel === "number" ? atx.resetChannel : parseInt(atx.resetChannel, 10);
        return {
            port: String(atx.port).trim(),
            channel: channel,
            resetChannel: CHANNELS.indexOf(reset) !== -1 && reset !== channel ? reset : null
        };
    }

    // 确认状态只对最后点的那个按钮有效：点了长按又点复位，复位要重新点两下
    function click(armed, kind, now) {
        var press = PRESSES[kind];
        if (!press || !press.confirm) {
            return {run: true, armed: null};
        }
        if (armed && armed.kind === kind && now - armed.at <= CONFIRM_MS) {
            return {run: true, armed: null};
        }
        return {run: false, armed: {kind: kind, at: now}};
    }

    return {
        CHANNELS: CHANNELS,
        CONFIRM_MS: CONFIRM_MS,
        PRESSES: PRESSES,
        normalizeChannel: normalizeChannel,
        normalize: normalize,
        click: click
    };
})();

if (typeof module !== "undefined" && module.exports) {
    module.exports = Atx;
}
