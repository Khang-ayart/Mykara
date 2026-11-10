package com.mykara.prototype

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Bộ vẽ dựa trên WebView: trang scene.html vẽ khung bằng canvas 2D, rồi ta chụp WebView
 * vào Bitmap và chép sang canvas của đường mã hóa. Gọi từ luồng dựng (không phải luồng UI).
 *
 * Quy trình mỗi khung:
 *  1. (luồng UI) gọi renderFrame(t) trong trang, chờ trang báo frameReady (sau 2 lần rAF);
 *  2. (luồng UI) webView.draw(bitmapCanvas);
 *  3. (luồng dựng) chép bitmap vào canvas đích.
 *
 * Đây là đường để đo: nếu chậm hơn thời gian thật nhiều thì cần phương án khác
 * (ví dụ vẽ bằng Canvas/OpenGL gốc cho phần nặng).
 */
class WebViewFrameRenderer(
    private val webView: WebView,
    private val w: Int,
    private val h: Int,
) : FrameRenderer {

    private val main = Handler(Looper.getMainLooper())
    private val bmp: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    private val bmpCanvas = Canvas(bmp)

    @Volatile
    private var frameLatch: CountDownLatch? = null

    /** Gọi từ cầu nối JavaScript khi trang đã vẽ xong và ghép khung. */
    fun onFrameReady() {
        frameLatch?.countDown()
    }

    override fun draw(canvas: Canvas, tMs: Long, width: Int, height: Int) {
        require(width == w && height == h) { "Kích thước khung ($width x $height) khác WebView ($w x $h)" }

        val ready = CountDownLatch(1)
        frameLatch = ready
        main.post { webView.evaluateJavascript("renderFrame($tMs)", null) }
        check(ready.await(3, TimeUnit.SECONDS)) { "WebView không báo xong khung t=$tMs ms (quá 3 giây)" }

        val drawn = CountDownLatch(1)
        var error: Throwable? = null
        main.post {
            try {
                bmp.eraseColor(0)
                webView.draw(bmpCanvas)
            } catch (t: Throwable) {
                error = t
            } finally {
                drawn.countDown()
            }
        }
        check(drawn.await(3, TimeUnit.SECONDS)) { "Chụp WebView quá 3 giây" }
        error?.let { throw IllegalStateException("Chụp WebView thất bại: ${it.message}", it) }

        canvas.drawBitmap(bmp, 0f, 0f, null)
    }

    fun release() {
        bmp.recycle()
    }
}
