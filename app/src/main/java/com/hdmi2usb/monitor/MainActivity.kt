package com.hdmi2usb.monitor

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), SurfaceHolder.Callback, StreamListener {

    private lateinit var displayView: DisplayView
    private lateinit var statusText: TextView
    private lateinit var statsBar: View
    private lateinit var statsText: TextView
    private lateinit var settingsButton: TextView
    private lateinit var permissionPanel: View
    private lateinit var permissionText: TextView
    private lateinit var permissionButton: Button

    private var controller: UvcController? = null
    private var plugMonitor: PlugMonitor? = null
    private var surfaceReady = false
    private var retryCount = 0
    private var streaming = false
    private var starting = false
    private var waitingPermission = false
    // 无信号恢复循环：watchdog 无帧后不退出，改为提示并每 2 秒自动重启流等待源恢复
    private var waitingSignal = false
    private var pendingDevice: UsbDevice? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val reopenRunnable = Runnable { tryOpenStream() }

    private val signalRetryRunnable = Runnable { retryAfterNoSignal() }

    // 每秒读一次渲染层收帧计数（takeFrameCount 自清零），差值即实际 fps
    private val statsRunnable = object : Runnable {
        override fun run() {
            val count = controller?.takeFrameCount() ?: run {
                statsBar.visibility = View.GONE
                return
            }
            statsText.text = getString(R.string.stats_format, statsWidth, statsHeight, count)
            mainHandler.postDelayed(this, 1_000)
        }
    }
    private var statsWidth = 0
    private var statsHeight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        // insetsController 依赖 DecorView，必须在 setContentView 之后获取
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        displayView = findViewById(R.id.display)
        statusText = findViewById(R.id.status_text)
        statsBar = findViewById(R.id.stats_bar)
        statsText = findViewById(R.id.stats_text)
        settingsButton = findViewById(R.id.settings_button)
        permissionPanel = findViewById(R.id.permission_panel)
        permissionText = findViewById(R.id.permission_text)
        permissionButton = findViewById(R.id.btn_grant)
        displayView.surfaceView.holder.addCallback(this)
        permissionButton.setOnClickListener { requestUsbPermission() }
        settingsButton.setOnClickListener { showFormatDialog() }
        // 冷启动入口：可能是 launcher 直接打开，也可能带 EXTRA_DEVICE 的 attach intent
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask：app 存活时再次插入采集卡，重置重试并重新打开
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED && surfaceReady) {
            retryCount = 0
            handleIntent(intent)
        }
    }

    /** attach 直连：优先取 intent 的 EXTRA_DEVICE（仍经 isVideo 验证），没有才回退设备枚举。 */
    private fun handleIntent(intent: Intent?) {
        val extra = intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        if (extra != null && UsbDevices.isVideo(extra)) {
            pendingDevice = extra
        }
        startPipeline()
    }

    private fun startPipeline() {
        if (plugMonitor == null) plugMonitor = PlugMonitor(this) { onCaptureCardDetached() }
        plugMonitor?.register()
        if (controller == null) controller = UvcController(this, this)
        if (surfaceReady) tryOpenStream()
    }

    private fun tryOpenStream() {
        // streaming/starting/waitingPermission/waitingSignal 守卫，防止重试回调/重复插卡/面板等待期重复 start
        if (streaming || starting || waitingPermission || waitingSignal) return
        val c = controller ?: return
        val device = pendingDevice ?: c.findDevice()
        pendingDevice = device
        if (device == null) {
            if (retryCount < MAX_RETRIES) {
                retryCount++
                statusText.setText(R.string.no_capture_card)
                statusText.visibility = View.VISIBLE
                mainHandler.postDelayed(reopenRunnable, RETRY_INTERVAL_MS)
            } else {
                Toast.makeText(this, R.string.no_capture_card_exit, Toast.LENGTH_SHORT).show()
                finish()
            }
        } else {
            statusText.visibility = View.GONE
            mainHandler.removeCallbacks(reopenRunnable)
            if (c.hasPermission(device)) {
                starting = true
                c.start(device, displayView.surfaceView.holder, loadPreferred())
            } else {
                requestUsbPermission()
            }
        }
    }

    /** 请求 USB 授权；拒绝时面板提示，不自动重弹，只等用户点按钮再请求。 */
    private fun requestUsbPermission() {
        if (waitingPermission) return
        val c = controller ?: return
        val device = pendingDevice ?: c.findDevice() ?: return
        // Android 15+ 要求 UVC 设备的 USB 授权请求前置 CAMERA 运行时权限，未持有时先请求
        if (!hasCameraPermission()) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        waitingPermission = true
        permissionText.setText(R.string.permission_rationale)
        permissionButton.setText(R.string.retry_grant)
        permissionPanel.visibility = View.VISIBLE
        c.requestPermission(device) { granted ->
            waitingPermission = false
            if (granted) {
                permissionPanel.visibility = View.GONE
                if (streaming || starting) return@requestPermission
                starting = true
                c.start(device, displayView.surfaceView.holder, loadPreferred())
            } else {
                permissionText.setText(R.string.permission_denied)
                permissionPanel.visibility = View.VISIBLE
            }
        }
    }

    private fun hasCameraPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** 画质选择对话框：自动 + 设备支持且 ≥1080p 的档位（手动档持久化，重启流生效）。 */
    private fun showFormatDialog() {
        val choices = controller?.manualChoices().orEmpty()
        if (choices.isEmpty()) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val labels = mutableListOf(getString(R.string.format_auto))
        labels += choices.map { getString(R.string.format_item, it.width, it.height, it.maxFps) }
        val cur = loadPreferred()
        val checked = if (cur == null) 0
        else choices.indexOfFirst { it.width == cur.width && it.height == cur.height && it.maxFps == cur.maxFps } + 1
        AlertDialog.Builder(this)
            .setTitle(R.string.format_title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                val preferred = if (which == 0) {
                    prefs.edit().remove(PREF_W).remove(PREF_H).remove(PREF_FPS).apply()
                    null
                } else {
                    val c = choices[which - 1]
                    prefs.edit()
                        .putInt(PREF_W, c.width).putInt(PREF_H, c.height).putInt(PREF_FPS, c.maxFps)
                        .apply()
                    c
                }
                applyPreferred(preferred)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 应用手动画质：重启取流（手动档失败时自动回落）。 */
    private fun applyPreferred(preferred: StreamOption?) {
        if (starting) return
        val c = controller ?: return
        val device = pendingDevice ?: c.findDevice() ?: return
        waitingSignal = false
        mainHandler.removeCallbacks(signalRetryRunnable)
        statusText.visibility = View.GONE
        streaming = false
        starting = true
        c.start(device, displayView.surfaceView.holder, preferred)
    }

    private fun loadPreferred(): StreamOption? {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val w = prefs.getInt(PREF_W, -1)
        val h = prefs.getInt(PREF_H, -1)
        val fps = prefs.getInt(PREF_FPS, -1)
        if (w <= 0 || h <= 0 || fps <= 0) return null
        return StreamOption(android.graphics.ImageFormat.JPEG, w, h, fps, fps)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAMERA) return
        if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            tryOpenStream()
        } else {
            permissionText.setText(R.string.camera_permission_denied)
            permissionPanel.visibility = View.VISIBLE
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        if (controller != null) tryOpenStream()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // 旋转/缩放只改变 surface 尺寸，渲染层下一帧自然画到新尺寸，无需重启流
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
    }

    override fun onStreamReady(width: Int, height: Int) {
        starting = false
        streaming = true
        waitingSignal = false
        mainHandler.removeCallbacks(signalRetryRunnable)
        statusText.visibility = View.GONE
        displayView.onStreamConfigured(width, height)
        // 启动每秒帧率统计（首秒前先展示分辨率占位）
        statsWidth = width
        statsHeight = height
        statsText.text = getString(R.string.stats_format, width, height, 0)
        statsBar.visibility = View.VISIBLE
        mainHandler.removeCallbacks(statsRunnable)
        mainHandler.postDelayed(statsRunnable, 1_000)
    }

    override fun onUnavailable() {
        starting = false
        streaming = false
        // 无信号恢复循环中的启动失败：静默等下一轮，不退出
        if (waitingSignal) return
        mainHandler.removeCallbacks(statsRunnable)
        statsBar.visibility = View.GONE
        Toast.makeText(this, R.string.stream_unavailable, Toast.LENGTH_SHORT).show()
        finish()
    }

    /** 看门狗无帧：进入常驻恢复循环，每 2 秒重启流直到源恢复（拔卡/销毁时打断）。 */
    override fun onNoSignal() {
        streaming = false
        starting = false
        if (waitingSignal) return
        waitingSignal = true
        mainHandler.removeCallbacks(statsRunnable)
        statsBar.visibility = View.GONE
        statusText.setText(R.string.no_signal)
        statusText.visibility = View.VISIBLE
        mainHandler.postDelayed(signalRetryRunnable, RETRY_INTERVAL_MS)
    }

    private fun retryAfterNoSignal() {
        if (!waitingSignal) return
        val c = controller ?: return
        val device = pendingDevice ?: c.findDevice()
        pendingDevice = device
        if (device == null) {
            // 卡被拔走：交给 PlugMonitor 的 DETACH 路径处理（close + finish）
            mainHandler.postDelayed(signalRetryRunnable, RETRY_INTERVAL_MS)
            return
        }
        // start 失败会回调 onUnavailable；waitingSignal 挡住退出，由本循环继续重试
        c.start(device, displayView.surfaceView.holder, loadPreferred())
    }

    private fun onCaptureCardDetached() {
        starting = false
        streaming = false
        waitingPermission = false
        waitingSignal = false
        mainHandler.removeCallbacks(statsRunnable)
        mainHandler.removeCallbacks(signalRetryRunnable)
        statusText.visibility = View.GONE
        statsBar.visibility = View.GONE
        controller?.close()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(reopenRunnable)
        mainHandler.removeCallbacks(statsRunnable)
        mainHandler.removeCallbacks(signalRetryRunnable)
        plugMonitor?.unregister()
        controller?.close()
    }

    private companion object {
        const val MAX_RETRIES = 3
        const val RETRY_INTERVAL_MS = 2_000L
        const val REQ_CAMERA = 1
        const val PREFS = "uvc"
        const val PREF_W = "preferred_w"
        const val PREF_H = "preferred_h"
        const val PREF_FPS = "preferred_fps"
    }
}
