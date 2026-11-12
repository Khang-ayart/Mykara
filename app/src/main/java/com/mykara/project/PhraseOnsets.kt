package com.mykara.project

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Dò "điểm bắt đầu cụm âm" trong dải giọng hát (250–3500 Hz): chỗ năng lượng tăng vọt sau một quãng nhỏ hơn.
 * Dùng để "hít" mốc gõ nhịp về đúng chỗ, không dùng để tự đoán câu nào là câu nào (đã thử, không đủ tin cậy
 * trên bài hát thật; xem docs/PROTOTYPE_RESULTS.md).
 *
 * Luồng: feed(pcm) nhiều lần rồi finish(). Không cần giữ cả bài trong bộ nhớ.
 */
class PhraseOnsets(private val sampleRate: Int, private val channels: Int, private val hz: Int = 100) {
    private class Biquad(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) {
        private val nb0 = b0 / a0; private val nb1 = b1 / a0; private val nb2 = b2 / a0
        private val na1 = a1 / a0; private val na2 = a2 / a0
        private var z1 = 0.0; private var z2 = 0.0
        fun process(x: Double): Double {
            val y = nb0 * x + z1
            z1 = nb1 * x - na1 * y + z2
            z2 = nb2 * x - na2 * y
            return y
        }
    }

    private fun highpass(f: Double): Biquad {
        val w = 2 * PI * f / sampleRate; val q = 0.7071; val al = sin(w) / (2 * q); val c = cos(w)
        return Biquad((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al)
    }

    private fun lowpass(f: Double): Biquad {
        val w = 2 * PI * f / sampleRate; val q = 0.7071; val al = sin(w) / (2 * q); val c = cos(w)
        return Biquad((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al)
    }

    private val hp = arrayOf(highpass(250.0), highpass(250.0))
    private val lp = arrayOf(lowpass(3500.0), lowpass(3500.0))
    private val hop = maxOf(1, sampleRate / hz)
    private var acc = 0.0
    private var n = 0
    private val env = ArrayList<Float>()

    /** pcm: 16-bit little-endian xen kẽ các kênh; len = số byte hợp lệ. */
    fun feed(pcm: ByteArray, len: Int) {
        val fb = 2 * channels
        var i = 0
        while (i + fb <= len) {
            var sum = 0
            for (c in 0 until channels) {
                val lo = pcm[i + 2 * c].toInt() and 0xFF
                val hi = pcm[i + 2 * c + 1].toInt()
                sum += (hi shl 8) or lo
            }
            var v = sum.toDouble() / channels / 32768.0
            for (f in hp) v = f.process(v)
            for (f in lp) v = f.process(v)
            acc += v * v
            if (++n == hop) {
                env.add(sqrt(acc / n).toFloat())
                acc = 0.0; n = 0
            }
            i += fb
        }
    }

    /** Danh sách mili-giây các điểm bắt đầu cụm, tăng dần. */
    fun finish(): List<Long> {
        val m = env.size
        if (m < 60) return emptyList()
        val sorted = env.sorted()
        val p95 = sorted[minOf(m - 1, (m * 0.95).toInt())].toDouble()
        if (p95 < 1e-5) return emptyList()
        val e = DoubleArray(m) { ln(1.0 + env[it] / (p95 + 1e-9) * 10.0) }
        // làm mượt trung bình trượt 8 khung (~80 ms), căn giữa
        val pre = DoubleArray(m + 1)
        for (i in 0 until m) pre[i + 1] = pre[i] + e[i]
        val sm = DoubleArray(m) {
            val lo = maxOf(0, it - 4); val hi = minOf(m, it + 4)
            (pre[hi] - pre[lo]) / (hi - lo)
        }
        val rise = DoubleArray(m)
        for (i in 40 until m - 8) {
            var fut = 0.0
            for (k in i + 3 until i + 8) fut += sm[k]
            fut /= 5.0
            var mn = Double.MAX_VALUE
            for (k in i - 40 until i - 3) if (sm[k] < mn) mn = sm[k]
            rise[i] = fut - mn
        }
        val rs = rise.sortedArray()
        val thr = maxOf(rs[minOf(m - 1, (m * 0.85).toInt())], MIN_RISE)
        val out = ArrayList<Long>()
        var i = 0
        while (i < m) {
            if (rise[i] > thr) {
                var j = i
                var best = i
                while (j < m && rise[j] > thr) { if (rise[j] > rise[best]) best = j; j++ }
                out.add(best * 1000L / hz)
                i = j + 30 // ít nhất ~0,3 s giữa hai điểm
            } else i++
        }
        return out
    }

    private companion object {
        /** Độ tăng tối thiểu (đơn vị log-năng lượng chuẩn hóa) để coi là một điểm bắt đầu thật. */
        const val MIN_RISE = 0.3
    }
}
