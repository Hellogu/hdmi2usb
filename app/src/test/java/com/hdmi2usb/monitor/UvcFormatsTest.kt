package com.hdmi2usb.monitor

import android.graphics.ImageFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class UvcFormatsTest {

    @Test
    fun `空数组返回空列表`() {
        assertEquals(emptyList<StreamOption>(), UvcFormats.toStreamOptions(IntArray(0)))
    }

    @Test
    fun `长度非3倍数返回空列表`() {
        assertEquals(emptyList<StreamOption>(), UvcFormats.toStreamOptions(intArrayOf(1920, 1080)))
    }

    @Test
    fun `扁平数组按三元组映射为JPEG格式`() {
        val options = UvcFormats.toStreamOptions(intArrayOf(1920, 1080, 30, 1280, 720, 60))
        assertEquals(2, options.size)
        assertEquals(StreamOption(ImageFormat.JPEG, 1920, 1080, 30, 30), options[0])
        assertEquals(StreamOption(ImageFormat.JPEG, 1280, 720, 30, 30), options[1])
    }

    @Test
    fun `帧率默认cap到30`() {
        val options = UvcFormats.toStreamOptions(intArrayOf(1280, 720, 60))
        assertEquals(30, options[0].minFps)
        assertEquals(30, options[0].maxFps)
    }

    @Test
    fun `低于cap的帧率保持原值`() {
        val options = UvcFormats.toStreamOptions(intArrayOf(640, 480, 15))
        assertEquals(15, options[0].maxFps)
    }

    @Test
    fun `非法条目被过滤`() {
        val options = UvcFormats.toStreamOptions(
            intArrayOf(0, 1080, 30, 1280, 720, 0, 640, 480, 30),
        )
        assertEquals(1, options.size)
        assertEquals(640, options[0].width)
    }
}
