package com.mykara.project

/**
 * Đồng bộ lời bằng cách gõ nhịp: người dùng nghe nhạc và chạm khi mỗi câu bắt đầu. Thuần tính toán.
 *
 * - Trừ một độ trễ cố định (người chạm thường chạm trễ so với tai nghe) rồi kẹp để các câu không chồng nhau.
 * - Điểm kết thúc câu = điểm bắt đầu câu sau trừ một khoảng nghỉ nhỏ; nếu câu sau quá xa (đoạn nhạc dạo)
 *   thì ước lượng theo độ dài chữ, không kéo dài tới câu sau.
 */
object TapSync {
    const val DEFAULT_LATENCY_MS = 150L
    private const val MIN_GAP_MS = 200L
    private const val MIN_LEN_MS = 300L
    private const val MS_PER_CHAR = 90L
    const val SNAP_WINDOW_MS = 250L

    class Entry(val lineId: String, val startMs: Long, val endMs: Long)

    fun compute(
        lineIds: List<String>,
        lineChars: List<Int>,
        tapsMs: List<Long>,
        audioDurationMs: Long?,
        latencyMs: Long = DEFAULT_LATENCY_MS,
        onsetsMs: List<Long> = emptyList(),
        snapWindowMs: Long = SNAP_WINDOW_MS,
    ): List<Entry> {
        require(lineIds.size == lineChars.size) { "lineIds và lineChars phải cùng số lượng" }
        val n = minOf(lineIds.size, tapsMs.size)
        if (n == 0) return emptyList()

        val starts = LongArray(n)
        for (i in 0 until n) {
            var s = (tapsMs[i] - latencyMs).coerceAtLeast(0L)
            snap(s, onsetsMs, snapWindowMs)?.let { s = it }
            if (i > 0 && s < starts[i - 1] + MIN_GAP_MS) s = starts[i - 1] + MIN_GAP_MS
            starts[i] = s
        }

        val out = ArrayList<Entry>(n)
        for (i in 0 until n) {
            val natural = starts[i] + (lineChars[i] * MS_PER_CHAR).coerceIn(1500L, 8000L)
            var end = if (i + 1 < n) minOf(natural, starts[i + 1] - 100L) else natural
            if (audioDurationMs != null) end = minOf(end, audioDurationMs)
            if (end < starts[i] + MIN_LEN_MS) end = starts[i] + MIN_LEN_MS
            out.add(Entry(lineIds[i], starts[i], end))
        }
        return out
    }

    /** Điểm dò gần nhất trong ±window quanh t, hoặc null nếu không có. Sai số tối đa của việc "hít" là window. */
    fun snap(t: Long, onsetsMs: List<Long>, windowMs: Long): Long? {
        var best: Long? = null
        var bd = Long.MAX_VALUE
        for (o in onsetsMs) {
            val d = Math.abs(o - t)
            if (d <= windowMs && d < bd) { bd = d; best = o }
        }
        return best
    }
}
