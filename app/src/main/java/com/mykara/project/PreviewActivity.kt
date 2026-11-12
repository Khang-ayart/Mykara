package com.mykara.project

import android.annotation.SuppressLint
import android.app.Activity
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
        if (ProjectDesign.semanticLayer(doc) == null) {
            AutoDesign.applyTemplate(doc, null)
            AutoDesign.applySemantic(doc, lib)
            store.save(id, doc)
        }

        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        web = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            addJavascriptInterface(Bridge(), "Android")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? = serve(r)
                override fun onPageFinished(v: WebView, url: String) {
                    v.evaluateJavascript("loadScene();startLoop();", null)
                }
            }
        }
        root.addView(web)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
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
        panel.addView(seek)
        info = TextView(this).apply { textSize = 13f; setTextColor(0xFF555555.toInt()) }
        panel.addView(info)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        playBtn = Button(this).apply { text = "▶ Phát"; setOnClickListener { togglePlay() } }
        row1.addView(playBtn)
        row1.addView(Button(this).apply {
            setOnClickListener {
                val next = Templates.next(ProjectDesign.templateId(doc)).id
                ProjectDesign.setTemplate(doc, next, "user")
                changed()
            }
            tag = "tpl"
        })
        row1.addView(Button(this).apply {
            setOnClickListener {
                ProjectDesign.setAspect(doc, if (ProjectDesign.aspect(doc) == "16:9") "9:16" else "16:9")
                changed()
            }
            tag = "asp"
        })
        panel.addView(row1)

        val sw = Switch(this).apply {
            text = "Thay chữ bằng hình (mây, mưa, tim…)"
            isChecked = ProjectDesign.semanticEnabled(doc)
            setOnCheckedChangeListener { _, on -> ProjectDesign.setSemanticEnabled(doc, on); changed() }
        }
        panel.addView(sw)
        panel.addView(Button(this).apply {
            text = "Tự thiết kế lại hình thay chữ (giữ phần bạn đã sửa)"
            setOnClickListener { AutoDesign.applySemantic(doc, lib); changed() }
        })
        root.addView(panel)
        setContentView(root)

        rebuild()
        web.loadUrl("https://mykara.local/app/engine.html")
        ui.post(tick)
    }

    private fun urlFor(rel: String) = "https://mykara.local/p/$id/$rel"

    private fun rebuild() {
        val portrait = ProjectDesign.aspect(doc) == "9:16"
        val b = SceneBuilder.build(doc, if (portrait) 720 else 1280, if (portrait) 1280 else 720, null, ::urlFor)
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
        (web.parent as ViewGroup).findViewWithTag<Button>("tpl")?.text = "Template: ${tpl.label}"
        (web.parent as ViewGroup).findViewWithTag<Button>("asp")?.text = "Khung ${ProjectDesign.aspect(doc)}"
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
            setOnCompletionListener { playing = false; playBtn.text = "▶ Phát"; posMs = durationMs }
        }.also { player = it }
    }

    private fun togglePlay() {
        if (playing) {
            player?.pause()
            playing = false
            playBtn.text = "▶ Phát"
        } else {
            if (posMs >= durationMs - 50) seekTo(0)
            val p = ensurePlayer()
            p?.seekTo(posMs.toInt())
            p?.start()
            clockBase = posMs
            clockStart = SystemClock.elapsedRealtime()
            playing = true
            playBtn.text = "⏸ Dừng"
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
                if (posMs >= durationMs) { posMs = durationMs; playing = false; playBtn.text = "▶ Phát"; player?.pause() }
                seek.progress = posMs.toInt()
            }
            ui.postDelayed(this, 16)
        }
    }

    private fun serve(r: WebResourceRequest): WebResourceResponse? {
        val u = r.url
        if (u.host != "mykara.local") return null
        val path = u.path ?: return notFound()
        return try {
            when {
                path.startsWith("/app/") -> {
                    val name = path.removePrefix("/app/")
                    if (name.contains("..")) return notFound()
                    respond(mime(name), assets.open(name).readBytes())
                }
                path.startsWith("/p/$id/") -> {
                    val rel = path.removePrefix("/p/$id/")
                    val f = store.assetFile(id, rel)
                    val base = store.dir(id).canonicalFile
                    if (!f.canonicalFile.path.startsWith(base.path + File.separator) || !f.isFile) return notFound()
                    respond(mime(rel), f.readBytes())
                }
                else -> notFound()
            }
        } catch (e: Exception) {
            notFound()
        }
    }

    private fun respond(type: String, bytes: ByteArray) = WebResourceResponse(type, "utf-8", ByteArrayInputStream(bytes)).apply {
        responseHeaders = mapOf("Access-Control-Allow-Origin" to "*")
    }

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html"
        "js" -> "application/javascript"
        "json" -> "application/json"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
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
