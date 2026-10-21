package com.rustkvm.protocol

import java.net.URI

private const val DEFAULT_HTTPS_PORT = 8443

/** Canonical `scheme://host:port/` string wrapper (avoids okhttp in core-protocol). */
class HttpUrl internal constructor(private val value: String) {
    override fun toString(): String = value
}

fun normalizeServerUrl(input: String): HttpUrl {
    var trimmed = input.trim().trimEnd('/')
    if (!trimmed.contains("://")) {
        trimmed = "https://$trimmed"
    }
    val uri = URI(trimmed)
    val scheme = uri.scheme ?: "https"
    val host = uri.host ?: throw IllegalArgumentException("Invalid server address: $input")
    val port = if (uri.port > 0) uri.port else DEFAULT_HTTPS_PORT
    return HttpUrl("$scheme://$host:$port/")
}
