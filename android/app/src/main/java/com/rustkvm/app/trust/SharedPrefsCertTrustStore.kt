package com.rustkvm.app.trust

import android.content.Context
import com.rustkvm.protocol.CertTrustStore

class SharedPrefsCertTrustStore(context: Context) : CertTrustStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun trustedFingerprint(hostPort: String): String? =
        prefs.getString(prefKey(hostPort), null)

    override fun trust(hostPort: String, sha256Hex: String) {
        prefs.edit().putString(prefKey(hostPort), sha256Hex.lowercase()).apply()
    }

    override fun clear(hostPort: String) {
        prefs.edit().remove(prefKey(hostPort)).apply()
    }

    private fun prefKey(hostPort: String): String = "cert_$hostPort"

    private companion object {
        const val PREFS_NAME = "rust_kvm_cert_trust"
    }
}
