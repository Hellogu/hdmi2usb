package com.hdmi2usb.monitor

/** 一条候选视频流（由 StreamConfigurationMap 适配而来，便于单元测试）。 */
data class StreamOption(
    val format: Int,
    val width: Int,
    val height: Int,
    val minFps: Int,
    val maxFps: Int,
)
