package com.hdmi2usb.monitor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.view.SurfaceHolder
import java.util.concurrent.atomic.AtomicLong

/**
 * 渲染线程：conflate 背压（native 帧回调只更新最新帧槽，渲染慢时旧帧直接丢弃，延迟恒定）
 * + BitmapFactory(inBitmap 复用，尺寸变化时重建) 解码 + Canvas 全幅绘制。
 * FIT/STRETCH 完全由 DisplayView 布局层承担，这里一律画满当前 surface。
 */
class FrameRenderer(private val holder: SurfaceHolder) {

    private val thread = HandlerThread("RenderThread").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile private var pending: ByteArray? = null
    @Volatile private var stopped = false
    private var renderQueued = false
    private var bitmap: Bitmap? = null
    // 收帧计数（conflate 之前，反映采集卡真实送帧率，供 fps 统计）
    private val framesReceived = AtomicLong(0)

    /** 帧提交（native 回调线程调用）：更新最新帧槽并确保渲染任务已排队。 */
    fun submit(frame: ByteArray) {
        if (stopped) return
        framesReceived.incrementAndGet()
        pending = frame
        synchronized(this) {
            if (renderQueued) return
            renderQueued = true
        }
        handler.post { render() }
    }

    /** 自上次调用以来收到的帧数并清零计数（UI 每秒 tick 取差值即为 fps）。 */
    fun takeFrameCount(): Long = framesReceived.getAndSet(0)

    private fun render() {
        synchronized(this) { renderQueued = false }
        val frame = pending
        pending = null
        if (stopped || frame == null) return
        val bmp = decode(frame) ?: return
        // surface 销毁期间 lockCanvas 返回 null：跳过绘制，Bitmap 保留供复用
        val canvas = holder.lockCanvas() ?: return
        try {
            canvas.drawBitmap(
                bmp,
                null,
                RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat()),
                null,
            )
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun decode(frame: ByteArray): Bitmap? {
        val prev = bitmap
        if (prev != null && !prev.isRecycled) {
            try {
                val opts = BitmapFactory.Options().apply { inBitmap = prev }
                val reused = BitmapFactory.decodeByteArray(frame, 0, frame.size, opts)
                if (reused != null) return reused
            } catch (_: IllegalArgumentException) {
                // inBitmap 尺寸/配置不匹配（降级换档后）→ 走全新解码
            }
        }
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888  // lockCanvas 是软件画布，禁用 HARDWARE 位图
            inMutable = true                             // inBitmap 复用要求可变位图
        }
        val fresh = BitmapFactory.decodeByteArray(frame, 0, frame.size, opts)
        if (fresh != null && fresh !== prev) {
            prev?.recycle()
            bitmap = fresh
        }
        return fresh
    }

    fun stop() {
        stopped = true
        framesReceived.set(0)
        // recycle 交给渲染线程执行（quitSafely 前已入队的消息会跑完），避免与进行中的 drawBitmap 竞态
        handler.post {
            bitmap?.recycle()
            bitmap = null
        }
        thread.quitSafely()
    }
}
