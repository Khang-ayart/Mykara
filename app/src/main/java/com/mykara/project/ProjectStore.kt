package com.mykara.project

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * Lưu dự án trên bộ nhớ trong của app:
 *   projects/<id>/project.json   nguồn dữ liệu chính
 *   projects/<id>/assets/        ảnh, nhạc đã sao chép (không phụ thuộc file gốc của người dùng)
 *   projects/<id>/cache/         dữ liệu suy ra, xóa được
 */
class ProjectStore(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "projects").apply { mkdirs() }

    class Info(val id: String, val title: String, val lastModified: Long)

    class ImportedFile(val displayName: String, val file: String, val hash: String)

    fun list(): List<Info> =
        (root.listFiles() ?: emptyArray()).filter { File(it, FILE).isFile }.map { d ->
            val f = File(d, FILE)
            val title = try {
                ProjectDoc.title(JSONObject(f.readText(Charsets.UTF_8)))
            } catch (_: Exception) {
                "(dự án lỗi)"
            }
            Info(d.name, title, f.lastModified())
        }.sortedByDescending { it.lastModified }

    fun create(title: String): String {
        val id = "p" + System.currentTimeMillis()
        File(dir(id), "assets").mkdirs()
        File(dir(id), "cache").mkdirs()
        save(id, ProjectDoc.newProject(title))
        return id
    }

    fun dir(id: String): File = File(root, id)

    fun assetFile(id: String, relPath: String): File = File(dir(id), relPath)

    fun load(id: String): JSONObject = JSONObject(File(dir(id), FILE).readText(Charsets.UTF_8))

    /** Ghi nguyên tử: ghi ra file tạm rồi đổi tên, để mất điện giữa chừng không làm hỏng dự án. */
    fun save(id: String, doc: JSONObject) {
        val d = dir(id).apply { mkdirs() }
        val tmp = File(d, "$FILE.tmp")
        tmp.writeText(doc.toString(2), Charsets.UTF_8)
        Files.move(tmp.toPath(), File(d, FILE).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun delete(id: String) {
        dir(id).deleteRecursively()
    }

    /** Sao chép nhạc vào dự án, tính hash, đọc độ dài, cập nhật và lưu doc. Chạy ở luồng nền. */
    fun importAudio(id: String, doc: JSONObject, uri: Uri): ImportedFile {
        val oldFile = ProjectDoc.audioFile(doc)
        val name = displayName(uri)
        val tmp = File(dir(id), "assets/.import.tmp").apply { parentFile?.mkdirs() }
        val hash = copyWithHash(uri, tmp)
        val rel = "assets/audio." + extensionOf(name, uri)
        moveReplacing(tmp, File(dir(id), rel))
        ProjectDoc.setAudio(doc, rel, hash, readDurationMs(File(dir(id), rel)))
        if (oldFile != null && oldFile != rel) File(dir(id), oldFile).delete()
        save(id, doc)
        return ImportedFile(name, rel, hash)
    }

    /** Trả về null nếu ảnh này (cùng nội dung) đã có trong dự án. */
    fun importImage(id: String, doc: JSONObject, uri: Uri): ImportedFile? {
        val name = displayName(uri)
        val tmp = File(dir(id), "assets/.import.tmp").apply { parentFile?.mkdirs() }
        val hash = copyWithHash(uri, tmp)
        if (ProjectDoc.images(doc).any { it.hash == hash }) {
            tmp.delete()
            return null
        }
        val n = (ProjectDoc.images(doc).mapNotNull { it.id.removePrefix("img").toIntOrNull() }.maxOrNull() ?: 0) + 1
        val rel = "assets/img$n." + extensionOf(name, uri)
        moveReplacing(tmp, File(dir(id), rel))
        ProjectDoc.addImage(doc, rel, hash)
        save(id, doc)
        return ImportedFile(name, rel, hash)
    }

    fun removeImage(id: String, doc: JSONObject, imageId: String) {
        val rel = ProjectDoc.removeImage(doc, imageId) ?: return
        File(dir(id), rel).delete()
        save(id, doc)
    }

    // ---------- chi tiết ----------

    private fun copyWithHash(uri: Uri, dest: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val input = appContext.contentResolver.openInputStream(uri) ?: error("Không mở được file đã chọn")
        input.use { ins ->
            DigestInputStream(ins, md).use { d -> dest.outputStream().use { o -> d.copyTo(o) } }
        }
        return "sha256:" + md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun moveReplacing(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun displayName(uri: Uri): String {
        try {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0) ?: ""
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "file"
    }

    private fun extensionOf(name: String, uri: Uri): String {
        val fromName = name.substringAfterLast('.', "").lowercase()
        val ext = if (fromName.isNotEmpty()) fromName else {
            val mime = appContext.contentResolver.getType(uri)
            (mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "bin").lowercase()
        }
        return if (ext.matches(Regex("[a-z0-9]{1,5}"))) ext else "bin"
    }

    private fun readDurationMs(f: File): Long? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (_: Exception) {
            null
        } finally {
            r.release()
        }
    }

    companion object {
        const val FILE = "project.json"
    }
}
