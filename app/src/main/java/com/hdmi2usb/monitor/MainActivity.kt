package com.hdmi2usb.monitor

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.storage.StorageManager
import android.util.Log
import android.view.SurfaceHolder
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

class MainActivity : Activity(), SurfaceHolder.Callback, StreamListener {

    private lateinit var displayView: DisplayView
    private lateinit var statusText: TextView
    private lateinit var statsBar: View
    private lateinit var statsText: TextView
    private lateinit var settingsButton: TextView
    private lateinit var recordButton: TextView
    private lateinit var permissionPanel: View
    private lateinit var permissionText: TextView
    private lateinit var permissionButton: Button

    private var controller: UvcController? = null
    private var plugMonitor: PlugMonitor? = null
    private var audioPlayer: AudioStreamPlayer? = null
    @Volatile private var recorder: StreamRecorder? = null
    private var surfaceReady = false
    private var retryCount = 0
    private var streaming = false
    private var starting = false
    private var waitingPermission = false
    // RECORD_AUDIO 拒绝后本实例内不再自动重弹（与 USB 权限拒绝只等用户主动操作的风格一致）
    private var recordPermissionRequested = false
    // 无信号恢复循环：watchdog 无帧后不退出，改为提示并每 2 秒自动重启流等待源恢复
    private var waitingSignal = false
    // 恢复循环重试次数：≥2 次起降档 ≤720p（高规格档在 USB 2.0 等口会协商成功但无帧）
    private var noSignalRetries = 0
    private var pendingDevice: UsbDevice? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val reopenRunnable = Runnable { tryOpenStream() }

    private val signalRetryRunnable = Runnable { retryAfterNoSignal() }

    // 顶栏自动隐藏（单击画面唤出；录制中常亮——showStats 对录制会话不挂隐藏任务）
    private val hideStatsRunnable = Runnable { statsBar.visibility = View.GONE }

    // targetSdk 36 预测返回下 onBackPressed 不可靠：用 dispatcher 注册返回拦截做退出二次确认
    private val backCallback = object : OnBackInvokedCallback {
        override fun onBackInvoked() = confirmExit()
    }
    private var exitDialog: Dialog? = null
    private var gestureDialog: Dialog? = null
    // 本会话已弹过手势引导（切档/断流恢复重新出画不再弹；冷启动重新计数）
    private var gestureHintShownThisSession = false

