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
        container = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        setContentView(container)
        showList()
    }

    /**
     * Màn Đồng bộ / Xem trước ghi thẳng vào project.json. Quay lại phải đọc lại từ đĩa, nếu không bản
     * trong bộ nhớ (cũ) sẽ ghi đè mốc thời gian vừa đồng bộ khi bấm Lưu hoặc rời màn hình.
     */
    private var reloadOnResume = false

    override fun onResume() {
        super.onResume()
        val id = currentId
        if (reloadOnResume && id != null) {
            reloadOnResume = false
            doc = store.load(id)
            showEditor()
        }
    }

    override fun onPause() {
        super.onPause()
        if (currentId != null && !reloadOnResume) saveEditor(silent = true)
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
        val c = this
        val col = Ui.column(c, padTop = 40)
        val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(Ui.title(c, "Mykara"))
        titles.addView(Ui.text(c, "Dự án của bạn", 14f, Ui.MUTED))
        head.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(Ui.text(c, "☕", 24f).apply {
            setPadding(dpi(12), dpi(8), dpi(12), dpi(8))
            setOnClickListener { startActivity(Intent(c, SupportActivity::class.java)) }
        })
        col.addView(head)
        col.addView(Ui.button(c, "＋  Dự án mới", Ui.PRIMARY) {
            val id = store.create("Dự án mới")
            openProject(id)
        }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dpi(16) })

        val projects = store.list()
        if (projects.isEmpty()) {
            col.addView(Ui.text(c, "Chưa có dự án nào. Bấm “Dự án mới” để bắt đầu: nhập ảnh, nhạc, lời — Mykara lo phần còn lại.", 15f, Ui.MUTED).apply { setPadding(0, dpi(24), 0, 0) })
        } else {
            col.addView(Ui.section(c, "Gần đây"))
        }
        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        for (p in projects) {
            val card = Ui.card(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val info = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            info.addView(Ui.text(c, p.title, 17f, Ui.TEXT, true))
            info.addView(Ui.text(c, fmt.format(Date(p.lastModified)), 12f, Ui.MUTED))
            card.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(Ui.text(c, "✕", 18f, Ui.MUTED).apply {
                setPadding(dpi(14), dpi(8), dpi(6), dpi(8))
                setOnClickListener {
                    AlertDialog.Builder(c)
                        .setMessage("Xóa dự án “${p.title}” và các file đã nhập? Không thể hoàn tác.")
                        .setPositiveButton("Xóa") { _, _ -> store.delete(p.id); showList() }
                        .setNegativeButton("Hủy", null)
                        .show()
                }
            })
            card.isClickable = true
            card.setOnClickListener { openProject(p.id) }
            col.addView(card)
        }

        col.addView(Ui.section(c, "Ứng dụng"))
        col.addView(Ui.button(c, "⬆  Kiểm tra cập nhật (bản ${Updater.installedBuild(c)})", Ui.SECONDARY) { checkUpdate() })
        col.addView(Ui.button(c, "Dựng thử kỹ thuật", Ui.TEXT_ONLY) {
            startActivity(Intent(c, MainActivity::class.java))
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
        val c = this
        val col = Ui.column(c, padTop = 24)

        val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.text(c, "←", 24f).apply {
            setPadding(0, dpi(8), dpi(16), dpi(8))
            setOnClickListener { saveEditor(silent = true); showList() }
        })
        head.addView(Ui.text(c, "Dự án", 20f, Ui.TEXT, true))
        col.addView(head)

        // Bước 1: thông tin + tài nguyên
        col.addView(Ui.section(c, "1 · Nhập liệu"))
        val info = Ui.card(c)
        info.addView(Ui.text(c, "Tên bài hát", 12f, Ui.MUTED))
        titleEdit = Ui.edit(c, ProjectDoc.title(d)).also { info.addView(it) }
        info.addView(Ui.text(c, "Ca sĩ", 12f, Ui.MUTED).apply { setPadding(0, dpi(10), 0, 0) })
        artistEdit = Ui.edit(c, ProjectDoc.artist(d)).also { info.addView(it) }
        info.addView(Ui.text(c, "Nhạc sĩ", 12f, Ui.MUTED).apply { setPadding(0, dpi(10), 0, 0) })
        composerEdit = Ui.edit(c, ProjectDoc.composer(d)).also { info.addView(it) }
        col.addView(info)

        val music = Ui.card(c)
        music.addView(Ui.text(c, "Nhạc", 12f, Ui.MUTED))
        audioView = Ui.text(c, audioSummary(d), 15f).also { music.addView(it) }
        music.addView(Ui.button(c, "Chọn / đổi file nhạc", Ui.SECONDARY) { audioPicker.launch(arrayOf("audio/*")) })
        col.addView(music)

        val photos = Ui.card(c)
        photos.addView(Ui.text(c, "Ảnh nền", 12f, Ui.MUTED))
        imagesBox = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }.also { photos.addView(it) }
        renderImages()
        photos.addView(Ui.button(c, "＋ Thêm ảnh", Ui.SECONDARY) { imagePicker.launch(arrayOf("image/*")) })
        col.addView(photos)

        val words = Ui.card(c)
        words.addView(Ui.text(c, "Lời bài hát (mỗi câu một dòng)", 12f, Ui.MUTED))
        lyricsEdit = Ui.edit(c, ProjectDoc.lyricsText(d), singleLine = false).also {
            it.minLines = 8
            words.addView(it)
        }
        col.addView(words)

        // Bước 2 và 3: các hành động nối tiếp nhau
        col.addView(Ui.section(c, "2 · Đồng bộ lời"))
        val sync = Ui.card(c)
        val total = ProjectDoc.lyricsText(d).let { ProjectDoc.splitLyrics(it).size }
        val timed = ProjectTiming.lines(d).size
        sync.addView(Ui.chip(c, if (total == 0) "Chưa có lời" else "Đã căn $timed/$total câu", total > 0 && timed >= total))
        sync.addView(Ui.button(c, "⏱  Gõ nhịp đồng bộ lời", Ui.SECONDARY) {
            saveEditor(silent = true)
            reloadOnResume = true
            currentId?.let { startActivity(Intent(c, SyncActivity::class.java).putExtra("id", it)) }
        })
        col.addView(sync)

        col.addView(Ui.section(c, "3 · Xem trước & chỉnh sửa"))
        col.addView(Ui.button(c, "▶  Xem trước video", Ui.PRIMARY) {
            saveEditor(silent = true)
            reloadOnResume = true
            currentId?.let { startActivity(Intent(c, PreviewActivity::class.java).putExtra("id", it)) }
        })
        statusView = Ui.text(c, "", 14f, Ui.OK).also { col.addView(it) }
        show(col, bottom = Ui.toolbar(
            c,
            Ui.tool(c, "💾", "Lưu") { saveEditor(silent = false) },
            Ui.tool(c, "⏱", "Đồng bộ") {
                saveEditor(silent = true)
                reloadOnResume = true
                currentId?.let { startActivity(Intent(c, SyncActivity::class.java).putExtra("id", it)) }
            },
            Ui.tool(c, "▶", "Xem trước") {
                saveEditor(silent = true)
                reloadOnResume = true
                currentId?.let { startActivity(Intent(c, PreviewActivity::class.java).putExtra("id", it)) }
            },
        ))
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
        if (imgs.isEmpty()) box.addView(Ui.text(this, "Chưa có ảnh", 15f, Ui.MUTED))
        for (img in imgs) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val thumb = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            thumbnail(store.assetFile(id, img.file))?.let { thumb.setImageBitmap(it) }
            row.addView(thumb, LinearLayout.LayoutParams(dpi(64), dpi(64)).apply { setMargins(0, dpi(4), dpi(12), dpi(4)) })
            row.addView(Ui.text(this, img.file.substringAfterLast('/'), 14f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Ui.text(this, "✕", 18f, Ui.MUTED).apply { setPadding(dpi(12), dpi(8), dpi(4), dpi(8)) }.also { it.setOnClickListener {
                io.execute {
                    try {
                        store.removeImage(id, d, img.id)
                        runOnUiThread { renderImages() }
                    } catch (t: Throwable) {
                        runOnUiThread { status("Lỗi xóa ảnh: ${t.message}", error = true) }
                    }
                }
            } })
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
            setTextColor(if (error) Ui.ERR else Ui.OK)
        }
    }

    // ---------- Dựng giao diện ----------

    /** Nội dung cuộn được; bottom (nếu có) là thanh công cụ dính ở đáy màn hình. */
    private fun show(content: View, bottom: View? = null) {
        container.removeAllViews()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (bottom != null) root.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        container.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun dpi(v: Int) = (v * dp).toInt()
}
