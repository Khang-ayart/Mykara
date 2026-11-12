package com.mykara.project

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapSyncTest {
    private val ids = listOf("l1", "l2", "l3")
    private val chars = listOf(10, 10, 10)

    @Test
    fun subtractsLatencyAndEndsBeforeNextLine() {
        val r = TapSync.compute(ids, chars, listOf(1000, 5000, 9000), 20000, latencyMs = 150)
        assertEquals(850L, r[0].startMs)
        assertEquals(4850L, r[1].startMs)
        // câu 1: tự nhiên = 850 + max(1500, 900) = 2350, ngắn hơn (câu sau - 100) nên giữ 2350
        assertEquals(2350L, r[0].endMs)
        assertTrue(r.all { it.endMs > it.startMs })
    }

    @Test
    fun endStopsBeforeNextWhenLinesAreClose() {
        val r = TapSync.compute(ids, listOf(100, 10, 10), listOf(1000, 3000, 6000), 20000, latencyMs = 0)
        // tự nhiên 1000 + 8000 > câu sau, nên kết thúc trước câu sau 100ms
        assertEquals(2900L, r[0].endMs)
    }

    @Test
    fun startsAreStrictlyIncreasingEvenIfTapsBunchUp() {
        val r = TapSync.compute(ids, chars, listOf(1000, 1050, 1060), null, latencyMs = 0)
        assertEquals(listOf(1000L, 1200L, 1400L), r.map { it.startMs })
    }

    @Test
    fun clampsLastLineToAudioDuration() {
        val r = TapSync.compute(listOf("l1"), listOf(200), listOf(9000), 10000, latencyMs = 0)
        assertEquals(10000L, r[0].endMs)
    }

    @Test
    fun stoppingEarlyOnlyTimesTheTappedLines() {
        val r = TapSync.compute(ids, chars, listOf(1000, 4000), 20000)
        assertEquals(2, r.size)
        assertEquals(listOf("l1", "l2"), r.map { it.lineId })
    }

    @Test
    fun noTapsGivesNothingAndNegativeStartIsClamped() {
        assertTrue(TapSync.compute(ids, chars, emptyList(), 1000).isEmpty())
        assertEquals(0L, TapSync.compute(listOf("l1"), listOf(5), listOf(50), null, latencyMs = 150)[0].startMs)
    }

    @Test
    fun setFromTapsWritesUserTimingAndStatus() {
        val d = ProjectDoc.newProject("A")
        ProjectDoc.setLyrics(d, "a b\nc d")
        ProjectTiming.setFromTaps(d, TapSync.compute(listOf("l1", "l2"), listOf(3, 3), listOf(1000, 5000), 20000))
        val lines = ProjectTiming.lines(d)
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.origin == "user" && it.confidence == 1.0 && !it.flagged })
        assertEquals("manual_tap", ProjectTiming.status(d))
    }

    @Test
    fun shiftKeepsLengthStaysInRangeAndMarksUser() {
        val d = ProjectDoc.newProject("A")
        ProjectDoc.setLyrics(d, "a\nb")
        d.getJSONObject("timing").getJSONArray("lines").put(
            JSONObject().put("lineId", "l1").put("startMs", 500L).put("endMs", 2500L).put("origin", "auto").put("flagged", true),
        )
        ProjectTiming.shift(d, "l1", -1000, 10000) // không xuống dưới 0
        var t = ProjectTiming.lines(d)[0]
        assertEquals(0L, t.startMs)
        assertEquals(2000L, t.endMs)
        assertEquals("user", t.origin)
        assertFalse(t.flagged)
        ProjectTiming.shift(d, "l1", 20000, 10000) // không vượt độ dài nhạc
        t = ProjectTiming.lines(d)[0]
        assertEquals(10000L, t.endMs)
        assertEquals(8000L, t.startMs)
    }
}
