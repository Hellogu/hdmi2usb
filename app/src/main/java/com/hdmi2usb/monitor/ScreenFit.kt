package com.hdmi2usb.monitor

/** 显示视口计算：FIT 等比留黑边，STRETCH 拉伸铺满。 */
object ScreenFit {

    enum class Mode { FIT, STRETCH }

    data class Viewport(val width: Int, val height: Int, val offsetX: Int, val offsetY: Int)

    fun viewport(
        streamW: Int,
        streamH: Int,
        containerW: Int,
        containerH: Int,
        mode: Mode,
    ): Viewport {
        require(streamW > 0 && streamH > 0 && containerW > 0 && containerH > 0) {
            "stream and container sizes must be positive"
        }
        if (mode == Mode.STRETCH) return Viewport(containerW, containerH, 0, 0)
        val streamRatio = streamW.toDouble() / streamH
        val containerRatio = containerW.toDouble() / containerH
        val (w, h) = if (streamRatio > containerRatio) {
            containerW to Math.round(containerW / streamRatio).toInt()
        } else {
            Math.round(containerH * streamRatio).toInt() to containerH
        }
        return Viewport(w, h, (containerW - w) / 2, (containerH - h) / 2)
    }
}
