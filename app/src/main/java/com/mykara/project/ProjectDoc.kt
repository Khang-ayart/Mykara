package com.mykara.project

import org.json.JSONArray
import org.json.JSONObject

/**
 * Thao tác trên project.json (theo schema/project.schema.json). Thuần Kotlin + org.json, không phụ thuộc Android,
 * nên chạy được trong kiểm thử đơn vị. Làm việc trực tiếp trên JSONObject để không làm mất trường lạ
 * (tương thích khi bản sau thêm trường mới).
 *
 * Nguyên tắc: không bao giờ ghi đè giá trị `origin: user`, và khi một thứ thay đổi chỉ động vào phần phụ thuộc nó.
 */
object ProjectDoc {
    const val VERSION = 1

    class ImageRef(val id: String, val file: String, val hash: String)

    class LyricsChange(val added: Int, val removed: Int, val edited: List<String>)

    // ---------- tạo mới ----------

    fun newProject(title: String): JSONObject = JSONObject().apply {
        put("version", VERSION)
        put("meta", JSONObject().put("title", title.trim().ifEmpty { "Dự án mới" }))
        put("assets", JSONObject().put("images", JSONArray()))
        put("lyrics", JSONObject().put("lines", JSONArray()))
        put("timing", JSONObject().put("status", "none").put("lines", JSONArray()))
        put(
            "design",
            JSONObject()
                .put("template", JSONObject().put("id", "karaoke").put("origin", "auto"))
                .put("aspect", "16:9"),
        )
        put("export", JSONObject().put("resolution", "720p").put("fps", 30).put("container", "mp4"))
    }

    // ---------- thông tin bài hát ----------

    fun setMeta(doc: JSONObject, title: String, artist: String, composer: String) {
        val meta = doc.getJSONObject("meta")
        val t = title.trim()
        if (t.isNotEmpty()) meta.put("title", t) else if (!meta.has("title")) meta.put("title", "Dự án mới")
        putOrRemove(meta, "artist", artist.trim())
        putOrRemove(meta, "composer", composer.trim())
    }

    fun title(doc: JSONObject): String = doc.getJSONObject("meta").optString("title", "")
    fun artist(doc: JSONObject): String = doc.getJSONObject("meta").optString("artist", "")
    fun composer(doc: JSONObject): String = doc.getJSONObject("meta").optString("composer", "")

    private fun putOrRemove(o: JSONObject, key: String, value: String) {
        if (value.isEmpty()) o.remove(key) else o.put(key, value)
    }

    // ---------- nhạc ----------

    fun audioFile(doc: JSONObject): String? =
        doc.getJSONObject("assets").optJSONObject("audio")?.optString("file")?.ifEmpty { null }

    fun audioDurationMs(doc: JSONObject): Long? {
        val a = doc.getJSONObject("assets").optJSONObject("audio") ?: return null
        return if (a.has("durationMs")) a.getLong("durationMs") else null
    }

    /**
     * Đặt file nhạc. Nếu thay bằng nhạc khác (hash khác) thì mọi timestamp đã có bị gắn cờ cần kiểm tra lại,
     * nhưng không bị xóa (người dùng có thể đã căn thủ công).
     */
    fun setAudio(doc: JSONObject, file: String, hash: String, durationMs: Long?) {
        val assets = doc.getJSONObject("assets")
        val oldHash = assets.optJSONObject("audio")?.optString("hash")
        val audio = JSONObject().put("file", file).put("hash", hash)
        if (durationMs != null) audio.put("durationMs", durationMs)
        assets.put("audio", audio)
        if (oldHash != null && oldHash.isNotEmpty() && oldHash != hash) {
            for (t in objects(doc.getJSONObject("timing").getJSONArray("lines"))) t.put("flagged", true)
        }
    }

    // ---------- ảnh ----------

    fun images(doc: JSONObject): List<ImageRef> =
        objects(doc.getJSONObject("assets").getJSONArray("images"))
            .map { ImageRef(it.getString("id"), it.getString("file"), it.getString("hash")) }

    /** Trả về id ảnh nếu thêm mới, hoặc id ảnh đã có nếu cùng hash. */
    fun addImage(doc: JSONObject, file: String, hash: String): String {
        val arr = doc.getJSONObject("assets").getJSONArray("images")
        objects(arr).firstOrNull { it.getString("hash") == hash }?.let { return it.getString("id") }
        val id = "img" + (maxSuffix(objects(arr).map { it.getString("id") }, "img") + 1)
        arr.put(JSONObject().put("id", id).put("file", file).put("hash", hash))
        return id
    }

    /** Xóa ảnh và mọi tham chiếu tới nó. Trả về đường dẫn file cần xóa, hoặc null nếu không có id đó. */
    fun removeImage(doc: JSONObject, id: String): String? {
        val arr = doc.getJSONObject("assets").getJSONArray("images")
        var file: String? = null
        val keep = JSONArray()
        for (o in objects(arr)) {
            if (o.getString("id") == id) file = o.getString("file") else keep.put(o)
        }
        if (file == null) return null
        doc.getJSONObject("assets").put("images", keep)

        val design = doc.getJSONObject("design")
        design.optJSONArray("background")?.let { bg ->
            design.put("background", filter(bg) { it.optString("imageId") != id })
        }
        design.optJSONObject("semanticLayer")?.let { sem ->
            sem.put(
                "items",
                filter(sem.getJSONArray("items")) {
                    val v = it.getJSONObject("visual")
                    !(v.getString("kind") == "userImage" && v.getString("ref") == id)
                },
            )
        }
        return file
    }

