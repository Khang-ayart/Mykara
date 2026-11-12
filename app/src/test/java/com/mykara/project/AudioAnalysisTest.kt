package com.mykara.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

class AudioAnalysisTest {
    private val sr = 22050

    /** Nhạc giả: nhịp bass 80 Hz mỗi `intervalMs`, cộng nhiễu, có thể thêm "giai điệu" cao và bass trôi chậm. */
    private fun synth(seconds: Int, intervalMs: Int, music: Boolean, noise: Double = 0.02): Pair<ByteArray, List<Long>> {
        val n = sr * seconds
        val x = DoubleArray(n)
        val rnd = Random(1)
        for (i in 0 until n) x[i] = (rnd.nextDouble() * 2 - 1) * noise
        val truth = ArrayList<Long>()
        var k = 1
        while (k * intervalMs < seconds * 1000) {
            val startMs = k.toLong() * intervalMs
            truth.add(startMs)
            val i0 = (startMs * sr / 1000).toInt()
            val m = (0.12 * sr).toInt()
            for (j in 0 until m) {
                if (i0 + j >= n) break
                val t = j.toDouble() / sr
                x[i0 + j] += 0.8 * sin(2 * PI * 80 * t) * exp(-t * 25)
            }
            k++
        }
        if (music) {
            for (i in 0 until n) {
                val t = i.toDouble() / sr
                x[i] += 0.15 * sin(2 * PI * 440 * t) + 0.1 * sin(2 * PI * 1320 * t + 1) +
                    0.12 * sin(2 * PI * 110 * t) * (0.6 + 0.4 * sin(2 * PI * 0.25 * t))
            }
        }
        val bytes = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (x[i] * 32767).toInt().coerceIn(-32768, 32767)
            bytes[2 * i] = (v and 0xFF).toByte()
            bytes[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return Pair(bytes, truth)
    }

    private fun analyze(bytes: ByteArray): AudioAnalysis {
        val a = EnvelopeAnalyzer(sr, 1)
        var off = 0
        while (off < bytes.size) { // đưa từng khối như khi giải mã luồng
            val len = minOf(4096, bytes.size - off)
            a.feed(bytes.copyOfRange(off, off + len), len)
            off += len
        }
        return a.finish()
    }

    private fun score(beats: List<Long>, truth: List<Long>): Pair<Int, Int> {
        val matched = truth.count { t -> beats.any { abs(it - t) <= 70 } }
        val extra = beats.count { b -> truth.none { abs(it - b) <= 70 } }
        return Pair(matched, extra)
    }

    @Test
    fun findsBeatsInCleanSignal() {
        val (bytes, truth) = synth(20, 500, music = false)
        val r = analyze(bytes)
        val (matched, extra) = score(r.beatsMs, truth)
        assertTrue("khớp $matched/${truth.size}", matched >= truth.size - 2)
        assertTrue("thừa $extra", extra <= 2)
        assertNotNull(r.bpm)
        assertEquals(120.0, r.bpm!!, 3.0)
    }

    @Test
    fun findsBeatsWithMusicOnTop() {
        val (bytes, truth) = synth(20, 500, music = true)
        val (matched, extra) = score(analyze(bytes).beatsMs, truth)
        assertTrue("khớp $matched/${truth.size}", matched >= truth.size - 3)
        assertTrue("thừa $extra", extra <= 3)
    }

    @Test
    fun findsFasterBeats() {
        val (bytes, truth) = synth(20, 400, music = true)
        val r = analyze(bytes)
        val (matched, extra) = score(r.beatsMs, truth)
        assertTrue("khớp $matched/${truth.size}", matched >= truth.size - 3)
        assertTrue("thừa $extra", extra <= 3)
        assertEquals(150.0, r.bpm!!, 4.0)
    }

    @Test
    fun silenceHasNoBeatsAndEmptyBass() {
        val r = analyze(ByteArray(sr * 2 * 5))
        assertEquals(0, r.beatsMs.size)
        assertNull(r.bpm)
        assertTrue(r.bass.all { it == 0f })
    }

    @Test
    fun jsonMatchesSceneAnalysisShape() {
        val (bytes, _) = synth(10, 500, music = false)
        val j = analyze(bytes).toJson()
        assertEquals(30, j.getJSONObject("bassEnvelope").getInt("hz"))
        val values = j.getJSONObject("bassEnvelope").getJSONArray("values")
        assertTrue(values.length() in 295..301)
        for (i in 0 until values.length()) assertTrue(values.getDouble(i) in 0.0..1.0)
    }

    @Test
    fun stereoIsMixedDown() {
        val (mono, _) = synth(6, 500, music = false)
        val stereo = ByteArray(mono.size * 2)
        for (i in 0 until mono.size / 2) { // L = R = mono
            for (c in 0..1) {
                stereo[4 * i + 2 * c] = mono[2 * i]
                stereo[4 * i + 2 * c + 1] = mono[2 * i + 1]
            }
        }
        val a = EnvelopeAnalyzer(sr, 2)
        a.feed(stereo, stereo.size)
        val r = a.finish()
        assertTrue(r.beatsMs.isNotEmpty())
    }
}
