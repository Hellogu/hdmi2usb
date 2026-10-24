package com.hdmi2usb.monitor

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log

/**
 * 采集卡 UAC 音频直通：AudioRecord（USB 声卡录音口）→ AudioTrack（内置扬声器），
 * 可选 PCM 旁路 sink（录制用）。与视频链路（libuvc）解耦：音频接口由系统 USB 音频
 * HAL 管理，无需占用 UsbDeviceConnection。找不到 USB 录音口或格式协商失败时静默
 * 降级为无声，不影响视频。
 */
class AudioStreamPlayer(context: Context) {

    /** 录制侧需要的 PCM 规格（16bit 小端，与 AVI/WAV 字节序一致）。 */
    data class AudioSpec(val sampleRate: Int, val channels: Int) {
        val blockAlign = channels * 2
        val bytesPerSec = sampleRate * blockAlign
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var worker: Thread? = null

    @Volatile private var running = false
    @Volatile private var recordRef: AudioRecord? = null
    @Volatile private var pcmSink: ((ByteArray) -> Unit)? = null
    @Volatile private var currentSpec: AudioSpec? = null

    private data class PcmFormat(val sampleRate: Int, val inChannels: Int) {
        val inMask: Int =
            if (inChannels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val outMask: Int =
            if (inChannels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
    }

    /** 启动直通（幂等）。返回 false 表示当前无可用 USB 录音口，未启动。 */
    @Synchronized
    fun start(): Boolean {
        if (running) return true
        // 只认采集卡的录音口（isSource）；监听口（isSink）与内置设备一律排除
        val usbIn = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE && it.isSource }
            ?: run {
                Log.d(TAG, "uac passthrough: no usb audio input device")
                return false
            }
        running = true
        worker = Thread({ loop(usbIn) }, "UacPassthrough").apply { start() }
        Log.d(TAG, "uac passthrough: start")
        return true
    }

    /** 停止并释放（幂等）。中断阻塞读后由工作线程自行收口，勿在主线程做 release。 */
    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        try {
            recordRef?.stop()   // 让阻塞中的 read 立即返回，加速退出
        } catch (_: Exception) {
        }
        worker = null
        Log.d(TAG, "uac passthrough: stop")
    }

    /** 注册 PCM 旁路 sink（录制用；音频工作线程回调，实现必须非阻塞）。 */
    fun setPcmSink(sink: ((ByteArray) -> Unit)?) {
        pcmSink = sink
    }

    /** 当前生效的 PCM 规格（未运行/未协商完成时为 null），录制建 AVI 音频头用。 */
    fun currentSpec(): AudioSpec? = currentSpec

    private fun loop(usbIn: AudioDeviceInfo) {
        // HDMI 音频常见为 48kHz 立体声；构造失败时逐级降级尝试
        val candidates = listOf(
            PcmFormat(48_000, 2),
            PcmFormat(48_000, 1),
            PcmFormat(44_100, 2),
            PcmFormat(44_100, 1),
        )
        for (f in candidates) {
            val minBuf = AudioRecord.getMinBufferSize(
                f.sampleRate, f.inMask, AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) continue

            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.DEFAULT, f.sampleRate,
                    f.inMask, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2,
                )
            } catch (e: Exception) {
                Log.d(TAG, "uac record init fail ${f.sampleRate}x${f.inChannels}: ${e.message}")
                null
            }
            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                record?.release()
                continue
            }
            // 锁定录音源为 USB 声卡：路由失败（可能录到内置麦克风污染音轨）则放弃该格式
            if (!record.setPreferredDevice(usbIn)) {
                Log.d(TAG, "uac record route to usb fail")
                record.release()
                recordRef = null
                continue
            }
            recordRef = record

            // 播放失败不再致命：仅当播放与录制 sink 都不可用时才放弃该格式
            val built = buildTrack(f, minBuf)
            val track = if (built != null && built.state == AudioTrack.STATE_INITIALIZED) {
                built
            } else {
                built?.release()
                null
            }
            if (track == null && pcmSink == null) {
                record.release()
                recordRef = null
                continue
            }
            // 播放目标锁内置扬声器：防止系统把输出自动路由到卡的监听口（回环啸叫）
            // 找不到内置扬声器时保持默认路由（即扬声器），不清除偏好
            track?.let { t ->
                builtinSpeaker()?.let { spk ->
                    if (!t.setPreferredDevice(spk)) Log.d(TAG, "uac track route to speaker fail")
                }
            }

            val spec = AudioSpec(f.sampleRate, f.inChannels)
            currentSpec = spec
            try {
                record.startRecording()
                track?.play()
                val buf = ByteArray(minBuf)
                while (running) {
                    val n = record.read(buf, 0, buf.size)
                    if (n <= 0) break
                    track?.write(buf, 0, n)
                    // sink 持有独立副本（buf 复用），字节序为小端，与 AVI PCM 一致
                    pcmSink?.invoke(buf.copyOf(n))
                }
            } catch (e: Exception) {
                Log.d(TAG, "uac passthrough loop end: ${e.message}")
            } finally {
                currentSpec = null
                try {
                    record.stop()
                } catch (_: Exception) {
                }
                try {
                    track?.stop()
                } catch (_: Exception) {
                }
                record.release()
                track?.release()
                recordRef = null
            }
            Log.d(TAG, "uac passthrough: exited on ${f.sampleRate}x${f.inChannels}")
            return
        }
        Log.d(TAG, "uac passthrough: no usable pcm format")
    }

    private fun buildTrack(f: PcmFormat, minBuf: Int): AudioTrack? = try {
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(f.sampleRate)
                    .setChannelMask(f.outMask)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuf * 2)
            .build()
    } catch (e: Exception) {
        Log.d(TAG, "uac track init fail ${f.sampleRate}x${f.inChannels}: ${e.message}")
        null
    }

    private fun builtinSpeaker(): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER && it.isSink }

    private companion object {
        private const val TAG = "Hdmi2Usb"
    }
}
