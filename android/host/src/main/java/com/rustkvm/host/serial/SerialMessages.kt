package com.rustkvm.host.serial

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 控制端发来的文本消息，字段与 `src/serial_bridge.rs` 的 `ClientOp` 一致。 */
sealed interface SerialClientOp {
    data class Open(val port: String, val baudRate: Int) : SerialClientOp
    data object Close : SerialClientOp
    data object Ping : SerialClientOp
}

/** 解析失败抛 [IllegalArgumentException]，消息直接回给客户端。 */
fun parseSerialClientOp(text: String): SerialClientOp {
    val obj: JsonObject = Json.parseToJsonElement(text).jsonObject
    return when (val op = obj["op"]?.jsonPrimitive?.contentOrNull) {
        "open" -> SerialClientOp.Open(
            port = requireNotNull(obj["port"]?.jsonPrimitive?.contentOrNull) { "缺少 port" },
            baudRate = requireNotNull(obj["baudRate"]?.jsonPrimitive?.intOrNull) { "缺少 baudRate" },
        )
        "close" -> SerialClientOp.Close
        "ping" -> SerialClientOp.Ping
        else -> throw IllegalArgumentException("未知的 op：$op")
    }
}

object SerialServerOp {
    fun status(open: Boolean, port: String?, baudRate: Int?): String = buildJsonObject {
        put("op", "status")
        put("open", open)
        if (port != null) put("port", port) else put("port", JsonNull)
        if (baudRate != null) put("baudRate", baudRate) else put("baudRate", JsonNull)
    }.toString()

    fun closed(): String = status(open = false, port = null, baudRate = null)

    fun error(message: String): String = buildJsonObject {
        put("op", "error")
        put("message", message)
    }.toString()

    /** 带 `busy` 让页面给出「接管」这个动作，而不是一条干巴巴的错误。 */
    fun busy(message: String): String = buildJsonObject {
        put("op", "error")
        put("message", message)
        put("code", "busy")
    }.toString()

    fun pong(serialUs: Long?): String = buildJsonObject {
        put("op", "pong")
        if (serialUs != null) put("serialUs", serialUs) else put("serialUs", JsonNull)
    }.toString()
}
