package com.hdmi2usb.monitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDevicesTest {

    @Test
    fun `设备级 class 0x0E 判定为视频`() {
        assertTrue(UsbDevices.isVideo(0x0E, 0, emptyList()))
    }

    @Test
    fun `IAD 封装 0xEF 子类 2 判定为视频`() {
        assertTrue(UsbDevices.isVideo(0xEF, 2, emptyList()))
    }

    @Test
    fun `IAD 子类非 2 不判定为视频`() {
        assertFalse(UsbDevices.isVideo(0xEF, 3, emptyList()))
    }

    @Test
    fun `任一 interface 为 0x0E 判定为视频`() {
        assertTrue(UsbDevices.isVideo(0x00, 0, listOf(3, 0x0E)))
    }

    @Test
    fun `全无视频类不判定为视频`() {
        assertFalse(UsbDevices.isVideo(0x00, 0, listOf(3, 8)))
    }

    @Test
    fun `设备级非视频但接口为视频时判定为视频`() {
        assertTrue(UsbDevices.isVideo(0x00, 0, listOf(0x0E)))
    }
}
