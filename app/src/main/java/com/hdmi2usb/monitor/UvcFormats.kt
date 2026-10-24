package com.hdmi2usb.monitor

import android.graphics.ImageFormat

/**
 * UVC 格式描述符扁平数组 [w0,h0,fps0,w1,h1,fps1,...] → 候选流列表。
 * format 统一填 ImageFormat.JPEG（使 StreamSelector 的压缩优先排序生效）；
 * fps 以描述符最高帧率填 min=max。默认不设硬 cap——设备描述符档位即厂商验证过的
 * 能力（如 MS2130 全档 60fps），自动/手动统一信任枚举结果。
 * 排序与超屏过滤交给 StreamSelector。
 */
object UvcFormats {

    fun toStreamOptions(flat: IntArray, capFps: Int = Int.MAX_VALUE): List<StreamOption> {
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

    /**
     * 手动选择菜单项：按分辨率分组（fps 取该组最大值），面积降序全量返回。
     * 常用/不常用的划分由 UI 层决定。
     */
    fun manualChoices(flat: IntArray): List<StreamOption> {
        return toStreamOptions(flat)
            .groupBy { it.width to it.height }
            .map { (size, group) ->
                val fps = group.maxOf { it.maxFps }
                StreamOption(ImageFormat.JPEG, size.first, size.second, fps, fps)
            }
            .sortedByDescending { it.width.toLong() * it.height }
    }
}
