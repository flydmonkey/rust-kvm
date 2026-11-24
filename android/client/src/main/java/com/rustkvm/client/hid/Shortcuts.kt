package com.rustkvm.client.hid

/**
 * 快捷键面板的纯逻辑：组合键文本解析、预设、自定义快捷键的校验和存取格式。
 * 和网页端 web/shortcuts.js 同一套规则和预设，两边的自定义写法互通。
 *
 * 按键名用 KeyboardEvent.code，和 [Ch9329Maps] 对得上。
 */
object Shortcuts {
    const val MAX_KEYS = 6
    const val MAX_STEPS = 16
    const val MAX_CUSTOM = 30
    const val MAX_LABEL_LENGTH = 20

    // 多步之间留点空，被控端弹出的菜单、对话框要时间画出来才接得住下一步
    const val STEP_GAP_MS = 150L

    // 开机连按：BIOS 只在自检那一两秒里认热键，所以从开机前就开始每 0.2 秒按一次。
    // 60 秒足够走完自检，忘了停也不会一直按下去
    const val REPEAT_INTERVAL_MS = 200L
    const val REPEAT_MAX_MS = 60_000L

    sealed interface Parsed<out T> {
        data class Ok<T>(val value: T) : Parsed<T>
        data class Invalid(val message: String) : Parsed<Nothing>
    }

    data class Combo(val codes: List<String>, val text: String)
    data class Sequence(val steps: List<List<String>>, val text: String)
    data class Shortcut(val label: String, val combo: String)
    data class Group(val title: String, val items: List<Shortcut>, val repeatable: Boolean = false)

    private class Key(val code: String, val name: String, val modifier: Boolean)

    // 修饰键别名 -> code 和显示名。macOS 用户习惯写 Cmd/Option，显示时保留原叫法
    private val MODIFIERS = mapOf(
        "ctrl" to ("ControlLeft" to "Ctrl"),
        "control" to ("ControlLeft" to "Ctrl"),
        "shift" to ("ShiftLeft" to "Shift"),
        "alt" to ("AltLeft" to "Alt"),
        "option" to ("AltLeft" to "Option"),
        "opt" to ("AltLeft" to "Option"),
        "win" to ("MetaLeft" to "Win"),
        "meta" to ("MetaLeft" to "Win"),
        "super" to ("MetaLeft" to "Super"),
        "cmd" to ("MetaLeft" to "Cmd"),
        "command" to ("MetaLeft" to "Cmd"),
    )

    private val KEYS = mapOf(
        "esc" to ("Escape" to "Esc"),
        "escape" to ("Escape" to "Esc"),
        "tab" to ("Tab" to "Tab"),
        "enter" to ("Enter" to "Enter"),
        "return" to ("Enter" to "Enter"),
        "space" to ("Space" to "Space"),
        "backspace" to ("Backspace" to "Backspace"),
        "del" to ("Delete" to "Del"),
        "delete" to ("Delete" to "Del"),
        "ins" to ("Insert" to "Ins"),
        "insert" to ("Insert" to "Ins"),
        "home" to ("Home" to "Home"),
        "end" to ("End" to "End"),
        "pgup" to ("PageUp" to "PgUp"),
        "pageup" to ("PageUp" to "PgUp"),
        "pgdn" to ("PageDown" to "PgDn"),
        "pagedown" to ("PageDown" to "PgDn"),
        "up" to ("ArrowUp" to "Up"),
        "down" to ("ArrowDown" to "Down"),
        "left" to ("ArrowLeft" to "Left"),
        "right" to ("ArrowRight" to "Right"),
        "prtsc" to ("PrintScreen" to "PrtSc"),
        "printscreen" to ("PrintScreen" to "PrtSc"),
        // Linux 的 Magic SysRq 就是 Alt+PrintScreen，键码相同
        "sysrq" to ("PrintScreen" to "SysRq"),
        "pause" to ("Pause" to "Pause"),
        "break" to ("Pause" to "Break"),
        "capslock" to ("CapsLock" to "CapsLock"),
        "numlock" to ("NumLock" to "NumLock"),
        "scrolllock" to ("ScrollLock" to "ScrollLock"),
        "menu" to ("ContextMenu" to "Menu"),
        "-" to ("Minus" to "-"),
        "=" to ("Equal" to "="),
        "[" to ("BracketLeft" to "["),
        "]" to ("BracketRight" to "]"),
        "\\" to ("Backslash" to "\\"),
        ";" to ("Semicolon" to ";"),
        "'" to ("Quote" to "'"),
        "," to ("Comma" to ","),
        "." to ("Period" to "."),
        "/" to ("Slash" to "/"),
        "`" to ("Backquote" to "`"),
    )

    private val FUNCTION_KEY = Regex("^f([1-9]|1[0-2])$")
    private val STEP_SEPARATOR = Regex("[\\s,，]+")

    private fun resolve(token: String): Key? {
        val lower = token.lowercase()
        MODIFIERS[lower]?.let { (code, name) -> return Key(code, name, modifier = true) }
        KEYS[lower]?.let { (code, name) -> return Key(code, name, modifier = false) }
        if (lower.length == 1 && lower[0] in 'a'..'z') {
            return Key("Key${lower.uppercase()}", lower.uppercase(), modifier = false)
        }
        if (lower.length == 1 && lower[0] in '0'..'9') {
            return Key("Digit$lower", lower, modifier = false)
        }
        FUNCTION_KEY.matchEntire(lower)?.let {
            val number = it.groupValues[1]
            return Key("F$number", "F$number", modifier = false)
        }
        return null
    }

