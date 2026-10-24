package com.hdmi2usb.monitor

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice

/** USB 视频设备判定：UvcController.findDevice 枚举与 PlugMonitor 拔卡广播共用，避免两处判定漂移。 */
object UsbDevices {

    fun isVideo(device: UsbDevice): Boolean =
        isVideo(
            device.deviceClass,
            device.deviceSubclass,
            List(device.interfaceCount) { device.getInterface(it).interfaceClass },
        )

    /** 纯函数判定（便于 JVM 单测）：设备 class 0x0E，或 IAD 封装（0xEF / subclass 2），或任一 interface 为 0x0E。 */
    fun isVideo(deviceClass: Int, deviceSubclass: Int, interfaceClasses: List<Int>): Boolean {
        if (deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
        if (deviceClass == 0xEF && deviceSubclass == 2) return true
        return interfaceClasses.any { it == UsbConstants.USB_CLASS_VIDEO }
    }
}
