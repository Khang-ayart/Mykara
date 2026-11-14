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

    /** Một đoạn hát đã đồng bộ bằng cách giữ nút. */
    class PartEntry(val lineIdx: Int, val segIdx: Int, val startMs: Long, val endMs: Long)

    /** Giữ nút ngắn hơn mức này thì coi là chạm: điểm cuối được ước lượng theo độ dài chữ. */
    const val HOLD_MIN_MS = 250L

    /**
     * Đồng bộ kiểu giữ nút: mỗi đoạn có lúc nhấn xuống ([downsMs]) và lúc thả ([upsMs], null nếu không có).
     * Điểm đầu xử lý như [compute] (trừ độ trễ, hít về điểm dò, các điểm đầu cách nhau ≥ 200 ms). Điểm cuối là lúc
     * thả (cũng trừ độ trễ) nếu giữ đủ lâu; nếu chỉ chạm thì ước lượng theo độ dài chữ. Điểm cuối luôn nằm trước
     * điểm đầu đoạn sau và trong độ dài nhạc, nhưng đoạn nào cũng dài ít nhất 300 ms.
     */
    fun computeHold(
        partChars: List<Int>,
        downsMs: List<Long>,
        upsMs: List<Long?>,
        audioDurationMs: Long?,
        latencyMs: Long = DEFAULT_LATENCY_MS,
        onsetsMs: List<Long> = emptyList(),
        snapWindowMs: Long = SNAP_WINDOW_MS,
    ): List<LongArray> {
        val n = minOf(partChars.size, downsMs.size)
        val starts = LongArray(n)
        for (i in 0 until n) {
            var s = (downsMs[i] - latencyMs).coerceAtLeast(0L)
            snap(s, onsetsMs, snapWindowMs)?.let { s = it }
            if (i > 0 && s < starts[i - 1] + MIN_GAP_MS) s = starts[i - 1] + MIN_GAP_MS
            starts[i] = s
        }
        val out = ArrayList<LongArray>(n)
        for (i in 0 until n) {
            val up = upsMs.getOrNull(i)
            val held = up != null && up - downsMs[i] >= HOLD_MIN_MS
            var end = if (held) (up!! - latencyMs) else starts[i] + (partChars[i] * MS_PER_CHAR).coerceIn(600L, 8000L)
            if (i + 1 < n) end = minOf(end, starts[i + 1] - 50L)
            if (audioDurationMs != null) end = minOf(end, audioDurationMs)
            if (end < starts[i] + MIN_LEN_MS) end = starts[i] + MIN_LEN_MS
            out.add(longArrayOf(starts[i], end))
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
