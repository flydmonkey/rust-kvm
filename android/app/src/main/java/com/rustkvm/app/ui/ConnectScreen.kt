package com.rustkvm.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rustkvm.app.data.RecentServersStore
import com.rustkvm.client.net.CertDecision
import com.rustkvm.client.net.KvmHttpClient
import com.rustkvm.protocol.CertTrustStore
import com.rustkvm.protocol.normalizeServerUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope

private data class PendingCertDecision(
    val decision: CertDecision,
    val result: CompletableDeferred<Boolean>,
)

@Composable
fun ConnectScreen(
    trustStore: CertTrustStore,
    recentServersStore: RecentServersStore,
    onConnected: (String) -> Unit,
    onOpenHost: () -> Unit = {},
) {
    var address by rememberSaveable { mutableStateOf("") }
    var recentServers by remember { mutableStateOf(recentServersStore.get()) }
    var connecting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var pendingCertDecision by remember { mutableStateOf<PendingCertDecision?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun askForCertificateTrust(decision: CertDecision): Boolean =
        withContext(Dispatchers.Main.immediate) {
            val request = PendingCertDecision(decision, CompletableDeferred())
            pendingCertDecision = request
            try {
                request.result.await()
            } finally {
                if (pendingCertDecision === request) pendingCertDecision = null
            }
        }

    fun connect(target: String = address) {
        if (connecting) return
        scope.launch {
            connecting = true
            errorMessage = null
            try {
                val normalizedUrl = normalizeServerUrl(target).toString()
                KvmHttpClient(
                    baseUrl = normalizedUrl,
                    trustStore = trustStore,
                    onCertDecision = ::askForCertificateTrust,
                ).getConfig()
                recentServers = recentServersStore.add(normalizedUrl)
                onConnected(normalizedUrl)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                errorMessage = error.message ?: "无法连接服务器"
            } finally {
                connecting = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("连接 rust-kvm", style = MaterialTheme.typography.headlineMedium)
        Text(
            "输入服务器地址。省略协议和端口时将使用 https:// 和 8443。",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = !connecting,
            singleLine = true,
            label = { Text("服务器地址") },
            placeholder = { Text("192.168.x.x 或完整 URL") },
            isError = errorMessage != null,
            supportingText = errorMessage?.let { message ->
                { Text("连接失败：$message") }
            },
        )
        Button(
            onClick = { connect() },
            enabled = address.isNotBlank() && !connecting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (connecting) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                    Text("正在连接…")
                }
            } else {
                Text("连接")
            }
        }

        if (recentServers.isNotEmpty()) {
            Text("最近服务器", style = MaterialTheme.typography.titleMedium)
            recentServers.forEach { server ->
                Text(
                    text = server,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !connecting) {
                            address = server
                            connect(server)
                        }
                        .padding(vertical = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
                HorizontalDivider()
            }
        }

        TextButton(onClick = onOpenHost, enabled = !connecting) {
            Text("本机作为宿主机（OTG 接 CH9329）")
        }
    }

    pendingCertDecision?.let { request ->
        CertTrustDialog(
            decision = request.decision,
            onTrust = { request.result.complete(true) },
            onReject = { request.result.complete(false) },
        )
    }
}
