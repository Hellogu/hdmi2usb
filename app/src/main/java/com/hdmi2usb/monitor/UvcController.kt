package com.hdmi2usb.monitor

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder

/** 流状态回调（主线程）。由 CameraListener 语义归位改名而来。 */
interface StreamListener {
    /** 流已就绪并开始渲染（主线程回调）。 */
    fun onStreamReady(width: Int, height: Int)
    /** 流不可用，应提示并退出（主线程回调）。 */
    fun onUnavailable()
}

class UvcController(context: Context, private val listener: StreamListener) {

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val workThread = HandlerThread("UvcThread").apply { start() }
    private val workHandler = Handler(workThread.looper)

    private var renderer: FrameRenderer? = null
    private var connection: UsbDeviceConnection? = null

    @Volatile private var released = false
    private var streamStarted = false

    private var permissionRegistered = false
    private var onPermissionResult: ((Boolean) -> Unit)? = null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            Log.d(TAG, "usb permission granted=$granted")
            onPermissionResult?.invoke(granted)
            onPermissionResult = null
        }
    }

    companion object {
        private const val TAG = "Hdmi2Usb"
        private const val ACTION_USB_PERMISSION = "com.hdmi2usb.monitor.USB_PERMISSION"

        init {
            System.loadLibrary("hdmi2usb_uvc")
        }
    }

    /** 遍历 USB 设备列表找视频设备（采集卡）。 */
    fun findDevice(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { UsbDevices.isVideo(it) }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    /** 请求 USB 授权；结果一次性回调到主线程。等待期间不设超时，由调用方持续展示面板。 */
    @SuppressLint("WrongConstant")
    fun requestPermission(device: UsbDevice, onResult: (Boolean) -> Unit) {
        onPermissionResult = onResult
        if (!permissionRegistered) {
            appContext.registerReceiver(
                permissionReceiver,
                IntentFilter(ACTION_USB_PERMISSION),
                Context.RECEIVER_NOT_EXPORTED,
            )
            permissionRegistered = true
        }
        // Android 12+ 官方模板：系统需要向结果广播 fill-in EXTRA_PERMISSION_GRANTED，
        // PendingIntent 必须 FLAG_MUTABLE（IMMUTABLE 会吞掉 fill-in，导致恒读 false）
        val pi = PendingIntent.getBroadcast(
            appContext,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        usbManager.requestPermission(device, pi)
    }

    /**
     * 启动取流（前提：已获得 USB 授权）。
     * 枚举 MJPEG 格式 → StreamSelector 排序 → 逐候选 nativeStart 降级尝试，全失败才 onUnavailable。
     */
    fun start(device: UsbDevice, holder: SurfaceHolder) {
        workHandler.post { startInternal(device, holder) }
    }

    private fun startInternal(device: UsbDevice, holder: SurfaceHolder) {
        if (released || streamStarted) return
        val conn = usbManager.openDevice(device)
        if (conn == null) {
            Log.d(TAG, "openDevice null")
            postUnavailable()
            return
        }
        connection = conn
        val fd = conn.fileDescriptor

        val formats = nativeGetStreamFormats(fd)
        Log.d(TAG, "formats=${formats?.size?.div(3)} mjpeg triples")
        if (formats == null || formats.isEmpty()) {
            releaseConnection()
            postUnavailable()
            return
        }

        val frame = holder.surfaceFrame
        val candidates = StreamSelector.select(
            UvcFormats.toStreamOptions(formats),
            frame.width(),
            frame.height(),
        )
        Log.d(TAG, "candidates=${candidates.size} surface=${frame.width()}x${frame.height()}")
        if (candidates.isEmpty()) {
            releaseConnection()
            postUnavailable()
            return
        }

        for (candidate in candidates) {
            Log.d(TAG, "try ${candidate.width}x${candidate.height}@${candidate.maxFps}")
            if (nativeStart(fd, candidate.width, candidate.height, candidate.maxFps)) {
                streamStarted = true
                renderer = FrameRenderer(holder)
                mainHandler.post {
                    listener.onStreamReady(candidate.width, candidate.height)
                }
                return
            }
        }
        releaseConnection()
        postUnavailable()
    }

    private fun postUnavailable() {
        mainHandler.post { listener.onUnavailable() }
    }

    private fun releaseConnection() {
        try {
            connection?.close()
        } catch (_: Exception) {
        }
        connection = null
    }

    // ==== 以下两个方法由 native 回调（libuvc 取流线程 / 看门狗线程），勿改名 ====

    private fun onFrame(frame: ByteArray) {
        renderer?.submit(frame)
    }

    private fun onStreamError() {
        mainHandler.post { listener.onUnavailable() }
    }

    /**
     * 唯一生命周期收口：teardown 投递到工作线程串行执行（与 startInternal 同队列，
     * 避免 nativeStop 与进行中的枚举/开流竞态）→ 退渲染线程 → 注销授权广播 → 状态复位。
     */
    fun close() {
        if (released) return
        released = true
        workHandler.post {
            try {
                nativeStop()
            } catch (_: Exception) {
            }
            streamStarted = false
            try {
                connection?.close()   // uvc_close 不关 fd，fd 归这里管
            } catch (_: Exception) {
            }
            connection = null
            // 与 startInternal 同队列串行，此处必然晚于其完成：收口渲染线程并撤销未决主线程回调
            renderer?.stop()
            renderer = null
            mainHandler.removeCallbacksAndMessages(null)
        }
        workThread.quitSafely()
        if (permissionRegistered) {
            try {
                appContext.unregisterReceiver(permissionReceiver)
            } catch (_: Exception) {
            }
            permissionRegistered = false
        }
    }

    private external fun nativeGetStreamFormats(fd: Int): IntArray?
    private external fun nativeStart(fd: Int, width: Int, height: Int, fps: Int): Boolean
    private external fun nativeStop()
}
