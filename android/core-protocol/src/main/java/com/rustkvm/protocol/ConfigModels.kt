package com.rustkvm.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class KvmConfig(
    val video: VideoSettings = VideoSettings(),
    val audio: AudioSettings = AudioSettings(),
    val serial: SerialSettings = SerialSettings(),
) {
    companion object {
        val DEFAULT = KvmConfig()
    }
}

@Serializable
data class VideoSettings(
    val device: String = "USB Video",
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 30,
    val codec: String = "mjpeg",
    val bitrateKbps: Int = 4000,
)

@Serializable
data class AudioSettings(
    val device: String = "Digital Audio Interface",
    val enabled: Boolean = true,
    val narrowband: Boolean = false,
)

@Serializable
data class SerialSettings(
    val port: String = "",
    val baudRate: Int = 115200,
    val mouseMode: String = "absolute",
)

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun parseConfig(text: String): KvmConfig = json.decodeFromString(text)
fun encodeConfig(config: KvmConfig): String = json.encodeToString(config)
