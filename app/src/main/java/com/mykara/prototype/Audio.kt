package com.mykara.prototype

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** PCM 16-bit little-endian, xen kẽ các kênh. */
class PcmAudio(val pcm: ByteArray, val sampleRate: Int, val channels: Int)

/** Nhạc thử: tiếng bíp mỗi 500ms, khớp vòng xung trong hình, để kiểm tra đồng bộ hình/tiếng. */
object ToneAudio {
    fun generate(durationMs: Int): PcmAudio {
        val sr = 44100
        val ch = 2
        val frames = sr * durationMs / 1000
        val bb = ByteBuffer.allocate(frames * ch * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val t = i.toDouble() / sr
            val env = exp(-(t % 0.5) * 18.0)
            val s = 0.5 * env * sin(2 * PI * 880 * t) + 0.08 * sin(2 * PI * 220 * t)
            val v = (s * 32767).toInt().coerceIn(-32768, 32767).toShort()
            bb.putShort(v)
            bb.putShort(v)
        }
        return PcmAudio(bb.array(), sr, ch)
    }
}

/** Giải mã MP3/M4A/FLAC/WAV... thành PCM, lấy tối đa maxMs từ đầu bài. */
object AudioDecoder {
    fun decode(context: Context, uri: Uri, maxMs: Int): PcmAudio {
        val ex = MediaExtractor()
        ex.setDataSource(context, uri, null)
        var track = -1
        var fmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                track = i
                fmt = f
                break
            }
        }
        require(track >= 0 && fmt != null) { "Không tìm thấy luồng âm thanh trong file" }
        ex.selectTrack(track)

        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()

        var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var limit = (maxMs.toLong() * sr / 1000 * ch * 2).toInt()
        val out = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inDone = false
        var outDone = false
        try {
            while (!outDone) {
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val b = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(b, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    limit = (maxMs.toLong() * sr / 1000 * ch * 2).toInt()
                } else if (o >= 0) {
                    val ob = codec.getOutputBuffer(o)!!
                    if (info.size > 0 && out.size() < limit) {
                        val bytes = ByteArray(info.size)
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        ob.get(bytes)
                        out.write(bytes)
                    }
                    codec.releaseOutputBuffer(o, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
                    if (out.size() >= limit) outDone = true
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            ex.release()
        }

        require(ch in 1..2) { "Chỉ hỗ trợ mono/stereo, file có $ch kênh" }
        val frameBytes = 2 * ch
        val total = (maxMs.toLong() * sr / 1000).toInt() * frameBytes
        val pcm = ByteArray(total) // phần thiếu (bài ngắn hơn) để im lặng
        val raw = out.toByteArray()
        System.arraycopy(raw, 0, pcm, 0, minOf(raw.size - raw.size % frameBytes, total))
        return PcmAudio(pcm, sr, ch)
    }
}
