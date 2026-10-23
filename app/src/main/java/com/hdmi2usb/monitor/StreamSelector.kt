package com.hdmi2usb.monitor

import android.graphics.ImageFormat

/**
 * 候选流排序：压缩格式（MJPEG，对应 [ImageFormat.JPEG]）优先 → 分辨率不超上限取最大 →
 * 同分辨率取最高帧率。全部档位均超出屏幕时回退为不过滤，超屏最少的（最接近屏幕）排最前。
 */
object StreamSelector {

    fun select(
        options: List<StreamOption>,
        maxWidth: Int,
        maxHeight: Int,
    ): List<StreamOption> {
        val valid = options.filter { it.width > 0 && it.height > 0 }
        val inBounds = valid.filter { it.width <= maxWidth && it.height <= maxHeight }
        if (inBounds.isEmpty()) {
            // 回退：不过滤，按超屏程度升序（超出面积越小越接近屏幕，越优先）。
            return valid.sortedWith(
                compareByDescending<StreamOption> { it.format == ImageFormat.JPEG }
                    .thenBy { (it.width - maxWidth).toLong() * (it.height - maxHeight) },
            )
        }
        return inBounds.sortedWith(
            compareByDescending<StreamOption> { it.format == ImageFormat.JPEG }
                .thenByDescending { it.width * it.height }
                .thenByDescending { it.maxFps },
        )
    }
}
