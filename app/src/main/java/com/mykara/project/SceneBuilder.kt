package com.mykara.project

import org.json.JSONArray
import org.json.JSONObject

/**
 * Biên dịch project.json (lời, timestamp, thiết kế, ảnh, nhạc) + template + phân tích nhạc thành Scene JSON.
 * Preview và Export gọi cùng hàm này nên hai bên khớp nhau. Thuần Kotlin, không phụ thuộc Android.
 *
 * Nếu chưa có timestamp nào, câu lời được rải đều trên bài để vẫn xem được bản tạm (tempTiming = true);
 * kết quả tạm đó không bao giờ được ghi lại vào dự án.
 */
object SceneBuilder {
    class Built(val scene: JSONObject, val tempTiming: Boolean, val untimedLines: Int, val droppedByBudget: Int)

    private class Word(val text: String, val startMs: Long, val endMs: Long)
    private class TextInfo(val layerId: String, val fromMs: Long, val toMs: Long, val words: List<Word>)
    private class Pre(
        val json: JSONObject, val from: Long, val lineId: String,
        val ts: Int, val te: Int, val hideTokens: Boolean, val to: Long,
    )

    private const val FADE_MS = 900L

    fun build(doc: JSONObject, width: Int, height: Int, analysis: JSONObject?, urlFor: (String) -> String): Built {
        val tpl = Templates.get(ProjectDesign.templateId(doc))
        val design = doc.getJSONObject("design")
        val lyric = doc.getJSONObject("lyrics").getJSONArray("lines")
        val lines = (0 until lyric.length()).map { lyric.getJSONObject(it) }
        val timingList = ProjectTiming.lines(doc)
        val timingMap = timingList.associateBy { it.lineId }
        val audioMs = ProjectDoc.audioDurationMs(doc)
        val maxEnd = timingList.maxOfOrNull { it.endMs } ?: 0L
        val duration: Long = audioMs ?: if (maxEnd > 0L) maxEnd + 3000L else 10000L

        // ---- timing (có thể là bản tạm) ----
        val timed = LinkedHashMap<String, LongArray>()
        for (l in lines) {
            val id = l.getString("id")
            val t = timingMap[id] ?: continue
            val s = t.startMs.coerceIn(0L, duration)
            val e = t.endMs.coerceIn(0L, duration)
            if (e > s) timed[id] = longArrayOf(s, e)
        }
        var temp = false
        if (timed.isEmpty() && lines.isNotEmpty()) {
            temp = true
            val offset = minOf(1500L, duration / 10)
            val slot = (duration - 2 * offset).coerceAtLeast(1000L) / lines.size
            for ((i, l) in lines.withIndex()) {
                val s = offset + i * slot
                val e = minOf(s + (slot * 9 / 10).coerceAtLeast(300L), duration)
                if (e > s) timed[l.getString("id")] = longArrayOf(s, e)
            }
        }
        val untimed = if (temp) 0 else lines.size - timed.size

        // ---- thông tin từng câu ----
        val overrides = HashMap<String, JSONObject>()
        design.optJSONArray("lines")?.let { arr ->
            for (i in 0 until arr.length()) overrides[arr.getJSONObject(i).getString("lineId")] = arr.getJSONObject(i)
        }
        val order = lines.filter { timed.containsKey(it.getString("id")) }
        val infos = LinkedHashMap<String, TextInfo>()
        for ((idx, l) in order.withIndex()) {
            val id = l.getString("id")
            val se = timed.getValue(id)
            val nextStart = order.getOrNull(idx + 1)?.let { timed.getValue(it.getString("id"))[0] }
            val from = (se[0] - 100L).coerceAtLeast(0L)
            var to = minOf(se[1] + 250L, duration)
            if (nextStart != null && nextStart > se[0]) to = minOf(to, nextStart - 50L)
            if (to < from + 400L) to = minOf(duration, from + 400L)
            if (to <= from) to = from + 1L
            infos[id] = TextInfo("t-$id", from, to, splitWords(l.getString("text"), se[0], se[1]))
        }

        // ---- hình ngữ nghĩa ----
        val hide = HashMap<String, MutableSet<Int>>()
        val items = ArrayList<JSONObject>()
        var dropped = 0
        val semLayer = ProjectDesign.semanticLayer(doc)
        if (semLayer != null && semLayer.optBoolean("enabled", true)) {
            val maxConc = semLayer.optJSONObject("budget")?.optInt("maxConcurrent", 3) ?: 3
            val imagesById = ProjectDoc.images(doc).associateBy { it.id }
            val pre = ArrayList<Pre>()
            for (src in ProjectDesign.semanticItems(doc)) {
                if (!src.optBoolean("enabled", true)) continue
                val mode = src.optString("textMode", "replace")
                if (mode == "hidden") continue
                val info = infos[src.getString("lineId")] ?: continue
                val ts = src.getInt("tokenStart")
                val te = src.getInt("tokenEnd")
                if (ts < 0 || te <= ts || te > info.words.size) continue

                val vIn = src.getJSONObject("visual")
                val kind = vIn.getString("kind")
                val ref = if (kind == "userImage") {
                    val img = imagesById[vIn.getString("ref")] ?: continue
                    urlFor(img.file)
                } else vIn.getString("ref")

                val phasesIn = src.getJSONArray("phases")
                val raw = ArrayList<Triple<Long, String, JSONObject>>()
                for (k in 0 until phasesIn.length()) {
                    val p = phasesIn.getJSONObject(k)
                    val wi = (ts + p.getInt("atToken")).coerceIn(ts, te - 1)
                    raw.add(Triple(info.words[wi].startMs, p.getString("preset"), p))
                }
                if (raw.isEmpty()) continue
                val from = raw.minOf { it.first }
                val to = minOf(maxOf(info.toMs, from + 800L), duration).coerceAtLeast(from + 2L)
                val phases = JSONArray()
                for ((at, preset, p) in raw) {
                    val o = JSONObject().put("atMs", at.coerceIn(from, to - 1L)).put("preset", preset)
                    if (p.has("durationMs")) o.put("durationMs", p.getLong("durationMs"))
                    p.optJSONObject("params")?.let { o.put("params", it) }
                    phases.put(o)
                }
                val whole = ts == 0 && te == info.words.size
                val anchorMode = if (whole) "hero" else if (mode == "replace") "inline" else "above"
                val id = src.getString("id")
                val json = JSONObject()
                    .put("id", id).put("fromMs", from).put("toMs", to)
                    .put("visual", JSONObject().put("kind", kind).put("ref", ref))
                    .put("phases", phases)
                    .put("seed", (id.hashCode() and 0x7fffffff) % 100000)
                    .put(
                        "anchor",
                        JSONObject().put("mode", anchorMode).put("layer", info.layerId)
                            .put("tokenStart", ts).put("tokenEnd", te),
                    )
                src.optJSONObject("style")?.let { json.put("style", it) }
                pre.add(Pre(json, from, src.getString("lineId"), ts, te, mode == "replace", to))
            }
            // Ngân sách: tối đa maxConc mục cùng lúc, mục đến sau bị bỏ (không giảm khung hình).
            val active = ArrayList<Long>()
            for (p in pre.sortedBy { it.from }) {
                active.removeAll { it <= p.from }
                if (active.size >= maxConc) {
                    dropped++
                    continue
                }
                active.add(p.to)
                items.add(p.json)
                if (p.hideTokens) hide.getOrPut(p.lineId) { HashSet() }.addAll(p.ts until p.te)
            }
        }

        // ---- ghép các layer theo thứ tự vẽ ----
        val layers = JSONArray()
        addBackground(layers, doc, design, tpl, duration, urlFor)

        if (tpl.darken > 0) layers.put(overlay("ov-darken", duration, "darken", """{"alpha":${tpl.darken}}"""))
        tpl.tint?.let { layers.put(overlay("ov-tint", duration, "tint", """{"color":"${it.first}","alpha":${it.second}}""")) }
        tpl.vignette?.let { layers.put(overlay("ov-vignette", duration, "vignette", """{"strength":$it}""")) }
        tpl.particles?.let {
            layers.put(
                JSONObject().put("id", "particles").put("type", "particles").put("fromMs", 0L).put("toMs", duration)
                    .put("source", it.first).put("params", JSONObject().put("count", it.second)),
            )
        }
        val reactive = reactiveSpecs(design, tpl)
        for ((i, r) in reactive.withIndex()) {
            layers.put(
                JSONObject().put("id", "reactive-$i").put("type", "reactive").put("fromMs", 0L).put("toMs", duration)
                    .put("source", r.first).put("params", JSONObject(r.second)),
            )
        }

        val base = if (width >= height) height.toDouble() else width * 0.56
        for ((idx, l) in order.withIndex()) {
            val id = l.getString("id")
            val info = infos.getValue(id)
            val se = timed.getValue(id)
            val ov = overrides[id]
            val layout = ov?.optJSONObject("layout")
            var fs = Math.round(tpl.fontFrac * base).toInt()
            if (layout != null && layout.has("fontSize")) fs = Math.round(layout.getDouble("fontSize") * height / 720.0).toInt()
            val style = JSONObject()
                .put("x", if (layout != null && layout.has("x")) layout.getDouble("x") else 0.5)
                .put("y", if (layout != null && layout.has("y")) layout.getDouble("y") else tpl.textY)
                .put("fontSize", fs)
                .put("font", layout?.optString("font", "")?.ifEmpty { null } ?: tpl.font)
                .put("weight", tpl.weight)
                .put("color", layout?.optString("color", "")?.ifEmpty { null } ?: tpl.color)
                .put("maxWidth", Math.round(width * 0.86).toInt())
            tpl.highlight?.let { style.put("highlightColor", it) }
            tpl.glow?.let { style.put("shadowColor", it) }

            val anims = JSONArray()
            val enterOv = ov?.optJSONObject("enter")
            val enterEffect = enterOv?.optString("effect")?.ifEmpty { null } ?: tpl.enters[idx % tpl.enters.size]
            val enterDur = enterOv?.optLong("durationMs", 0L)?.takeIf { it > 0 }
                ?: if (enterEffect == "typewriter") minOf(1200L, (se[1] - se[0]) / 2).coerceAtLeast(300L) else 500L
            anims.put(JSONObject().put("phase", "enter").put("effect", enterEffect).put("durationMs", enterDur))
            val holdEffect = ov?.optJSONObject("hold")?.optString("effect")?.ifEmpty { null } ?: tpl.hold
            if (holdEffect != null) anims.put(JSONObject().put("phase", "hold").put("effect", holdEffect))
            val exitOv = ov?.optJSONObject("exit")
            val exitEffect = exitOv?.optString("effect")?.ifEmpty { null } ?: tpl.exits[idx % tpl.exits.size]
            val exitDur = exitOv?.optLong("durationMs", 0L)?.takeIf { it > 0 } ?: 400L
            anims.put(JSONObject().put("phase", "exit").put("effect", exitEffect).put("durationMs", exitDur))

            val words = JSONArray()
            for (w in info.words) words.put(JSONObject().put("text", w.text).put("startMs", w.startMs).put("endMs", w.endMs))

            val layer = JSONObject()
                .put("id", info.layerId).put("type", "text").put("fromMs", info.fromMs).put("toMs", info.toMs)
                .put("text", l.getString("text")).put("style", style).put("animations", anims).put("words", words)
            hide[id]?.let { h -> if (h.isNotEmpty()) layer.put("hideText", JSONArray(h.sorted())) }
            if (tpl.karaoke) layer.put("params", JSONObject().put("karaoke", true))
            if (tpl.layout == "collage") {
                val params = layer.optJSONObject("params") ?: JSONObject().also { layer.put("params", it) }
                params.put("layout", "collage")
                val hidden = hide[id] ?: emptySet<Int>()
                accentIndex(info.words.map { it.text }, hidden)?.let { params.put("accent", JSONArray().put(it)) }
            }
            layers.put(layer)
        }

        if (items.isNotEmpty()) {
            val arr = JSONArray()
            for (o in items) arr.put(o)
            layers.put(
                JSONObject().put("id", "semantic").put("type", "semantic").put("fromMs", 0L).put("toMs", duration).put("items", arr),
            )
        }
        tpl.letterbox?.let { layers.put(overlay("ov-letterbox", duration, "letterbox", """{"frac":$it}""")) }

        val scene = JSONObject()
            .put("version", 1).put("width", width).put("height", height).put("fps", 30)
            .put("durationMs", duration).put("template", tpl.id)
            .put("audio", JSONObject().put("file", ProjectDoc.audioFile(doc)?.let(urlFor) ?: ""))
            .put("layers", layers)
        if (analysis != null) scene.put("analysis", analysis)
        return Built(scene, temp, untimed, dropped)
    }

