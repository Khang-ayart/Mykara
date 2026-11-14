package com.mykara.project

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Preview: dựng Scene JSON từ dự án và phát bằng engine (cùng engine với xuất video).
 * Đổi template / khung hình / lớp hình thay chữ chỉ dựng lại cảnh, không đụng timing hay file gốc.
 */
class PreviewActivity : Activity() {
    private lateinit var store: ProjectStore
    private lateinit var id: String
    private lateinit var doc: JSONObject
    private lateinit var lib: SemanticLibrary
    private lateinit var web: WebView
    private lateinit var seek: SeekBar
    private lateinit var playBtn: Button
    private lateinit var info: TextView
    private var sceneJson = "{}"
    private var sceneObj: JSONObject = JSONObject()
    private var durationMs = 1L
    private var player: MediaPlayer? = null
    private val ui = Handler(Looper.getMainLooper())

    // vị trí phát: đọc trên luồng giao diện rồi cache, để JS đọc được từ luồng khác
    @Volatile private var posMs = 0L
    private var playing = false
    private var clockBase = 0L
    private var clockStart = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ProjectStore(this)
        id = intent.getStringExtra("id") ?: run { finish(); return }
        doc = store.load(id)
        lib = SemanticLibrary(JSONObject(assets.open("semantic_library.json").bufferedReader(Charsets.UTF_8).readText()))

