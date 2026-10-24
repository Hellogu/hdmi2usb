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
    /** 流已就绪并开始渲染（主线程回调）。fps 为档位标称帧率。 */
    fun onStreamReady(width: Int, height: Int, fps: Int)
    /** 流启动失败（设备被占用/枚举失败等）；UI 可退出，或在无信号恢复循环中继续重试（主线程回调）。 */
    fun onUnavailable()
    /** 看门狗超时：流启动但持续无帧（如 HDMI 无信号），可自动恢复（主线程回调）。 */
    fun onNoSignal()
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
     * [preferred] 非空时置于候选列表首位（手动锁档），仍保留自动候选兜底。
     * [maxPixels] 限制候选档位面积上限（无信号恢复降档用；过滤后为空则不限制）。
     */
    fun start(
        device: UsbDevice,
        holder: SurfaceHolder,
        preferred: StreamOption? = null,
        maxPixels: Long = Long.MAX_VALUE,
    ) {
        workHandler.post { startInternal(device, holder, preferred, maxPixels) }
    }

    /** surface 销毁重建（如转屏）后刷新渲染层：重建 FrameRenderer 绑定新 holder，流本身不中断。 */
    fun refreshRenderer(holder: SurfaceHolder) {
        workHandler.post {
            val old = renderer ?: return@post
            old.stop()
            renderer = FrameRenderer(holder)
        }
    }

    /** 手动选择菜单数据（依赖最近一次枚举的格式缓存；流未启动时为空）。 */
    fun manualChoices(): List<StreamOption> =
        currentFormats?.let { UvcFormats.manualChoices(it) } ?: emptyList()

    private var currentFormats: IntArray? = null

    // 最近一次开流绑定的 surface（自动首选档计算用）
    @Volatile private var lastHolder: SurfaceHolder? = null

    /** 当前 surface 尺寸下的自动首选档（与 startInternal 自动路径同逻辑）；无缓存时为 null。 */
    fun autoFirstChoice(): StreamOption? {
        val formats = currentFormats ?: return null
        val frame = lastHolder?.surfaceFrame ?: return null
        return StreamSelector.select(
            UvcFormats.toStreamOptions(formats),
            frame.width(),
            frame.height(),
        ).firstOrNull()
    }

    private fun startInternal(
        device: UsbDevice,
        holder: SurfaceHolder,
        preferred: StreamOption?,
        maxPixels: Long,
    ) {
        if (released) return
        // 重启路径（切换分辨率/帧率）：先停旧流并收口旧的渲染与连接，同队列串行无竞态
        if (streamStarted) {
            nativeStop()
            streamStarted = false
            // MS2130 固件在停流与重开之间需要间隔；切档路径间隔过短会导致二次开流零帧
            try {
                Thread.sleep(300)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        pendingReady = null
        renderer?.stop()
        renderer = null
        releaseConnection()
        currentFormats = null

        val conn = usbManager.openDevice(device)
        if (conn == null) {
            Log.d(TAG, "openDevice null")
            postUnavailable()
            return
        }
        connection = conn
        val fd = conn.fileDescriptor

        val formats = nativeGetStreamFormats(fd)
        Log.d(TAG, "formats=${formats?.size?.div(3)} mjpeg triples raw=${formats?.toList()}")
        if (formats == null || formats.isEmpty()) {
            releaseConnection()
            postUnavailable()
            return
        }
        currentFormats = formats
        lastHolder = holder

        val frame = holder.surfaceFrame
        val auto = StreamSelector.select(
            UvcFormats.toStreamOptions(formats),
            frame.width(),
            frame.height(),
        )
        // 手动档在前优先尝试，失败自动回落（手动档设备不支持/瞬态占用时无需用户重试）
        // 面积上限过滤（无信号恢复降档）：USB 2.0 等口高规格档可能协商成功但无帧，靠降档恢复
        val filtered = auto.filter { it.width.toLong() * it.height <= maxPixels }
        val autoList = filtered.ifEmpty { auto }
        val candidates = if (preferred != null && preferred.width.toLong() * preferred.height <= maxPixels) {
            listOf(preferred) + autoList
        } else {
            autoList
        }
        Log.d(TAG, "candidates=${candidates.size} surface=${frame.width()}x${frame.height()} preferred=${preferred != null}")
        if (candidates.isEmpty()) {
            releaseConnection()
            postUnavailable()
            return
        }

        for (candidate in candidates) {
            Log.d(TAG, "try ${candidate.width}x${candidate.height}@${candidate.maxFps}")
            if (nativeStart(fd, candidate.width, candidate.height, candidate.maxFps)) {
                streamStarted = true
                // 决议：onStreamReady 延迟到首帧到达（libuvc 回调线程 onFrame 里触发）。
                // nativeStart 成功≠出画（如高帧率档在带宽不足的口上协商成功但零帧），
                // 提前触发会让 UI 误判出画、并短路无信号恢复的降档计数
                pendingReady = candidate
                renderer = FrameRenderer(holder)
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
        // 首帧到达：此刻才宣告流就绪（width/height/fps 为实际启动的档位）
        pendingReady?.let { cand ->
            pendingReady = null
            mainHandler.post { listener.onStreamReady(cand.width, cand.height, cand.maxFps) }
        }
        renderer?.submit(frame)
        // 录制旁路：JNI 每帧新建数组（引用独立），tap 必须非阻塞，否则会拖慢取流回调
        frameTap?.invoke(frame)
    }

    // 首帧回调挂起的档位（null=已触发或未启动）；libuvc 回调线程写，工作线程 stop 时清
    @Volatile private var pendingReady: StreamOption? = null

    /** 录制等旁路消费者的帧挂钩（libuvc 回调线程调用）。null 表示无旁路。 */
    @Volatile var frameTap: ((ByteArray) -> Unit)? = null

    private fun onStreamError() {
        mainHandler.post { listener.onNoSignal() }
    }

    /** 自上次调用以来渲染层收到的帧数（流未启动/已关闭时返回 null），供 UI 每秒统计 fps。 */
    fun takeFrameCount(): Long? = renderer?.takeFrameCount()

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
            pendingReady = null
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
