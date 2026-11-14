package com.mykara.project

import android.annotation.SuppressLint
import android.content.ContentValues
import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.mykara.prototype.AudioDecoder
import com.mykara.prototype.ExportConfig
import com.mykara.prototype.FrameRenderer
import com.mykara.prototype.Mp4Exporter
import com.mykara.prototype.PcmAudio
import com.mykara.prototype.WebViewFrameRenderer
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Xuất video từ dự án: dựng Scene JSON bằng đúng SceneBuilder của Xem trước, cho engine.html vẽ từng
 * khung trong WebView kích thước thật (720p / 1080p), mã hóa H.264 + AAC thành MP4 rồi lưu vào
 * Thư viện (Movies/Mykara). Vẽ theo thời gian cố định nên kết quả không phụ thuộc tốc độ máy.
 */
class ExportActivity : ComponentActivity() {

    private lateinit var store: ProjectStore
    private lateinit var id: String
    private lateinit var doc: JSONObject
    private lateinit var server: ProjectWebServer
    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private lateinit var body: LinearLayout
    private val ui = Handler(Looper.getMainLooper())

    private var use1080 = false
    private var portrait = false

    @Volatile private var renderer: WebViewFrameRenderer? = null
    @Volatile private var cancelled = false
    @Volatile private var running = false
    @Volatile private var sceneJson = "{}"
    // App ra nền (về màn hình chính, khóa máy…) thì WebView không còn được ghép hình: tạm dừng, quay lại thì chạy tiếp.
    @Volatile private var paused = false
    @Volatile private var pauseGen = 0
    private var lastNotifPermille = -100

    private var progress: ProgressBar? = null
    private var statusText: TextView? = null
    private var resultUri: Uri? = null
    private var askedNotif = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ProjectStore(this)
        id = intent.getStringExtra("id") ?: run { finish(); return }
        doc = store.load(id)
        server = ProjectWebServer(this, store, id)
        portrait = ProjectDesign.aspect(doc) == "9:16"

