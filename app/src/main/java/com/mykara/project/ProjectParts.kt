package com.mykara.project

import org.json.JSONArray
import org.json.JSONObject

/** Khối `timing` của project.json. */
object ProjectTiming {
    class Line(
        val lineId: String,
        val startMs: Long,
        val endMs: Long,
        val flagged: Boolean,
        val origin: String,
        val confidence: Double?,
    )

    fun lines(doc: JSONObject): List<Line> {
        val arr = doc.getJSONObject("timing").getJSONArray("lines")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Line(
                o.getString("lineId"), o.getLong("startMs"), o.getLong("endMs"),
                o.optBoolean("flagged", false), o.optString("origin", "auto"),
                if (o.has("confidence")) o.getDouble("confidence") else null,
            )
        }
    }

    fun status(doc: JSONObject): String = doc.getJSONObject("timing").optString("status", "none")

    /** Thay toàn bộ timing bằng kết quả gõ nhịp (người dùng chủ động đồng bộ lại nên ghi đè cả mốc cũ). */
    fun setFromTaps(doc: JSONObject, entries: List<TapSync.Entry>) {
        val timing = doc.getJSONObject("timing")
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject().put("lineId", e.lineId).put("startMs", e.startMs).put("endMs", e.endMs)
                    .put("confidence", 1.0).put("origin", "user"),
            )
        }
        timing.put("lines", arr)
        timing.put("status", "manual_tap")
    }

    /** Dịch cả câu (giữ độ dài) theo deltaMs; không đẩy ra ngoài [0, độ dài nhạc]. Đánh dấu do người dùng chỉnh. */
    fun shift(doc: JSONObject, lineId: String, deltaMs: Long, audioDurationMs: Long?) {
        val arr = doc.getJSONObject("timing").getJSONArray("lines")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getString("lineId") != lineId) continue
            val s = o.getLong("startMs")
            val e = o.getLong("endMs")
            var d = deltaMs
            if (s + d < 0) d = -s
            if (audioDurationMs != null && e + d > audioDurationMs) d = (audioDurationMs - e).coerceAtLeast(0L)
            o.put("startMs", s + d).put("endMs", e + d).put("origin", "user")
            o.remove("flagged")
            o.remove("words")
            return
        }
    }
}

/** Khối `design` của project.json: template, tỷ lệ, lớp hình ngữ nghĩa. */
object ProjectDesign {
    fun templateId(doc: JSONObject): String = doc.getJSONObject("design").getJSONObject("template").optString("id", "cinematic")

    fun templateOrigin(doc: JSONObject): String = doc.getJSONObject("design").getJSONObject("template").optString("origin", "auto")

    fun setTemplate(doc: JSONObject, id: String, origin: String) {
        val old = doc.getJSONObject("design").optJSONObject("template")
        val t = JSONObject().put("id", id).put("origin", origin)
        // Cùng template thì giữ font và "lần làm lại"; đổi template thì bắt đầu mới.
        if (old != null && old.optString("id") == id) old.optJSONObject("params")?.let { t.put("params", it) }
        doc.getJSONObject("design").put("template", t)
    }

    private fun tplParams(doc: JSONObject): JSONObject {
        val t = doc.getJSONObject("design").getJSONObject("template")
        return t.optJSONObject("params") ?: JSONObject().also { t.put("params", it) }
    }

    /** Số lần người dùng bấm "Làm lại": đổi hạt giống của mọi lựa chọn ngẫu nhiên có kiểm soát (bố cục, kiểu vào/ra, ảnh nền đầu). */
    fun variant(doc: JSONObject): Int =
        doc.getJSONObject("design").getJSONObject("template").optJSONObject("params")?.optInt("variant", 0) ?: 0

    fun bumpVariant(doc: JSONObject) { tplParams(doc).put("variant", variant(doc) + 1) }

    fun fontId(doc: JSONObject): String? =
        doc.getJSONObject("design").getJSONObject("template").optJSONObject("params")?.optString("fontId", "")?.ifEmpty { null }

