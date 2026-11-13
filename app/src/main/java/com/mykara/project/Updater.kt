package com.mykara.project

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cập nhật nhanh cho bản thử nghiệm: hỏi GitHub bản dựng mới nhất (prerelease `prototype-latest`),
 * so số build với bản đang cài, tải APK và mở trình cài đặt của Android (người dùng vẫn phải bấm Cài đặt).
 */
object Updater {
    private const val API = "https://api.github.com/repos/Khang-ayart/Mykara/releases/tags/prototype-latest"

    class Latest(val build: Int, val apkUrl: String, val sizeBytes: Long)

    fun installedBuild(context: Context): Int =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()

    /** Chạy trên luồng nền. Trả về null nếu không đọc được thông tin bản mới. */
    fun fetchLatest(): Latest? {
        val j = JSONObject(httpGet(API))
        return parseRelease(j)
    }

    internal fun parseRelease(j: JSONObject): Latest? {
        val notes = j.optString("body", "")
        val build = Regex("""\(build (\d+)\)""").find(notes)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val assets = j.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) {
                return Latest(build, a.getString("browser_download_url"), a.optLong("size", -1))
            }
        }
        return null
    }

    /** Chạy trên luồng nền. [onProgress] nhận phần trăm 0..100 (hoặc -1 nếu không biết kích thước). */
    fun download(context: Context, latest: Latest, onProgress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val out = File(dir, "Mykara-update.apk")
        val c = open(latest.apkUrl)
        val total = if (c.contentLengthLong > 0) c.contentLengthLong else latest.sizeBytes
        c.inputStream.use { input ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var last = -2
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    val pct = if (total > 0) (done * 100 / total).toInt() else -1
                    if (pct != last) { last = pct; onProgress(pct) }
                }
            }
        }
        return out
    }

    /** true nếu đã mở trình cài đặt; false nếu cần người dùng cấp quyền "cài ứng dụng từ nguồn này" trước. */
    fun install(context: Context, apk: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }

    private fun open(url: String): HttpURLConnection {
        var u = url
        repeat(5) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "Mykara-updater")
            c.instanceFollowRedirects = false
            val code = c.responseCode
            if (code in 300..399) { u = c.getHeaderField("Location") ?: error("Chuyển hướng không có địa chỉ"); c.disconnect() }
            else if (code == 200) return c
            else error("Máy chủ trả về $code")
        }
        error("Quá nhiều lần chuyển hướng")
    }

    private fun httpGet(url: String): String = open(url).inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
}
