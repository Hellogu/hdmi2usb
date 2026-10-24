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
import android.util.Log
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
    @Volatile
    private var released = false

    fun findExternalCamera(): String? {
        val ids = cameraManager.cameraIdList
        val fromList = ids.firstOrNull { id ->
            try {
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_EXTERNAL
            } catch (_: Exception) {
                false
            }
        }
        if (fromList != null) return fromList
        // 部分 ROM（如 HyperOS）会把外接 USB 相机从 getCameraIdList() 过滤掉，
        // 但 getCameraCharacteristics 仍可直接访问。AOSP 外接相机 ID 从 100 起递增，逐个探测。
        for (probe in 100..110) {
            val idStr = probe.toString()
            try {
                val chars = cameraManager.getCameraCharacteristics(idStr)
                if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                    Log.d("Hdmi2Usb", "external camera found via probe: $idStr")
                    return idStr
                }
            } catch (_: Exception) {
                // 该 ID 不存在（未上电或非外接段），继续探测
            }
        }
        return null
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
        if (released) return
        cameraHandler.post {
            if (released) return@post
            cameraId = id
            this.holder = holder
            val frame = holder.surfaceFrame
            candidates = enumerateStreams(id, frame.width(), frame.height())
            Log.d("Hdmi2Usb", "start: candidates=${candidates.size} surface=${frame.width()}x${frame.height()}")
            if (candidates.isEmpty()) {
                mainHandler.post { listener.onUnavailable() }
                return@post
            }
            candidateIndex = 0
            reopenedOnce = false
            openCamera()
        }
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
                    Log.d("Hdmi2Usb", "openCamera onError=$error")
                    camera.close()
                    if (released) return
                    // 廉价 UVC 采集卡在 HAL 层偶发 ERROR_CAMERA_DEVICE，与 onDisconnected 同样给一次重开机会
                    if (!reopenedOnce) {
                        reopenedOnce = true
                        openCamera()
                    } else {
                        mainHandler.post { listener.onUnavailable() }
                    }
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
        Log.d("Hdmi2Usb", "createSession idx=$candidateIndex fmt=${candidate.format} ${candidate.width}x${candidate.height} fps=${candidate.minFps}-${candidate.maxFps}")
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
                        Log.d("Hdmi2Usb", "repeating request OK, stream ready")
                        mainHandler.post { listener.onStreamReady(candidate.width, candidate.height) }
                    } catch (e: Exception) {
                        Log.d("Hdmi2Usb", "setRepeatingRequest fail: $e")
                        advanceCandidate()
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    Log.d("Hdmi2Usb", "onConfigureFailed")
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
