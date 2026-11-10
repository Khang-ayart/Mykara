package com.mykara.prototype

import android.app.Activity
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Bộ vẽ dùng WebView: gọi renderFrame(tMs) trong trang HTML (canvas 2D), đợi trạng thái hiển thị
 * của WebView sẵn sàng (postVisualStateCallback), rồi chép WebView vào canvas của GlFramePipe.
 *
 * WebView phải gắn vào cửa sổ mới dựng được, nên được đặt dưới giao diện, gần như trong suốt.
 * draw() được gọi từ luồng xuất; việc chạm WebView luôn diễn ra trên luồng giao diện.
 */
class WebViewRenderer(
    private val activity: Activity,
    private val container: FrameLayout,
    private val w: Int,
    private val h: Int,
) : FrameRenderer {

    private val ui = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var requestId = 0L

    /** Gọi từ luồng nền: tạo WebView, tải trang, khởi tạo canvas kích thước w x h. */
    fun prepare() {
        val ready = CountDownLatch(1)
        ui.post {
            val wv = WebView(activity)
            wv.settings.javaScriptEnabled = true
            wv.alpha = 0.01f
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript("init($w,$h)") { ready.countDown() }
                }
            }
            container.addView(wv, 0, FrameLayout.LayoutParams(w, h))
            web = wv
            wv.loadUrl("file:///android_asset/render.html")
        }
        check(ready.await(20, TimeUnit.SECONDS)) { "WebView không tải được trang dựng hình" }
    }

    override fun draw(canvas: Canvas, tMs: Long, width: Int, height: Int) {
        val done = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        ui.post {
            val wv = web
            if (wv == null) {
                failure.set("WebView chưa được tạo")
                done.countDown()
                return@post
            }
            wv.evaluateJavascript("renderFrame($tMs)") {
                wv.postVisualStateCallback(++requestId, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        try {
                            wv.draw(canvas)
                        } catch (t: Throwable) {
                            failure.set(t.toString())
                        }
                        done.countDown()
                    }
                })
            }
        }
        check(done.await(3, TimeUnit.SECONDS)) { "WebView không phản hồi khi dựng khung ${tMs}ms" }
        failure.get()?.let { throw IllegalStateException("WebView lỗi khi vẽ: $it") }
    }

    fun dispose() {
        ui.post {
            web?.let {
                container.removeView(it)
                it.destroy()
            }
            web = null
        }
    }
}
