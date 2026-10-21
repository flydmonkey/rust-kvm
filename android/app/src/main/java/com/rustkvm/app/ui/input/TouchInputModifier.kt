package com.rustkvm.app.ui.input

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.min
import kotlin.math.roundToInt

data class NormalizedTouch(val x: Float, val y: Float)

fun normalizeLetterboxedTouch(
    x: Float,
    y: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    videoWidth: Float,
    videoHeight: Float,
): NormalizedTouch? = normalizeLetterboxedTouch(
    x = x,
    y = y,
    viewportWidth = viewportWidth,
    viewportHeight = viewportHeight,
    videoWidth = videoWidth,
    videoHeight = videoHeight,
    clamp = false,
)

/**
 * [sink] 必须在重组之间保持同一个实例：它是 pointerInput 的 key，换实例会打断进行中的手势。
 */
fun Modifier.ch9329TouchInput(
    videoWidth: Int,
    videoHeight: Int,
    absoluteMouse: Boolean,
    sink: PointerSink,
): Modifier = pointerInput(videoWidth, videoHeight, absoluteMouse, sink) {
    val longPressMs = viewConfiguration.longPressTimeoutMillis
    val slopPx = viewConfiguration.touchSlop
    val scrollPxPerNotch = SCROLL_DP_PER_NOTCH * density
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val interpreter = TouchGestureInterpreter(
            absoluteMouse = absoluteMouse,
            normalize = { position, clamp ->
                normalizeLetterboxedTouch(
                    x = position.x,
                    y = position.y,
                    viewportWidth = size.width.toFloat(),
                    viewportHeight = size.height.toFloat(),
                    videoWidth = videoWidth.toFloat(),
                    videoHeight = videoHeight.toFloat(),
                    clamp = clamp,
                )
            },
            sink = sink,
            slopPx = slopPx,
            scrollPxPerNotch = scrollPxPerNotch,
        )
        interpreter.onEvent(listOf(TouchPoint(down.id.value, down.position, pressed = true)))
        down.consume()

        var lastUptime = down.uptimeMillis
        while (!interpreter.finished) {
            val event = if (interpreter.awaitingLongPress) {
                val remaining = longPressMs - (lastUptime - down.uptimeMillis)
                withTimeoutOrNull(remaining.coerceAtLeast(1)) { awaitPointerEvent() }
            } else {
                awaitPointerEvent()
            }
            if (event == null) {
                interpreter.onLongPress()
                continue
            }
            lastUptime = event.changes.maxOf { it.uptimeMillis }
            interpreter.onEvent(
                event.changes.map { TouchPoint(it.id.value, it.position, it.pressed) },
            )
            event.changes.forEach { it.consume() }
        }
    }
}

internal fun hasExceededDragSlop(
    down: Offset,
    current: Offset,
    slopPx: Float = ABSOLUTE_DRAG_SLOP_PX,
): Boolean = (current - down).getDistanceSquared() > slopPx * slopPx

internal data class RelativeDelta(val dx: Int, val dy: Int)

internal class RelativeMotionAccumulator {
    private var residualX = 0f
    private var residualY = 0f

    fun consume(dx: Float, dy: Float): RelativeDelta {
        val totalX = residualX + dx
        val totalY = residualY + dy
        val roundedX = totalX.roundToInt()
        val roundedY = totalY.roundToInt()
        residualX = totalX - roundedX
        residualY = totalY - roundedY
        return RelativeDelta(roundedX, roundedY)
    }
}

private fun normalizeLetterboxedTouch(
    x: Float,
    y: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    videoWidth: Float,
    videoHeight: Float,
    clamp: Boolean,
): NormalizedTouch? {
    if (viewportWidth <= 0f || viewportHeight <= 0f || videoWidth <= 0f || videoHeight <= 0f) {
        return null
    }
    val scale = min(viewportWidth / videoWidth, viewportHeight / videoHeight)
    val contentWidth = videoWidth * scale
    val contentHeight = videoHeight * scale
    val left = (viewportWidth - contentWidth) / 2f
    val top = (viewportHeight - contentHeight) / 2f
    if (!clamp && (x < left || x > left + contentWidth || y < top || y > top + contentHeight)) {
        return null
    }
    return NormalizedTouch(
        x = ((x - left) / contentWidth).coerceIn(0f, 1f),
        y = ((y - top) / contentHeight).coerceIn(0f, 1f),
    )
}

/** 浏览器按 100px 一格；手指比滚轮慢得多，按 16dp 一格才不至于要划好几屏。 */
private const val SCROLL_DP_PER_NOTCH = 16f
