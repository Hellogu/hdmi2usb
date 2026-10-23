package com.hdmi2usb.monitor

import android.graphics.ImageFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamSelectorTest {

    private val yuv = ImageFormat.YUV_420_888
    // Camera2/ImageFormat 没有 MJPEG 常量，压缩流（MJPEG/UVC）对应 JPEG。
    private val mjpeg = ImageFormat.JPEG

    @Test
    fun `mjpeg 优先于 yuv 即使分辨率更小`() {
        val options = listOf(
            StreamOption(yuv, 1920, 1080, 30, 30),
            StreamOption(mjpeg, 1280, 720, 60, 60),
        )
        val result = StreamSelector.select(options, 1920, 1080)
        assertEquals(mjpeg, result.first().format)
    }

    @Test
    fun `超出屏幕分辨率的档位被过滤`() {
        val options = listOf(
            StreamOption(mjpeg, 3840, 2160, 30, 30),
            StreamOption(mjpeg, 1920, 1080, 60, 60),
        )
        val result = StreamSelector.select(options, 1920, 1080)
        assertEquals(listOf(1920 to 1080), result.map { it.width to it.height })
    }

    @Test
    fun `分辨率大的排前面`() {
        val options = listOf(
            StreamOption(mjpeg, 1280, 720, 60, 60),
            StreamOption(mjpeg, 1920, 1080, 60, 60),
        )
        val result = StreamSelector.select(options, 1920, 1080)
        assertEquals(1920, result.first().width)
    }

    @Test
    fun `同分辨率取最高帧率`() {
        val options = listOf(
            StreamOption(mjpeg, 1920, 1080, 30, 30),
            StreamOption(mjpeg, 1920, 1080, 60, 60),
        )
        val result = StreamSelector.select(options, 1920, 1080)
        assertEquals(60, result.first().maxFps)
    }

    @Test
    fun `全部超屏时回退为不过滤并按分辨率降序输出`() {
        val options = listOf(
            StreamOption(mjpeg, 3840, 2160, 30, 30),
            StreamOption(mjpeg, 2560, 1440, 30, 30),
        )
        val result = StreamSelector.select(options, 1920, 1080)
        assertEquals(2560, result.first().width)
    }

    @Test
    fun `非法尺寸被过滤`() {
        val options = listOf(
            StreamOption(mjpeg, 0, 0, 30, 30),
            StreamOption(mjpeg, 640, 480, 30, 30),
        )
        val result = StreamSelector.select(options, 1920, 1080)
        assertEquals(listOf(640 to 480), result.map { it.width to it.height })
    }
}