        // WebView nằm dưới, đúng kích thước khung xuất (điểm ảnh thật); giao diện đặt đè lên che đi.
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            addJavascriptInterface(Bridge(), "Android")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? = server.serve(r)
            }
        }
        root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        root.addView(web, FrameLayout.LayoutParams(1280, 720))
        body = Ui.column(this, padTop = 24)
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(body) }
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        showOptions()
    }

    // ---------- giao diện ----------

    private fun header(): LinearLayout {
        val c = this
        return LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.text(c, "←", 24f).apply {
                setPadding(0, Ui.dp(c, 8), Ui.dp(c, 16), Ui.dp(c, 8))
                setOnClickListener { onBack() }
            })
            addView(Ui.text(c, "Xuất video", 20f, Ui.TEXT, true))
        }
    }

    private fun showOptions() {
        val c = this
        body.removeAllViews()
        body.addView(header())

        val warn = ArrayList<String>()
        if (ProjectDoc.audioFile(doc) == null) warn.add("Dự án chưa có nhạc: video sẽ không có tiếng.")
        if (ProjectTiming.lines(doc).isEmpty()) warn.add("Lời chưa đồng bộ: video dùng thời gian chia đều tạm.")

        body.addView(Ui.section(c, "Dự án"))
        val info = Ui.card(c)
        info.addView(Ui.text(c, ProjectDoc.title(doc), 17f, Ui.TEXT, true))
        val ms = ProjectDoc.audioDurationMs(doc)
        if (ms != null) info.addView(Ui.text(c, "Độ dài ${ms / 60000}:${"%02d".format(ms / 1000 % 60)}", 13f, Ui.MUTED))
        for (w in warn) info.addView(Ui.text(c, "⚠ $w", 13f, Ui.ERR).apply { setPadding(0, Ui.dp(c, 6), 0, 0) })
        body.addView(info)

        body.addView(Ui.section(c, "Độ phân giải"))
        body.addView(choiceRow(listOf("720p (nhanh)", "1080p (nét)"), if (use1080) 1 else 0) { use1080 = it == 1 })
        body.addView(Ui.section(c, "Khung hình"))
        body.addView(choiceRow(listOf("16:9 ngang", "9:16 dọc"), if (portrait) 1 else 0) { portrait = it == 1 })

        body.addView(Ui.text(c, "Màn hình sẽ được giữ sáng trong lúc xuất. Bạn có thể sang app khác: việc xuất tạm dừng và chạy tiếp khi bạn quay lại Mykara. Bài dài sẽ mất vài phút.", 13f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(c, 16), 0, Ui.dp(c, 8))
        })
        body.addView(Ui.button(c, "⬇  Bắt đầu xuất", Ui.PRIMARY) { startExport() })
    }

    /** Hàng nút chọn một trong nhiều (kiểu thẻ). */
    private fun choiceRow(labels: List<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
        val c = this
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        val views = ArrayList<TextView>()
        fun paint(sel: Int) {
            for ((i, v) in views.withIndex()) {
                v.background = Ui.rounded(if (i == sel) Ui.ACCENT else Ui.PANEL_HI, 12, c)
                v.setTextColor(if (i == sel) android.graphics.Color.WHITE else Ui.TEXT)
            }
        }
        for ((i, l) in labels.withIndex()) {
            val v = Ui.text(c, l, 15f, Ui.TEXT, true).apply {
                gravity = Gravity.CENTER
                minHeight = Ui.dp(c, 48)
                setOnClickListener { onPick(i); paint(i) }
            }
            views.add(v)
            row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(if (i == 0) 0 else Ui.dp(c, 8), 0, 0, 0)
            })
        }
        paint(selected)
        return row
    }

    private fun showProgress() {
        val c = this
        body.removeAllViews()
        body.addView(header())
        body.addView(Ui.section(c, "Đang xuất"))
        val card = Ui.card(c)
        statusText = Ui.text(c, "Đang chuẩn bị…", 15f).also { card.addView(it) }
        progress = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            card.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(c, 12) })
        }
        body.addView(card)
        body.addView(Ui.button(c, "Hủy", Ui.SECONDARY) { cancelExport() })
    }

    private fun showDone(name: String, mb: Double, took: Long, speed: String) {
        val c = this
        body.removeAllViews()
        body.addView(header())
        body.addView(Ui.section(c, "Xong"))
        val card = Ui.card(c)
        card.addView(Ui.text(c, "✓ Đã lưu vào Thư viện › Movies › Mykara", 15f, Ui.OK, true))
        card.addView(Ui.text(c, String.format(Locale.US, "%s · %.1f MB · mất %d giây", name, mb, took / 1000), 13f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(c, 6), 0, 0)
        })
        card.addView(Ui.text(c, speed, 12f, Ui.MUTED).apply { setPadding(0, Ui.dp(c, 4), 0, 0) })
        body.addView(card)
        resultUri?.let { u ->
            body.addView(Ui.button(c, "▶  Mở video", Ui.PRIMARY) {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            })
            body.addView(Ui.button(c, "Chia sẻ", Ui.SECONDARY) {
                startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        "Chia sẻ video",
                    ),
                )
            })
        }
        body.addView(Ui.button(c, "Xuất bản khác", Ui.TEXT_ONLY) { showOptions() })
    }

    private fun showError(msg: String) {
        val c = this
        body.removeAllViews()
        body.addView(header())
        body.addView(Ui.section(c, "Không xuất được"))
        val card = Ui.card(c)
        card.addView(Ui.text(c, msg, 14f, Ui.ERR))
        body.addView(card)
        body.addView(Ui.button(c, "Thử lại", Ui.PRIMARY) { showOptions() })
    }

    // ---------- xuất ----------

    private fun startExport() {
        if (running) return
        running = true
        cancelled = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showProgress()
        lastNotifPermille = -100
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !askedNotif) {
            askedNotif = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7) // chỉ để hiện thông báo tiến độ; từ chối vẫn xuất được
        }
        ExportService.start(this, id)

        val long = if (use1080) 1920 else 1280
        val short = if (use1080) 1080 else 720
        val w = if (portrait) short else long
        val h = if (portrait) long else short
        val bitrate = if (use1080) 8_000_000 else 5_000_000

        Thread {
            val t0 = SystemClock.elapsedRealtime()
            var tmp: File? = null
            try {
                status("Đang dựng cảnh…")
                val built = SceneBuilder.build(doc, w, h, null, server::urlFor)
                val durationMs = maxOf(1000L, built.scene.optLong("durationMs", 1000L)).toInt()
                sceneJson = built.scene.toString()

                status("Đang đọc nhạc…")
                val audio = loadAudio(durationMs)

                status("Đang tải engine và ảnh…")
                prepareWebView(w, h)

                val r = WebViewFrameRenderer(web, w, h, WebViewFrameRenderer.Mode.DIRECT)
                renderer = r
                val guarded = object : FrameRenderer {
                    override val drawOnMainThread: Boolean get() = r.drawOnMainThread
                    override fun prepare(tMs: Long) {
                        var attempts = 0
                        while (true) {
                            while (paused && !cancelled) Thread.sleep(100)
                            if (cancelled) throw CancellationException("Đã hủy")
                            val gen = pauseGen
                            try {
                                r.prepare(tMs)
                                if (gen == pauseGen && !paused) return
                            } catch (e: IllegalStateException) {
                                // khung quá hạn vì app vừa ra nền: bỏ qua, vẽ lại khi quay về; còn lỗi thật thì báo
                                if (gen == pauseGen && !paused) throw e
                            }
                            check(++attempts < 30) { "Không vẽ được khung t=$tMs ms sau nhiều lần thử" }
                        }
                    }
                    override fun draw(canvas: android.graphics.Canvas, tMs: Long, width: Int, height: Int) = r.draw(canvas, tMs, width, height)
                }

                tmp = File(cacheDir, "mykara_export.mp4")
                val cfg = ExportConfig(w, h, bitrate, 30, durationMs)
                val renderStart = SystemClock.elapsedRealtime()
                val result = Mp4Exporter(cfg, guarded, audio) { done, total ->
                    if (done % 5 == 0 || done == total) {
                        val el = SystemClock.elapsedRealtime() - renderStart
                        val eta = if (done > 0) el * (total - done) / done / 1000 else -1L
                        val pct = done * 1000 / maxOf(1, total)
                        val line = "Đang dựng khung $done / $total" + if (eta >= 0) " · còn khoảng ${eta / 60}:${"%02d".format(eta % 60)}" else ""
                        ui.post {
                            progress?.progress = pct
                            statusText?.text = line
                            if (pct - lastNotifPermille >= 10 || pct == 1000) {
                                lastNotifPermille = pct
                                ExportService.update(this, id, pct, line)
                            }
                        }
                    }
                }.export(tmp)
                val renderTook = SystemClock.elapsedRealtime() - renderStart

                if (result.framesWritten != result.framesExpected) {
                    status("Cảnh báo: ghi ${result.framesWritten}/${result.framesExpected} khung.")
                }
                status("Đang lưu vào Thư viện…")
                val name = saveToGallery(tmp)
                val mb = tmp.length() / 1_048_576.0
                val took = SystemClock.elapsedRealtime() - t0
                val speed = String.format(
                    Locale.US, "Dựng %d khung trong %d giây (%.1f khung/giây, %.1f× thời gian thật) · %s%s",
                    result.framesWritten, renderTook / 1000, result.framesWritten * 1000.0 / maxOf(1L, renderTook),
                    durationMs.toDouble() / maxOf(1L, renderTook), result.encoderName, if (result.hardwareEncoder) "" else " (phần mềm)",
                )
                ui.post { showDone(name, mb, took, speed) }
            } catch (c: CancellationException) {
                ui.post { showOptions() }
            } catch (t: Throwable) {
                if (cancelled) {
                    ui.post { showOptions() }
                } else {
                    val msg = "${t.javaClass.simpleName}: ${t.message}"
                    ui.post { showError(msg) }
                }
            } finally {
                tmp?.let { if (it.exists()) it.delete() }
                renderer?.release()
                renderer = null
                running = false
                paused = false
                ExportService.stop(applicationContext)
                ui.post { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
        }.start()
    }

    private fun status(s: String) = ui.post { statusText?.text = s }

    private fun cancelExport() {
        cancelled = true
        statusText?.text = "Đang hủy…"
    }

    private fun loadAudio(durationMs: Int): PcmAudio {
        val rel = ProjectDoc.audioFile(doc)
        val f = rel?.let { store.assetFile(id, it) }
        if (f != null && f.isFile) return AudioDecoder.decode(this, Uri.fromFile(f), durationMs)
        val sr = 44100
        return PcmAudio(ByteArray((durationMs.toLong() * sr / 1000).toInt() * 4), sr, 2) // im lặng
    }

    /** Đặt WebView đúng w×h điểm ảnh, tải engine, nạp cảnh rồi chờ ảnh và phông sẵn sàng. */
    private fun prepareWebView(w: Int, h: Int) {
        val loaded = CountDownLatch(1)
        runOnUiThread {
            web.layoutParams = FrameLayout.LayoutParams(w, h)
            web.requestLayout()
            web.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? = server.serve(r)
                override fun onPageFinished(v: WebView, url: String) {
                    v.evaluateJavascript("loadScene();", null)
                    loaded.countDown()
                }
            }
            web.clearCache(true)
            web.loadUrl("https://mykara.local/app/engine.html")
        }
        check(loaded.await(20, TimeUnit.SECONDS)) { "Không tải được engine" }
        var waited = 0
        while ((web.width != w || web.height != h) && waited < 4000) { Thread.sleep(50); waited += 50 }
        check(web.width == w && web.height == h) { "WebView chưa đạt kích thước ${w}x$h (hiện ${web.width}x${web.height})" }
        // chờ engine nạp xong ảnh/phông
        var ok = false
        var tries = 0
        while (!ok && tries < 200) {
            val got = CountDownLatch(1)
            var res = "false"
            runOnUiThread { web.evaluateJavascript("isReady()") { res = it ?: "false"; got.countDown() } }
            got.await(2, TimeUnit.SECONDS)
            ok = res == "true"
            if (!ok) { Thread.sleep(100); tries++ }
        }
        check(ok) { "Engine không sẵn sàng (ảnh hoặc phông chưa tải xong)" }
    }

    private fun saveToGallery(file: File): String {
        val safe = ProjectDoc.title(doc).replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').take(40).ifEmpty { "video" }
        val name = "Mykara_${safe}_${System.currentTimeMillis() / 1000}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Mykara")
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("Không tạo được mục trong Thư viện")
        contentResolver.openOutputStream(uri)!!.use { o -> file.inputStream().use { it.copyTo(o) } }
        resultUri = uri
        return name
    }

    private fun onBack() {
        if (!running) { finish(); return }
        // Trước đây bấm Quay lại là hủy ngay: giờ hỏi lại cho chắc.
        AlertDialog.Builder(this)
            .setTitle("Đang xuất video")
            .setMessage("Thoát bây giờ sẽ hủy việc xuất. Muốn làm việc khác, bấm nút Home: việc xuất tạm dừng và chạy tiếp khi bạn quay lại app.")
            .setPositiveButton("Tiếp tục xuất", null)
            .setNegativeButton("Hủy xuất") { _, _ -> cancelExport() }
            .show()
    }

    override fun onStart() {
        super.onStart()
        paused = false
    }

    override fun onStop() {
        if (running) { pauseGen++; paused = true }
        super.onStop()
    }

    @Deprecated("Back")
    override fun onBackPressed() = onBack()

    private inner class Bridge {
        @JavascriptInterface fun getScene(): String = sceneJson
        @JavascriptInterface fun mode(): String = "export"
        @JavascriptInterface fun pos(): Long = 0L
        @JavascriptInterface fun frameReady() { renderer?.onFrameReady() }
        @JavascriptInterface fun frameData(b64: String) { renderer?.onFrameData(b64) }
    }

    override fun onDestroy() {
        cancelled = true
        ExportService.stop(applicationContext)
        ui.removeCallbacksAndMessages(null)
        web.destroy()
        super.onDestroy()
    }
}
