package com.mykara.project

import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.mykara.prototype.MainActivity
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/**
 * Màn hình chính: danh sách dự án → trình nhập liệu (thông tin bài hát, nhạc, ảnh, lời) → lưu.
 * Mọi thứ lưu vào project.json từng thành phần riêng; mở lại thấy đủ.
 */
class ProjectActivity : ComponentActivity() {

    private lateinit var store: ProjectStore
    private lateinit var container: FrameLayout
    private val io = Executors.newSingleThreadExecutor()
    private val dp by lazy { resources.displayMetrics.density }

    // Trạng thái trình chỉnh sửa
    private var currentId: String? = null
    private var doc: JSONObject? = null
    private var titleEdit: EditText? = null
    private var artistEdit: EditText? = null
    private var composerEdit: EditText? = null
    private var lyricsEdit: EditText? = null
    private var statusView: TextView? = null
    private var audioView: TextView? = null
    private var imagesBox: LinearLayout? = null

    private val audioPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importAudio(uri)
    }
    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importImages(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ProjectStore(this)
        container = FrameLayout(this).apply { setBackgroundColor(0xFFFFFFFF.toInt()) }
        setContentView(container)
        showList()
    }

    override fun onPause() {
        super.onPause()
        if (currentId != null) saveEditor(silent = true)
    }

    @Deprecated("Dùng cho nút Back để quay về danh sách")
    override fun onBackPressed() {
        if (currentId != null) {
            saveEditor(silent = true)
            showList()
        } else {
            super.onBackPressed()
        }
    }

    // ---------- Danh sách ----------

    private fun showList() {
        currentId = null
        doc = null
        val col = column()
        col.addView(text("Mykara", 26f))
        col.addView(text("Dự án của bạn", 14f, 0xFF666666.toInt()))
        col.addView(button("＋ Dự án mới") {
            val id = store.create("Dự án mới")
            openProject(id)
        })

        val projects = store.list()
        if (projects.isEmpty()) {
            col.addView(text("Chưa có dự án nào. Bấm “Dự án mới” để bắt đầu.", 15f).apply { setPadding(0, dpi(16), 0, 0) })
        }
        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        for (p in projects) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(
                button("${p.title}\n${fmt.format(Date(p.lastModified))}") { openProject(p.id) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            row.addView(button("Xóa") {
                AlertDialog.Builder(this)
                    .setMessage("Xóa dự án “${p.title}” và các file đã nhập? Không thể hoàn tác.")
                    .setPositiveButton("Xóa") { _, _ -> store.delete(p.id); showList() }
                    .setNegativeButton("Hủy", null)
                    .show()
            })
            col.addView(row)
        }

        col.addView(text("", 8f))
        col.addView(button("⬆ Kiểm tra cập nhật (bản ${Updater.installedBuild(this)})") { checkUpdate() })
        col.addView(button("Dựng thử kỹ thuật (prototype)") {
            startActivity(Intent(this, MainActivity::class.java))
        })
        show(col)
    }

    private fun openProject(id: String) {
        currentId = id
        doc = store.load(id)
        showEditor()
    }

    // ---------- Trình nhập liệu ----------

    private fun showEditor() {
        val d = doc ?: return
        val col = column()
        col.addView(button("← Danh sách dự án") {
            saveEditor(silent = true)
            showList()
        })

        col.addView(label("Tên bài hát"))
        titleEdit = edit(ProjectDoc.title(d), singleLine = true).also { col.addView(it) }
        col.addView(label("Ca sĩ"))
        artistEdit = edit(ProjectDoc.artist(d), singleLine = true).also { col.addView(it) }
        col.addView(label("Nhạc sĩ"))
        composerEdit = edit(ProjectDoc.composer(d), singleLine = true).also { col.addView(it) }

        col.addView(label("Nhạc"))
        audioView = text(audioSummary(d), 14f).also { col.addView(it) }
        col.addView(button("Chọn / đổi file nhạc") { audioPicker.launch(arrayOf("audio/*")) })

        col.addView(label("Ảnh nền"))
        imagesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { col.addView(it) }
        renderImages()
        col.addView(button("＋ Thêm ảnh") { imagePicker.launch(arrayOf("image/*")) })

        col.addView(label("Lời bài hát (mỗi câu một dòng)"))
        lyricsEdit = edit(ProjectDoc.lyricsText(d), singleLine = false).also {
            it.minLines = 8
            it.gravity = Gravity.TOP
            col.addView(it)
        }

        col.addView(button("Lưu") { saveEditor(silent = false) })
        col.addView(button("⏱ Đồng bộ lời (gõ nhịp)") {
            saveEditor(silent = true)
            currentId?.let { startActivity(Intent(this, SyncActivity::class.java).putExtra("id", it)) }
        })
        col.addView(button("▶ Xem trước video") {
            saveEditor(silent = true)
            currentId?.let { startActivity(Intent(this, PreviewActivity::class.java).putExtra("id", it)) }
        })
        statusView = text("", 14f, 0xFF2E7D32.toInt()).also { col.addView(it) }
        show(col)
    }

    private fun audioSummary(d: JSONObject): String {
        val f = ProjectDoc.audioFile(d) ?: return "Chưa có nhạc"
        val ms = ProjectDoc.audioDurationMs(d)
        val dur = if (ms != null) " · ${ms / 60000}:${"%02d".format(ms / 1000 % 60)}" else ""
        return f.substringAfterLast('/') + dur
    }

    private fun renderImages() {
        val box = imagesBox ?: return
        val id = currentId ?: return
        val d = doc ?: return
        box.removeAllViews()
        val imgs = ProjectDoc.images(d)
        if (imgs.isEmpty()) box.addView(text("Chưa có ảnh", 14f))
        for (img in imgs) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val thumb = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            thumbnail(store.assetFile(id, img.file))?.let { thumb.setImageBitmap(it) }
            row.addView(thumb, LinearLayout.LayoutParams(dpi(64), dpi(64)).apply { setMargins(0, dpi(4), dpi(12), dpi(4)) })
            row.addView(text(img.file.substringAfterLast('/'), 14f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(button("Xóa") {
                io.execute {
                    try {
                        store.removeImage(id, d, img.id)
                        runOnUiThread { renderImages() }
                    } catch (t: Throwable) {
                        runOnUiThread { status("Lỗi xóa ảnh: ${t.message}", error = true) }
                    }
                }
            })
            box.addView(row)
        }
    }

    private fun thumbnail(f: java.io.File): android.graphics.Bitmap? {
        if (!f.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 256 && bounds.outHeight / sample > 256) sample *= 2
        return BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // ---------- Nhập file (luồng nền) ----------

    private fun checkUpdate() {
        val tv = TextView(this).apply { text = "Đang hỏi GitHub…"; textSize = 16f; setPadding((24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt(), 0) }
        val dlg = AlertDialog.Builder(this).setTitle("Cập nhật").setView(tv).setNegativeButton("Đóng", null).show()
        Thread {
            try {
                val latest = Updater.fetchLatest()
                val mine = Updater.installedBuild(this)
                if (latest == null) {
                    runOnUiThread { tv.text = "Không đọc được thông tin bản mới." }
                } else if (latest.build <= mine) {
                    runOnUiThread { tv.text = "Bạn đang dùng bản mới nhất (build $mine)." }
                } else {
                    runOnUiThread { tv.text = "Có bản mới: build ${latest.build} (đang dùng $mine). Đang tải…" }
                    val apk = Updater.download(this, latest) { pct ->
                        runOnUiThread { tv.text = "Đang tải build ${latest.build}… " + if (pct >= 0) "$pct%" else "" }
                    }
                    runOnUiThread {
                        val opened = Updater.install(this, apk)
                        tv.text = if (opened) "Đã tải xong. Bấm \"Cài đặt\" ở hộp thoại của Android."
                        else "Cần cho phép Mykara cài ứng dụng: bật công tắc ở màn hình vừa mở, quay lại rồi bấm cập nhật lần nữa."
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { tv.text = "Lỗi: ${e.message}" }
            }
        }.start()
    }

    private fun importAudio(uri: Uri) {
        val id = currentId ?: return
        val d = doc ?: return
        status("Đang nhập nhạc…")
        io.execute {
            try {
                store.importAudio(id, d, uri)
                runOnUiThread {
                    audioView?.text = audioSummary(d)
                    status("Đã nhập nhạc.")
                }
            } catch (t: Throwable) {
                runOnUiThread { status("Lỗi nhập nhạc: ${t.message}", error = true) }
            }
        }
    }

    private fun importImages(uris: List<Uri>) {
        val id = currentId ?: return
        val d = doc ?: return
        status("Đang nhập ${uris.size} ảnh…")
        io.execute {
            var added = 0
            var dup = 0
            try {
                for (u in uris) if (store.importImage(id, d, u) != null) added++ else dup++
                runOnUiThread {
                    renderImages()
                    status("Đã thêm $added ảnh" + if (dup > 0) " ($dup ảnh trùng đã bỏ qua)." else ".")
                }
            } catch (t: Throwable) {
                runOnUiThread { status("Lỗi nhập ảnh: ${t.message}", error = true) }
            }
        }
    }

    // ---------- Lưu ----------

    private fun saveEditor(silent: Boolean) {
        val id = currentId ?: return
        val d = doc ?: return
        val title = titleEdit?.text?.toString() ?: return
        val artist = artistEdit?.text?.toString() ?: ""
        val composer = composerEdit?.text?.toString() ?: ""
        val lyrics = lyricsEdit?.text?.toString() ?: ""
        try {
            ProjectDoc.setMeta(d, title, artist, composer)
            val c = ProjectDoc.setLyrics(d, lyrics)
            store.save(id, d)
            if (!silent) {
                val n = ProjectDoc.splitLyrics(lyrics).size
                val parts = ArrayList<String>()
                if (c.added > 0) parts.add("thêm ${c.added}")
                if (c.removed > 0) parts.add("xóa ${c.removed}")
                if (c.edited.isNotEmpty()) parts.add("sửa ${c.edited.size} (cần kiểm tra lại thời gian nếu đã căn)")
                status("Đã lưu. Lời: $n câu" + if (parts.isNotEmpty()) " (" + parts.joinToString(", ") + ")." else ".")
            }
        } catch (t: Throwable) {
            status("Lỗi lưu: ${t.message}", error = true)
        }
    }

    private fun status(msg: String, error: Boolean = false) {
        statusView?.apply {
            text = msg
            setTextColor(if (error) 0xFFC62828.toInt() else 0xFF2E7D32.toInt())
        }
    }

    // ---------- Dựng giao diện ----------

    private fun show(content: View) {
        container.removeAllViews()
        container.addView(
            ScrollView(this).apply { addView(content) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    private fun dpi(v: Int) = (v * dp).toInt()

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpi(16), dpi(40), dpi(16), dpi(24))
    }

    private fun text(s: String, size: Float, color: Int = 0xFF222222.toInt()) =
        TextView(this).apply { text = s; textSize = size; setTextColor(color) }

    private fun label(s: String) = text(s, 13f, 0xFF666666.toInt()).apply { setPadding(0, dpi(14), 0, 0) }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun edit(value: String, singleLine: Boolean) = EditText(this).apply {
        setText(value)
        textSize = 16f
        if (singleLine) {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        } else {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
    }
}
