package com.mykara.prototype

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.WebView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Bộ vẽ dựa trên WebView: trang scene.html vẽ khung bằng canvas 2D.
 *
 * DIRECT: WebView vẽ thẳng vào canvas phần cứng của đường mã hóa (draw() chạy trên luồng UI).
 *         Trang vẽ xong thì chờ 2 lần requestAnimationFrame để trình duyệt ghép khung.
 * JPEG:   trang xuất khung thành JPEG (canvas.toDataURL) gửi sang Android, giải mã rồi chép vào canvas.
 *         Chậm hơn nhưng không phụ thuộc cách WebView ghép hình. (Chụp WebView qua Bitmap phần mềm
 *         đã thử: ra màn đen trên Android 16, nên bỏ.)
 */
class WebViewFrameRenderer(
    private val webView: WebView,
    private val w: Int,
    private val h: Int,
    private val mode: Mode,
) : FrameRenderer {

    enum class Mode { DIRECT, JPEG }

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var latch: CountDownLatch? = null

    @Volatile
    private var jpeg: Bitmap? = null
    private var reuse: Bitmap? = null

    override val drawOnMainThread: Boolean get() = mode == Mode.DIRECT

    /** Gọi từ cầu nối JavaScript (chế độ DIRECT) khi trang đã vẽ và ghép khung. */
    fun onFrameReady() {
        latch?.countDown()
    }

    /** Gọi từ cầu nối JavaScript (chế độ JPEG) với dữ liệu JPEG dạng base64. */
    fun onFrameData(b64: String) {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        val opts = BitmapFactory.Options().apply {
            inMutable = true
            inBitmap = reuse
        }
        val bmp = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: IllegalArgumentException) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inMutable = true })
        }
        reuse = bmp
        jpeg = bmp
        latch?.countDown()
    }

    override fun prepare(tMs: Long) {
        val ready = CountDownLatch(1)
        latch = ready
        jpeg = null
        val js = if (mode == Mode.DIRECT) "renderFrame($tMs)" else "renderFrameJpeg($tMs)"
        main.post { webView.evaluateJavascript(js, null) }
        check(ready.await(5, TimeUnit.SECONDS)) { "WebView không trả khung t=$tMs ms (quá 5 giây)" }
    }

    override fun draw(canvas: Canvas, tMs: Long, width: Int, height: Int) {
        require(width == w && height == h) { "Kích thước khung ($width x $height) khác WebView ($w x $h)" }
        when (mode) {
            Mode.DIRECT -> webView.draw(canvas)
            Mode.JPEG -> canvas.drawBitmap(checkNotNull(jpeg) { "Chưa nhận được JPEG cho khung t=$tMs ms" }, 0f, 0f, null)
        }
    }

    fun release() {
        reuse = null
        jpeg = null
    }
}
