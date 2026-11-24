package com.rustkvm.host.server

import com.rustkvm.host.tls.SelfSignedCert
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine

/** 宿主机的 HTTPS 服务。路由在 [hostModule]，这里只管 TLS 和启停。 */
class HostServer(
    private val deps: HostDependencies,
    private val cert: SelfSignedCert,
    val port: Int = DEFAULT_PORT,
) {
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    @Synchronized
    fun start() {
        check(server == null) { "服务已经在运行" }
        server = embeddedServer(
            factory = Netty,
            environment = applicationEnvironment(),
            configure = {
                sslConnector(
                    keyStore = cert.keyStore,
                    keyAlias = SelfSignedCert.KEY_ALIAS,
                    keyStorePassword = { SelfSignedCert.password() },
                    privateKeyPassword = { SelfSignedCert.password() },
                ) {
                    host = "0.0.0.0"
                    port = this@HostServer.port
                }
            },
            module = { hostModule(deps) },
        ).also { it.start(wait = false) }
    }

    @Synchronized
    fun stop() {
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
        server = null
    }

    /** 实际监听的端口（[port] 为 0 时由系统分配，测试用）。 */
    suspend fun boundPort(): Int =
        checkNotNull(server) { "服务没有运行" }.engine.resolvedConnectors().first().port

    companion object {
        const val DEFAULT_PORT = 8443
    }
}
