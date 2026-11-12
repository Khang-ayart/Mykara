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
        doc.getJSONObject("design").put("template", JSONObject().put("id", id).put("origin", origin))
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
