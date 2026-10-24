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
        assertEquals(StreamOption(ImageFormat.JPEG, 1280, 720, 60, 60), options[1])
    }

    @Test
    fun `默认信任描述符帧率不cap`() {
        val options = UvcFormats.toStreamOptions(intArrayOf(1920, 1080, 60))
        assertEquals(60, options[0].minFps)
        assertEquals(60, options[0].maxFps)
    }

    @Test
    fun `显式capFps仍然生效`() {
        val options = UvcFormats.toStreamOptions(intArrayOf(1280, 720, 60), capFps = 30)
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

    @Test
    fun `手动选项全量返回按面积降序`() {
        val choices = UvcFormats.manualChoices(
            intArrayOf(1920, 1080, 60, 1280, 720, 60, 640, 480, 60, 3840, 2160, 30),
        )
        assertEquals(4, choices.size)
        assertEquals(3840, choices[0].width)
        assertEquals(30, choices[0].maxFps)
        assertEquals(1920, choices[1].width)
        assertEquals(60, choices[1].maxFps)
        assertEquals(640, choices[3].width)
    }

    @Test
    fun `同分辨率多帧率取最大值`() {
        val choices = UvcFormats.manualChoices(
            intArrayOf(1920, 1080, 60, 1920, 1080, 30),
        )
        assertEquals(1, choices.size)
        assertEquals(60, choices[0].maxFps)
    }
}
