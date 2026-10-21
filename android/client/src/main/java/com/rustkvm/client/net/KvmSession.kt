package com.rustkvm.client.net

import com.rustkvm.client.hid.SerialConnection
import com.rustkvm.protocol.ProtocolPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.Closeable
import java.io.IOException

class KvmSession(
    private val httpClient: KvmHttpClient,
    private val takeoverSerial: Boolean = false,
) : Closeable, SerialConnection {
    // 视频和音频不在这里限容：H.264 一帧都不能悄悄丢，丢哪些只有消费方按编码判断得了，
    // 所以消费方用 receiveBatch 一次取走全部积压再决定。
    private val videoChannel = Channel<StreamMessage>(Channel.UNLIMITED)
    private val audioChannel = Channel<StreamMessage>(Channel.UNLIMITED)
    private val serialTextChannel = Channel<String>(Channel.UNLIMITED)
    private val serialBinaryChannel = Channel<ByteArray>(
        capacity = SERIAL_BINARY_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val videoMessages: ReceiveChannel<StreamMessage> = videoChannel
    val audioMessages: ReceiveChannel<StreamMessage> = audioChannel
    override val serialText: Flow<String> = serialTextChannel.receiveAsFlow()
    override val serialBinary: Flow<ByteArray> = serialBinaryChannel.receiveAsFlow()

    private val connectMutex = Mutex()
    private val stateLock = Any()
    private val termination = CompletableDeferred<Throwable?>()
    private val sockets = mutableMapOf<SocketKind, WebSocket>()
    private var closed = false

    override suspend fun connect(): Unit = connectMutex.withLock {
        synchronized(stateLock) {
            check(!closed) { "Session is closed" }
            check(sockets.isEmpty()) { "Session is already connected" }
        }

        try {
            coroutineScope {
                SocketKind.entries.map { kind ->
                    async { openWebSocket(kind) }
                }.awaitAll()
            }
        } catch (error: Throwable) {
            terminate(error)
            throw error
        }
        Unit
    }

    override fun sendSerialText(text: String): Boolean =
        synchronized(stateLock) { sockets[SocketKind.SERIAL] }?.send(text) ?: false

    override fun sendSerialBinary(data: ByteArray): Boolean =
        synchronized(stateLock) { sockets[SocketKind.SERIAL] }?.send(data.toByteString()) ?: false

    /**
     * 只重连画面这一路。服务端把重连当作新观看者，会立刻强制一个 IDR；否则 H.264
     * 解码器出错或积压后要等下一个自然 IDR，GOP 是 10 秒。键鼠和声音不受影响。
     *
     * 调用方必须是 [videoMessages] 的唯一消费者：旧连接留在队列里的消息在这里清掉。
     */
    suspend fun reopenVideo() {
        val old = synchronized(stateLock) {
            check(!closed) { "Session is closed" }
            sockets.remove(SocketKind.VIDEO)
        }
        old?.cancel()
        while (videoChannel.tryReceive().isSuccess) Unit
        try {
            openWebSocket(SocketKind.VIDEO)
        } catch (error: Throwable) {
            terminate(error)
            throw error
        }
    }

    suspend fun awaitDisconnect(): Throwable? = termination.await()

    fun disconnect() {
        val openSockets = markClosed()
        openSockets.forEach { it.close(NORMAL_CLOSE_CODE, "Client disconnected") }
        closeChannels()
        termination.complete(null)
    }

    override fun close() = disconnect()

    private suspend fun openWebSocket(kind: SocketKind): WebSocket {
        var trustRetried = false
        while (true) {
            val opened = CompletableDeferred<WebSocket>()
            val request = Request.Builder().url(socketUrl(kind)).build()
            val socket = httpClient.okHttpClient.newWebSocket(
                request,
                listener(kind, opened),
            )
            try {
                return opened.await()
            } catch (cancelled: CancellationException) {
                socket.cancel()
                throw cancelled
            } catch (error: Throwable) {
                socket.cancel()
                if (trustRetried || !httpClient.acceptCertificateFrom(error)) {
                    throw error
                }
                trustRetried = true
            }
        }
    }

    private fun socketUrl(kind: SocketKind): okhttp3.HttpUrl {
        val url = httpClient.url(kind.path)
        return if (kind == SocketKind.SERIAL && takeoverSerial) {
            url.newBuilder().addQueryParameter("takeover", "1").build()
        } else {
            url
        }
    }

    private fun listener(
        kind: SocketKind,
        opened: CompletableDeferred<WebSocket>,
    ): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val shouldCancel = synchronized(stateLock) {
                if (closed) {
                    true
                } else {
                    sockets[kind] = webSocket
                    false
                }
            }
            if (shouldCancel) {
                webSocket.cancel()
                opened.completeExceptionally(IOException("Session closed while connecting"))
            } else {
                opened.complete(webSocket)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // reopenVideo 之后旧连接可能还在吐消息，混进来会让新解码器先吃到旧码流
            if (!isCurrentSocket(kind, webSocket)) return
            when (kind) {
                SocketKind.VIDEO -> videoChannel.trySend(StreamMessage.Text(text))
                SocketKind.AUDIO -> audioChannel.trySend(StreamMessage.Text(text))
                SocketKind.SERIAL -> serialTextChannel.trySend(text)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (!isCurrentSocket(kind, webSocket)) return
            val data = bytes.toByteArray()
            when (kind) {
                SocketKind.VIDEO -> videoChannel.trySend(StreamMessage.Binary(data))
                SocketKind.AUDIO -> audioChannel.trySend(StreamMessage.Binary(data))
                SocketKind.SERIAL -> serialBinaryChannel.trySend(data)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!opened.completeExceptionally(t)) {
                // 被 reopenVideo 换下的旧连接 cancel 后也会走到这里，不能连带拆掉整个会话
                if (!isCurrentSocket(kind, webSocket)) return
                if (kind == SocketKind.AUDIO) {
                    terminateAudio(webSocket, t)
                } else {
                    terminate(t)
                }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (isCurrentSocket(kind, webSocket)) {
                val error = IOException("${kind.name.lowercase()} WebSocket closed: $code $reason")
                if (kind == SocketKind.AUDIO) {
                    terminateAudio(webSocket, null)
                } else {
                    terminate(error)
                }
            }
        }
    }

    private fun isCurrentSocket(kind: SocketKind, socket: WebSocket): Boolean =
        synchronized(stateLock) { !closed && sockets[kind] === socket }

    private fun terminate(error: Throwable) {
        val openSockets = markClosed()
        openSockets.forEach(WebSocket::cancel)
        closeChannels(error)
        termination.complete(error)
    }

    private fun terminateAudio(socket: WebSocket, error: Throwable?) {
        val removed = synchronized(stateLock) {
            if (!closed && sockets[SocketKind.AUDIO] === socket) {
                sockets.remove(SocketKind.AUDIO)
                true
            } else {
                false
            }
        }
        if (!removed) return
        audioChannel.close(error)
    }

    private fun markClosed(): List<WebSocket> = synchronized(stateLock) {
        if (closed) return@synchronized emptyList()
        closed = true
        val result = sockets.values.toList()
        sockets.clear()
        result
    }

    private fun closeChannels(error: Throwable? = null) {
        videoChannel.close(error)
        audioChannel.close(error)
        serialTextChannel.close(error)
        serialBinaryChannel.close(error)
    }

    private enum class SocketKind(val path: String) {
        VIDEO(ProtocolPaths.WS_VIDEO),
        AUDIO(ProtocolPaths.WS_AUDIO),
        SERIAL(ProtocolPaths.WS_SERIAL),
    }

    private companion object {
        const val NORMAL_CLOSE_CODE = 1000
        const val SERIAL_BINARY_CAPACITY = 64
    }
}
