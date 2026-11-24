package com.rustkvm.host.tls

import com.rustkvm.protocol.sha256Fingerprint
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date

/**
 * 宿主机的自签证书，与 `src/certs.rs` 同一套规矩。
 *
 * HTTPS 在这里不是为了安全而是硬性依赖：浏览器的剪贴板等 API 只在 secure context 下可用。
 * 证书必须覆盖所有可能被用来访问的地址，本机 IP 变了（换 Wi-Fi）就得重签，否则浏览器
 * 会因为 SAN 不匹配直接拒连。所以签发时用的名字列表一起存下来做比对。
 */
class SelfSignedCert private constructor(
    val keyStore: KeyStore,
    val certificate: X509Certificate,
    val names: List<String>,
) {
    val certPem: String
        get() = buildString {
            append("-----BEGIN CERTIFICATE-----\n")
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(certificate.encoded).let(::append)
            append("\n-----END CERTIFICATE-----\n")
        }

    /** 与客户端首次信任对话框里的指纹同一个算法和格式，方便用户逐字对照。 */
    val sha256Fingerprint: String
        get() = sha256Fingerprint(certificate)

    companion object {
        const val KEY_ALIAS = "rustkvm"

        // 每次给一份新的：Ktor 用完会把传进去的数组清零，共享一份的话第二次启动服务就打不开密钥库
        fun password(): CharArray = "rustkvm".toCharArray()

        fun loadOrCreate(dir: File, names: List<String> = subjectAltNames()): SelfSignedCert {
            val storeFile = File(dir, "host.p12")
            val namesFile = File(dir, "sans.txt")
            val fingerprint = names.joinToString("\n")
            if (storeFile.exists() && namesFile.exists() && namesFile.readText() == fingerprint) {
                val loaded = runCatching { load(storeFile, names) }.getOrNull()
                if (loaded != null) return loaded
            }
            val created = generate(names)
            dir.mkdirs()
            storeFile.outputStream().use { created.keyStore.store(it, password()) }
            namesFile.writeText(fingerprint)
            return created
        }

        /** localhost、127.0.0.1，以及全部非回环、非链路本地的 IPv4。 */
        fun subjectAltNames(): List<String> {
            val addresses = runCatching {
                NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp }
                    .flatMap { it.inetAddresses.toList() }
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    .mapNotNull { it.hostAddress }
            }.getOrDefault(emptyList())
            return (listOf("localhost", "127.0.0.1") + addresses).distinct().sorted()
        }

        private fun load(file: File, names: List<String>): SelfSignedCert {
            val store = KeyStore.getInstance("PKCS12")
            file.inputStream().use { store.load(it, password()) }
            val certificate = store.getCertificate(KEY_ALIAS) as X509Certificate
            certificate.checkValidity()
            return SelfSignedCert(store, certificate, names)
        }

        private fun generate(names: List<String>): SelfSignedCert {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val subject = X500Name("CN=rust-kvm Android host")
            val now = System.currentTimeMillis()
            val builder = JcaX509v3CertificateBuilder(
                subject,
                BigInteger(64, SecureRandom()),
                Date(now - ONE_DAY_MS),
                Date(now + VALIDITY_MS),
                subject,
                keyPair.public,
            )
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
            builder.addExtension(
                Extension.subjectAlternativeName,
                false,
                GeneralNames(names.map(::generalName).toTypedArray()),
            )
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
            val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            // Android 不支持 JKS，只能用 PKCS12
            val store = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry(KEY_ALIAS, keyPair.private, password(), arrayOf(certificate))
            }
            return SelfSignedCert(store, certificate, names)
        }

        private fun generalName(name: String): GeneralName =
            if (name.isNotEmpty() && name.all { it.isDigit() || it == '.' }) {
                GeneralName(GeneralName.iPAddress, name)
            } else {
                GeneralName(GeneralName.dNSName, name)
            }

        private const val ONE_DAY_MS = 24L * 60 * 60 * 1000

        // 不设太短：过期重签会让所有已信任的客户端突然要重新确认指纹
        private const val VALIDITY_MS = 10L * 365 * ONE_DAY_MS
    }
}
