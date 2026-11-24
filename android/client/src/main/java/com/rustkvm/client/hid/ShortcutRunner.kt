package com.rustkvm.client.hid

import kotlinx.coroutines.delay

/** 逐步发一个多步快捷键。某一步没发出去就停下，返回 false */
suspend fun sendShortcutSteps(
    steps: List<List<String>>,
    gapMs: Long = Shortcuts.STEP_GAP_MS,
    tap: (List<String>) -> Boolean,
): Boolean {
    steps.forEachIndexed { index, codes ->
        if (index > 0) delay(gapMs)
        if (!tap(codes)) return false
    }
    return true
}

enum class RepeatEnd { TimedOut, Failed }

/**
 * 开机连按：反复发 [steps] 直到到时限或发送失败。用户叫停就取消所在的协程，
 * 次数由 [onCount] 报出去，取消时调用方手里已经有最新次数。
 */
suspend fun repeatShortcutSteps(
    steps: List<List<String>>,
    intervalMs: Long = Shortcuts.REPEAT_INTERVAL_MS,
    maxMs: Long = Shortcuts.REPEAT_MAX_MS,
    gapMs: Long = Shortcuts.STEP_GAP_MS,
    now: () -> Long = System::currentTimeMillis,
    onCount: (Int) -> Unit = {},
    tap: (List<String>) -> Boolean,
): RepeatEnd {
    val started = now()
    var count = 0
    while (true) {
        if (now() - started >= maxMs) return RepeatEnd.TimedOut
        if (!sendShortcutSteps(steps, gapMs, tap)) return RepeatEnd.Failed
        count += 1
        onCount(count)
        delay(intervalMs)
    }
}
