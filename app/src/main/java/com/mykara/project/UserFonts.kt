package com.mykara.project

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Font do người dùng nhập. File gốc không bị đụng tới: ta chép bản sao vào bộ nhớ riêng của app (filesDir/userfonts),
 * không gửi đi đâu. Danh sách và thông tin nằm trong userfonts/index.json; lựa chọn "font mặc định" ở SharedPreferences.
 *
 * Hỗ trợ TTF và OTF (đọc được tên, tác giả, giấy phép, độ phủ tiếng Việt). WOFF/WOFF2 chưa hỗ trợ vì Android không
 * hiển thị được trong danh sách xem trước.
 */
object UserFonts {
    const val MAX_BYTES = 12L * 1024 * 1024
    const val MAX_FONTS = 40

    class ImportResult(val font: Fonts.Def?, val error: String?, val warning: String?)

    private lateinit var dir: File
    private lateinit var prefs: android.content.SharedPreferences
    private val lock = Any()
    private var cache: List<Fonts.Def>? = null

    /** Gọi một lần khi app khởi động (Application) và an toàn khi gọi lại. */
    fun init(context: Context) {
        val c = context.applicationContext
        if (!this::dir.isInitialized) {
            dir = File(c.filesDir, "userfonts").apply { mkdirs() }
            prefs = c.getSharedPreferences("fonts", Context.MODE_PRIVATE)
        }
        Fonts.userDefs = { list() }
        Fonts.defaultId = { prefs.getString("default", null) }
    }

    private fun indexFile() = File(dir, "index.json")

    private fun readIndex(): JSONArray = try {
        if (indexFile().isFile) JSONArray(indexFile().readText(Charsets.UTF_8)) else JSONArray()
    } catch (_: Exception) {
        JSONArray()
    }

    private fun writeIndex(a: JSONArray) {
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(a.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(indexFile())) { indexFile().delete(); tmp.renameTo(indexFile()) }
        cache = null
    }

