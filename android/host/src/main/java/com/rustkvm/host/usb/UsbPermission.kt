package com.rustkvm.host.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

private const val ACTION_USB_PERMISSION = "com.rustkvm.host.USB_PERMISSION"

/** 弹系统的 USB 授权框。结果不用回调拿：宿主机页面定时刷新列表就能看到。 */
internal fun requestUsbPermission(context: Context, manager: UsbManager, device: UsbDevice) {
    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
    manager.requestPermission(device, PendingIntent.getBroadcast(context, 0, intent, flags))
}
