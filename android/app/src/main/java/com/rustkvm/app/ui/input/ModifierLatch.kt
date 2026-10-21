package com.rustkvm.app.ui.input

/**
 * 软键盘没有 Ctrl / Alt / Win，所以功能键栏上的修饰键是粘滞的：点一下锁定，作用于
 * 下一个键（功能键栏的或软键盘打的）之后自动松开。
 *
 * 再点一次已锁定的修饰键表示「就要它本身」：把当前锁定的修饰键作为一次按键单独发出
 * 并全部松开。连点两下 Win 打开开始菜单就靠这个。
 */
data class ModifierTapResult(
    val latched: Set<String>,
    /** 非空时要立刻发出的一次按键：按下 [sendModifiers] 再按下松开 [sendKey]。 */
    val sendKey: String? = null,
    val sendModifiers: Set<String> = emptySet(),
)

fun onModifierTap(latched: Set<String>, modifier: String): ModifierTapResult =
    if (modifier in latched) {
        ModifierTapResult(
            latched = emptySet(),
            sendKey = modifier,
            sendModifiers = latched - modifier,
        )
    } else {
        ModifierTapResult(latched = latched + modifier)
    }