    // 每秒读一次渲染层收帧计数（takeFrameCount 自清零），差值即实际 fps
    private val statsRunnable = object : Runnable {
        override fun run() {
            val count = controller?.takeFrameCount() ?: run {
                statsBar.visibility = View.GONE
                return
            }
            statsText.text = getString(R.string.stats_format, statsWidth, statsHeight, count)
            // 首帧到达才收起等待提示：无信号恢复期间提示常驻，避免「显示 2s → 黑屏 10s」闪烁
            if (count > 0 && statusText.visibility == View.VISIBLE) {
                statusText.visibility = View.GONE
            }
            updateRecordButton()
            mainHandler.postDelayed(this, 1_000)
        }
    }
    private var statsWidth = 0
    private var statsHeight = 0
    private var statsFps = 30

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
        recordButton = findViewById(R.id.record_button)
        permissionPanel = findViewById(R.id.permission_panel)
        permissionText = findViewById(R.id.permission_text)
        permissionButton = findViewById(R.id.btn_grant)
        displayView.surfaceView.holder.addCallback(this)
        // 单击画面唤出顶栏（双击仍是 FIT/STRETCH 切换，GestureDetector 内互不干扰）
        displayView.onSingleTap = { showStats() }
        permissionButton.setOnClickListener { requestUsbPermission() }
        settingsButton.setOnClickListener { showFormatDialog() }
        recordButton.setOnClickListener { toggleRecord() }
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback,
        )
        // 冷启动入口：可能是 launcher 直接打开，也可能带 EXTRA_DEVICE 的 attach intent
        handleIntent(intent)
    }

    /** 返回键二次确认：深色圆角小卡片，与手势引导弹窗风格一致；确认后真正退出（onStop/onDestroy 完成收尾）。 */
    private fun confirmExit() {
        if (exitDialog?.isShowing == true) return
        val dp = resources.displayMetrics.density
        fun label(textRes: Int, color: Int, endPad: Int, onClick: () -> Unit): TextView =
            TextView(this).apply {
                setText(textRes)
                textSize = 13f
                setTextColor(color)
                setPadding(0, (10 * dp).toInt(), endPad, 0)
                setOnClickListener { onClick() }
            }
        val title = TextView(this).apply {
            setText(R.string.exit_title)
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, (8 * dp).toInt())
        }
        val msg = TextView(this).apply {
            setText(R.string.exit_confirm)
            textSize = 13f
            setTextColor(0xFFBDBDBD.toInt())
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(label(R.string.cancel, 0xFFBDBDBD.toInt(), (20 * dp).toInt()) { exitDialog?.dismiss() })
            addView(label(R.string.exit_ok, 0xFFE53935.toInt(), 0) { exitDialog?.dismiss(); finish() })
        }
        exitDialog = cardDialog(listOf(title, msg, row)).also { it.show() }
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
        if (audioPlayer == null) audioPlayer = AudioStreamPlayer(this)
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

    private fun hasRecordPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** 音频直通跟随视频出画启动；无录音权限时只请求一次，拒绝则本次会话保持无声。 */
    private fun tryStartAudio() {
        if (!hasRecordPermission()) {
            if (!recordPermissionRequested) {
                recordPermissionRequested = true
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_RECORD)
            }
            return
        }
        audioPlayer?.start()
    }

    /** 画质选择（两级）：一级=自动+常用档+更多；二级=全部档位。选择持久化，重启流生效。 */
    private fun showFormatDialog() {
        val all = controller?.manualChoices().orEmpty()
        if (all.isEmpty()) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val common = all.filter { it.width.toLong() * it.height >= COMMON_MIN_PIXELS }
        val cur = loadPreferred()
        val labels = mutableListOf(getString(R.string.format_auto))
        labels += common.map { getString(R.string.format_item, it.width, it.height, it.maxFps) }
        if (all.size > common.size) labels += getString(R.string.format_more)
        val checked = if (cur == null) 0
        else (common.indexOfFirst { it.width == cur.width && it.height == cur.height && it.maxFps == cur.maxFps } + 1)
            .takeIf { it > 0 } ?: -1
        AlertDialog.Builder(this)
            .setTitle(R.string.format_title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                when {
                    which == 0 -> {
                        prefs.edit().remove(PREF_W).remove(PREF_H).remove(PREF_FPS).apply()
                        applyPreferred(null)
                    }
                    which <= common.size -> applyChoice(common[which - 1])
                    else -> showAllDialog(all)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 二级菜单：全部档位（含常用，便于一次看全）。 */
    private fun showAllDialog(all: List<StreamOption>) {
        val cur = loadPreferred()
        val labels = all.map { getString(R.string.format_item, it.width, it.height, it.maxFps) }
        val checked = cur?.let {
            all.indexOfFirst { c -> c.width == it.width && c.height == it.height && c.maxFps == it.maxFps }
        }?.takeIf { it >= 0 } ?: -1
        AlertDialog.Builder(this)
            .setTitle(R.string.format_more)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                applyChoice(all[which])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyChoice(c: StreamOption) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt(PREF_W, c.width).putInt(PREF_H, c.height).putInt(PREF_FPS, c.maxFps)
            .apply()
        applyPreferred(c)
    }

    /** 应用手动画质：重启取流（手动档失败时自动回落）。 */
    private fun applyPreferred(preferred: StreamOption?) {
        if (starting) return
        val c = controller ?: return
        val device = pendingDevice ?: c.findDevice() ?: return
        // 正常播放中点选与当前一致的档位（手动同档，或自动首选恰好等于当前档）：
        // 重启流黑屏无收益，直接忽略（无信号状态下仍放行以允许手动重启）
        if (streaming) {
            val target = preferred ?: c.autoFirstChoice()
            if (target != null && target.width == statsWidth &&
                target.height == statsHeight && target.maxFps == statsFps
            ) return
        }
        waitingSignal = false
        mainHandler.removeCallbacks(signalRetryRunnable)
        statusText.visibility = View.GONE
        teardownPipeline()    // 切画质按断流策略收尾录制并静音，onStreamReady 后自动恢复
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

    // ==== 录制 ====

    /** ⏺ 切换：录制中则停止收尾；未录制时要求流在播、空间充足。 */
    private fun toggleRecord() {
        val rec = recorder
        if (rec != null && rec.isRecording) {
            stopRecording()
            return
        }
        if (!streaming || starting) return
        if (!hasFreeSpace()) {
            Toast.makeText(applicationContext, R.string.record_no_space, Toast.LENGTH_SHORT).show()
            return
        }
        // 音频规格取当前直通协商结果；直通未运行（无音轨/无权限）则按决议降级纯视频
        val spec = audioPlayer?.currentSpec()
        val newRecorder = StreamRecorder(
            this, statsWidth, statsHeight, statsFps, spec, mainHandler,
        ) { r, name -> onRecordFinished(r, name) }
        if (!newRecorder.startSucceeded) {
            Toast.makeText(applicationContext, R.string.record_failed, Toast.LENGTH_SHORT).show()
            return
        }
        recorder = newRecorder
        controller?.frameTap = newRecorder::onFrame
        audioPlayer?.setPcmSink(newRecorder::onPcm)
        updateRecordButton()
        showStats()   // 录制中顶栏常亮（时长反馈 + 随时停录）
    }

    /** 停止录制并断开帧/音频旁路（收尾入库由写线程异步完成）。 */
    private fun stopRecording() {
        recorder?.stop()
        resetRecordingState()
    }

    /** 复位录制会话状态（stopRecording 与过期收尾回调共用）。 */
    private fun resetRecordingState() {
        recorder = null
        controller?.frameTap = null
        audioPlayer?.setPcmSink(null)
        updateRecordButton()
        showStats()   // 录制结束，顶栏回到 5 秒自动隐藏节奏
    }

    private fun onRecordFinished(finished: StreamRecorder, name: String?) {
        // 仅当收尾的仍是当前会话时复位（手动停止已先置空；期间新起的录制不能误伤）
        if (recorder === finished) resetRecordingState()
        Toast.makeText(
            applicationContext,
            if (name != null) getString(R.string.record_saved, name)
            else getString(R.string.record_failed),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun updateRecordButton() {
        val rec = recorder
        if (rec != null && rec.isRecording) {
            val sec = (SystemClock.elapsedRealtime() - rec.startedAtRealtime) / 1000
            recordButton.text = getString(R.string.record_active, (sec / 60).toInt(), (sec % 60).toInt())
            recordButton.setTextColor(0xFFE53935.toInt())
        } else {
            recordButton.text = getString(R.string.record_icon)
            recordButton.setTextColor(0xFFFFFFFF.toInt())
        }
    }

    /** 起录空间底线：公共存储可分配字节 ≥1GB（比 availableBytes 更保守，计入系统保留）。 */
    private fun hasFreeSpace(): Boolean = try {
        val sm = getSystemService(StorageManager::class.java)
        sm.getAllocatableBytes(StorageManager.UUID_DEFAULT) >= MIN_FREE_BYTES
    } catch (e: Exception) {
        Log.d(TAG, "query free space fail: ${e.message}")
        false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RECORD) {
            // 授权成功且视频正在播放：补启音频；拒绝则保持无声，不自动重弹
            if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                streaming
            ) {
                audioPlayer?.start()
            }
            return
        }
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
        // 转屏等场景 surface 销毁重建而流未中断：renderer 还持旧 holder 会永久黑屏，需刷新绑定
        if (streaming) controller?.refreshRenderer(holder)
        if (controller != null) tryOpenStream()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // 旋转/缩放只改变 surface 尺寸，渲染层下一帧自然画到新尺寸，无需重启流
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
    }

    override fun onStreamReady(width: Int, height: Int, fps: Int) {
        starting = false
        streaming = true
        waitingSignal = false
        mainHandler.removeCallbacks(signalRetryRunnable)
        // 恢复循环中流重启成功：占用已解除，提示切回无信号语义；首帧到达后由 statsRunnable 收起
        if (statusText.visibility == View.VISIBLE) {
            statusText.setText(R.string.no_signal)
        }
        displayView.onStreamConfigured(width, height)
        noSignalRetries = 0   // 恢复出画，重置降档计数（下次无信号从原档位重试）
        // 启动每秒帧率统计（首秒前先展示分辨率占位）
        statsWidth = width
        statsHeight = height
        statsFps = fps
        statsText.text = getString(R.string.stats_format, width, height, 0)
        showStats()
        mainHandler.removeCallbacks(statsRunnable)
        mainHandler.postDelayed(statsRunnable, 1_000)
        showGestureHintOnce()
        tryStartAudio()
    }

    /** 首次进 app 的手势引导：紧凑深色小卡片，勾选「不再提示」后关闭，之后不再出现。 */
    private fun showGestureHintOnce() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (gestureHintShownThisSession ||
            prefs.getBoolean(PREF_GESTURE_HINT, false) ||
            gestureDialog?.isShowing == true
        ) return
        gestureHintShownThisSession = true
        val dp = resources.displayMetrics.density
        val msg = TextView(this).apply {
            setText(R.string.gesture_dialog_msg)
            textSize = 14f
            setTextColor(0xFFE0E0E0.toInt())
        }
        val box = CheckBox(this).apply {
            setText(R.string.gesture_never)
            textSize = 13f
            setTextColor(0xFFBDBDBD.toInt())
        }
        val close = TextView(this).apply {
            setText(R.string.gesture_close)
            textSize = 13f
            setTextColor(0xFF64D8CB.toInt())
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), 0, 0)
            setOnClickListener {
                if (box.isChecked) {
                    getSharedPreferences(PREFS, MODE_PRIVATE)
                        .edit().putBoolean(PREF_GESTURE_HINT, true).apply()
                }
                gestureDialog?.dismiss()
            }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(box, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(close)
        }
        gestureDialog = cardDialog(listOf(msg, row)).also { it.show() }
    }

    /** 深色圆角小卡片对话框（240dp 宽），与全屏画面风格一致。 */
    private fun cardDialog(rows: List<View>): Dialog {
        val dp = resources.displayMetrics.density
        val pad = (14 * dp).toInt()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad / 2)
            rows.forEach { addView(it) }
            background = GradientDrawable().apply {
                setColor(0xF21E1E1E.toInt())
                cornerRadius = 12 * dp
            }
        }
        return Dialog(this).apply {
            setContentView(card)
            setCanceledOnTouchOutside(false)
            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout((240 * dp).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
    }

    /** 显示顶栏并重置自动隐藏计时（录制中不挂隐藏任务，常亮）。 */
    private fun showStats() {
        mainHandler.removeCallbacks(hideStatsRunnable)
        statsBar.visibility = View.VISIBLE
        if (recorder == null) mainHandler.postDelayed(hideStatsRunnable, STATS_AUTOHIDE_MS)
    }

    override fun onUnavailable() {
        starting = false
        streaming = false
        teardownPipeline()
        // 恢复循环中的启动失败：提示切为「可能被占用」并继续重试（静默返回会中断恢复循环）
        if (waitingSignal) {
            mainHandler.removeCallbacks(statsRunnable)
            statsBar.visibility = View.GONE
            statusText.setText(R.string.stream_busy_retry)
            statusText.visibility = View.VISIBLE
            mainHandler.postDelayed(signalRetryRunnable, RETRY_INTERVAL_MS)
            return
        }
        mainHandler.removeCallbacks(statsRunnable)
        statsBar.visibility = View.GONE
        Toast.makeText(this, R.string.stream_unavailable, Toast.LENGTH_SHORT).show()
        finish()
    }

    /** 看门狗无帧：进入常驻恢复循环，每 2 秒重启流直到源恢复（拔卡/销毁时打断）。 */
    override fun onNoSignal() {
        streaming = false
        starting = false
        teardownPipeline()
        if (waitingSignal) return
        waitingSignal = true
        noSignalRetries = 0
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
        noSignalRetries++
        // 第 1 次重试沿用当前档位（可能只是源瞬时中断）；
        // 之后降档 ≤720p 并忽略手动锁定：高规格档在 USB 2.0 等口会「协商成功但无帧」，降档才能恢复出画
        val preferred = if (noSignalRetries == 1) loadPreferred() else null
        val maxPixels = if (noSignalRetries >= 2) RECOVERY_MAX_PIXELS else Long.MAX_VALUE
        // start 失败回调 onUnavailable：切「被占用」提示并由本循环继续重试
        c.start(device, displayView.surfaceView.holder, preferred, maxPixels)
    }

    private fun onCaptureCardDetached() {
        starting = false
        streaming = false
        waitingPermission = false
        waitingSignal = false
        teardownPipeline()
        mainHandler.removeCallbacks(statsRunnable)
        mainHandler.removeCallbacks(signalRetryRunnable)
        statusText.visibility = View.GONE
        statsBar.visibility = View.GONE
        controller?.close()
        finish()
    }

    /** 流拆除共用收尾：音频直通停止 + 录制异步收尾（断流/切画质/拔卡/销毁共用）。 */
    private fun teardownPipeline() {
        audioPlayer?.stop()
        stopRecording()
    }

    /** 退后台/熄屏：立即静音并收尾录制（视频流保持，回到前台画面即恢复、onStart 补启音频）。 */
    override fun onStop() {
        super.onStop()
        audioPlayer?.stop()
        stopRecording()
    }

    override fun onStart() {
        super.onStart()
        // 回前台恢复音频直通（start 幂等；未授权/流未播时 start 内部自行处理为无声）
        if (streaming && hasRecordPermission()) audioPlayer?.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback)
        mainHandler.removeCallbacks(reopenRunnable)
        mainHandler.removeCallbacks(statsRunnable)
        mainHandler.removeCallbacks(hideStatsRunnable)
        mainHandler.removeCallbacks(signalRetryRunnable)
        plugMonitor?.unregister()
        // 先停音频/录制再关流：防止 close() 异常跳过音频释放（返回后声音仍在的历史问题）
        teardownPipeline()
        controller?.close()
    }

    private companion object {
        const val TAG = "Hdmi2Usb"
        const val PREF_GESTURE_HINT = "gesture_dialog_dismissed"
        const val MAX_RETRIES = 3
        const val RETRY_INTERVAL_MS = 2_000L
        // 顶栏自动隐藏：单击画面显示，无操作 5 秒后隐藏（录制中常亮，见 hideStats）
        const val STATS_AUTOHIDE_MS = 5_000L
        const val REQ_CAMERA = 1
        const val REQ_RECORD = 2
        // 常用档位门槛：≥720p 面积（1280x720），更低的老式档位进「更多规格」
        const val COMMON_MIN_PIXELS = 1280L * 720
        // 无信号恢复降档上限：≥2 次重试仍无帧时只试 ≤720p 档位
        const val RECOVERY_MAX_PIXELS = 1280L * 720
        const val MIN_FREE_BYTES = 1L shl 30    // 起录底线：剩余空间 ≥1GB
        const val PREFS = "uvc"
        const val PREF_W = "preferred_w"
        const val PREF_H = "preferred_h"
        const val PREF_FPS = "preferred_fps"
    }
}
