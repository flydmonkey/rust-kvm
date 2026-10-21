package com.rustkvm.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.rustkvm.host.service.HostService
import com.rustkvm.host.service.HostState
import androidx.core.content.ContextCompat
import com.rustkvm.host.usb.UsbSerialPorts
import com.rustkvm.host.usb.UvcVideoDevices
import kotlinx.coroutines.delay

/** 本机作为宿主机：经 OTG 接 CH9329，对局域网提供和 Windows 服务一样的接口。 */
@Composable
fun HostScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val state by HostService.state.collectAsState()
    val usbPorts = remember { UsbSerialPorts(context) }
    val videoDevices = remember { UvcVideoDevices(context) }
    var serialEntries by remember { mutableStateOf(emptyList<UsbSerialPorts.Entry>()) }
    var cameras by remember { mutableStateOf(emptyList<UvcVideoDevices.Entry>()) }
    var cameraDenied by remember { mutableStateOf(false) }

    // 授权框的结果和插拔都不另外监听：页面开着就每秒重新枚举一次，USB 设备本来就这么少
    LaunchedEffect(usbPorts, videoDevices) {
        while (true) {
            serialEntries = runCatching { usbPorts.entries() }.getOrDefault(emptyList())
            cameras = runCatching { videoDevices.entries() }.getOrDefault(emptyList())
            delay(1_000)
        }
    }

    // 系统要求先有相机权限才肯给 UVC 设备授权；没有的话 USB 授权框根本不弹
    var pendingCamera by remember { mutableStateOf<String?>(null) }
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraDenied = !granted
        val name = pendingCamera
        pendingCamera = null
        if (granted && name != null) videoDevices.requestPermission(name)
    }

    fun grantCamera(name: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            videoDevices.requestPermission(name)
        } else {
            pendingCamera = name
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    // 通知被拒也照样能跑，只是通知栏看不到；所以无论结果如何都启动
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { HostService.start(context) }

    fun startHost() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            HostService.start(context)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("返回") }
            Text("本机作为宿主机", style = MaterialTheme.typography.headlineSmall)
        }
        Text(
            "用 OTG 把 HDMI 采集卡和 CH9329 接到这台手机，启动后局域网里的浏览器或 Android 客户端就能连进来看画面、控制键鼠。" +
                "声音和 H.264 要等后续版本。",
            style = MaterialTheme.typography.bodyMedium,
        )

        when (val current = state) {
            HostState.Stopped -> Button(onClick = ::startHost, modifier = Modifier.fillMaxWidth()) {
                Text("启动宿主机")
            }

            HostState.Starting -> Text("正在启动…（首次启动要生成证书，可能需要几秒）")

            is HostState.Running -> {
                Text("运行中", style = MaterialTheme.typography.titleMedium)
                if (current.urls.isEmpty()) {
                    Text("没有拿到局域网地址，请确认手机已连上 Wi-Fi。", color = MaterialTheme.colorScheme.error)
                } else {
                    Text("在浏览器或 Android 客户端里输入：")
                    SelectionContainer {
                        Column {
                            current.urls.forEach { Text(it, style = MaterialTheme.typography.titleMedium) }
                        }
                    }
                }
                Text("首次连接会提示证书不受信任，核对指纹一致后信任即可：", style = MaterialTheme.typography.bodySmall)
                SelectionContainer {
                    Text(
                        current.fingerprint.chunked(4).joinToString(" "),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(onClick = { HostService.stop(context) }, modifier = Modifier.fillMaxWidth()) {
                    Text("停止宿主机")
                }
            }

            is HostState.Failed -> {
                Text("启动失败：${current.message}", color = MaterialTheme.colorScheme.error)
                Button(onClick = ::startHost, modifier = Modifier.fillMaxWidth()) { Text("重试") }
            }
        }

        HorizontalDivider()
        Text("USB 采集卡", style = MaterialTheme.typography.titleMedium)
        if (cameraDenied) {
            Text(
                "没有相机权限，系统不允许给采集卡授权。可以到系统设置 → 应用 → rust-kvm → 权限里打开相机。",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (cameras.isEmpty()) {
            Text(
                "没有找到采集卡。HDMI 采集卡（MS2109 一类）接到手机上，被控电脑的 HDMI 接到采集卡上。" +
                    "采集卡和 CH9329 要同时接的话需要带供电的 OTG Hub。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        cameras.forEach { entry ->
            UsbDeviceRow(entry.device.name, entry.device.index, entry.hasPermission) {
                grantCamera(entry.device.index)
            }
        }

        HorizontalDivider()
        Text("USB 串口设备", style = MaterialTheme.typography.titleMedium)
        if (serialEntries.isEmpty()) {
            Text(
                "没有找到 USB 串口。确认 OTG 线已接好、CH9329 的 USB 转串口一端插在手机上。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        serialEntries.forEach { entry ->
            UsbDeviceRow(entry.device.description ?: "USB 串口", entry.device.name, entry.hasPermission) {
                usbPorts.requestPermission(entry.device.name)
            }
        }
    }
}

@Composable
private fun UsbDeviceRow(title: String, path: String, granted: Boolean, onGrant: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title)
            Text(path, style = MaterialTheme.typography.bodySmall)
        }
        if (granted) {
            Text("已授权", color = MaterialTheme.colorScheme.primary)
        } else {
            Button(onClick = onGrant) { Text("授权") }
        }
    }
}
