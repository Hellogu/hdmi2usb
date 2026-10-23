package com.hdmi2usb.monitor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
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

class MainActivity : Activity(), SurfaceHolder.Callback, CameraListener {

    private lateinit var displayView: DisplayView
    private lateinit var statusText: TextView
    private lateinit var permissionPanel: View
    private lateinit var permissionText: TextView
    private lateinit var permissionButton: Button

    private var controller: CameraController? = null
    private var plugMonitor: PlugMonitor? = null
    private var surfaceReady = false
    private var retryCount = 0
    private var streaming = false
    private var starting = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val reopenRunnable = Runnable { tryOpenCamera() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContentView(R.layout.activity_main)
        displayView = findViewById(R.id.display)
        statusText = findViewById(R.id.status_text)
        permissionPanel = findViewById(R.id.permission_panel)
        permissionText = findViewById(R.id.permission_text)
        permissionButton = findViewById(R.id.btn_grant)
        displayView.surfaceView.holder.addCallback(this)
        permissionButton.setOnClickListener {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startPipeline()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask：app 存活时再次插入采集卡，重置重试并重新打开
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED && surfaceReady) {
            retryCount = 0
            tryOpenCamera()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAMERA) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            permissionPanel.visibility = View.GONE
            startPipeline()
        } else {
            permissionText.setText(R.string.permission_denied)
            permissionButton.setText(R.string.retry_grant)
            permissionPanel.visibility = View.VISIBLE
        }
    }

    private fun startPipeline() {
        if (plugMonitor == null) plugMonitor = PlugMonitor(this) { onCaptureCardDetached() }
        plugMonitor?.register()
        if (controller == null) controller = CameraController(this, this)
        if (surfaceReady) tryOpenCamera()
    }

    private fun tryOpenCamera() {
        // 已在流或正在建立会话时直接返回，防止重试回调/重复插卡导致对已打开的相机再次 start
        if (streaming || starting) return
        val c = controller ?: return
        val id = c.findExternalCamera()
        if (id == null) {
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
            starting = true
            c.start(id, displayView.surfaceView.holder)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        if (controller != null) tryOpenCamera()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // 旋转/缩放只改变 surface 尺寸，相机输出自动缩放，无需重建会话
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
    }

    override fun onStreamReady(width: Int, height: Int) {
        starting = false
        streaming = true
        displayView.onStreamConfigured(width, height)
    }

    override fun onUnavailable() {
        starting = false
        streaming = false
        Toast.makeText(this, R.string.camera_unavailable, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun onCaptureCardDetached() {
        starting = false
        streaming = false
        controller?.close()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(reopenRunnable)
        plugMonitor?.unregister()
        controller?.close()
    }

    private companion object {
        const val REQ_CAMERA = 1
        const val MAX_RETRIES = 3
        const val RETRY_INTERVAL_MS = 2_000L
    }
}
