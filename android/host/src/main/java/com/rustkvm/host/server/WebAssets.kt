package com.rustkvm.host.server

import android.content.res.AssetManager
import io.ktor.http.ContentType
import java.io.FileNotFoundException

/** 打包进 APK 的 `web/`。抽成接口是为了 JVM 单测不依赖 AssetManager。 */
fun interface WebAssets {
    /** [path] 是相对 `web/` 的路径，不存在返回 null。 */
    fun read(path: String): ByteArray?
}

class AndroidWebAssets(private val assets: AssetManager) : WebAssets {
    override fun read(path: String): ByteArray? = try {
        assets.open("web/$path").use { it.readBytes() }
    } catch (_: FileNotFoundException) {
        null
    }
}

/** 把 URL 路径换成 `web/` 下的相对路径；带 `..` 之类想跳出目录的一律拒绝。 */
internal fun webAssetPath(urlPath: String): String? {
    val segments = urlPath.trim('/').split('/').filter { it.isNotEmpty() }
    if (segments.any { it == ".." || it == "." || it.contains('\\') }) return null
    return if (segments.isEmpty()) "index.html" else segments.joinToString("/")
}

internal fun contentTypeFor(path: String): ContentType =
    when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> ContentType.Text.Html.withParameter("charset", "utf-8")
        "js", "mjs" -> ContentType.Text.JavaScript.withParameter("charset", "utf-8")
        "css" -> ContentType.Text.CSS.withParameter("charset", "utf-8")
        "json", "webmanifest" -> ContentType.Application.Json
        "svg" -> ContentType.Image.SVG
        "png" -> ContentType.Image.PNG
        "jpg", "jpeg" -> ContentType.Image.JPEG
        "ico" -> ContentType("image", "x-icon")
        "wasm" -> ContentType("application", "wasm")
        "txt" -> ContentType.Text.Plain.withParameter("charset", "utf-8")
        else -> ContentType.Application.OctetStream
    }
