package com.rustkvm.host.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.rustkvm.host.config.HostConfigStore
import com.rustkvm.host.serial.SerialHub
import com.rustkvm.host.server.AndroidWebAssets
import com.rustkvm.host.server.HostDependencies
import com.rustkvm.host.server.HostServer
import com.rustkvm.host.tls.SelfSignedCert
import com.rustkvm.host.usb.UsbSerialPorts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface HostState {
    data object Stopped : HostState
    data object Starting : HostState
    data class Running(val urls: List<String>, val fingerprint: String) : HostState
    data class Failed(val message: String) : HostState
}

/**
 * 宿主机前台服务。放在前台服务里是因为系统会冻结后台进程的网络，而宿主机要在
 * 屏幕关着、App 不在前台时继续接客；通知栏常驻也让用户知道串口被占着。
 */
class HostService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: HostServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification("正在启动…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        if (server == null) scope.launch { startHost() }
        return START_NOT_STICKY
    }

    private fun startHost() {
        mutableState.value = HostState.Starting
        try {
            val cert = SelfSignedCert.loadOrCreate(File(filesDir, "tls"))
            val deps = HostDependencies(
                config = HostConfigStore(File(filesDir, "config.json")),
                serialPorts = UsbSerialPorts(this),
                serialHub = SerialHub(),
                certPem = { cert.certPem },
                webAssets = AndroidWebAssets(assets),
            )
            val started = HostServer(deps, cert).also { it.start() }
            server = started
            acquireLocks()
            val urls = cert.names
                .filter { it != "localhost" && it != "127.0.0.1" }
                .map { "https://$it:${started.port}" }
            mutableState.value = HostState.Running(urls, cert.sha256Fingerprint)
            updateNotification(urls.firstOrNull()?.let { "访问地址 $it" } ?: "没有可用的局域网地址")
        } catch (error: Exception) {
            Log.e(TAG, "宿主机启动失败", error)
            mutableState.value = HostState.Failed(error.message ?: "宿主机启动失败")
            stopSelf()
        }
    }

    override fun onDestroy() {
        runCatching { server?.stop() }
        server = null
        releaseLocks()
        scope.cancel()
        if (mutableState.value !is HostState.Failed) mutableState.value = HostState.Stopped
        super.onDestroy()
    }

    // 屏幕关了也要继续收发：CPU 和 Wi-Fi 都得撑住，否则键鼠延迟会突然涨到秒级
    private fun acquireLocks() {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rustkvm:host").apply { acquire() }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "rustkvm:host").apply { acquire() }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "宿主机", NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, HostService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("rust-kvm 宿主机运行中")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    companion object {
        private const val TAG = "HostService"
        private const val CHANNEL_ID = "host"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.rustkvm.host.STOP"

        private val mutableState = MutableStateFlow<HostState>(HostState.Stopped)
        val state: StateFlow<HostState> = mutableState.asStateFlow()

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, HostService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, HostService::class.java).setAction(ACTION_STOP))
        }
    }
}
