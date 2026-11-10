package com.mykara.prototype

import android.annotation.SuppressLint
import android.content.ContentValues
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private var pickedUri: Uri? = null
    private lateinit var logView: TextView
    private lateinit var progressView: TextView
    private lateinit var renderButton: Button
    private lateinit var pickButton: Button
    private lateinit var radio1080: RadioButton
    private lateinit var rendererGroup: RadioGroup
    private lateinit var webView: WebView

    @Volatile
    private var pageLoaded = false

    @Volatile
    private var activeWebRenderer: WebViewFrameRenderer? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pickedUri = uri
            pickButton.text = "Đã chọn nhạc (chạm để đổi)"
            log("Đã chọn nhạc: ${uri.lastPathSegment}")
        }
    }

    /** Cầu nối: trang web báo khung đã sẵn sàng. */
    private inner class Bridge {
        @JavascriptInterface
        fun frameReady() {
            activeWebRenderer?.onFrameReady()
        }

        @JavascriptInterface
        fun frameData(b64: String) {
            activeWebRenderer?.onFrameData(b64)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // WebView nằm dưới cùng (được che bởi giao diện), vẫn "hiển thị" theo Android nên trình duyệt vẫn vẽ.
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            setBackgroundColor(Color.BLACK)
            addJavascriptInterface(Bridge(), "Android")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    pageLoaded = true
                }
            }
            loadUrl("file:///android_asset/scene.html")
        }

        val pad = (16 * resources.displayMetrics.density).toInt()
        val ui = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            setBackgroundColor(Color.WHITE)
        }

        ui.addView(TextView(this).apply {
            text = "Mykara – thử dựng MP4 10 giây"
            textSize = 20f
            gravity = Gravity.START
        })

        pickButton = Button(this).apply {
            text = "Chọn file nhạc (bỏ qua = dùng tiếng bíp thử)"
            setOnClickListener { picker.launch(arrayOf("audio/*")) }
        }
        ui.addView(pickButton)

        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val radio720 = RadioButton(this).apply { text = "720p"; id = 1001; isChecked = true }
        radio1080 = RadioButton(this).apply { text = "1080p"; id = 1002 }
        group.addView(radio720)
        group.addView(radio1080)
        ui.addView(group)

        rendererGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        rendererGroup.addView(RadioButton(this).apply { text = "Bộ vẽ: Canvas gốc"; id = 2001; isChecked = true })
        rendererGroup.addView(RadioButton(this).apply { text = "Bộ vẽ: WebView – vẽ trực tiếp"; id = 2002 })
        rendererGroup.addView(RadioButton(this).apply { text = "Bộ vẽ: WebView – qua ảnh JPEG (chậm, chắc chắn)"; id = 2003 })
        ui.addView(rendererGroup)

        renderButton = Button(this).apply {
            text = "Dựng thử 10 giây"
            setOnClickListener { startRender() }
        }
        ui.addView(renderButton)

        progressView = TextView(this).apply { text = "" }
        ui.addView(progressView)

        logView = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply {
            addView(logView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        ui.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val root = FrameLayout(this)
        root.addView(webView, FrameLayout.LayoutParams(1280, 720))
        root.addView(ui, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        log("Máy: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, chip ${Build.HARDWARE}")
    }

    private fun log(s: String) {
        runOnUiThread { logView.append(s + "\n") }
    }

    /** Đặt kích thước WebView đúng bằng khung xuất, rồi cho trang cấu hình canvas. Gọi từ luồng dựng. */
    private fun prepareWebView(w: Int, h: Int) {
        check(pageLoaded) { "Trang scene.html chưa tải xong, thử lại sau vài giây" }
        val done = CountDownLatch(1)
        runOnUiThread {
            webView.layoutParams = FrameLayout.LayoutParams(w, h)
            webView.requestLayout()
            webView.evaluateJavascript("setup($w,$h)") { done.countDown() }
        }
        check(done.await(5, TimeUnit.SECONDS)) { "Không cấu hình được trang WebView" }
        var waited = 0
        while ((webView.width != w || webView.height != h) && waited < 3000) {
            Thread.sleep(50)
            waited += 50
        }
        check(webView.width == w && webView.height == h) {
            "WebView chưa đạt kích thước ${w}x$h (hiện ${webView.width}x${webView.height})"
        }
    }

    private fun startRender() {
        renderButton.isEnabled = false
        pickButton.isEnabled = false
        val use1080 = radio1080.isChecked
        val sel = rendererGroup.checkedRadioButtonId
        val useWeb = sel != 2001
        val webMode = if (sel == 2003) WebViewFrameRenderer.Mode.JPEG else WebViewFrameRenderer.Mode.DIRECT
        val rendererLabel = when (sel) { 2002 -> "WebView trực tiếp"; 2003 -> "WebView qua JPEG"; else -> "Canvas gốc" }
        val uri = pickedUri
        log(
            "---- Bắt đầu: ${if (use1080) "1080p" else "720p"}, " +
                "${if (uri != null) "nhạc đã chọn" else "tiếng bíp thử"}, " +
                "bộ vẽ: $rendererLabel",
        )

        Thread {
            var webRenderer: WebViewFrameRenderer? = null
            try {
                val t0 = System.nanoTime()
                val audio = if (uri != null) AudioDecoder.decode(this, uri, 10_000) else ToneAudio.generate(10_000)
                log("Âm thanh: ${audio.sampleRate} Hz, ${audio.channels} kênh (giải mã ${(System.nanoTime() - t0) / 1_000_000} ms)")

                val cfg = if (use1080) ExportConfig(1920, 1080, 8_000_000) else ExportConfig(1280, 720, 5_000_000)
                val renderer: FrameRenderer = if (useWeb) {
                    prepareWebView(cfg.width, cfg.height)
                    WebViewFrameRenderer(webView, cfg.width, cfg.height, webMode).also {
                        webRenderer = it
                        activeWebRenderer = it
                    }
                } else {
                    SampleLyricRenderer()
                }

                val tmp = File(cacheDir, "mykara_test.mp4")
                val result = Mp4Exporter(cfg, renderer, audio) { done, total ->
                    if (done % 10 == 0 || done == total) {
                        runOnUiThread { progressView.text = "Đang dựng: $done / $total khung" }
                    }
                }.export(tmp)

                val saved = saveToGallery(tmp)
                val rt = cfg.durationMs.toDouble() / result.totalMs
                log(
                    String.format(
                        Locale.US,
                        "XONG: %d/%d khung, tổng %.1f s (nhanh gấp %.2f lần thời gian thật)\n" +
                            "  vẽ khung: %d ms (%.1f ms/khung), mã hóa AAC: %d ms\n" +
                            "  bộ mã hóa: %s (phần cứng: %s)\n" +
                            "  file: %.1f MB\n" +
                            "  đã lưu vào Thư viện/Movies/Mykara (%s)",
                        result.framesWritten, result.framesExpected,
                        result.totalMs / 1000.0, rt,
                        result.renderMs, result.renderMs.toDouble() / result.framesExpected,
                        result.audioEncodeMs,
                        result.encoderName, result.hardwareEncoder,
                        tmp.length() / 1_048_576.0,
                        saved,
                    ),
                )
                if (result.framesWritten != result.framesExpected) {
                    log("CẢNH BÁO: số khung ghi ra khác số khung vẽ → mốc thời gian có thể lệch. Hãy gửi lại dòng này.")
                }
                runOnUiThread { progressView.text = "Xong. Mở Thư viện/Google Photos để xem video." }
            } catch (t: Throwable) {
                log("LỖI: ${t.javaClass.simpleName}: ${t.message}\n${t.stackTraceToString().take(1500)}")
                runOnUiThread { progressView.text = "Lỗi, xem nhật ký bên dưới." }
            } finally {
                activeWebRenderer = null
                webRenderer?.release()
                runOnUiThread {
                    renderButton.isEnabled = true
                    pickButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun saveToGallery(file: File): String {
        val name = "Mykara_${System.currentTimeMillis()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Mykara")
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Không tạo được mục trong Thư viện")
        contentResolver.openOutputStream(uri)!!.use { o ->
            file.inputStream().use { it.copyTo(o) }
        }
        return name
    }
}
