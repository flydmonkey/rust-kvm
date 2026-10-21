package com.rustkvm.client.net

import kotlinx.coroutines.channels.ReceiveChannel

/**
 * 一条 WebSocket 上的 Text 与 Binary 必须共用一个队列：编码头 / 格式头只对它之后的
 * 帧生效，拆成两个队列就无法知道一帧是在新头之前还是之后到的。
 */
sealed interface StreamMessage {
    data class Text(val text: String) : StreamMessage
    class Binary(val data: ByteArray) : StreamMessage
}

/**
 * 挂起等到至少一条，再把已经到了的全部取走。消费方据此看到积压了多少，
 * 才能像浏览器那样丢掉过时的帧，而不是逐条处理到延迟越积越大。
 *
 * 通道正常关闭返回 null；带错误关闭则抛出该错误。
 */
suspend fun <T> ReceiveChannel<T>.receiveBatch(): List<T>? {
    val first = receiveCatching()
    if (first.isClosed) {
        first.exceptionOrNull()?.let { throw it }
        return null
    }
    val batch = mutableListOf(first.getOrThrow())
    while (true) {
        batch += tryReceive().getOrNull() ?: break
    }
    return batch
}
