package com.mykara.project

import org.json.JSONArray
import org.json.JSONObject

/**
 * Bước "tự thiết kế": đề xuất hình thay chữ và template. Chạy lại bao nhiêu lần cũng được:
 * mục `origin: user` (kể cả mục người dùng đã tắt) được giữ nguyên, mục `origin: auto` được tạo lại.
 */
object AutoDesign {
    private const val MAX_ITEMS_PER_LINE = 3

    class Result(val added: Int, val keptUser: Int)

    fun applySemantic(doc: JSONObject, lib: SemanticLibrary): Result {
        val layer = ProjectDesign.ensureSemanticLayer(doc)
        val old = ProjectDesign.semanticItems(doc)
        val keep = old.filter { it.optString("origin") == "user" }

        val lines = doc.getJSONObject("lyrics").getJSONArray("lines")
        val lineOrder = HashMap<String, Int>()
        val used = HashMap<String, MutableSet<Int>>()
        for (i in 0 until lines.length()) lineOrder[lines.getJSONObject(i).getString("id")] = i
        for (k in keep) {
            val set = used.getOrPut(k.getString("lineId")) { HashSet() }
            for (t in k.getInt("tokenStart") until k.getInt("tokenEnd")) set.add(t)
        }

        var next = (old.mapNotNull { it.getString("id").removePrefix("s").toIntOrNull() }.maxOrNull() ?: 0) + 1
        val added = ArrayList<JSONObject>()
        val images = ProjectDoc.images(doc)
        var frames = 0

        for (li in 0 until lines.length()) {
            val line = lines.getJSONObject(li)
            val lineId = line.getString("id")
            val tokens = line.getString("text").split(Regex("\\s+")).filter { it.isNotEmpty() }
            val norm = tokens.map { SemanticLibrary.normalize(it) }
            val taken = used.getOrPut(lineId) { HashSet() }
            var i = 0
            var count = 0
            while (i < tokens.size && count < MAX_ITEMS_PER_LINE) {
                val e = lib.entries.firstOrNull { en ->
                    val n = en.tokens.size
                    i + n <= norm.size &&
                        (0 until n).all { norm[i + it] == en.tokens[it] } &&
                        (i until i + n).none { it in taken }
                }
                if (e == null) {
                    i++
                    continue
                }
                val span = e.tokens.size
                val whole = span == tokens.size
                added.add(
                    JSONObject()
                        .put("id", "s${next++}")
                        .put("lineId", lineId)
                        .put("tokenStart", i)
                        .put("tokenEnd", i + span)
                        .put("visual", visualFor(lib, e.visual, images, frames++))
                        .put("phases", lib.phasesFor(e, span))
                        // Cụm phủ cả câu: giữ chữ bên cạnh hình lớn, để người xem vẫn đọc được lời.
                        .put("textMode", if (whole) "beside" else e.textMode)
                        .put("enabled", !e.ambiguous)
                        .put("confidence", e.confidence)
                        .put("origin", "auto"),
                )
                for (t in i until i + span) taken.add(t)
                count++
                i += span
            }
        }

        val all = (keep + added).sortedWith(
            compareBy({ lineOrder[it.getString("lineId")] ?: Int.MAX_VALUE }, { it.getInt("tokenStart") }),
        )
        val arr = JSONArray()
        for (o in all) arr.put(o)
        layer.put("items", arr)
        return Result(added.size, keep.size)
    }

    /**
     * Hình cho một mục thư viện. Khung phim/ảnh chứa ảnh của người dùng: lần lượt lấy các ảnh trong dự án
     * (không có ảnh thì khung trống). Người dùng đổi ảnh khác khi xem trước.
     */
    private fun visualFor(lib: SemanticLibrary, id: String, images: List<ProjectDoc.ImageRef>, n: Int): JSONObject {
        val v = lib.visual(id)
        if (v != null && v.kind == "frame") {
            val o = JSONObject().put("kind", "frame").put("variant", v.variant)
            o.put("ref", if (images.isEmpty()) "" else images[n % images.size].id)
            return o
        }
        return JSONObject().put("kind", "vector").put("ref", id)
    }

    /** Chọn template theo nhịp nhạc, trừ khi người dùng đã tự chọn. Trả về id template. */
    fun applyTemplate(doc: JSONObject, bpm: Double?): String {
        if (ProjectDesign.templateOrigin(doc) == "user") return ProjectDesign.templateId(doc)
        val id = if (bpm != null && bpm >= 118.0) "sweep" else "karaoke"
        ProjectDesign.setTemplate(doc, id, "auto")
        return id
    }
}
