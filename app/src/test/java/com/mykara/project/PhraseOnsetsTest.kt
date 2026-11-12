package com.mykara.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class PhraseOnsetsTest {
    private val sr = 16000

    /** Giọng giả (800 Hz + hài) theo từng đoạn; bass 80 Hz liên tục phía dưới. */
    private fun synth(bursts: List<Pair<Double, Double>>, seconds: Double, bass: Boolean = true): ByteArray {
        val n = (seconds * sr).toInt()
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            var v = if (bass) 0.4 * sin(2 * PI * 80 * t) else 0.0
            for ((s, e) in bursts) if (t in s..e) {
                val fade = minOf(1.0, (t - s) / 0.02, (e - t) / 0.05)
                v += 0.5 * fade * (sin(2 * PI * 800 * t) + 0.4 * sin(2 * PI * 1600 * t))
            }
            val x = (v.coerceIn(-1.0, 1.0) * 32000).toInt()
            out[2 * i] = (x and 0xFF).toByte(); out[2 * i + 1] = (x shr 8).toByte()
        }
        return out
    }

    private fun run(pcm: ByteArray): List<Long> {
        val p = PhraseOnsets(sr, 1)
        var o = 0
        while (o < pcm.size) { val l = minOf(4096, pcm.size - o); p.feed(pcm.copyOfRange(o, o + l), l); o += l }
        return p.finish()
    }

    @Test
    fun findsEachPhraseStartAndIgnoresBass() {
        val bursts = listOf(2.0 to 3.5, 5.0 to 6.5, 8.5 to 9.8, 12.0 to 13.5)
        val on = run(synth(bursts, 16.0))
        for ((s, _) in bursts) {
            val near = on.minByOrNull { Math.abs(it - s * 1000) }
            assertNotNull(near)
            assertTrue("điểm dò lệch quá xa: $near vs $s", Math.abs(near!! - s * 1000) < 300)
        }
        assertTrue("quá nhiều điểm thừa: $on", on.size <= bursts.size + 2)
    }

    @Test
    fun silenceAndPureBassGiveNothing() {
        assertEquals(0, run(synth(emptyList(), 10.0, bass = false)).size)
        assertEquals(0, run(synth(emptyList(), 10.0, bass = true)).size)
    }

    @Test
    fun snapPullsTapsToNearbyOnsetOnly() {
        val onsets = listOf(1000L, 4000L, 9000L)
        assertEquals(4000L, TapSync.snap(4200, onsets, 250))
        assertNull(TapSync.snap(6000, onsets, 250))
        val e = TapSync.compute(
            listOf("l1", "l2", "l3"), listOf(10, 10, 10),
            tapsMs = listOf(1230L, 4180L, 6500L), audioDurationMs = 20000, latencyMs = 150, onsetsMs = onsets,
        )
        assertEquals(1000L, e[0].startMs) // 1230-150=1080 → hít về 1000
        assertEquals(4000L, e[1].startMs) // 4180-150=4030 → 4000
        assertEquals(6350L, e[2].startMs) // không có điểm dò gần: giữ nguyên
    }

    @Test
    fun snapNeverBreaksOrdering() {
        // hai lần chạm gần nhau cùng hít về một điểm → vẫn cách nhau tối thiểu
        val e = TapSync.compute(
            listOf("l1", "l2"), listOf(10, 10), listOf(1100L, 1250L), 20000, 150, onsetsMs = listOf(1000L),
        )
        assertTrue(e[1].startMs >= e[0].startMs + 200)
    }
}
