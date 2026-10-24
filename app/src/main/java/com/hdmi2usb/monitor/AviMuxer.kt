package com.hdmi2usb.monitor

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * MJPEG + 可选 PCM 音轨的 AVI 1.0 封装器（仅录制写线程使用，非线程安全）。
 * 头部占位起写，[finalize] 时回填真实帧数/实测平均帧率/建议缓冲，最后补 idx1 索引。
 * idx 块偏移约定：相对 'movi' fourCC 位置（首个 chunk = 4），主流播放器均兼容。
 */
class AviMuxer(
    file: File,
    private val width: Int,
    private val height: Int,
    nominalFps: Int,
    val audioSpec: AudioStreamPlayer.AudioSpec?,
) {
    private val raf = RandomAccessFile(file, "rw")
    private val scale = 1000
    private val nominalRate = nominalFps.coerceAtLeast(1) * scale

    private val idx = ArrayList<IdxEntry>()
    private var moviFourccPos = 0L
    private var moviSizePos = 0L
    private var videoBytes = 0L
    private var maxFrame = 0
    private var maxAudioChunk = 0

    private var patchRiffSize = 0L
    private var patchHdrlSize = 0L
    private var patchAvihFrames = 0L
    private var patchAvihMaxBytes = 0L
    private var patchAvihSuggest = 0L
    private var patchVidRate = 0L
    private var patchVidLength = 0L
    private var patchVidSuggest = 0L
    private var patchAudLength = 0L
    private var patchAudSuggest = 0L

    private data class IdxEntry(val id: String, val size: Int, val offset: Int)

    /** 写头部（占位值）并打开 movi 区。 */
    @Throws(IOException::class)
    fun start() {
        raf.seek(0L)
        // RIFF('AVI ')
        cc("RIFF")
        patchRiffSize = raf.filePointer
        i32(0)
        cc("AVI ")
        // LIST(hdrl)
        cc("LIST")
        patchHdrlSize = raf.filePointer
        i32(0)
        cc("hdrl")
        // avih 主头
        cc("avih")
        i32(56)
        i32(scale * 1_000_000 / nominalRate)          // usec/frame（名义值，播放器多按 strh 帧率走）
        patchAvihMaxBytes = raf.filePointer
        i32(0)                                        // maxBytesPerSec
        i32(0)                                        // paddingGranularity
        i32(0x10)                                     // AVIF_HASINDEX
        patchAvihFrames = raf.filePointer
        i32(0)                                        // totalFrames
        i32(0)                                        // initialFrames
        i32(if (audioSpec != null) 2 else 1)          // streams
        patchAvihSuggest = raf.filePointer
        i32(0)                                        // suggestedBufferSize
        i32(width)
        i32(height)
        i32(0)
        i32(0)
        i32(0)
        i32(0)
        // 视频 strl
        cc("LIST")
        val vidStrlSizePos = raf.filePointer
        i32(0)
        cc("strl")
        cc("strh")
        i32(56)
        cc("vids")
        cc("MJPG")
        i32(0)                                        // flags
        i16(0)                                        // priority
        i16(0)                                        // language
        i32(0)                                        // initialFrames
        i32(scale)                                    // dwScale
        patchVidRate = raf.filePointer
        i32(nominalRate)                              // dwRate：fps = rate/scale
        i32(0)                                        // start
        patchVidLength = raf.filePointer
        i32(0)                                        // dwLength：帧数
        patchVidSuggest = raf.filePointer
        i32(0)                                        // suggestedBufferSize
        i32(-1)                                       // quality
        i32(0)                                        // sampleSize（视频流固定 0）
        i16(0)
        i16(0)
        i16(width)
        i16(height)                                   // rcFrame
        cc("strf")
        i32(40)
        i32(40)                                       // biSize
        i32(width)                                    // biWidth
        i32(height)                                   // biHeight（正数=底向上，MJPG 播放器按 JPEG 自解释）
        i32(1)                                        // biPlanes
        i32(24)                                       // biBitCount
        cc("MJPG")                                    // biCompression
        i32(width * height * 3)                       // biSizeImage
        i32(0)
        i32(0)
        i32(0)
        i32(0)
        i32At(vidStrlSizePos, (raf.filePointer - vidStrlSizePos - 4).toInt())
        // 音频 strl（可选）
        if (audioSpec != null) {
            cc("LIST")
            val audStrlSizePos = raf.filePointer
            i32(0)
            cc("strl")
            cc("strh")
            i32(56)
            cc("auds")
            i32(0)                                    // handler
            i32(0)
            i16(0)
            i16(0)
            i32(0)
            i32(audioSpec.blockAlign)                 // dwScale = 每样本块字节数
            i32(audioSpec.bytesPerSec)                // dwRate = 平均字节率
            i32(0)                                    // start
            patchAudLength = raf.filePointer
            i32(0)                                    // dwLength = 总块数
            patchAudSuggest = raf.filePointer
            i32(0)
            i32(-1)                                   // quality
            i32(audioSpec.blockAlign)                 // sampleSize
            i16(0)
            i16(0)
            i16(0)
            i16(0)                                    // rcFrame
            cc("strf")
            i32(18)                                   // WAVEFORMATEX
            i16(1)                                    // wFormatTag = PCM
            i16(audioSpec.channels)
            i32(audioSpec.sampleRate)
            i32(audioSpec.bytesPerSec)
            i16(audioSpec.blockAlign)
            i16(16)                                   // wBitsPerSample
            i16(0)                                    // cbSize
            i32At(audStrlSizePos, (raf.filePointer - audStrlSizePos - 4).toInt())
        }
        i32At(patchHdrlSize, (raf.filePointer - patchHdrlSize - 4).toInt())
        // LIST(movi)
        cc("LIST")
        moviSizePos = raf.filePointer
        i32(0)
        cc("movi")
        moviFourccPos = raf.filePointer - 4
    }

    fun writeVideo(jpeg: ByteArray) {
        videoBytes += jpeg.size
        if (jpeg.size > maxFrame) maxFrame = jpeg.size
        writeChunk("00dc", jpeg)
    }

    fun writeAudio(pcm: ByteArray) {
        if (pcm.size > maxAudioChunk) maxAudioChunk = pcm.size
        writeChunk("01wb", pcm)
    }

    private fun writeChunk(id: String, data: ByteArray) {
        val pos = raf.filePointer
        cc(id)
        i32(data.size)
        raf.write(data)
        if (data.size % 2 == 1) raf.write(0)          // WORD 对齐填充
        idx.add(IdxEntry(id, data.size, (pos - moviFourccPos).toInt()))
    }

    /**
     * 回填头部并写 idx1 索引。
     * [measuredFps] 为录制期间实测平均帧率（按帧到达时间戳计算），写回视频流帧率。
     */
    @Throws(IOException::class)
    fun finalize(totalFrames: Int, measuredFps: Double, totalAudioBytes: Long) {
        val moviEnd = raf.filePointer
        // idx1
        cc("idx1")
        i32(idx.size * 16)
        for (e in idx) {
            cc(e.id)
            i32(0x10)                                 // AVIIF_KEYFRAME（MJPEG 全关键帧）
            i32(e.offset)
            i32(e.size)
        }
        val rate = if (measuredFps in 1.0..240.0) (measuredFps * scale).toInt() else nominalRate
        i32At(patchRiffSize, (raf.filePointer - 8).toInt())
        i32At(moviSizePos, (moviEnd - moviSizePos - 4).toInt())
        i32At(patchVidRate, rate)
        i32At(patchVidLength, totalFrames)
        i32At(patchVidSuggest, maxFrame)
        i32At(patchAvihFrames, totalFrames)
        i32At(patchAvihSuggest, maxOf(maxFrame, maxAudioChunk))
        val durSec = if (rate > 0 && totalFrames > 0) totalFrames.toDouble() * scale / rate else 0.0
        val bytesPerSec = if (durSec > 0.2) ((videoBytes + totalAudioBytes) / durSec).toInt() else 0
        i32At(patchAvihMaxBytes, bytesPerSec)
        if (audioSpec != null) {
            i32At(patchAudLength, (totalAudioBytes / audioSpec.blockAlign).toInt())
            i32At(patchAudSuggest, maxAudioChunk)
        }
    }

    fun close() {
        try {
            raf.close()
        } catch (_: IOException) {
        }
    }

    private fun cc(s: String) = raf.write(s.toByteArray(Charsets.US_ASCII))

    private fun i32(v: Int) {
        raf.write(v)
        raf.write(v ushr 8)
        raf.write(v ushr 16)
        raf.write(v ushr 24)
    }

    private fun i16(v: Int) {
        raf.write(v)
        raf.write(v ushr 8)
    }

    private fun i32At(pos: Long, v: Int) {
        val back = raf.filePointer
        raf.seek(pos)
        i32(v)
        raf.seek(back)
    }
}
