package com.hdmi2usb.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/** 监听采集卡拔出；插入场景由 manifest 的 USB_DEVICE_ATTACHED intent-filter 承担。 */
class PlugMonitor(
    private val context: Context,
    private val onDetached: () -> Unit,
) {
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            if (device != null && device.isVideoDevice()) onDetached()
        }
    }

    private val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)

    fun register() {
        if (registered) return
        context.registerReceiver(receiver, filter)
        registered = true
    }

    fun unregister() {
        if (!registered) return
        context.unregisterReceiver(receiver)
        registered = false
    }

    private fun UsbDevice.isVideoDevice(): Boolean {
        // 视频控制类（0x0E）或 IAD 封装的摄像头（0xEF / subclass 2）
        if (deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
        if (deviceClass == 0xEF && deviceSubclass == 2) return true
        for (i in 0 until interfaceCount) {
            if (getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO) return true
        }
        return false
    }
}
