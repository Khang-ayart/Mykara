package com.mykara.project

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Cấp file cho WebView qua địa chỉ giả https://mykara.local/ :
 *  /app/...        → thư mục assets của app (engine.html, engine.js, fonts/)
 *  /p/<id>/...     → file trong thư mục dự án (ảnh đã nhập), chặn mọi đường thoát ra ngoài thư mục đó
 * Dùng chung cho Xem trước và Xuất video để hai nơi vẽ ra cùng kết quả.
 */
class ProjectWebServer(private val context: Context, private val store: ProjectStore, private val id: String) {

    fun urlFor(rel: String) = "https://mykara.local/p/$id/$rel"

    fun serve(r: WebResourceRequest): WebResourceResponse? {
        val u = r.url
        if (u.host != "mykara.local") return null
        val path = u.path ?: return notFound()
        return try {
            when {
                path.startsWith("/app/") -> {
                    val name = path.removePrefix("/app/")
                    if (name.contains("..")) return notFound()
                    respond(mime(name), context.assets.open(name).readBytes())
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
        "ttf" -> "font/ttf"
        else -> "application/octet-stream"
    }
}
