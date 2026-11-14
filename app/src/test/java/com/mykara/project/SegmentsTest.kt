package com.mykara.project

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentsTest {
    private val toks = Segments.tokens("cố quên hết.... theo tháng năm.....")

    @Test
    fun ellipsisInsideTheLineSplitsIntoParts() {
        assertEquals(listOf(2), Segments.defaultBreaks(toks))
        assertEquals(listOf(0 to 3, 3 to 6), Segments.ranges(toks.size, listOf(2)))
    }

    @Test
    fun trailingEllipsisAndPlainLinesAreNotSplit() {
        assertEquals(emptyList<Int>(), Segments.defaultBreaks(Segments.tokens("ngày xưa em đã…")))
        assertEquals(listOf(0 to 3), Segments.ranges(3, emptyList()))
        assertEquals(emptyList<Pair<Int, Int>>(), Segments.ranges(0, listOf(1)))
    }

    @Test
    fun userBreaksOverrideTheDefaultEvenWhenEmpty() {
        val line = JSONObject().put("breaks", JSONArray(listOf(0, 4)))
        assertEquals(listOf(0, 4), Segments.breaksOf(line, toks))
        assertEquals(emptyList<Int>(), Segments.breaksOf(JSONObject().put("breaks", JSONArray()), toks))
        assertEquals(listOf(2), Segments.breaksOf(JSONObject(), toks))
        assertEquals(listOf(0 to 1, 1 to 5, 5 to 6), Segments.ranges(6, listOf(0, 4)))
        assertEquals(listOf(0, 4), Segments.breaksFrom(Segments.ranges(6, listOf(0, 4))))
    }

    @Test
    fun wordsInsideAPartFillItAndThePauseBelongsToNoWord() {
        val segs = listOf(Segments.Seg(0, 3, 1000, 2500), Segments.Seg(3, 6, 4000, 5000))
        assertTrue(Segments.valid(segs, 6))
        val t = Segments.wordTimes(toks, segs)
        assertEquals(1000L, t[0].first)
        assertEquals(2500L, t[2].second) // từ cuối đoạn 1 kết thúc ở cuối đoạn
        assertEquals(4000L, t[3].first) // từ đầu đoạn 2 chỉ bắt đầu sau khoảng nghỉ
        assertEquals(5000L, t[5].second)
        for (i in 1..2) assertEquals(t[i - 1].second, t[i].first)
    }

    @Test
    fun invalidSegmentsAreRejected() {
        assertFalse(Segments.valid(listOf(Segments.Seg(0, 3, 1000, 2500)), 6)) // không phủ hết
        assertFalse(Segments.valid(listOf(Segments.Seg(0, 3, 1000, 2500), Segments.Seg(3, 6, 2000, 3000)), 6)) // chồng nhau
        assertFalse(Segments.valid(emptyList(), 6))
    }
}
