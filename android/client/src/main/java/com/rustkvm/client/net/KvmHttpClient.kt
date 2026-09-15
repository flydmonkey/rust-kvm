package com.rustkvm.client.net

import com.rustkvm.protocol.CertTrustStore
import com.rustkvm.protocol.KvmConfig
import com.rustkvm.protocol.ProtocolPaths
import com.rustkvm.protocol.encodeConfig
import com.rustkvm.protocol.normalizeServerUrl
import com.rustkvm.protocol.parseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager

class KvmHttpClient(
    baseUrl: String,
    private val trustStore: CertTrustStore,
    private val onCertDecision: suspend (CertDecision) -> Boolean,
) {
    internal val baseUrl: HttpUrl = normalizeServerUrl(baseUrl).toString().toHttpUrl().also(::requireHttpsScheme)
    internal val hostPort: String = "${this.baseUrl.host}:${this.baseUrl.port}"
    private val trustDecisionMutex = Mutex()

    internal val okHttpClient: OkHttpClient

    init {
        val trustManager = TofuTrustManager(hostPort, trustStore)
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }
        okHttpClient = OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            // The pinned certificate is the server identity, including for IP/self-signed certificates.
            .hostnameVerifier { _, _ -> true }
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    suspend fun getConfig(): KvmConfig {
        val request = Request.Builder().url(url(ProtocolPaths.API_CONFIG)).get().build()
        return executeWithTrustRetry(request).use { response ->
            parseConfig(response.requireBody())
        }
    }

    suspend fun putConfig(config: KvmConfig) {
        val body = encodeConfig(config).toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(url(ProtocolPaths.API_CONFIG))
            .put(body)
            .build()
        executeWithTrustRetry(request).use { response ->
            response.requireSuccessful()
        }
    }

    suspend fun getDevicesJson(): String {
        val request = Request.Builder().url(url(ProtocolPaths.API_DEVICES)).get().build()
        return executeWithTrustRetry(request).use { response ->
            response.requireBody()
        }
    }

    fun newSession(takeoverSerial: Boolean = false): KvmSession =
        KvmSession(this, takeoverSerial)

    internal fun url(path: String): HttpUrl =
        baseUrl.resolve(path.removePrefix("/"))
            ?: throw IllegalArgumentException("Invalid protocol path: $path")

    internal suspend fun acceptCertificateFrom(error: Throwable): Boolean {
        val decision = error.findTofuDecision() ?: return false
        val newFingerprint = when (decision) {
            CertDecision.Trusted -> return true
            is CertDecision.NeedsUserTrust -> decision.fingerprint
            is CertDecision.Mismatch -> decision.new
        }

        return trustDecisionMutex.withLock {
            if (trustStore.trustedFingerprint(hostPort).equals(newFingerprint, ignoreCase = true)) {
                return@withLock true
            }
            if (!onCertDecision(decision)) {
                return@withLock false
            }
            trustStore.trust(hostPort, newFingerprint)
            true
        }
    }

    private suspend fun executeWithTrustRetry(request: Request): Response =
        withContext(Dispatchers.IO) {
            try {
                okHttpClient.newCall(request).execute().requireSuccessful()
            } catch (firstFailure: IOException) {
                if (!acceptCertificateFrom(firstFailure)) {
                    throw firstFailure
                }
                okHttpClient.newCall(request).execute().requireSuccessful()
            }
        }

    private fun Response.requireBody(): String {
        requireSuccessful()
        return body?.string() ?: throw IOException("HTTP $code returned an empty body")
    }

    private fun Response.requireSuccessful(): Response {
        if (!isSuccessful) {
            close()
            throw IOException("HTTP request failed with status $code")
        }
        return this
    }

    private fun Throwable.findTofuDecision(): CertDecision? {
        var current: Throwable? = this
        while (current != null) {
            if (current is TofuCertificateException) return current.decision
            current = current.cause
        }
        return null
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        internal fun requireHttpsScheme(url: HttpUrl) {
            if (!url.isHttps) {
                throw IllegalArgumentException("仅支持 https")
            }
        }
    }
}