    /** Danh sách font của người dùng; mục nào mất file thì bỏ qua (không làm hỏng gì khác). */
    fun list(): List<Fonts.Def> = synchronized(lock) {
        cache?.let { return it }
        val a = readIndex()
        val out = ArrayList<Fonts.Def>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isEmpty() || !fileOf(id, o).isFile) continue
            out.add(
                Fonts.Def(
                    id = id,
                    family = o.optString("name", id),
                    weight = o.optString("weight", "400"),
                    label = o.optString("name", id),
                    script = o.optString("style") == "handwriting" || o.optString("style") == "calligraphy",
                    style = o.optString("style", "display"),
                    builtIn = false,
                    css = "\"${cssName(id)}\", \"Be Vietnam Pro\", sans-serif",
                    author = o.optString("author"),
                    license = o.optString("license").ifEmpty { "Không rõ (bạn tự chịu trách nhiệm về quyền sử dụng)" },
                    vi = o.optString("vi", "unknown"),
                    variable = o.optBoolean("variable", false),
                ),
            )
        }
        out.also { cache = it }
    }

    fun cssName(id: String) = "UF-$id"

    private fun fileOf(id: String, o: JSONObject? = null) = File(dir, o?.optString("file")?.ifEmpty { null } ?: "$id.ttf")

    fun fileFor(id: String): File? = synchronized(lock) {
        val o = (0 until readIndex().length()).map { readIndex().getJSONObject(it) }.firstOrNull { it.optString("id") == id } ?: return null
        fileOf(id, o).takeIf { it.isFile }
    }

    /** File font theo tên file trong thư mục userfonts; chặn mọi đường thoát ra ngoài thư mục. */
    fun fontByFileName(name: String): File? {
        if (name.isEmpty() || name.contains('/') || name.contains("..") || name == "index.json") return null
        val f = File(dir, name)
        return f.takeIf { it.isFile && it.canonicalFile.parentFile == dir.canonicalFile }
    }

    /** Nội dung CSS @font-face cho WebView (engine.html nạp ở /app/userfonts.css). */
    fun fontFaceCss(): String = synchronized(lock) {
        val sb = StringBuilder()
        val a = readIndex()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val f = fileOf(id, o)
            if (id.isEmpty() || !f.isFile) continue
            sb.append("@font-face { font-family: '").append(cssName(id)).append("'; src: url('/uf/").append(f.name)
                .append("'); font-weight: 100 900; }\n")
        }
        sb.toString()
    }

    class Loaded(val displayName: String, val bytes: ByteArray?, val error: String?)

    /** Đọc nội dung file người dùng chọn (giới hạn kích thước), chưa lưu gì cả. */
    fun read(context: Context, uri: Uri): Loaded {
        return try {
            val resolver = context.contentResolver
            var displayName = "font"
            var size = -1L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { displayName = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
            val tooBig = "File quá lớn (tối đa ${MAX_BYTES / 1024 / 1024} MB)."
            if (size > MAX_BYTES) return Loaded(displayName, null, tooBig)
            val bytes = resolver.openInputStream(uri)?.use { s ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) return Loaded(displayName, null, tooBig)
                }
                out.toByteArray()
            } ?: return Loaded(displayName, null, "Không đọc được file.")
            Loaded(displayName, bytes, null)
        } catch (e: Exception) {
            Loaded("font", null, "Không đọc được file: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Đọc rồi nhập luôn (dùng khi không cần bước xem trước). */
    fun importFrom(context: Context, uri: Uri): ImportResult {
        val l = read(context, uri)
        val bytes = l.bytes ?: return ImportResult(null, l.error ?: "Không đọc được file.", null)
        return importBytes(l.displayName, bytes)
    }

    fun importBytes(displayName: String, bytes: ByteArray): ImportResult = synchronized(lock) {
        val ext = displayName.substringAfterLast('.', "").lowercase()
        if (ext == "woff" || ext == "woff2") {
            return ImportResult(null, "Chưa hỗ trợ ${ext.uppercase()}. Hãy dùng file .ttf hoặc .otf.", null)
        }
        val info = FontInfo.parse(bytes)
            ?: return ImportResult(null, "File này không phải font TTF/OTF hợp lệ (hoặc font bị hỏng).", null)
        val index = readIndex()
        if (index.length() >= MAX_FONTS) return ImportResult(null, "Đã đủ $MAX_FONTS font, hãy xóa bớt trước khi thêm.", null)
        // Cùng nội dung đã nhập rồi thì không nhập lại.
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        for (i in 0 until index.length()) {
            val o = index.getJSONObject(i)
            if (o.optString("sha") == sha) return ImportResult(null, "Font này đã có trong thư viện (“${o.optString("name")}”).", null)
        }
        if (info.vietnamese == "none") {
            return ImportResult(null, "Font này không có chữ tiếng Việt có dấu nên không dùng được cho lời bài hát.", null)
        }
        val id = "u" + System.currentTimeMillis().toString(36)
        val file = File(dir, "$id.${info.format}")
        file.writeBytes(bytes)
        val name = (info.fullName ?: info.family ?: displayName.substringBeforeLast('.')).take(60)
        val o = JSONObject()
            .put("id", id).put("file", file.name).put("sha", sha)
            .put("name", name).put("style", "display").put("weight", "400")
            .put("author", info.author ?: "").put("license", info.license ?: "")
            .put("vi", info.vietnamese).put("format", info.format).put("variable", info.variable).put("added", System.currentTimeMillis())
        index.put(o)
        writeIndex(index)
        val warning = if (info.vietnamese == "partial") "Font thiếu một số chữ tiếng Việt có dấu; chữ thiếu sẽ dùng font dự phòng." else null
        ImportResult(list().firstOrNull { it.id == id }, null, warning)
    }

    private fun update(id: String, change: (JSONObject) -> Unit) = synchronized(lock) {
        val a = readIndex()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.optString("id") == id) { change(o); writeIndex(a); return@synchronized true }
        }
        false
    }

    fun rename(id: String, name: String) = update(id) { it.put("name", name.trim().take(60).ifEmpty { it.optString("name") }) }

    fun setStyle(id: String, style: String) = update(id) { if (Fonts.styles.any { s -> s.first == style }) it.put("style", style) }

    fun setWeight(id: String, weight: String) = update(id) { it.put("weight", weight) }

    fun delete(id: String): Boolean = synchronized(lock) {
        val a = readIndex()
        val keep = JSONArray()
        var removed: JSONObject? = null
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.optString("id") == id) removed = o else keep.put(o)
        }
        val r = removed ?: return false
        writeIndex(keep)
        fileOf(id, r).delete()
        if (prefs.getString("default", null) == id) prefs.edit().remove("default").apply()
        true
    }

    fun setDefault(id: String?) {
        prefs.edit().apply { if (id == null) remove("default") else putString("default", id) }.apply()
    }

    fun defaultId(): String? = prefs.getString("default", null)
}
