package com.rustkvm.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rustkvm.client.net.CertDecision
import com.rustkvm.client.net.KvmHttpClient
import com.rustkvm.protocol.AudioSettings
import com.rustkvm.protocol.CertTrustStore
import com.rustkvm.protocol.KvmConfig
import com.rustkvm.protocol.KvmDevices
import com.rustkvm.protocol.SerialSettings
import com.rustkvm.protocol.VideoSettings
import com.rustkvm.protocol.parseDevices
import java.io.IOException
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    baseUrl: String,
    trustStore: CertTrustStore,
    onBack: () -> Unit,
) {
    val client = remember(baseUrl, trustStore) {
        KvmHttpClient(
            baseUrl = baseUrl,
            trustStore = trustStore,
            onCertDecision = ::rejectSettingsTrustDecision,
        )
    }
    var form by remember(baseUrl) { mutableStateOf<SettingsForm?>(null) }
    var devices by remember(baseUrl) { mutableStateOf<KvmDevices?>(null) }
    var loading by remember(baseUrl) { mutableStateOf(true) }
    var saving by remember(baseUrl) { mutableStateOf(false) }
    var status by remember(baseUrl) { mutableStateOf<String?>(null) }
    var reloadKey by remember(baseUrl) { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(client, reloadKey) {
        loading = true
        status = null
        try {
            val config = client.getConfig()
            form = SettingsForm.from(config)
            devices = parseDevices(client.getDevicesJson())
        } catch (error: Exception) {
            status = "加载失败：${error.message ?: error.javaClass.simpleName}"
        } finally {
            loading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("基础设置", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("返回控制页") }
        }
        Text(
            "设备列表由宿主机枚举；文本框也允许保留当前未检测到的设备。",
            style = MaterialTheme.typography.bodySmall,
        )

        if (loading && form == null) {
            CircularProgressIndicator()
        }

        form?.let { current ->
            SettingsSection("画面") {
                DeviceField(
                    label = "视频设备",
                    value = current.videoDevice,
                    onValueChange = { form = current.copy(videoDevice = it) },
                    choices = devices?.video?.items?.map { "${it.name}（索引 ${it.index}）" }.orEmpty(),
                    problem = devices?.video?.error,
                )
                NumberField("宽度", current.width) { form = current.copy(width = it) }
                NumberField("高度", current.height) { form = current.copy(height = it) }
                NumberField("帧率", current.fps) { form = current.copy(fps = it) }
                ChoiceField(
                    label = "编码",
                    value = current.codec,
                    choices = listOf("mjpeg" to "MJPEG", "h264" to "H.264"),
                    onValueChange = { form = current.copy(codec = it) },
                )
                NumberField(
                    label = "H.264 码率 (kbps)",
                    value = current.bitrateKbps,
                    enabled = current.codec == "h264",
                ) { form = current.copy(bitrateKbps = it) }
            }

            SettingsSection("声音") {
                DeviceField(
                    label = "音频设备",
                    value = current.audioDevice,
                    onValueChange = { form = current.copy(audioDevice = it) },
                    choices = devices?.audio?.items?.map {
                        val rate = it.sampleRate?.let { rate -> "$rate Hz" } ?: "采样率未知"
                        val channels = it.channels?.let { count -> "$count 声道" } ?: "声道未知"
                        "${it.name}（$rate，$channels）"
                    }.orEmpty(),
                    problem = devices?.audio?.error,
                )
                BooleanField("启用音频", current.audioEnabled) {
                    form = current.copy(audioEnabled = it)
                }
                BooleanField("窄带音频", current.audioNarrowband) {
                    form = current.copy(audioNarrowband = it)
                }
            }

            SettingsSection("串口与鼠标") {
                DeviceField(
                    label = "串口",
                    value = current.serialPort,
                    onValueChange = { form = current.copy(serialPort = it) },
                    choices = devices?.serial?.items?.map {
                        it.description?.let { description -> "${it.name} — $description" } ?: it.name
                    }.orEmpty(),
                    problem = devices?.serial?.error,
                )
                NumberField("波特率", current.baudRate) { form = current.copy(baudRate = it) }
                ChoiceField(
                    label = "鼠标模式",
                    value = current.mouseMode,
                    choices = listOf("absolute" to "绝对", "relative" to "相对"),
                    onValueChange = { form = current.copy(mouseMode = it) },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !saving && !loading,
                    onClick = {
                        val config = runCatching { current.toConfig() }
                            .onFailure { status = it.message }
                            .getOrNull() ?: return@Button
                        scope.launch {
                            saving = true
                            status = "正在保存…"
                            try {
                                client.putConfig(config)
                                form = SettingsForm.from(config)
                                status = "已保存；返回控制页后会按新配置重新连接"
                            } catch (error: Exception) {
                                status = "保存失败：${error.message ?: error.javaClass.simpleName}"
                            } finally {
                                saving = false
                            }
                        }
                    },
                ) {
                    Text(if (saving) "保存中…" else "保存")
                }
                TextButton(
                    enabled = !saving && !loading,
                    onClick = { reloadKey += 1 },
                ) {
                    Text("重新加载")
                }
            }
        }

        status?.let {
            Text(
                text = it,
                color = if (it.startsWith("加载失败") || it.startsWith("保存失败") || it.startsWith("请输入")) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = { content() })
    HorizontalDivider()
}

@Composable
private fun DeviceField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    choices: List<String>,
    problem: String?,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    if (choices.isNotEmpty()) {
        Text("检测到：${choices.joinToString("；")}", style = MaterialTheme.typography.bodySmall)
    }
    problem?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    enabled: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

@Composable
private fun BooleanField(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.padding(top = 12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ChoiceField(
    label: String,
    value: String,
    choices: List<Pair<String, String>>,
    onValueChange: (String) -> Unit,
) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { (choice, text) ->
            FilterChip(
                selected = value == choice,
                onClick = { onValueChange(choice) },
                label = { Text(text) },
            )
        }
    }
}

internal data class SettingsForm(
    val videoDevice: String,
    val width: String,
    val height: String,
    val fps: String,
    val codec: String,
    val bitrateKbps: String,
    val audioDevice: String,
    val audioEnabled: Boolean,
    val audioNarrowband: Boolean,
    val serialPort: String,
    val baudRate: String,
    val mouseMode: String,
) {
    fun toConfig(): KvmConfig = KvmConfig(
        video = VideoSettings(
            device = videoDevice,
            width = width.positiveInt("宽度"),
            height = height.positiveInt("高度"),
            fps = fps.positiveInt("帧率"),
            codec = codec,
            bitrateKbps = bitrateKbps.positiveInt("码率"),
        ),
        audio = AudioSettings(
            device = audioDevice,
            enabled = audioEnabled,
            narrowband = audioNarrowband,
        ),
        serial = SerialSettings(
            port = serialPort,
            baudRate = baudRate.positiveInt("波特率"),
            mouseMode = mouseMode,
        ),
    )

    companion object {
        fun from(config: KvmConfig): SettingsForm = SettingsForm(
            videoDevice = config.video.device,
            width = config.video.width.toString(),
            height = config.video.height.toString(),
            fps = config.video.fps.toString(),
            codec = config.video.codec,
            bitrateKbps = config.video.bitrateKbps.toString(),
            audioDevice = config.audio.device,
            audioEnabled = config.audio.enabled,
            audioNarrowband = config.audio.narrowband,
            serialPort = config.serial.port,
            baudRate = config.serial.baudRate.toString(),
            mouseMode = config.serial.mouseMode,
        )
    }
}

private fun String.positiveInt(label: String): Int =
    toIntOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("请输入有效的$label")

private fun rejectSettingsTrustDecision(decision: CertDecision): Boolean =
    when (decision) {
        CertDecision.Trusted -> true
        is CertDecision.NeedsUserTrust -> throw IOException("服务器证书不在信任记录中")
        is CertDecision.Mismatch -> throw IOException("服务器证书已变更")
    }
