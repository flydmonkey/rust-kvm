package com.rustkvm.app.ui

/**
 * 控制页全屏时，从屏幕边缘拖鼠标很容易误触系统返回手势，所以要在 [windowMs] 内连按两次
 * 返回才算真的要断开。
 */
class DoubleBackPress(private val windowMs: Long = DOUBLE_BACK_WINDOW_MS) {
    private var firstPressAt: Long? = null

    /** 返回 true 表示这是确认的第二次按下。 */
    fun press(nowMs: Long): Boolean {
        val first = firstPressAt
        if (first != null && nowMs - first <= windowMs) {
            firstPressAt = null
            return true
        }
        firstPressAt = nowMs
        return false
    }

    companion object {
        const val DOUBLE_BACK_WINDOW_MS = 2_000L
    }
}