        // Lần đầu: tự thiết kế (template + hình thay chữ). Những lần sau giữ nguyên, kể cả chỉnh của người dùng.
        // Chạy lại mỗi lần mở: phần của người dùng được giữ, phần tự động theo kịp lời đã sửa.
        val firstDesign = ProjectDesign.semanticLayer(doc) == null
        if (firstDesign) AutoDesign.applyTemplate(doc, null)
        AutoDesign.applySemantic(doc, lib)
        // Lần đầu: tắt thay chữ bằng hình cho nhẹ; người dùng bật lại bằng công tắc nếu muốn.
        if (firstDesign) ProjectDesign.setSemanticEnabled(doc, false)
        store.save(id, doc)

        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        web = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            addJavascriptInterface(Bridge(), "Android")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? = server.serve(r)
                override fun onPageFinished(v: WebView, url: String) {
                    v.evaluateJavascript("loadScene();startLoop();", null)
                    if (regenerating) waitReadyThenClose()
                }
            }
        }
        root.addView(web)

        val c = this
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.PANEL)
            setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 4))
        }
        seek = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) seekTo(p.toLong())
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        playBtn = Button(this).apply {
            text = "▶"
            textSize = 18f
            setTextColor(Color.WHITE)
            background = Ui.rounded(Ui.ACCENT, 24, c)
            minWidth = 0; minimumWidth = 0
            setPadding(Ui.dp(c, 18), 0, Ui.dp(c, 18), 0)
            setOnClickListener { togglePlay() }
        }
        val transport = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        transport.addView(playBtn, LinearLayout.LayoutParams(Ui.dp(c, 64), Ui.dp(c, 44)))
        transport.addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(transport)
        info = TextView(this).apply { textSize = 12f; setTextColor(Ui.MUTED) }
        panel.addView(info)

        val sw = Switch(this).apply {
            text = "Thay chữ bằng hình (mây, mưa, tim…)"
            setTextColor(Ui.TEXT)
            isChecked = ProjectDesign.semanticEnabled(doc)
            setOnCheckedChangeListener { _, on -> ProjectDesign.setSemanticEnabled(doc, on); changed() }
        }
        panel.addView(sw)

        tplTool = Ui.tool(this, "🎞", "Kiểu") {
            val next = Templates.next(ProjectDesign.templateId(doc)).id
            ProjectDesign.setTemplate(doc, next, "user")
            changed()
        }
        // Karaoke giữ font cố định nên nút này đổi bộ biểu tượng; các kiểu khác đổi font.
        fontTool = Ui.tool(this, "Aa", "Font") {
            val tpl = Templates.get(ProjectDesign.templateId(doc))
            val f = Fonts.next(tpl, ProjectDesign.fontId(doc))
            if (f != null) { ProjectDesign.setFont(doc, f.id); changed() } else showIconDialog()
        }
        aspTool = Ui.tool(this, "▭", "Khung") {
            ProjectDesign.setAspect(doc, if (ProjectDesign.aspect(doc) == "16:9") "9:16" else "16:9")
            changed()
        }
        editTool = Ui.tool(this, "✎", "Hình") { showItems() }
        val redoTool = Ui.tool(this, "↻", "Làm lại") { confirmRegenerate() }
        val autoTool = Ui.tool(this, "✨", "Tự động") { autoDesign() }
        val exportTool = Ui.tool(this, "⬇", "Xuất") {
            if (playing) togglePlay()
            store.save(id, doc)
            startActivity(android.content.Intent(this, ExportActivity::class.java).putExtra("id", id))
        }
        val emphTool = Ui.tool(this, "B", "Nhấn chữ") { showEmphasisLines() }
        ornTool = Ui.tool(this, "🌸", "Trang trí") { showOrnamentDialog() }
        posTool = Ui.tool(this, "↕", "Vị trí") { showPositionDialog() }
        val tools = listOf(tplTool, fontTool, emphTool, ornTool, posTool, aspTool, editTool, autoTool, redoTool, exportTool)
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 4)) }
        for (t in tools) bar.addView(t, LinearLayout.LayoutParams(Ui.dp(c, 78), ViewGroup.LayoutParams.WRAP_CONTENT))
        // Nhiều công cụ: cuộn ngang thay vì nén nhỏ lại
        // Thanh xác nhận sau khi Làm lại: giữ bản mới hoặc quay về bản cũ
        confirmBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 6))
            addView(TextView(c).apply { text = "Bản mới đã dựng xong. Bạn giữ bản này chứ?"; textSize = 14f; setTextColor(Ui.TEXT) })
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Ui.button(c, "↩ Quay lại bản cũ", Ui.SECONDARY) { undoRegenerate() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = Ui.dp(c, 6) })
                addView(Ui.button(c, "✓ Giữ bản này", Ui.PRIMARY) { keepRegenerated() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
        panel.addView(confirmBar)
        panel.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(bar) })
        root.addView(panel)
        setContentView(root)

        rebuild()
        web.loadUrl("https://mykara.local/app/engine.html")
        ui.post(tick)
    }

    // ---------- làm lại bản xem trước ----------

    private var busy: AlertDialog? = null

    private fun confirmRegenerate() {
        var choice = 0
        AlertDialog.Builder(this).setTitle("Làm lại bản xem trước này?")
            .setMessage("App dựng lại từ đầu với cách bố trí khác (kiểu chữ vào/ra, ảnh mở đầu, biểu tượng, từ nhấn tự động). Dựng xong bạn sẽ được chọn giữ bản mới hoặc quay lại bản cũ.")
            .setSingleChoiceItems(arrayOf("Giữ phần tôi đã sửa tay", "Bỏ cả phần tôi đã sửa"), 0) { _, i -> choice = i }
            .setPositiveButton("Làm lại") { _, _ -> regenerate(clearUserEdits = choice == 1) }
            .setNegativeButton("Hủy", null).show()
    }

    private var confirmBar: LinearLayout? = null
    private var snapshot: String? = null

    private fun keepRegenerated() {
        snapshot = null
        confirmBar?.visibility = View.GONE
    }

    private fun undoRegenerate() {
        val snap = snapshot ?: return
        doc = JSONObject(snap)
        snapshot = null
        confirmBar?.visibility = View.GONE
        changed()
        android.widget.Toast.makeText(this, "Đã quay lại bản cũ", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun regenerate(clearUserEdits: Boolean) {
        if (playing) togglePlay()
        busy = AlertDialog.Builder(this).setTitle("Đang dựng lại…").setMessage("Vui lòng đợi, không thao tác trong lúc này.")
            .setCancelable(false).show()
        if (snapshot == null) snapshot = doc.toString() // giữ bản cũ để có thể quay lại
        ProjectDesign.bumpVariant(doc)
        if (clearUserEdits) ProjectDesign.clearSemanticItems(doc)
        AutoDesign.applySemantic(doc, lib)
        store.save(id, doc)
        rebuild()
        // xóa bộ nhớ đệm và tải lại engine từ đầu: không còn gì của bản cũ
        web.clearCache(true)
        regenerating = true
        web.loadUrl("https://mykara.local/app/engine.html")
    }

    private var regenerating = false

    private fun waitReadyThenClose() {
        web.evaluateJavascript("isReady()") { r ->
            if (r == "true") {
                busy?.dismiss(); busy = null; regenerating = false
                confirmBar?.visibility = View.VISIBLE
            }
            else ui.postDelayed({ waitReadyThenClose() }, 150)
        }
    }

    // ---------- sửa từng hình thay chữ ----------

    private fun lineText(lineId: String): String {
        val a = doc.getJSONObject("lyrics").getJSONArray("lines")
        for (i in 0 until a.length()) if (a.getJSONObject(i).getString("id") == lineId) return a.getJSONObject(i).getString("text")
        return ""
    }

    private fun itemWords(it: JSONObject): String {
        val toks = lineText(it.getString("lineId")).split(Regex("\\s+")).filter { w -> w.isNotEmpty() }
        return toks.subList(it.getInt("tokenStart").coerceIn(0, toks.size), it.getInt("tokenEnd").coerceIn(0, toks.size)).joinToString(" ")
    }

    private fun visualLabel(v: JSONObject): String = when (v.getString("kind")) {
        "userImage" -> "ảnh của bạn"
        "frame" -> when (v.optString("variant")) { "film" -> "khung phim"; "viewfinder" -> "khung ngắm"; else -> "khung ảnh" }
        else -> lib.visual(v.getString("ref"))?.let { "${it.emoji} ${it.label}" } ?: v.getString("ref")
    }

    // ---------- chọn chữ nhấn ----------

    private fun lineTokens(lineId: String) = lineText(lineId).split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** Từ nhấn đang có hiệu lực: lựa chọn của người dùng, hoặc cách tự động của kiểu nét bút. */
    private fun effectiveEmphasis(lineId: String): List<Int> {
        ProjectDesign.emphasis(doc, lineId)?.let { return it }
        if (Templates.get(ProjectDesign.templateId(doc)).layout == "collage") {
            SceneBuilder.accentIndex(lineTokens(lineId), emptySet(), ProjectDesign.variant(doc))?.let { return listOf(it) }
        }
        return emptyList()
    }

    private fun showEmphasisLines() {
        val arr = doc.getJSONObject("lyrics").getJSONArray("lines")
        val ids = (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
        if (ids.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Nhấn chữ").setMessage("Dự án chưa có lời.").setPositiveButton("Đóng", null).show()
            return
        }
        val labels = ids.mapIndexed { n, lid ->
            val toks = lineTokens(lid)
            val e = effectiveEmphasis(lid).filter { it < toks.size }.joinToString(", ") { toks[it] }
            val mark = if (ProjectDesign.emphasis(doc, lid) != null) "★" else "  "
            "$mark ${n + 1}. ${lineText(lid)}" + if (e.isNotEmpty()) "\n      nhấn: $e" else ""
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Chọn câu để chọn chữ nhấn (★ = bạn đã chọn)")
            .setItems(labels) { _, i -> showEmphasisWords(ids[i]) }
            .setNegativeButton("Đóng", null).show()
    }

    private fun showEmphasisWords(lineId: String) {
        val toks = lineTokens(lineId)
        val cur = effectiveEmphasis(lineId).toSet()
        val checked = BooleanArray(toks.size) { it in cur }
        ProjectTiming.lines(doc).firstOrNull { it.lineId == lineId }?.let { seekTo(it.startMs + 200L); seek.progress = posMs.toInt() }
        AlertDialog.Builder(this).setTitle("Chữ cần nhấn")
            .setMultiChoiceItems(toks.toTypedArray(), checked) { _, which, on -> checked[which] = on }
            .setPositiveButton("Áp dụng") { _, _ ->
                ProjectDesign.setEmphasis(doc, lineId, toks.indices.filter { checked[it] })
                changed(); showEmphasisLines()
            }
            .setNeutralButton("Tự động") { _, _ -> ProjectDesign.setEmphasis(doc, lineId, null); changed(); showEmphasisLines() }
            .setNegativeButton("Quay lại") { _, _ -> showEmphasisLines() }.show()
    }

    private fun showItems() {
        val items = ProjectDesign.semanticItems(doc)
        if (items.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Hình thay chữ")
                .setMessage("Chưa có hình nào. Thử bấm \"Tự thiết kế lại\", hoặc thêm lời có các từ như mưa, mây, gió, trăng.")
                .setPositiveButton("Đóng", null).show()
            return
        }
        val labels = items.map {
            val on = if (it.optBoolean("enabled", true)) "●" else "○"
            "$on “${itemWords(it)}” → ${visualLabel(it.getJSONObject("visual"))}"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Chọn một hình để sửa (● bật, ○ tắt)")
            .setItems(labels) { _, i -> showItem(items[i].getString("id")) }
            .setNegativeButton("Đóng", null).show()
    }

    private fun item(id: String): JSONObject? = ProjectDesign.semanticItems(doc).firstOrNull { it.getString("id") == id }

    private fun showItem(id: String) {
        val it = item(id) ?: return
        val on = it.optBoolean("enabled", true)
        val isFrame = it.getJSONObject("visual").getString("kind") == "frame"
        val acts = ArrayList<Pair<String, () -> Unit>>()
        acts.add("Xem đoạn này" to { jumpTo(id) })
        acts.add((if (on) "Tắt hình này (hiện lại chữ)" else "Bật hình này") to {
            ProjectDesign.editItem(doc, id) { o -> o.put("enabled", !on) }; changed(); jumpTo(id); showItem(id)
        })
        acts.add("Đổi hình…" to { pickVisual(id) })
        acts.add("Đổi kiểu chuyển động…" to { pickChain(id) })
        acts.add("Chữ: thay bằng hình / giữ chữ…" to { pickTextMode(id) })
        if (isFrame && ProjectDoc.images(doc).isNotEmpty()) acts.add("Đổi ảnh trong khung…" to { pickFrameImage(id) })
        AlertDialog.Builder(this).setTitle("“${itemWords(it)}”")
            .setItems(acts.map { a -> a.first }.toTypedArray()) { _, i -> acts[i].second() }
            .setNegativeButton("Quay lại") { _, _ -> showItems() }.show()
    }

    private fun jumpTo(id: String) {
        val layers = sceneObj.optJSONArray("layers") ?: return
        for (i in 0 until layers.length()) {
            val L = layers.getJSONObject(i)
            if (L.getString("type") != "semantic") continue
            val arr = L.getJSONArray("items")
            for (k in 0 until arr.length()) {
                val o = arr.getJSONObject(k)
                if (o.getString("id") == id) {
                    // lùi một chút để thấy cả lúc hình xuất hiện
                    val t = (o.getLong("fromMs") + 200L)
                    seekTo(t); seek.progress = posMs.toInt()
                    return
                }
            }
        }
    }

    private fun pickVisual(id: String) {
        val images = ProjectDoc.images(doc)
        val opts = ArrayList<Pair<String, JSONObject>>()
        for (v in lib.visuals) {
            val json = if (v.kind == "frame") {
                JSONObject().put("kind", "frame").put("variant", v.variant).put("ref", images.firstOrNull()?.id ?: "")
            } else JSONObject().put("kind", "vector").put("ref", v.id)
            opts.add("${v.emoji} ${v.label}" to json)
        }
        images.forEachIndexed { n, im -> opts.add("Ảnh của bạn #${n + 1}" to JSONObject().put("kind", "userImage").put("ref", im.id)) }
        AlertDialog.Builder(this).setTitle("Chọn hình")
            .setItems(opts.map { o -> o.first }.toTypedArray()) { _, i ->
                ProjectDesign.editItem(doc, id) { o -> o.put("visual", opts[i].second) }
                changed(); jumpTo(id); showItem(id)
            }.setNegativeButton("Quay lại") { _, _ -> showItem(id) }.show()
    }

    private fun pickChain(id: String) {
        val it = item(id) ?: return
        val span = (it.getInt("tokenEnd") - it.getInt("tokenStart")).coerceAtLeast(1)
        AlertDialog.Builder(this).setTitle("Kiểu chuyển động")
            .setItems(lib.chains.map { c -> c.label }.toTypedArray()) { _, i ->
                val phases: JSONArray = lib.chainPhases(lib.chains[i], span)
                ProjectDesign.editItem(doc, id) { o -> o.put("phases", phases) }
                changed(); jumpTo(id); showItem(id)
            }.setNegativeButton("Quay lại") { _, _ -> showItem(id) }.show()
    }

    private fun pickTextMode(id: String) {
        val modes = arrayOf("replace" to "Thay chữ bằng hình (ẩn chữ)", "beside" to "Giữ chữ, hình hiện kèm", "hidden" to "Ẩn cả hình lẫn hiệu ứng")
        AlertDialog.Builder(this).setTitle("Chữ và hình")
            .setItems(modes.map { m -> m.second }.toTypedArray()) { _, i ->
                ProjectDesign.editItem(doc, id) { o -> o.put("textMode", modes[i].first) }
                changed(); jumpTo(id); showItem(id)
            }.setNegativeButton("Quay lại") { _, _ -> showItem(id) }.show()
    }

    private fun pickFrameImage(id: String) {
        val images = ProjectDoc.images(doc)
        AlertDialog.Builder(this).setTitle("Ảnh trong khung")
            .setItems(images.mapIndexed { n, _ -> "Ảnh #${n + 1}" }.toTypedArray()) { _, i ->
                ProjectDesign.editItem(doc, id) { o -> o.getJSONObject("visual").put("ref", images[i].id) }
                changed(); jumpTo(id); showItem(id)
            }.setNegativeButton("Quay lại") { _, _ -> showItem(id) }.show()
    }

    private val server by lazy { ProjectWebServer(this, store, id) }
    private fun urlFor(rel: String) = server.urlFor(rel)

    private fun rebuild() {
        val portrait = ProjectDesign.aspect(doc) == "9:16"
        val b = SceneBuilder.build(doc, if (portrait) 720 else 1280, if (portrait) 1280 else 720, null, ::urlFor)
        sceneObj = b.scene
        sceneJson = b.scene.toString()
        durationMs = maxOf(1L, b.scene.optLong("durationMs", 1L))
        seek.max = durationMs.toInt()
        val tpl = Templates.get(ProjectDesign.templateId(doc))
        val sem = ProjectDesign.semanticItems(doc).count { it.optBoolean("enabled", true) }
        val notes = buildList {
            if (b.tempTiming) add("Chưa đồng bộ lời: đang chia đều thời gian để xem thử")
            if (b.untimedLines > 0) add("${b.untimedLines} câu chưa có thời gian")
            if (b.droppedByBudget > 0) add("${b.droppedByBudget} hình bị bỏ vì giới hạn hiệu ứng")
            if (ProjectDoc.audioFile(doc) == null) add("Chưa có nhạc")
        }
        info.text = "Template: ${tpl.label} · Khung ${ProjectDesign.aspect(doc)} · $sem hình thay chữ" +
            (if (notes.isEmpty()) "" else "\n" + notes.joinToString("\n"))
        toolLabel(tplTool, tpl.label)
        if (Fonts.adjustable(tpl)) {
            (fontTool.getChildAt(0) as? TextView)?.text = "Aa"
            toolLabel(fontTool, Fonts.get(ProjectDesign.fontId(doc))?.family ?: tpl.font)
        } else {
            val set = IconSets.forDoc(doc)
            (fontTool.getChildAt(0) as? TextView)?.text = set.emojis[0]
            toolLabel(fontTool, set.label)
        }
        toolLabel(ornTool, ORNAMENTS.firstOrNull { it.first == ProjectDesign.ornament(doc) }?.second ?: "Trang trí")
        toolLabel(aspTool, ProjectDesign.aspect(doc))
        toolLabel(editTool, "Hình (${ProjectDesign.semanticItems(doc).size})")
    }

    /** Tạo lại các hình thay chữ tự động từ lời hiện tại, giữ nguyên phần bạn đã sửa, rồi báo kết quả. */
    private fun autoDesign() {
        val r = AutoDesign.applySemantic(doc, lib)
        changed()
        val msg = when {
            r.added + r.keptUser == 0 -> "Lời hiện tại chưa có từ nào khớp thư viện hình (mưa, mây, gió, trăng, chim…)."
            else -> "Đã thiết kế lại: ${r.added} hình tự động, giữ ${r.keptUser} hình bạn đã sửa."
        }
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    private lateinit var ornTool: LinearLayout
    private lateinit var posTool: LinearLayout
    private var dragOn = false

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()

    private val ORNAMENTS = listOf(
        Pair<String?, String>(null, "Không trang trí"),
        Pair<String?, String>("hoa", "Hoa dây"),
        Pair<String?, String>("la", "Dây lá"),
        Pair<String?, String>("sao", "Sao lấp lánh"),
    )

    private fun showOrnamentDialog() {
        val tpl = Templates.get(ProjectDesign.templateId(doc))
        if (tpl.layout == "collage") { toast("Kiểu Nét bút đã có cách xếp chữ riêng nên chưa thêm trang trí được. Dùng Karaoke hoặc Lyric lướt."); return }
        val cur = ProjectDesign.ornament(doc)
        AlertDialog.Builder(this).setTitle("Trang trí quanh lời")
            .setSingleChoiceItems(ORNAMENTS.map { it.second }.toTypedArray(), ORNAMENTS.indexOfFirst { it.first == cur }.coerceAtLeast(0)) { d, i ->
                ProjectDesign.setOrnament(doc, ORNAMENTS[i].first)
                changed(); d.dismiss()
            }.setNegativeButton("Đóng", null).show()
    }

    private fun setDrag(on: Boolean) {
        dragOn = on
        web.evaluateJavascript("setDragMode($on)", null)
        (posTool.getChildAt(0) as? TextView)?.setTextColor(if (on) Ui.ACCENT else Ui.TEXT)
        toolLabel(posTool, if (on) "Đang kéo" else "Vị trí")
        if (on) toast("Đặt ngón tay lên video rồi kéo lên xuống để dời lời, thả tay để chốt.")
    }

    private fun showPositionDialog() {
        val tpl = Templates.get(ProjectDesign.templateId(doc))
        if (tpl.layout == "collage") { toast("Kiểu Nét bút xếp chữ theo cụm nên chưa kéo được. Dùng Karaoke hoặc Lyric lướt."); return }
        AlertDialog.Builder(this).setTitle("Vị trí lời")
            .setItems(arrayOf(if (dragOn) "Tắt chế độ kéo" else "Kéo trên video để dời lời lên xuống", "Về vị trí mặc định của kiểu")) { _, i ->
                if (i == 0) setDrag(!dragOn) else { ProjectDesign.setTextY(doc, null); changed(); toast("Đã đưa lời về vị trí mặc định.") }
            }.setNegativeButton("Đóng", null).show()
    }

    // ---------- biểu tượng hai bên câu (karaoke) ----------

    private val prefs by lazy { getSharedPreferences("mykara_prefs", MODE_PRIVATE) }

    private fun recentIconSets(): List<String> = try {
        val a = org.json.JSONArray(prefs.getString("icon_sets", "[]"))
        (0 until a.length()).map { a.getString(it) }
    } catch (_: Exception) { emptyList() }

    private fun rememberIconSet(joined: String) {
        val l = (listOf(joined) + recentIconSets().filter { it != joined }).take(6)
        prefs.edit().putString("icon_sets", org.json.JSONArray(l).toString()).apply()
    }

    /** Tách chuỗi thành từng biểu tượng (kể cả emoji ghép), bỏ chữ và khoảng trắng. */
    private fun emojiList(s: String): List<String> {
        val bi = android.icu.text.BreakIterator.getCharacterInstance()
        bi.setText(s)
        val out = ArrayList<String>()
        var st = bi.first()
        var en = bi.next()
        while (en != android.icu.text.BreakIterator.DONE) {
            val g = s.substring(st, en)
            st = en; en = bi.next()
            if (g.isNotBlank() && !g.all { it.code < 128 || it.isLetterOrDigit() }) out.add(g)
        }
        return out.take(8)
    }

    private fun showIconDialog() {
        val names = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        for (s in IconSets.all) {
            names.add("${s.emojis.take(4).joinToString(" ")}   ${s.label}")
            actions.add { ProjectDesign.setIconSet(doc, s.id); changed() }
        }
        for (r in recentIconSets()) {
            names.add("$r   (bộ của tôi)")
            actions.add { ProjectDesign.setCustomIcons(doc, emojiList(r)); changed() }
        }
        names.add("✎  Tự chọn emoji…")
        actions.add { showIconEditor() }
        AlertDialog.Builder(this).setTitle("Biểu tượng hai bên câu")
            .setItems(names.toTypedArray()) { _, i -> actions[i]() }
            .setNegativeButton("Đóng", null).show()
    }

    private fun showIconEditor() {
        val c = this
        val palette = "💜 💗 💖 💙 💚 💛 🧡 ❤️ 🤍 🖤 ✨ ⭐ 🌟 💫 🌙 ☀️ 🌈 ☁️ 🔥 🎵 🎶 🎤 🎧 🎸 🌸 🌼 🌷 🌺 🌻 🍀 🦋 🕊️ 🐱 🐶 🐰 🐻 🦄 🍓 🍒 🍭 🎀 🎈 🎁 💎 👑 😊 🥰 😘 😢".split(" ")
        val edit = Ui.edit(c, ProjectDesign.customIcons(doc).joinToString(""), "Gõ hoặc dán emoji (tối đa 8)")
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(c, 16), Ui.dp(c, 8), Ui.dp(c, 16), 0) }
        col.addView(Ui.text(c, "Các emoji này lần lượt xuất hiện hai bên mỗi câu. Chọn nhanh bên dưới hoặc dùng bàn phím emoji để gõ.", 13f, Ui.MUTED))
        col.addView(edit)
        val grid = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        for (chunk in palette.chunked(8)) {
            val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
            for (e in chunk) row.addView(Ui.text(c, e, 24f).apply {
                gravity = android.view.Gravity.CENTER
                setOnClickListener { edit.setText(emojiList(edit.text.toString() + e).joinToString("")) ; edit.setSelection(edit.text.length) }
            }, LinearLayout.LayoutParams(0, Ui.dp(c, 44), 1f))
            grid.addView(row)
        }
        col.addView(grid)
        val sv = android.widget.ScrollView(c).apply { addView(col) }
        AlertDialog.Builder(this).setTitle("Emoji của tôi").setView(sv)
            .setPositiveButton("Dùng") { _, _ ->
                val l = emojiList(edit.text.toString())
                if (l.isEmpty()) { toast("Chưa có emoji nào. Dùng bộ có sẵn."); return@setPositiveButton }
                ProjectDesign.setCustomIcons(doc, l)
                rememberIconSet(l.joinToString(""))
                changed()
            }
            .setNeutralButton("Xóa hết") { _, _ -> edit.setText("") ; showIconEditor() }
            .setNegativeButton("Hủy", null).show()
    }

    private lateinit var tplTool: LinearLayout
    private lateinit var fontTool: LinearLayout
    private lateinit var aspTool: LinearLayout
    private lateinit var editTool: LinearLayout

    private fun toolLabel(tool: LinearLayout, s: String) {
        (tool.getChildAt(1) as? TextView)?.text = s
    }

    private fun changed() {
        store.save(id, doc)
        rebuild()
        web.evaluateJavascript("loadScene();", null)
    }

    private fun ensurePlayer(): MediaPlayer? {
        player?.let { return it }
        val f = ProjectDoc.audioFile(doc) ?: return null
        val file = store.assetFile(id, f)
        if (!file.exists()) return null
        return MediaPlayer().apply {
            setDataSource(file.absolutePath)
            prepare()
            setOnCompletionListener { playing = false; playBtn.text = "▶"; posMs = durationMs }
        }.also { player = it }
    }

    private fun togglePlay() {
        if (playing) {
            player?.pause()
            playing = false
            playBtn.text = "▶"
        } else {
            if (posMs >= durationMs - 50) seekTo(0)
            val p = ensurePlayer()
            p?.seekTo(posMs.toInt())
            p?.start()
            clockBase = posMs
            clockStart = SystemClock.elapsedRealtime()
            playing = true
            playBtn.text = "⏸"
        }
    }

    private fun seekTo(ms: Long) {
        posMs = ms.coerceIn(0, durationMs)
        clockBase = posMs
        clockStart = SystemClock.elapsedRealtime()
        player?.seekTo(posMs.toInt())
    }

    private val tick = object : Runnable {
        override fun run() {
            if (playing) {
                val p = player
                posMs = if (p != null) p.currentPosition.toLong()
                else clockBase + (SystemClock.elapsedRealtime() - clockStart)
                if (posMs >= durationMs) { posMs = durationMs; playing = false; playBtn.text = "▶"; player?.pause() }
                seek.progress = posMs.toInt()
            }
            ui.postDelayed(this, 16)
        }
    }

    private inner class Bridge {
        @JavascriptInterface fun getScene(): String = sceneJson
        @JavascriptInterface fun mode(): String = "preview"
        @JavascriptInterface fun pos(): Long = posMs
        @JavascriptInterface fun frameReady() {}
        @JavascriptInterface fun textYChanged(y: Double) { runOnUiThread { ProjectDesign.setTextY(doc, y); changed() } }
    }

    override fun onPause() {
        super.onPause()
        if (playing) togglePlay()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        player?.release()
        web.destroy()
        super.onDestroy()
    }
}
