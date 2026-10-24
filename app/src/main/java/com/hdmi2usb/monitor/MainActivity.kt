package com.hdmi2usb.monitor

import android.app.Activity
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
    private lateinit var statsText: TextView
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
    private var pendingDevice: UsbDevice? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val reopenRunnable = Runnable { tryOpenStream() }

    // 每秒读一次渲染层收帧计数（takeFrameCount 自清零），差值即实际 fps
    private val statsRunnable = object : Runnable {
        override fun run() {
            val count = controller?.takeFrameCount() ?: run {
                statsText.visibility = View.GONE
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
        statsText = findViewById(R.id.stats_text)
        permissionPanel = findViewById(R.id.permission_panel)
        permissionText = findViewById(R.id.permission_text)
        permissionButton = findViewById(R.id.btn_grant)
        displayView.surfaceView.holder.addCallback(this)
        permissionButton.setOnClickListener { requestUsbPermission() }
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
        // streaming/starting/waitingPermission 三重守卫，防止重试回调/重复插卡/面板等待期重复 start
        if (streaming || starting || waitingPermission) return
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
                c.start(device, displayView.surfaceView.holder)
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
                c.start(device, displayView.surfaceView.holder)
            } else {
                permissionText.setText(R.string.permission_denied)
                permissionPanel.visibility = View.VISIBLE
            }
        }
    }

    private fun hasCameraPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

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
        displayView.onStreamConfigured(width, height)
        // 启动每秒帧率统计（首秒前先展示分辨率占位）
        statsWidth = width
        statsHeight = height
        statsText.text = getString(R.string.stats_format, width, height, 0)
        statsText.visibility = View.VISIBLE
        mainHandler.removeCallbacks(statsRunnable)
        mainHandler.postDelayed(statsRunnable, 1_000)
    }

    override fun onUnavailable() {
        starting = false
        streaming = false
        mainHandler.removeCallbacks(statsRunnable)
        statsText.visibility = View.GONE
        Toast.makeText(this, R.string.stream_unavailable, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun onCaptureCardDetached() {
        starting = false
        streaming = false
        waitingPermission = false
        mainHandler.removeCallbacks(statsRunnable)
        statsText.visibility = View.GONE
        controller?.close()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(reopenRunnable)
        mainHandler.removeCallbacks(statsRunnable)
        plugMonitor?.unregister()
        controller?.close()
    }

    private companion object {
        const val MAX_RETRIES = 3
        const val RETRY_INTERVAL_MS = 2_000L
        const val REQ_CAMERA = 1
    }
}
