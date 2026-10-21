package com.rustkvm.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

sealed class VideoText {
    /** [fallbackReason] 非空表示配置要 H.264 但编码器起不来，服务端退回了 MJPEG。 */
    data class Codec(
        val codec: String,
        val avcCodec: String?,
        val fallbackReason: String? = null,
    ) : VideoText()
    data class Idle(val skipped: Long?) : VideoText()
    data object Unknown : VideoText()
}

sealed class SerialText {
    data class Status(val open: Boolean, val baudRate: Int?) : SerialText()
    data class Error(val message: String, val code: String?) : SerialText()
    data object Pong : SerialText()
    data object Unknown : SerialText()
}

data class AudioFormatHeader(
    val sampleRate: Int,
    val channels: Int,
    val format: String,
)

private val json = Json { ignoreUnknownKeys = true }

private fun jsonObject(text: String): JsonObject? =
    runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject

fun parseVideoText(text: String): VideoText {
    val obj = jsonObject(text) ?: return VideoText.Unknown
    return when (obj["op"]?.jsonPrimitive?.contentOrNull) {
        "codec" -> VideoText.Codec(
            codec = obj["codec"]?.jsonPrimitive?.contentOrNull ?: return VideoText.Unknown,
            avcCodec = obj["avcCodec"]?.jsonPrimitive?.contentOrNull,
            fallbackReason = obj["fallbackReason"]?.jsonPrimitive?.contentOrNull,
        )
        "idle" -> VideoText.Idle(
            skipped = obj["skipped"]?.jsonPrimitive?.longOrNull,
        )
        else -> VideoText.Unknown
    }
}

fun parseAudioText(text: String): AudioFormatHeader? {
    val obj = jsonObject(text) ?: return null
    if (obj["op"]?.jsonPrimitive?.contentOrNull != "format") return null
    val sampleRate = obj["sampleRate"]?.jsonPrimitive?.intOrNull ?: return null
    val channels = obj["channels"]?.jsonPrimitive?.intOrNull ?: return null
    val format = obj["format"]?.jsonPrimitive?.contentOrNull ?: return null
    return AudioFormatHeader(sampleRate = sampleRate, channels = channels, format = format)
}

fun parseAudioError(text: String): String? {
    val obj = jsonObject(text) ?: return null
    if (obj["op"]?.jsonPrimitive?.contentOrNull != "error") return null
    return obj["message"]?.jsonPrimitive?.contentOrNull
}

fun parseSerialText(text: String): SerialText {
    val obj = jsonObject(text) ?: return SerialText.Unknown
    return when (obj["op"]?.jsonPrimitive?.contentOrNull) {
        "status" -> SerialText.Status(
            open = obj["open"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: return SerialText.Unknown,
            baudRate = obj["baudRate"]?.jsonPrimitive?.intOrNull,
        )
        "error" -> SerialText.Error(
            message = obj["message"]?.jsonPrimitive?.contentOrNull ?: return SerialText.Unknown,
            code = obj["code"]?.jsonPrimitive?.contentOrNull,
        )
        "pong" -> SerialText.Pong
        else -> SerialText.Unknown
    }
}
