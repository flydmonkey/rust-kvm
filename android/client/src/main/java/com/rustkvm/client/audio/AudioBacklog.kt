package com.rustkvm.client.audio

import com.rustkvm.client.net.StreamMessage

/**
 * 采集时钟和手机声卡时钟永远差一点，网络又是成批到达，PCM 会在队列里慢慢积压，
 * 积多少延迟就永久多多少。积压超过 [maxChunks] 时丢掉最旧的 PCM 块，只留最新
 * [keepChunks] 块；格式头一律保留且保持顺序。
 *
 * 服务端每块是 10ms，默认值即「超过 200ms 裁回 50ms」，对应浏览器 worklet 的同一策略。
 */
fun trimAudioBacklog(
    batch: List<StreamMessage>,
    maxChunks: Int = MAX_BACKLOG_CHUNKS,
    keepChunks: Int = KEEP_CHUNKS,
): List<StreamMessage> {
    val chunks = batch.count { it is StreamMessage.Binary }
    if (chunks <= maxChunks) return batch
    var toDrop = chunks - keepChunks
    return batch.filter { message ->
        if (message is StreamMessage.Binary && toDrop > 0) {
            toDrop -= 1
            false
        } else {
            true
        }
    }
}

private const val MAX_BACKLOG_CHUNKS = 20
private const val KEEP_CHUNKS = 5
