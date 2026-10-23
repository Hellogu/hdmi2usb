package com.hdmi2usb.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ScreenFitTest {

    @Test
    fun `fit 16比9 画面放进 16比10 容器上下留黑边`() {
        val vp = ScreenFit.viewport(1920, 1080, 1600, 1000, ScreenFit.Mode.FIT)
        assertEquals(1600, vp.width)
        assertEquals(900, vp.height)
        assertEquals(0, vp.offsetX)
        assertEquals(50, vp.offsetY)
    }

    @Test
    fun `fit 4比3 画面放进 16比9 容器左右留黑边`() {
        val vp = ScreenFit.viewport(1024, 768, 1920, 1080, ScreenFit.Mode.FIT)
        assertEquals(1440, vp.width)
        assertEquals(1080, vp.height)
        assertEquals(240, vp.offsetX)
        assertEquals(0, vp.offsetY)
    }

    @Test
    fun `fit 竖容器适配横画面`() {
        val vp = ScreenFit.viewport(1920, 1080, 800, 1200, ScreenFit.Mode.FIT)
        assertEquals(800, vp.width)
        assertEquals(450, vp.height)
        assertEquals(0, vp.offsetX)
        assertEquals(375, vp.offsetY)
    }

    @Test
    fun `stretch 铺满容器`() {
        val vp = ScreenFit.viewport(1920, 1080, 1200, 800, ScreenFit.Mode.STRETCH)
        assertEquals(1200, vp.width)
        assertEquals(800, vp.height)
        assertEquals(0, vp.offsetX)
        assertEquals(0, vp.offsetY)
    }

    @Test
    fun `非正尺寸抛出异常`() {
        assertThrows(IllegalArgumentException::class.java) {
            ScreenFit.viewport(0, 1080, 100, 100, ScreenFit.Mode.FIT)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScreenFit.viewport(1920, 1080, 100, 0, ScreenFit.Mode.STRETCH)
        }
    }
}