    fun setFont(doc: JSONObject, id: String) {
        tplParams(doc).put("fontId", id)
        doc.getJSONObject("design").getJSONObject("template").put("origin", "user")
    }

    /** Chỉ số từ nhấn do người dùng chọn cho một câu; null = chưa chọn (dùng cách tự động của template). */
    fun emphasis(doc: JSONObject, lineId: String): List<Int>? {
        val arr = doc.getJSONObject("design").optJSONArray("lines") ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getString("lineId") != lineId) continue
            val e = o.optJSONArray("emphasis") ?: return null
            return (0 until e.length()).map { e.getInt(it) }
        }
        return null
    }

    /** Lưu từ nhấn của một câu (rỗng = không nhấn từ nào); null = bỏ lựa chọn, trở về tự động. */
    fun setEmphasis(doc: JSONObject, lineId: String, idx: List<Int>?) {
        val design = doc.getJSONObject("design")
        var arr = design.optJSONArray("lines")
        if (arr == null) { arr = JSONArray(); design.put("lines", arr) }
        var o: JSONObject? = null
        for (i in 0 until arr.length()) if (arr.getJSONObject(i).getString("lineId") == lineId) o = arr.getJSONObject(i)
        if (idx == null) { o?.remove("emphasis"); return }
        if (o == null) { o = JSONObject().put("lineId", lineId); arr.put(o) }
        o.put("emphasis", JSONArray(idx.sorted())).put("origin", "user")
    }

    fun iconSetId(doc: JSONObject): String? =
        doc.getJSONObject("design").getJSONObject("template").optJSONObject("params")?.optString("iconSetId", "")?.ifEmpty { null }

    fun setIconSet(doc: JSONObject, id: String) {
        tplParams(doc).put("iconSetId", id)
        doc.getJSONObject("design").getJSONObject("template").put("origin", "user")
    }

    /** Xóa mọi mục hình thay chữ (cả phần người dùng đã sửa) rồi để tự thiết kế lại từ đầu. */
    fun clearSemanticItems(doc: JSONObject) {
        ensureSemanticLayer(doc).put("items", JSONArray())
    }

    fun aspect(doc: JSONObject): String = doc.getJSONObject("design").optString("aspect", "16:9")

    fun setAspect(doc: JSONObject, aspect: String) {
        require(aspect == "16:9" || aspect == "9:16") { "Tỷ lệ không hỗ trợ: $aspect" }
        doc.getJSONObject("design").put("aspect", aspect)
    }

    fun semanticLayer(doc: JSONObject): JSONObject? = doc.getJSONObject("design").optJSONObject("semanticLayer")

    fun ensureSemanticLayer(doc: JSONObject): JSONObject {
        val design = doc.getJSONObject("design")
        return design.optJSONObject("semanticLayer") ?: JSONObject()
            .put("enabled", true)
            .put("budget", JSONObject().put("maxConcurrent", 3).put("maxParticles", 150))
            .put("items", JSONArray())
            .also { design.put("semanticLayer", it) }
    }

    fun semanticEnabled(doc: JSONObject): Boolean = semanticLayer(doc)?.optBoolean("enabled", true) ?: false

    fun setSemanticEnabled(doc: JSONObject, on: Boolean) {
        ensureSemanticLayer(doc).put("enabled", on)
    }

    fun semanticItems(doc: JSONObject): List<JSONObject> {
        val arr = semanticLayer(doc)?.optJSONArray("items") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    /** Sửa một mục: mọi chỉnh sửa của người dùng đặt origin = user để chạy lại tự động không ghi đè. */
    fun editItem(doc: JSONObject, itemId: String, change: (JSONObject) -> Unit): Boolean {
        val item = semanticItems(doc).firstOrNull { it.getString("id") == itemId } ?: return false
        change(item)
        item.put("origin", "user")
        return true
    }
}