    /** "Ctrl+Alt+F2" -> codes [ControlLeft, AltLeft, F2]。单独一个修饰键也合法：只按 Win 是打开开始菜单 */
    fun parseCombo(text: String): Parsed<Combo> {
        val raw = text.trim()
        if (raw.isEmpty()) return Parsed.Invalid("组合键是空的")
        val tokens = raw.split("+").map(String::trim)
        if (tokens.any(String::isEmpty)) return Parsed.Invalid("「$raw」里有多余的 +")
        val modifiers = mutableListOf<Key>()
        val keys = mutableListOf<Key>()
        for (token in tokens) {
            val key = resolve(token) ?: return Parsed.Invalid("不认识的按键「$token」")
            val bucket = if (key.modifier) modifiers else keys
            if (bucket.none { it.code == key.code }) bucket += key
        }
        if (keys.size > MAX_KEYS) return Parsed.Invalid("一次最多同时按 $MAX_KEYS 个普通键")
        // 修饰键排在前面：发送时要先按住修饰键，再按普通键
        val all = modifiers + keys
        return Parsed.Ok(Combo(all.map { it.code }, all.joinToString("+") { it.name }))
    }

    /** 多步序列用空格或逗号隔开："Ctrl+K Ctrl+C"、"Win+R, Enter" */
    fun parseSequence(text: String): Parsed<Sequence> {
        val raw = text.trim()
        if (raw.isEmpty()) return Parsed.Invalid("快捷键是空的")
        val parts = raw.split(STEP_SEPARATOR).filter(String::isNotEmpty)
        if (parts.size > MAX_STEPS) return Parsed.Invalid("一个快捷键最多 $MAX_STEPS 步")
        val steps = mutableListOf<List<String>>()
        val texts = mutableListOf<String>()
        for (part in parts) {
            when (val combo = parseCombo(part)) {
                is Parsed.Invalid -> return combo
                is Parsed.Ok -> {
                    steps += combo.value.codes
                    texts += combo.value.text
                }
            }
        }
        return Parsed.Ok(Sequence(steps, texts.joinToString(" ")))
    }

    val PRESETS: List<Group> = listOf(
        Group(
            "Windows",
            listOf(
                Shortcut("安全选项", "Ctrl+Alt+Del"),
                Shortcut("开始菜单", "Win"),
                Shortcut("锁屏", "Win+L"),
                Shortcut("显示桌面", "Win+D"),
                Shortcut("运行", "Win+R"),
                Shortcut("资源管理器", "Win+E"),
                Shortcut("任务管理器", "Ctrl+Shift+Esc"),
                Shortcut("切换窗口", "Alt+Tab"),
                Shortcut("关闭窗口", "Alt+F4"),
                Shortcut("截图工具", "Win+Shift+S"),
            ),
        ),
        Group(
            "macOS",
            listOf(
                Shortcut("聚焦搜索", "Cmd+Space"),
                Shortcut("切换应用", "Cmd+Tab"),
                Shortcut("退出应用", "Cmd+Q"),
                Shortcut("强制退出", "Cmd+Option+Esc"),
                Shortcut("锁屏", "Ctrl+Cmd+Q"),
            ),
        ),
        Group(
            "Linux",
            listOf(Shortcut("终端", "Ctrl+Alt+T")) +
                (1..7).map { Shortcut("TTY$it", "Ctrl+Alt+F$it") },
        ),
        Group(
            "开机 / BIOS",
            listOf(
                Shortcut("进 BIOS（台式机）", "Del"),
                Shortcut("进 BIOS（笔记本）", "F2"),
                Shortcut("启动菜单", "F12"),
                Shortcut("启动菜单", "F11"),
                Shortcut("启动菜单", "F8"),
                Shortcut("退出 / 返回", "Esc"),
            ),
            repeatable = true,
        ),
        Group(
            "其他",
            listOf(
                Shortcut("全屏截图", "PrtSc"),
                Shortcut("暂停", "Pause"),
                Shortcut("滚动锁定", "ScrollLock"),
                Shortcut("数字锁定", "NumLock"),
                Shortcut("右键菜单", "Menu"),
            ),
        ),
    )

    /** 名称去掉首尾空白、截到上限；换行和制表符换成空格，存储格式靠它们分隔 */
    private fun normalizeLabel(label: String, fallback: String): String {
        val text = label.replace(Regex("[\\t\\r\\n]"), " ").trim()
        if (text.isEmpty()) return fallback
        val codePoints = text.codePoints().limit(MAX_LABEL_LENGTH.toLong()).toArray()
        return String(codePoints, 0, codePoints.size)
    }

    /** 校验一条自定义快捷键；没填名称就用规范化后的组合键当名称 */
    fun makeCustom(label: String, combo: String): Parsed<Shortcut> =
        when (val parsed = parseSequence(combo)) {
            is Parsed.Invalid -> parsed
            is Parsed.Ok -> Parsed.Ok(Shortcut(normalizeLabel(label, parsed.value.text), parsed.value.text))
        }

    /** 存储里的东西可能是旧版本或手改坏的，丢掉坏条目而不是整个作废 */
    fun normalizeCustomList(list: List<Shortcut>): List<Shortcut> =
        list.asSequence()
            .mapNotNull { (makeCustom(it.label, it.combo) as? Parsed.Ok)?.value }
            .take(MAX_CUSTOM)
            .toList()

    /** 一行一条，名称和组合键之间是制表符 */
    fun encodeCustom(list: List<Shortcut>): String =
        normalizeCustomList(list).joinToString("\n") { "${it.label}\t${it.combo}" }

    fun decodeCustom(raw: String?): List<Shortcut> =
        normalizeCustomList(
            raw.orEmpty().lineSequence()
                .mapNotNull { line ->
                    val tab = line.indexOf('\t')
                    if (tab < 0) null else Shortcut(line.substring(0, tab), line.substring(tab + 1))
                }
                .toList(),
        )
}
