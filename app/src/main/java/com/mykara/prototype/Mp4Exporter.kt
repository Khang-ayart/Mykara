package com.mykara.prototype

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer

class ExportConfig(
    val width: Int,
    val height: Int,
    val videoBitrate: Int,
    val fps: Int = 30,
    val durationMs: Int = 10_000,
)

class ExportResult(
    val file: File,
    val framesExpected: Int,
    val framesWritten: Int,
    val totalMs: Long,
    val audioEncodeMs: Long,
    val renderMs: Long,
    val encoderName: String,
    val hardwareEncoder: Boolean,
)

/**
 * Vẽ từng khung (theo thời gian cố định, không theo đồng hồ thật) vào Surface của bộ mã hóa H.264,
 * mã hóa âm thanh AAC, rồi ghép thành MP4.
 *
 * Lưu ý: khi vẽ bằng lockHardwareCanvas, thời điểm khung do hệ thống tự gán theo đồng hồ thật,
 * nên ta ghi đè lại mốc thời gian theo số thứ tự khung. Cách này đúng khi bộ mã hóa không dùng
 * khung B và không bỏ khung (đã có kiểm tra số khung cuối cùng). Bản chính thức nên chuyển sang EGL
 * để đặt mốc thời gian chính xác.
 */
class Mp4Exporter(
    private val cfg: ExportConfig,
    private val renderer: FrameRenderer,
    private val audio: PcmAudio,
    private val onProgress: (done: Int, total: Int) -> Unit,
) {
    private class Sample(val data: ByteArray, val ptsUs: Long)

    fun export(out: File): ExportResult {
        val startNs = System.nanoTime()
        val (audioFormat, audioSamples) = encodeAac(audio)
        val audioMs = (System.nanoTime() - startNs) / 1_000_000
        if (out.exists()) out.delete()

        val vfmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, cfg.width, cfg.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, cfg.videoBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, cfg.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val venc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var surface: Surface? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var videoTrack = -1
        var framesOut = 0
        var renderNs = 0L
        val info = MediaCodec.BufferInfo()
        val totalFrames = cfg.fps * cfg.durationMs / 1000

        try {
            venc.configure(vfmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = venc.createInputSurface()
            venc.start()
            val m = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m

            fun drain(eos: Boolean) {
                val deadline = System.nanoTime() + 15_000_000_000L
                while (true) {
                    val idx = venc.dequeueOutputBuffer(info, if (eos) 10_000L else 0L)
                    if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!eos) return
                        check(System.nanoTime() < deadline) { "Bộ mã hóa video không kết thúc (quá thời gian chờ)" }
                        continue
                    }
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        videoTrack = m.addTrack(venc.outputFormat)
                        val audioTrack = m.addTrack(audioFormat)
                        m.start()
                        muxerStarted = true
                        val ai = MediaCodec.BufferInfo()
                        for (s in audioSamples) {
                            ai.set(0, s.data.size, s.ptsUs, 0)
                            m.writeSampleData(audioTrack, ByteBuffer.wrap(s.data), ai)
                        }
                        continue
                    }
                    if (idx < 0) continue
                    val buf = venc.getOutputBuffer(idx)!!
                    val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (!isConfig && info.size > 0 && muxerStarted) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        info.presentationTimeUs = framesOut * 1_000_000L / cfg.fps
                        framesOut++
                        m.writeSampleData(videoTrack, buf, info)
                    }
                    val end = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    venc.releaseOutputBuffer(idx, false)
                    if (end) return
                }
            }

            for (i in 0 until totalFrames) {
                val tMs = i * 1000L / cfg.fps
                val t0 = System.nanoTime()
                val canvas = surface.lockHardwareCanvas()
                try {
                    renderer.draw(canvas, tMs, cfg.width, cfg.height)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
                renderNs += System.nanoTime() - t0
                drain(false)
                onProgress(i + 1, totalFrames)
            }
            venc.signalEndOfInputStream()
            drain(true)
            check(muxerStarted) { "Không ghi được video (muxer chưa khởi động)" }
        } finally {
            try { venc.stop() } catch (_: Exception) {}
            venc.release()
            surface?.release()
            if (muxerStarted) {
                try { muxer?.stop() } catch (_: Exception) {}
            }
            muxer?.release()
        }

        return ExportResult(
            file = out,
            framesExpected = totalFrames,
            framesWritten = framesOut,
            totalMs = (System.nanoTime() - startNs) / 1_000_000,
            audioEncodeMs = audioMs,
            renderMs = renderNs / 1_000_000,
            encoderName = venc.name,
            hardwareEncoder = venc.codecInfo.isHardwareAccelerated,
        )
    }

    private fun encodeAac(a: PcmAudio): Pair<MediaFormat, List<Sample>> {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, a.sampleRate, a.channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val samples = ArrayList<Sample>()
        var outFormat: MediaFormat? = null
        val info = MediaCodec.BufferInfo()
        val frameBytes = 2 * a.channels
        var offset = 0
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        buf.clear()
                        var n = minOf(buf.remaining(), a.pcm.size - offset)
                        n -= n % frameBytes
                        val ptsUs = (offset / frameBytes).toLong() * 1_000_000L / a.sampleRate
                        if (n <= 0) {
                            codec.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.put(a.pcm, offset, n)
                            codec.queueInputBuffer(idx, 0, n, ptsUs, 0)
                            offset += n
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, 10_000)
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outFormat = codec.outputFormat
                } else if (out >= 0) {
                    val ob = codec.getOutputBuffer(out)!!
                    val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (!isConfig && info.size > 0) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        ob.get(bytes)
                        samples.add(Sample(bytes, info.presentationTimeUs))
                    }
                    codec.releaseOutputBuffer(out, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
        }
        return Pair(checkNotNull(outFormat) { "Bộ mã hóa AAC không trả về định dạng" }, samples)
    }
}
