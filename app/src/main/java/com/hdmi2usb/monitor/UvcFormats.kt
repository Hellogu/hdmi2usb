package com.hdmi2usb.monitor

import android.graphics.ImageFormat

/**
 * UVC 格式描述符扁平数组 [w0,h0,fps0,w1,h1,fps1,...] → 候选流列表。
 * format 统一填 ImageFormat.JPEG（使 StreamSelector 的压缩优先排序生效）；
 * fps 以描述符最高帧率填 min=max 并 cap 到 [capFps]（USB 2.0 下 1080p60 有丢帧/花屏风险，稳定优先）。
 * 排序与超屏过滤交给 StreamSelector。
 */
object UvcFormats {

    const val FPS_CAP = 30

    fun toStreamOptions(flat: IntArray, capFps: Int = FPS_CAP): List<StreamOption> {
        if (flat.size % 3 != 0) return emptyList()
        val options = mutableListOf<StreamOption>()
        var i = 0
        while (i < flat.size) {
            val w = flat[i]
            val h = flat[i + 1]
            val fps = flat[i + 2]
            if (w > 0 && h > 0 && fps > 0) {
                val capped = if (fps > capFps) capFps else fps
                options += StreamOption(ImageFormat.JPEG, w, h, capped, capped)
            }
            i += 3
        }
        return options
    }
}
