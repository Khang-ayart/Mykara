package com.mykara.project

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sqrt

/** Kết quả phân tích nhạc, đúng dạng khối `analysis` của Scene JSON. */
class AudioAnalysis(val beatsMs: List<Long>, val bassHz: Int, val bass: FloatArray, val bpm: Double?) {
    fun toJson(): JSONObject {
        val bassArr = JSONArray()
        for (v in bass) bassArr.put(Math.round(v * 1000f) / 1000.0)
        val beats = JSONArray()
        for (b in beatsMs) beats.put(b)
        val o = JSONObject()
        o.put("beatsMs", beats)
        o.put("bassEnvelope", JSONObject().put("hz", bassHz).put("values", bassArr))
        if (bpm != null) o.put("bpm", Math.round(bpm * 10.0) / 10.0)
        return o
    }
}

/**
 * Phân tích nhạc dạng luồng (không cần giữ cả bài trong bộ nhớ): lọc thông thấp ~150 Hz lấy đường bass,
 * lấy RMS mỗi 1/30 giây, chuẩn hóa theo phân vị 98, rồi dò nhịp bằng đỉnh cục bộ vượt trung bình quanh nó.
 *
 * Đây là bộ dò nhịp đơn giản dựa trên bass: hợp với nhạc có trống/bass rõ; nhạc không trống (ballad, giọng hát
 * trần) sẽ cho ít hoặc không có nhịp, khi đó hiệu ứng theo nhịp đơn giản là không xuất hiện.
 */
class EnvelopeAnalyzer(private val sampleRate: Int, private val channels: Int, private val hz: Int = 30) {
    private val hop = maxOf(1, sampleRate / hz)
    private val alpha = 1.0 - exp(-2.0 * PI * 150.0 / sampleRate)
    private var y = 0.0
    private var acc = 0.0
    private var n = 0
    private val env = ArrayList<Float>()

    /** pcm: 16-bit little-endian, xen kẽ các kênh; len = số byte hợp lệ. */
    fun feed(pcm: ByteArray, len: Int) {
        val frameBytes = 2 * channels
        var i = 0
        while (i + frameBytes <= len) {
            var sum = 0
            for (c in 0 until channels) {
                val lo = pcm[i + 2 * c].toInt() and 0xFF
                val hi = pcm[i + 2 * c + 1].toInt()
                sum += (hi shl 8) or lo
            }
            val v = sum.toDouble() / channels / 32768.0
            y += alpha * (v - y)
            acc += y * y
            n++
            if (n == hop) {
                env.add(sqrt(acc / n).toFloat())
                acc = 0.0
                n = 0
            }
            i += frameBytes
        }
    }

    fun finish(): AudioAnalysis {
        val m = env.size
        if (m == 0) return AudioAnalysis(emptyList(), hz, FloatArray(0), null)
        val sorted = env.sorted()
        val p98 = sorted[minOf(m - 1, (m * 0.98).toInt())]
        if (p98 < 1e-4f) return AudioAnalysis(emptyList(), hz, FloatArray(m), null)
        val e = FloatArray(m) { (env[it] / p98).coerceIn(0f, 1f) }

        val beats = ArrayList<Long>()
        var last = -100
        val w = 45
        for (i in 1 until m - 1) {
            if (!(e[i] >= e[i - 1] && e[i] > e[i + 1])) continue
            val lo = maxOf(0, i - w)
            val hi = minOf(m, i + w + 1)
            var s = 0f
            for (k in lo until hi) s += e[k]
            val mean = s / (hi - lo)
            if (e[i] > 1.3f * mean + 0.05f && e[i] > 0.25f && i - last >= 8) {
                beats.add(i * 1000L / hz)
                last = i
            }
        }

        var bpm: Double? = null
        if (beats.size >= 8) {
            val intervals = (1 until beats.size).map { beats[it] - beats[it - 1] }.sorted()
            var b = 60000.0 / intervals[intervals.size / 2]
            while (b < 70.0) b *= 2.0
            while (b > 180.0) b /= 2.0
            bpm = b
        }
        return AudioAnalysis(beats, hz, e, bpm)
    }
}
