package com.hdmi2usb.monitor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Range
import android.view.SurfaceHolder

interface CameraListener {
    /** 流已就绪并开始渲染（主线程回调）。 */
    fun onStreamReady(width: Int, height: Int)
    /** 相机不可用，应提示并退出（主线程回调）。 */
    fun onUnavailable()
}

class CameraController(context: Context, private val listener: CameraListener) {

    private val cameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraThread = HandlerThread("CameraThread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var cameraId: String? = null
    private var holder: SurfaceHolder? = null
    private var candidates: List<StreamOption> = emptyList()
    private var candidateIndex = 0
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reopenedOnce = false
    private var released = false

    fun findExternalCamera(): String? =
        cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_EXTERNAL
        }

    /** 枚举外部相机候选流：压缩格式（MJPEG，对应 JPEG）优先、不超屏幕、按分辨率/帧率降序。 */
    fun enumerateStreams(id: String, screenW: Int, screenH: Int): List<StreamOption> {
        val map = cameraManager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return emptyList()
        val options = mutableListOf<StreamOption>()
        for (format in SUPPORTED_FORMATS) {
            val sizes = map.getOutputSizes(format) ?: continue
            for (size in sizes) {
                val minNs = map.getOutputMinFrameDuration(format, size) // 最短帧间隔 → 最高帧率
                val fpsMax = if (minNs > 0) (1_000_000_000.0 / minNs).toInt() else 0
                // 公开 API 无 getOutputMaxFrameDuration（API 36 桩确认不存在），下限取与上限一致（固定帧率范围）
                val fpsMin = fpsMax
                options += StreamOption(format, size.width, size.height, fpsMin, fpsMax)
            }
        }
        return StreamSelector.select(options, screenW, screenH)
    }

    fun start(id: String, holder: SurfaceHolder) {
        cameraId = id
        this.holder = holder
        val frame = holder.surfaceFrame
        candidates = enumerateStreams(id, frame.width(), frame.height())
        if (candidates.isEmpty()) {
            mainHandler.post { listener.onUnavailable() }
            return
        }
        candidateIndex = 0
        reopenedOnce = false
        openCamera()
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val id = cameraId ?: return
        try {
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (released) {
                        camera.close()
                        return
                    }
                    device = camera
                    createSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    session = null
                    if (released) return
                    if (!reopenedOnce) {
                        reopenedOnce = true
                        openCamera()
                    } else {
                        mainHandler.post { listener.onUnavailable() }
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (released) return
                    mainHandler.post { listener.onUnavailable() }
                }
            }, cameraHandler)
        } catch (_: SecurityException) {
            mainHandler.post { listener.onUnavailable() }
        } catch (_: CameraAccessException) {
            mainHandler.post { listener.onUnavailable() }
        }
    }

    private fun createSession() {
        val camera = device ?: return
        val surface = holder?.surface ?: return
        if (candidateIndex >= candidates.size) {
            mainHandler.post { listener.onUnavailable() }
            return
        }
        val candidate = candidates[candidateIndex]
        try {
            camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (released) {
                        s.close()
                        return
                    }
                    session = s
                    try {
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(surface)
                            if (candidate.maxFps > 0) {
                                set(
                                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                    Range(candidate.minFps.coerceIn(1, candidate.maxFps), candidate.maxFps),
                                )
                            }
                        }
                        s.setRepeatingRequest(request.build(), null, cameraHandler)
                        mainHandler.post { listener.onStreamReady(candidate.width, candidate.height) }
                    } catch (_: Exception) {
                        advanceCandidate()
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    s.close()
                    if (released) return
                    advanceCandidate()
                }
            }, cameraHandler)
        } catch (_: Exception) {
            advanceCandidate()
        }
    }

    /** 流协商失败 → 逐级降级：换下一个候选配置重建会话。 */
    private fun advanceCandidate() {
        session?.close()
        session = null
        candidateIndex++
        if (candidateIndex >= candidates.size) {
            mainHandler.post { listener.onUnavailable() }
        } else {
            createSession()
        }
    }

    fun close() {
        if (released) return
        released = true
        try {
            session?.close()
        } catch (_: Exception) {
        }
        try {
            device?.close()
        } catch (_: Exception) {
        }
        session = null
        device = null
        cameraThread.quitSafely()
    }

    private companion object {
        /** 采集卡常见输出格式，压缩流（JPEG/MJPEG）优先探测。 */
        val SUPPORTED_FORMATS =
            intArrayOf(ImageFormat.JPEG, ImageFormat.YUV_420_888, ImageFormat.PRIVATE)
    }
}
