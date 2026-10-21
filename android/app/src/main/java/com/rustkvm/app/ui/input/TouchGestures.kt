package com.rustkvm.app.ui.input

import androidx.compose.ui.geometry.Offset

interface PointerSink {
    fun absolute(point: NormalizedTouch, buttons: Int)
    fun relative(dx: Int, dy: Int, buttons: Int)

    /** 正数向上滚，与 CH9329 协议一致。 */
    fun scroll(notches: Int)
}

data class TouchPoint(val id: Long, val position: Offset, val pressed: Boolean)

/**
 * 把一次触摸手势翻译成鼠标动作。手机上没有右键和滚轮，所以：
 *
 * - 单指点按：左键单击
 * - 单指长按：右键单击
 * - 单指拖动：绝对模式下按住左键拖拽；相对模式下只挪光标（触控板），不按键
 * - 双指上下拖动：滚轮，内容跟着手指走
 * - 双指轻点：右键单击
 *
 * 绝对模式下按下手指只先把光标挪过去，等确定是点按、拖动还是长按之后才按键——
 * 一按下就按左键的话，长按右键和双指手势都会先误触一次左键。
 */
class TouchGestureInterpreter(
    private val absoluteMouse: Boolean,
    private val normalize: (position: Offset, clamp: Boolean) -> NormalizedTouch?,
    private val sink: PointerSink,
    private val slopPx: Float = ABSOLUTE_DRAG_SLOP_PX,
    private val scrollPxPerNotch: Float = SCROLL_PX_PER_NOTCH,
) {
    private enum class Mode { Idle, Pending, Moving, Dragging, LongPressed, TwoFinger, Done }

    private var mode = Mode.Idle
    private var primaryId = 0L
    private var downPosition = Offset.Zero
    private var previous = Offset.Zero
    private var initialPoint: NormalizedTouch? = null
    private val relativeMotion = RelativeMotionAccumulator()

    private var scrollIds = 0L to 0L
    private var scrollStart = Offset.Zero
    private var scrollLast = Offset.Zero
    private var scrollResidual = 0f
    private var scrollMoved = false

    /** 还没确定手势类型、需要计时长按的阶段。 */
    val awaitingLongPress: Boolean
        get() = mode == Mode.Pending

    var finished: Boolean = false
        private set

    fun onEvent(pointers: List<TouchPoint>) {
        val pressed = pointers.filter { it.pressed }
        if (mode == Mode.Idle) {
            start(pressed.firstOrNull() ?: return)
            if (pressed.size >= 2) startTwoFinger(pressed)
            finished = pressed.isEmpty()
            return
        }

        when (mode) {
            Mode.Pending, Mode.Moving -> onSingleFinger(pointers, pressed)
            Mode.Dragging -> onDragging(pointers)
            Mode.TwoFinger -> onTwoFinger(pointers)
            Mode.Idle, Mode.LongPressed, Mode.Done -> Unit
        }
        finished = pressed.isEmpty()
    }

    fun onLongPress() {
        if (mode != Mode.Pending) return
        click(RIGHT_BUTTON)
        mode = Mode.LongPressed
    }

    private fun start(point: TouchPoint) {
        mode = Mode.Pending
        primaryId = point.id
        downPosition = point.position
        previous = point.position
        if (absoluteMouse) {
            initialPoint = normalize(point.position, false)
            initialPoint?.let { sink.absolute(it, 0) }
        }
    }

    private fun onSingleFinger(pointers: List<TouchPoint>, pressed: List<TouchPoint>) {
        if (pressed.size >= 2) {
            startTwoFinger(pressed)
            return
        }
        val primary = pointers.firstOrNull { it.id == primaryId } ?: return
        if (!primary.pressed) {
            if (mode == Mode.Pending) click(LEFT_BUTTON)
            mode = Mode.Done
            return
        }
        val beyondSlop = hasExceededDragSlop(downPosition, primary.position, slopPx)
        if (absoluteMouse) {
            val start = initialPoint
            if (beyondSlop && start != null) {
                mode = Mode.Dragging
                sink.absolute(start, LEFT_BUTTON)
                sink.absolute(clamped(primary.position), LEFT_BUTTON)
            }
            return
        }
        if (mode == Mode.Pending && beyondSlop) mode = Mode.Moving
        if (mode == Mode.Moving) {
            val delta = primary.position - previous
            previous = primary.position
            val rounded = relativeMotion.consume(delta.x, delta.y)
            if (rounded.dx != 0 || rounded.dy != 0) sink.relative(rounded.dx, rounded.dy, 0)
        }
    }

    private fun onDragging(pointers: List<TouchPoint>) {
        val primary = pointers.firstOrNull { it.id == primaryId } ?: return
        val point = clamped(primary.position)
        if (primary.pressed) {
            sink.absolute(point, LEFT_BUTTON)
        } else {
            sink.absolute(point, 0)
            mode = Mode.Done
        }
    }

    private fun startTwoFinger(pressed: List<TouchPoint>) {
        val (first, second) = pressed[0] to pressed[1]
        mode = Mode.TwoFinger
        scrollIds = first.id to second.id
        scrollStart = centroid(first.position, second.position)
        scrollLast = scrollStart
        scrollResidual = 0f
        scrollMoved = false
    }

    private fun onTwoFinger(pointers: List<TouchPoint>) {
        val first = pointers.firstOrNull { it.id == scrollIds.first }
        val second = pointers.firstOrNull { it.id == scrollIds.second }
        if (first == null || second == null || !first.pressed || !second.pressed) {
            if (!scrollMoved) click(RIGHT_BUTTON)
            mode = Mode.Done
            return
        }
        val center = centroid(first.position, second.position)
        if (hasExceededDragSlop(scrollStart, center, slopPx)) scrollMoved = true
        scrollResidual += center.y - scrollLast.y
        scrollLast = center
        val notches = (scrollResidual / scrollPxPerNotch).toInt()
        if (notches != 0) {
            scrollResidual -= notches * scrollPxPerNotch
            sink.scroll(notches)
        }
    }

    private fun click(button: Int) {
        if (absoluteMouse) {
            val point = initialPoint ?: return
            sink.absolute(point, button)
            sink.absolute(point, 0)
        } else {
            sink.relative(0, 0, button)
            sink.relative(0, 0, 0)
        }
    }

    private fun clamped(position: Offset): NormalizedTouch =
        checkNotNull(normalize(position, true))

    private fun centroid(a: Offset, b: Offset) = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
}

internal const val LEFT_BUTTON = 0x01
internal const val RIGHT_BUTTON = 0x02
internal const val ABSOLUTE_DRAG_SLOP_PX = 6f
internal const val SCROLL_PX_PER_NOTCH = 40f
