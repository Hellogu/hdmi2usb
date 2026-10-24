package com.hdmi2usb.monitor

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceView
import android.widget.FrameLayout

/**
 * 黑底 SurfaceView 容器。
 * FIT：SurfaceView 按流宽高比定尺寸并居中（等比留黑边）；
 * STRETCH：SurfaceView 铺满容器（拉伸填充）。
 * 双击切换模式，只重布局，不重建相机会话。
 */
class DisplayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    val surfaceView = SurfaceView(context).also { addView(it) }

    var mode: ScreenFit.Mode = ScreenFit.Mode.FIT
        private set

    var onModeChanged: ((ScreenFit.Mode) -> Unit)? = null

    /** 单击画面（与双击切换互不干扰，双击判定期间单击不触发）。 */
    var onSingleTap: (() -> Unit)? = null

    private var streamW = 0
    private var streamH = 0

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleMode()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTap?.invoke()
                return true
            }
        },
    )

    init {
        setBackgroundColor(Color.BLACK)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return true
    }

    /** 相机流就绪：记录分辨率并应用当前缩放模式。 */
    fun onStreamConfigured(width: Int, height: Int) {
        streamW = width
        streamH = height
        applyLayout()
    }

    fun toggleMode() {
        mode = if (mode == ScreenFit.Mode.FIT) ScreenFit.Mode.STRETCH else ScreenFit.Mode.FIT
        applyLayout()
        onModeChanged?.invoke(mode)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyLayout()
    }

    private fun applyLayout() {
        if (streamW == 0 || streamH == 0 || width == 0 || height == 0) return
        val vp = ScreenFit.viewport(streamW, streamH, width, height, mode)
        val lp = surfaceView.layoutParams as LayoutParams
        lp.width = vp.width
        lp.height = vp.height
        lp.leftMargin = vp.offsetX
        lp.topMargin = vp.offsetY
        surfaceView.layoutParams = lp
    }
}
