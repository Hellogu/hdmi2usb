package com.hdmi2usb.monitor

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 录制管线：视频/音频双有界队列 → 写线程 → AVI（占位头）→ 正常收尾才移入公共相册。
 * 帧来源为 libuvc 回调线程（[onFrame]）与音频直通线程（[onPcm]），均非阻塞：满则丢最旧，
 * 与显示 conflate 同哲学——绝不反压采集。停止为异步收尾：[stop] 置位后由写线程
 * 清队 → 回填 AVI 头 → 复制进 MediaStore（Movies/Hdmi2Usb）→ 主线程回调 [onFinished]。
 * 崩溃安全：录制中只写应用私有临时文件，意外退出不会在相册留下损坏条目。
 */
class StreamRecorder(
    context: Context,
    private val width: Int,
    private val height: Int,
    private val nominalFps: Int,
    audioSpec: AudioStreamPlayer.AudioSpec?,      // null = 音频不可用，降级纯视频
    private val mainHandler: Handler,
    private val onFinished: (recorder: StreamRecorder, fileName: String?) -> Unit,
) {
    private val appContext = context.applicationContext
    private val videoQueue = LinkedBlockingQueue<ByteArray>(VIDEO_QUEUE)
    private val audioQueue = LinkedBlockingQueue<ByteArray>(AUDIO_QUEUE)
    private val tmpFile: File
    private val muxer: AviMuxer?
    private val writer: Thread
    val startedAtRealtime = SystemClock.elapsedRealtime()
    val fileName = "Hdmi2Usb_" +
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    @Volatile private var recording = true
    @Volatile private var frameCount = 0L
    @Volatile private var audioBytes = 0L
    @Volatile private var firstFrameAt = 0L
    @Volatile private var lastFrameAt = 0L

    val isRecording: Boolean get() = recording

    /** false 表示 AVI 头部创建失败（磁盘 IO 异常等），本次录制未开始。 */
    val startSucceeded: Boolean

    init {
        val dir = appContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: appContext.filesDir
        tmpFile = File(dir, "recording_tmp.avi")
        // 清掉崩溃残留或上一会话收尾未及删除的临时文件，避免两个写线程踩同一文件
        tmpFile.delete()
        muxer = try {
            AviMuxer(tmpFile, width, height, nominalFps, audioSpec).also { it.start() }
        } catch (e: Exception) {
            Log.d(TAG, "avi muxer open fail: ${e.message}")
            null
        }
        startSucceeded = muxer != null
        writer = Thread({ writeLoop() }, "RecordWriter")
        if (startSucceeded) writer.start()
    }

    /** libuvc 回调线程调用：帧引用直接入队（JNI 每帧新建数组，无所有权冲突）。 */
    fun onFrame(frame: ByteArray) {
        if (!recording) return
        val now = System.nanoTime()
        if (firstFrameAt == 0L) firstFrameAt = now
        lastFrameAt = now
        frameCount++
        while (!videoQueue.offer(frame)) videoQueue.poll()
    }

    /** 音频直通线程调用：PCM 块引用直接入队。 */
    fun onPcm(chunk: ByteArray) {
        if (!recording) return
        audioBytes += chunk.size
        while (!audioQueue.offer(chunk)) audioQueue.poll()
    }

    /** 停止（幂等，任意线程）：异步收尾，完成时间取决于清队与入库耗时。 */
    fun stop() {
        recording = false
    }

    private fun writeLoop() {
        val m = muxer ?: return
        var ok = true
        try {
            while (recording || videoQueue.isNotEmpty() || audioQueue.isNotEmpty()) {
                val v = videoQueue.poll(200, TimeUnit.MILLISECONDS)
                if (v != null) m.writeVideo(v)
                while (true) {
                    val a = audioQueue.poll() ?: break
                    m.writeAudio(a)
                }
            }
            val measured = if (frameCount >= 2 && lastFrameAt > firstFrameAt) {
                (frameCount - 1) * 1_000_000_000.0 / (lastFrameAt - firstFrameAt)
            } else {
                nominalFps.toDouble()
            }
            m.finalize(frameCount.toInt(), measured, audioBytes)
        } catch (e: Exception) {
            Log.d(TAG, "record write fail: ${e.message}")
            ok = false
        }
        m.close()
        val name = if (ok) publishToGallery() else {
            tmpFile.delete()
            null
        }
        mainHandler.post { onFinished(this@StreamRecorder, name) }
    }

    /** 临时文件复制进公共相册 Movies/Hdmi2Usb/，成功返回显示名。 */
    private fun publishToGallery(): String? {
        return try {
            val resolver = appContext.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/avi")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Hdmi2Usb")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values,
            ) ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                tmpFile.inputStream().use { it.copyTo(out, COPY_BUFFER) }
            } ?: return null
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            tmpFile.delete()
            fileName
        } catch (e: Exception) {
            Log.d(TAG, "publish fail: ${e.message}")
            null
        }
    }

    private companion object {
        private const val TAG = "Hdmi2Usb"
        private const val VIDEO_QUEUE = 120      // ≈2 秒 @60fps
        private const val AUDIO_QUEUE = 200      // ≈2 秒 @48kHz 立体声
        private const val COPY_BUFFER = 1 shl 20
    }
}