    // ---------- lời bài hát ----------

    fun lyricsText(doc: JSONObject): String =
        objects(doc.getJSONObject("lyrics").getJSONArray("lines")).joinToString("\n") { it.getString("text") }

    /** Tách lời thành các câu: bỏ dòng trống, cắt khoảng trắng đầu/cuối và gộp khoảng trắng liên tiếp. */
    fun splitLyrics(text: String): List<String> =
        text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotEmpty() }

    /**
     * Cập nhật lời mà giữ được công sức đã bỏ vào các câu không đổi:
     *  1. Câu giống hệt câu cũ giữ nguyên id (và timestamp, hiệu ứng của nó).
     *  2. Các câu còn lại: nếu số câu mới và cũ còn lại bằng nhau thì ghép theo thứ tự, coi là sửa tại chỗ;
     *     câu đó giữ id và timestamp nhưng bị gắn cờ cần kiểm tra, lớp hình ngữ nghĩa của nó bị bỏ
     *     (vì vị trí từ không còn đúng) và timestamp từng từ bị xóa.
     *  3. Còn lại là câu mới (id mới); câu cũ không ghép được bị xóa cùng timestamp/thiết kế của nó.
     */
    fun setLyrics(doc: JSONObject, text: String): LyricsChange {
        val lyrics = doc.getJSONObject("lyrics")
        val oldLines = objects(lyrics.getJSONArray("lines"))
        val newTexts = splitLyrics(text)

        val assigned = arrayOfNulls<Int>(newTexts.size) // chỉ số câu cũ
        val usedOld = BooleanArray(oldLines.size)
        for ((i, t) in newTexts.withIndex()) {
            val j = oldLines.indices.firstOrNull { !usedOld[it] && oldLines[it].getString("text") == t }
            if (j != null) {
                assigned[i] = j
                usedOld[j] = true
            }
        }
        val remNew = newTexts.indices.filter { assigned[it] == null }
        val remOld = oldLines.indices.filter { !usedOld[it] }
        val editedIds = ArrayList<String>()
        if (remNew.isNotEmpty() && remNew.size == remOld.size) {
            for (k in remNew.indices) {
                assigned[remNew[k]] = remOld[k]
                usedOld[remOld[k]] = true
                editedIds.add(oldLines[remOld[k]].getString("id"))
            }
        }

        var next = maxSuffix(oldLines.map { it.getString("id") }, "l") + 1
        val newArr = JSONArray()
        val keptIds = HashSet<String>()
        var added = 0
        for ((i, t) in newTexts.withIndex()) {
            val j = assigned[i]
            if (j != null) {
                val o = oldLines[j]
                o.put("text", t)
                newArr.put(o)
                keptIds.add(o.getString("id"))
            } else {
                val id = "l${next++}"
                newArr.put(JSONObject().put("id", id).put("text", t).put("origin", "user"))
                keptIds.add(id)
                added++
            }
        }
        lyrics.put("lines", newArr)
        val removed = oldLines.indices.count { !usedOld[it] }

        // Timing: bỏ câu không còn, sửa tại chỗ thì gắn cờ và xóa timestamp từng từ.
        val timing = doc.getJSONObject("timing")
        timing.put(
            "lines",
            filter(timing.getJSONArray("lines")) { it.getString("lineId") in keptIds }.also { arr ->
                for (t in objects(arr)) {
                    if (t.getString("lineId") in editedIds) {
                        t.put("flagged", true)
                        t.remove("words")
                    }
                }
            },
        )

        // Thiết kế: bỏ phần của câu không còn; lớp ngữ nghĩa của câu sửa cũng bỏ.
        val design = doc.getJSONObject("design")
        design.optJSONArray("lines")?.let { dl ->
            design.put("lines", filter(dl) { it.getString("lineId") in keptIds })
        }
        design.optJSONObject("semanticLayer")?.let { sem ->
            sem.put(
                "items",
                filter(sem.getJSONArray("items")) {
                    val id = it.getString("lineId")
                    id in keptIds && id !in editedIds
                },
            )
        }
        return LyricsChange(added, removed, editedIds)
    }

    // ---------- tiện ích ----------

    private fun objects(arr: JSONArray): List<JSONObject> = (0 until arr.length()).map { arr.getJSONObject(it) }

    private fun filter(arr: JSONArray, keep: (JSONObject) -> Boolean): JSONArray {
        val out = JSONArray()
        for (o in objects(arr)) if (keep(o)) out.put(o)
        return out
    }

    /** Số lớn nhất trong các id dạng prefix+số (ví dụ l7 → 7); 0 nếu chưa có. */
    private fun maxSuffix(ids: List<String>, prefix: String): Int =
        ids.mapNotNull { if (it.startsWith(prefix)) it.removePrefix(prefix).toIntOrNull() else null }.maxOrNull() ?: 0
}
