package com.rustkvm.host.serial

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 串口控制权：同一时间只有一个控制端。语义与 `src/serial_bridge.rs` 的 `SerialHub` 一致。
 *
 * 只有锁不够：锁能等不能赶，而占着串口的可能是另一台设备上、用户碰不到的页面。
 * 所以现任还带一个退位信号，接管请求敲它。
 */
class SerialHub(
    private val controlWaitMs: Long = CONTROL_WAIT_MS,
    private val takeoverWaitMs: Long = TAKEOVER_WAIT_MS,
) {
    private val seat = Mutex()
    private val lock = Any()
    private var evict: CompletableDeferred<Unit>? = null

    /** 拿不到返回 null。[takeover] 为真时先请现任退位。 */
    suspend fun claim(takeover: Boolean): ControlSeat? {
        val wait = if (takeover) {
            synchronized(lock) { evict }?.complete(Unit)
            takeoverWaitMs
        } else {
            // 不能一上来就拒：上一个控制端刚断开时，它还要下发全释放包、关口，
            // 刷新页面正好落在这个窗口里，秒拒会让人以为明明没人用却说被占了
            controlWaitMs
        }
        withTimeoutOrNull(wait) { seat.lock() } ?: return null

        val signal = CompletableDeferred<Unit>()
        synchronized(lock) { evict = signal }
        return ControlSeat(signal) {
            synchronized(lock) { if (evict === signal) evict = null }
            seat.unlock()
        }
    }

    companion object {
        const val CONTROL_WAIT_MS = 2_000L
        const val TAKEOVER_WAIT_MS = 5_000L
    }
}

class ControlSeat internal constructor(
    /** 完成表示有人要接管，现任该收摊了。每任一个新的，敲给上一任的信号不会误伤下一任。 */
    val evicted: Deferred<Unit>,
    private val onRelease: () -> Unit,
) {
    private val released = AtomicBoolean(false)

    fun release() {
        if (released.compareAndSet(false, true)) onRelease()
    }
}
