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
        if (ProjectDesign.semanticLayer(doc) == null) AutoDesign.applyTemplate(doc, null)
        AutoDesign.applySemantic(doc, lib)
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
        fontTool = Ui.tool(this, "Aa", "Font") {
            val tpl = Templates.get(ProjectDesign.templateId(doc))
            ProjectDesign.setFont(doc, Fonts.next(tpl, ProjectDesign.fontId(doc)).id)
            changed()
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
        panel.addView(Ui.toolbar(this, tplTool, fontTool, aspTool, editTool, autoTool, redoTool, exportTool).apply {
            setBackgroundColor(Ui.PANEL)
            setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 4))
        })
        root.addView(panel)
        setContentView(root)

        rebuild()
        web.loadUrl("https://mykara.local/app/engine.html")
        ui.post(tick)
    }

    // ---------- làm lại bản xem trước ----------

    private var busy: AlertDialog? = null

    private fun confirmRegenerate() {
        AlertDialog.Builder(this).setTitle("Làm lại bản xem trước này?")
            .setMessage("App xóa hẳn bản đang xem rồi dựng lại từ đầu với cách bố trí khác (kiểu chữ vào/ra, ảnh mở đầu, từ nhấn). Bạn sẽ phải đợi dựng xong.")
            .setItems(arrayOf("Làm lại, giữ phần tôi đã sửa tay", "Làm lại từ đầu, bỏ cả phần tôi đã sửa")) { _, i -> regenerate(clearUserEdits = i == 1) }
            .setNegativeButton("Hủy", null).show()
    }

    private fun regenerate(clearUserEdits: Boolean) {
        if (playing) togglePlay()
        busy = AlertDialog.Builder(this).setTitle("Đang dựng lại…").setMessage("Vui lòng đợi, không thao tác trong lúc này.")
            .setCancelable(false).show()
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
            if (r == "true") { busy?.dismiss(); busy = null; regenerating = false }
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
        toolLabel(fontTool, Fonts.get(ProjectDesign.fontId(doc))?.family ?: tpl.font)
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