    // ---------- chi tiết ----------

    /**
     * Chọn một từ nhấn màu cho câu (chỉ khi câu từ 3 từ trở lên): từ cuối nếu đủ dài, nếu không thì từ dài nhất.
     * Bỏ qua từ đã được thay bằng hình. Người dùng có thể đổi sau.
     */
    internal fun accentIndex(tokens: List<String>, hidden: Collection<Int>): Int? {
        if (tokens.size < 3) return null
        val cand = tokens.indices.filter { it !in hidden }
        if (cand.isEmpty()) return null
        val last = cand.last()
        if (tokens[last].length >= 3) return last
        return cand.maxByOrNull { tokens[it].length }
    }

    private fun overlay(id: String, duration: Long, source: String, params: String): JSONObject =
        JSONObject().put("id", id).put("type", "overlay").put("fromMs", 0L).put("toMs", duration)
            .put("source", source).put("params", JSONObject(params))

    private fun reactiveSpecs(design: JSONObject, tpl: Template): List<Pair<String, String>> {
        val arr = design.optJSONArray("reactive")
        if (arr != null && arr.length() > 0) {
            return (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Pair(o.getString("type"), (o.optJSONObject("params") ?: JSONObject()).toString())
            }
        }
        return tpl.reactive
    }

    private fun addBackground(
        layers: JSONArray, doc: JSONObject, design: JSONObject, tpl: Template, duration: Long, urlFor: (String) -> String,
    ) {
        val images = ProjectDoc.images(doc).associateBy { it.id }
        class Seg(val file: String, val from: Long, val to: Long)
        val segs = ArrayList<Seg>()
        val bg = design.optJSONArray("background")
        if (bg != null && bg.length() > 0) {
            for (i in 0 until bg.length()) {
                val b = bg.getJSONObject(i)
                val img = images[b.getString("imageId")] ?: continue
                segs.add(Seg(img.file, b.getLong("fromMs"), b.getLong("toMs")))
            }
        } else if (images.isNotEmpty()) {
            val list = ProjectDoc.images(doc)
            val len = duration / list.size
            for ((i, img) in list.withIndex()) {
                segs.add(Seg(img.file, i * len, if (i == list.size - 1) duration else (i + 1) * len))
            }
        }
        if (segs.isEmpty()) {
            layers.put(overlay("bg-gradient", duration, "gradient", """{"c1":"${tpl.gradient.first}","c2":"${tpl.gradient.second}"}"""))
            return
        }
        for ((i, s) in segs.withIndex()) {
            val from = if (i == 0) s.from else (s.from - FADE_MS).coerceAtLeast(0L)
            val to = if (i == segs.size - 1) s.to else minOf(duration, s.to + FADE_MS)
            val params = JSONObject().put("fadeMs", FADE_MS).put("kenBurns", tpl.kenBurns)
            if (tpl.reactZoom > 0) params.put("reactZoom", tpl.reactZoom)
            layers.put(
                JSONObject().put("id", "bg-$i").put("type", "image").put("fromMs", from).put("toMs", to)
                    .put("source", urlFor(s.file)).put("params", params),
            )
        }
    }

    private fun splitWords(text: String, s: Long, e: Long): List<Word> {
        val toks = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (toks.isEmpty()) return emptyList()
        val total = toks.sumOf { it.length }.toDouble()
        var acc = 0.0
        var prevEnd = s
        val out = ArrayList<Word>()
        for ((i, t) in toks.withIndex()) {
            acc += t.length
            val end = if (i == toks.size - 1) e else s + ((e - s) * acc / total).toLong()
            val w = Word(t, prevEnd, maxOf(end, prevEnd))
            prevEnd = w.endMs
            out.add(w)
        }
        return out
    }
}
