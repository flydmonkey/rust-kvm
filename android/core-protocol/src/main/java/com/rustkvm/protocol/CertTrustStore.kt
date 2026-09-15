package com.rustkvm.protocol

import java.security.MessageDigest
import java.security.cert.X509Certificate

interface CertTrustStore {
    fun trustedFingerprint(hostPort: String): String?
    fun trust(hostPort: String, sha256Hex: String)
    fun clear(hostPort: String)
}

fun sha256Fingerprint(cert: X509Certificate): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(cert.encoded).joinToString("") { "%02x".format(it) }
}
