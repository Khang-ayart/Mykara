package com.mykara.project

import org.json.JSONArray
import org.json.JSONObject

/**
 * Chia một câu lời thành các đoạn hát liên tiếp (ví dụ "cố quên hết…" | "theo tháng năm…"): giữa hai đoạn ca sĩ
 * có thể nghỉ, màu tô đứng yên trong lúc nghỉ. Thuần tính toán, không phụ thuộc Android.
 *
 * Đoạn được chỉ bằng số thứ tự từ (token tách theo khoảng trắng): [from, to) là nửa mở.
 */
object Segments {
    class Seg(val from: Int, val to: Int, val startMs: Long, val endMs: Long)

    private val ELLIPSIS = Regex("(…|\\.{2,})[\"')\\]]*$")

    fun tokens(text: String): List<String> = text.split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** Mặc định: ngắt sau mỗi từ kết thúc bằng "…" hoặc "..", trừ từ cuối câu. */
    fun defaultBreaks(tokens: List<String>): List<Int> =
        tokens.indices.filter { it < tokens.size - 1 && ELLIPSIS.containsMatchIn(tokens[it]) }

    /** Chỗ ngắt do người dùng đặt (lyrics.lines[].breaks) nếu có, nếu không thì mặc định. */
    fun breaksOf(line: JSONObject, tokens: List<String>): List<Int> {
        val a = line.optJSONArray("breaks") ?: return defaultBreaks(tokens)
        return (0 until a.length()).map { a.getInt(it) }.filter { it in 0 until tokens.size - 1 }.distinct().sorted()
    }

    /** Các khoảng [from, to) theo chỗ ngắt (ngắt sau từ có chỉ số b). Câu rỗng không có đoạn nào. */
    fun ranges(tokenCount: Int, breaks: List<Int>): List<Pair<Int, Int>> {
        if (tokenCount <= 0) return emptyList()
        val out = ArrayList<Pair<Int, Int>>()
        var from = 0
        for (b in breaks.filter { it in 0 until tokenCount - 1 }.distinct().sorted()) {
            out.add(from to b + 1)
            from = b + 1
        }
        out.add(from to tokenCount)
        return out
    }

    /** Chỗ ngắt tương ứng với một danh sách khoảng. */
    fun breaksFrom(ranges: List<Pair<Int, Int>>): List<Int> = ranges.dropLast(1).map { it.second - 1 }

    /** Đoạn hợp lệ: phủ liên tục từ từ 0 đến hết, mỗi đoạn không rỗng, thời gian không lùi. */
    fun valid(segs: List<Seg>, tokenCount: Int): Boolean {
        if (segs.isEmpty() || tokenCount <= 0) return false
        var next = 0
        var t = 0L
        for (s in segs) {
            if (s.from != next || s.to <= s.from || s.endMs <= s.startMs || s.startMs < t) return false
            next = s.to
            t = s.endMs
        }
        return next == tokenCount
    }

    /**
     * Thời gian từng từ: trong mỗi đoạn chia theo độ dài chữ giữa đầu và cuối đoạn; từ cuối đoạn kết thúc đúng
     * ở cuối đoạn. Khoảng nghỉ giữa hai đoạn không thuộc từ nào.
     */
    fun wordTimes(tokens: List<String>, segs: List<Seg>): List<Pair<Long, Long>> {
        val out = ArrayList<Pair<Long, Long>>()
        for (s in segs) {
            val toks = tokens.subList(s.from, s.to)
            val total = toks.sumOf { it.length }.toDouble()
            var acc = 0.0
            var prevEnd = s.startMs
            for ((i, t) in toks.withIndex()) {
                acc += t.length
                val end = if (i == toks.size - 1) s.endMs else s.startMs + ((s.endMs - s.startMs) * acc / total).toLong()
                val e = maxOf(end, prevEnd)
                out.add(prevEnd to e)
                prevEnd = e
            }
        }
        return out
    }

    fun toJson(segs: List<Seg>): JSONArray = JSONArray().also { a ->
        for (s in segs) a.put(JSONObject().put("from", s.from).put("to", s.to).put("startMs", s.startMs).put("endMs", s.endMs))
    }

    fun fromJson(a: JSONArray?): List<Seg> {
        if (a == null) return emptyList()
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Seg(o.getInt("from"), o.getInt("to"), o.getLong("startMs"), o.getLong("endMs"))
        }
    }
}
