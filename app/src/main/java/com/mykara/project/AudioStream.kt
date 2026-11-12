package com.mykara.project

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

/** Giải mã một file âm thanh theo luồng (không giữ cả bài trong bộ nhớ). */
object AudioStream {
    class Format(val sampleRate: Int, val channels: Int)

    /**
     * Giải mã [file] và gọi [onPcm] với từng khối PCM 16-bit xen kẽ kênh. [onFormat] được gọi trước khối đầu tiên
     * (và lại nếu codec báo đổi định dạng). Trả về thời lượng đã giải mã (mili-giây).
     */
    fun decode(file: File, onFormat: (Format) -> Unit, onPcm: (ByteArray, Int) -> Unit): Long {
        val ex = MediaExtractor()
        ex.setDataSource(file.absolutePath)
        var track = -1
        var fmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; fmt = f; break }
        }
        require(track >= 0 && fmt != null) { "Không tìm thấy luồng âm thanh trong file" }
        ex.selectTrack(track)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()

        var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        require(ch in 1..2) { "Chỉ hỗ trợ mono/stereo, file có $ch kênh" }
        onFormat(Format(sr, ch))
        var bytes = 0L
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
                    require(ch in 1..2) { "Chỉ hỗ trợ mono/stereo, file có $ch kênh" }
                    onFormat(Format(sr, ch))
                } else if (o >= 0) {
                    if (info.size > 0) {
                        val ob = codec.getOutputBuffer(o)!!
                        val chunk = ByteArray(info.size)
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        ob.get(chunk)
                        onPcm(chunk, chunk.size)
                        bytes += chunk.size
                    }
                    codec.releaseOutputBuffer(o, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            ex.release()
        }
        return bytes * 1000L / (sr.toLong() * ch * 2)
    }

    /** Dò điểm bắt đầu cụm âm của cả file (mili-giây). */
    fun phraseOnsets(file: File): List<Long> {
        var p: PhraseOnsets? = null
        decode(file, { f -> if (p == null) p = PhraseOnsets(f.sampleRate, f.channels) }, { b, n -> p!!.feed(b, n) })
        return p?.finish() ?: emptyList()
    }
}
