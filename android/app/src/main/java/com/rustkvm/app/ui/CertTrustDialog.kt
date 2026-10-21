package com.rustkvm.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.rustkvm.client.net.CertDecision

@Composable
fun CertTrustDialog(
    decision: CertDecision,
    onTrust: () -> Unit,
    onReject: () -> Unit,
) {
    val mismatch = decision is CertDecision.Mismatch
    AlertDialog(
        onDismissRequest = onReject,
        title = {
            Text(if (mismatch) "服务器证书已更改" else "信任服务器证书？")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (decision) {
                    is CertDecision.NeedsUserTrust -> {
                        Text("这是首次连接该服务器。请核对证书信息后再信任。")
                        Text("证书主题：${decision.subject}")
                        Fingerprint(label = "SHA-256 指纹", value = decision.fingerprint)
                    }

                    is CertDecision.Mismatch -> {
                        Text(
                            text = "警告：当前证书与此前信任的证书不一致。仅在确认服务器证书确实已更新时继续。",
                            color = MaterialTheme.colorScheme.error,
                        )
                        Fingerprint(label = "原指纹", value = decision.old)
                        Fingerprint(label = "新指纹", value = decision.new)
                    }

                    CertDecision.Trusted -> Unit
                }
            }
        },
        confirmButton = {
            Button(onClick = onTrust) {
                Text(if (mismatch) "信任新证书" else "信任并连接")
            }
        },
        dismissButton = {
            TextButton(onClick = onReject) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun Fingerprint(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value.chunked(2).joinToString(":"), style = MaterialTheme.typography.bodySmall)
    }
}
