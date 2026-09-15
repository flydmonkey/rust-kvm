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

fun Modifier.ch9329TouchInput(
    videoWidth: Int,
    videoHeight: Int,
    absoluteMouse: Boolean,
    onAbsolute: (x: Float, y: Float, buttons: Int) -> Unit,
    onRelative: (dx: Int, dy: Int, buttons: Int) -> Unit,
): Modifier = pointerInput(videoWidth, videoHeight, absoluteMouse, onAbsolute, onRelative) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val initial = normalizeLetterboxedTouch(
            x = down.position.x,
            y = down.position.y,
            viewportWidth = size.width.toFloat(),
            viewportHeight = size.height.toFloat(),
            videoWidth = videoWidth.toFloat(),
            videoHeight = videoHeight.toFloat(),
        ) ?: return@awaitEachGesture

        down.consume()
        if (absoluteMouse) {
            onAbsolute(initial.x, initial.y, PRIMARY_BUTTON)
        } else {
            onRelative(0, 0, PRIMARY_BUTTON)
        }

        var previous = down.position
        var dragging = false
        val relativeMotion = RelativeMotionAccumulator()
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            val pressed = change.pressed
            if (absoluteMouse) {
                if (pressed && !dragging) {
                    dragging = hasExceededDragSlop(down.position, change.position)
                }
                if (dragging || !pressed) {
                    val point = if (dragging) {
                        normalizeClamped(
                            position = change.position,
                            viewportWidth = size.width.toFloat(),
                            viewportHeight = size.height.toFloat(),
                            videoWidth = videoWidth.toFloat(),
                            videoHeight = videoHeight.toFloat(),
                        )
                    } else {
                        initial
                    }
                    onAbsolute(point.x, point.y, if (pressed) PRIMARY_BUTTON else 0)
                }
            } else {
                val delta = change.position - previous
                val rounded = relativeMotion.consume(delta.x, delta.y)
                onRelative(
                    rounded.dx,
                    rounded.dy,
                    if (pressed) PRIMARY_BUTTON else 0,
                )
            }
            previous = change.position
            change.consume()
            if (!pressed) break
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

private fun normalizeClamped(
    position: Offset,
    viewportWidth: Float,
    viewportHeight: Float,
    videoWidth: Float,
    videoHeight: Float,
): NormalizedTouch = checkNotNull(
    normalizeLetterboxedTouch(
        x = position.x,
        y = position.y,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        videoWidth = videoWidth,
        videoHeight = videoHeight,
        clamp = true,
    ),
)

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

private const val PRIMARY_BUTTON = 1
private const val ABSOLUTE_DRAG_SLOP_PX = 6f
