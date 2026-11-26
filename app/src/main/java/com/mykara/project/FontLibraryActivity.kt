package com.mykara.project

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File

/**
 * Thư viện font: font có sẵn theo phong cách + font người dùng tự nhập (TTF/OTF), xem trước bằng đoạn văn tiếng Việt
 * (sửa được), đổi tên, đổi phong cách, độ đậm (font biến thiên), xóa, chọn mặc định.
 *
 * Hai cách mở: từ màn hình chính (quản lý), hoặc từ Xem trước với extra "pick" = "lyrics" | "title" — khi đó chạm một
 * font là chọn và trả kết quả (extra "fontId"; chuỗi rỗng = bỏ lựa chọn, dùng font mặc định).
 */
class FontLibraryActivity : ComponentActivity() {
    private lateinit var listBox: LinearLayout
    private lateinit var sampleEdit: android.widget.EditText
    private var filter = "all"
    private var expanded: String? = null
    private var pick: String? = null
    private var currentId: String? = null
    private val typefaces = HashMap<String, Typeface?>()

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmImport(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UserFonts.init(this)
        pick = intent.getStringExtra("pick")
        currentId = intent.getStringExtra("current")
        val c = this

        val col = Ui.column(c, padTop = 24)
        val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.text(c, "←", 24f).apply {
            setPadding(0, Ui.dp(c, 8), Ui.dp(c, 16), Ui.dp(c, 8))
            setOnClickListener { finish() }
        })
        val titleText = when (pick) {
            "lyrics" -> "Chọn font cho lời"
            "title" -> "Chọn font cho tiêu đề"
            else -> "Thư viện font"
        }
        head.addView(Ui.text(c, titleText, 20f, Ui.TEXT, true))
        col.addView(head)

        col.addView(Ui.button(c, "＋ Thêm font từ máy (.ttf, .otf)", Ui.PRIMARY) { picker.launch(arrayOf("*/*")) })
        col.addView(Ui.button(c, "❓ Cách tải và thêm font", Ui.SECONDARY) { showHelp() })
        col.addView(Ui.text(c, "Font được chép vào bộ nhớ riêng của Mykara, chạy hoàn toàn offline; file gốc của bạn không bị thay đổi và không gửi đi đâu.", 12f, Ui.MUTED).apply {
            setPadding(0, 0, 0, Ui.dp(c, 8))
        })

        col.addView(Ui.section(c, "Nội dung xem trước"))
        sampleEdit = Ui.edit(c, prefs().getString("sample", SAMPLE) ?: SAMPLE, "Nhập chữ để xem thử", singleLine = false).apply {
            minLines = 2
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    prefs().edit().putString("sample", s?.toString() ?: "").apply()
                    refreshSamples()
                }
            })
        }
        col.addView(sampleEdit)

        col.addView(Ui.section(c, "Phong cách"))
        val chips = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        fun chip(id: String, label: String) {
            chips.addView(Ui.text(c, label, 13f, if (filter == id) Ui.ACCENT else Ui.TEXT, filter == id).apply {
                setPadding(Ui.dp(c, 14), Ui.dp(c, 8), Ui.dp(c, 14), Ui.dp(c, 8))
                background = Ui.rounded(if (filter == id) Ui.PANEL_HI else Ui.PANEL, 18, c)
                setOnClickListener { filter = id; expanded = null; renderChips(chips); render() }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, Ui.dp(c, 8), 0) }
            })
        }
        chipBuilder = { chips.removeAllViews(); chip("all", "Tất cả"); chip("mine", "Của tôi"); for ((id, label) in Fonts.styles) chip(id, label) }
        chipBuilder()
        col.addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(chips) })

        listBox = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(0, Ui.dp(c, 12), 0, 0) }
        col.addView(listBox)

        if (pick == null) {
            col.addView(Ui.button(c, "Khôi phục font mặc định", Ui.TEXT_ONLY) {
                UserFonts.setDefault(null); render()
                Toast.makeText(c, "Đã bỏ font mặc định tự chọn", Toast.LENGTH_SHORT).show()
            })
            col.addView(Ui.text(c, "Font bạn nhập là của bạn: Mykara không chia sẻ hay tải lên. Bạn tự chịu trách nhiệm về quyền sử dụng font đó (kể cả khi đăng video). Font có sẵn theo giấy phép SIL Open Font License; bản quyền thuộc tác giả từng font.", 12f, Ui.MUTED).apply {
                setPadding(0, Ui.dp(c, 8), 0, 0)
            })
        }

        setContentView(ScrollView(c).apply { setBackgroundColor(Ui.BG); isFillViewport = true; addView(col) })
        render()
    }

    private var chipBuilder: () -> Unit = {}
    private fun renderChips(@Suppress("UNUSED_PARAMETER") box: LinearLayout) = chipBuilder()

    private fun prefs() = getSharedPreferences("fonts", MODE_PRIVATE)

    private fun sample(): String = sampleEdit.text.toString().ifBlank { SAMPLE }

    private fun typefaceOf(d: Fonts.Def): Typeface? = typefaces.getOrPut(d.id + "|" + d.weight) {
        try {
            val w = d.weight.toIntOrNull() ?: 400
            val b = if (d.builtIn) {
                val path = Fonts.assetPath(d) ?: return@getOrPut null
                Typeface.Builder(assets, path)
            } else {
                Typeface.Builder(UserFonts.fileFor(d.id) ?: return@getOrPut null)
            }
            b.setFontVariationSettings("'wght' $w").build()
        } catch (_: Exception) {
            null // font hỏng: dùng font hệ thống để xem trước, không làm sập app
        }
    }

    private fun refreshSamples() {
        for (i in 0 until listBox.childCount) {
            (listBox.getChildAt(i).findViewWithTag<TextView>("sample"))?.text = sample()
        }
    }

    private fun render() {
        val c = this
        listBox.removeAllViews()
        val all = Fonts.library()
        val shown = when (filter) {
            "all" -> all
            "mine" -> all.filter { !it.builtIn }
            else -> all.filter { it.style == filter }
        }
        if (pick == "title") {
            listBox.addView(rowSimple("Giống font của lời", currentId == null) { finishPick("") })
        }
        if (pick == "lyrics") {
            listBox.addView(rowSimple("Font mặc định của kiểu / của tôi", currentId == null) { finishPick("") })
        }
        if (shown.isEmpty()) {
            listBox.addView(Ui.text(c, if (filter == "mine") "Bạn chưa thêm font nào. Bấm “Thêm font từ máy”, hoặc “Cách tải và thêm font” nếu chưa biết tải ở đâu." else "Chưa có font nào ở mục này.", 14f, Ui.MUTED))
        }
        val defaultId = Fonts.defaultId()
        for (d in shown) listBox.addView(card(d, d.id == defaultId))
    }

    private fun rowSimple(label: String, selected: Boolean, onClick: () -> Unit) = Ui.card(this, 14).apply {
        addView(Ui.text(context, (if (selected) "✓ " else "") + label, 15f, if (selected) Ui.ACCENT else Ui.TEXT, true))
        setOnClickListener { onClick() }
    }

    private fun finishPick(id: String) {
        setResult(RESULT_OK, Intent().putExtra("fontId", id).putExtra("pick", pick))
        finish()
    }

    private fun card(d: Fonts.Def, isDefault: Boolean): View {
        val c = this
        val card = Ui.card(c, 14)
        val selected = pick != null && d.id == currentId
        val top = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(Ui.text(c, (if (selected) "✓ " else "") + d.family, 16f, if (selected) Ui.ACCENT else Ui.TEXT, true).apply {
            typeface = typefaceOf(d) ?: Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (isDefault) top.addView(Ui.text(c, "★ mặc định", 12f, Ui.OK, true))
        card.addView(top)
        val meta = buildList {
            add(Fonts.styleLabel(d.style))
            add(if (d.builtIn) "Có sẵn" else "Của bạn")
            if (d.author.isNotEmpty()) add(d.author)
        }.joinToString(" · ")
        card.addView(Ui.text(c, meta, 12f, Ui.MUTED))
        card.addView(Ui.text(c, sample(), 20f, Ui.TEXT).apply {
            tag = "sample"
            typeface = typefaceOf(d) ?: Typeface.DEFAULT
            setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 4))
        })
        if (d.vi == "partial") card.addView(Ui.text(c, "⚠ Thiếu một số chữ tiếng Việt có dấu — chữ thiếu dùng font dự phòng.", 12f, Ui.ERR))
        if (d.vi == "unknown") card.addView(Ui.text(c, "⚠ Chưa kiểm tra được chữ tiếng Việt.", 12f, Ui.ERR))
        if (pick == null) card.addView(Ui.text(c, "Giấy phép: ${d.license}", 11f, Ui.MUTED))

        if (pick != null) {
            card.setOnClickListener { finishPick(d.id) }
            return card
        }
        card.setOnClickListener { expanded = if (expanded == d.id) null else d.id; render() }
        if (expanded == d.id) {
            card.addView(Ui.button(c, if (isDefault) "Bỏ font mặc định" else "★ Đặt làm font mặc định", Ui.SECONDARY) {
                UserFonts.setDefault(if (isDefault) null else d.id); render()
            })
            if (!d.builtIn) {
                card.addView(Ui.button(c, "Đổi tên hiển thị", Ui.SECONDARY) { askRename(d) })
                card.addView(Ui.button(c, "Phong cách: ${Fonts.styleLabel(d.style)}", Ui.SECONDARY) { askStyle(d) })
                if (d.variable) card.addView(Ui.button(c, "Độ đậm: ${weightLabel(d.weight)}", Ui.SECONDARY) { askWeight(d) })
                card.addView(Ui.button(c, "Xóa font này", Ui.TEXT_ONLY) { askDelete(d) })
            }
        }
        return card
    }

    private fun weightLabel(w: String) = when (w) { "300" -> "Mảnh"; "400" -> "Thường"; "500" -> "Vừa"; "600" -> "Hơi đậm"; "700" -> "Đậm"; "800" -> "Rất đậm"; else -> w }

    private fun askWeight(d: Fonts.Def) {
        val ws = listOf("300", "400", "500", "600", "700", "800")
        AlertDialog.Builder(this).setTitle("Độ đậm").setItems(ws.map { weightLabel(it) }.toTypedArray()) { _, i ->
            UserFonts.setWeight(d.id, ws[i]); typefaces.clear(); render()
        }.show()
    }

    private fun askStyle(d: Fonts.Def) {
        val st = Fonts.styles
        AlertDialog.Builder(this).setTitle("Phong cách").setItems(st.map { it.second }.toTypedArray()) { _, i ->
            UserFonts.setStyle(d.id, st[i].first); render()
        }.show()
    }

    private fun askRename(d: Fonts.Def) {
        val e = Ui.edit(this, d.family)
        AlertDialog.Builder(this).setTitle("Đổi tên hiển thị").setView(e)
            .setPositiveButton("Lưu") { _, _ -> UserFonts.rename(d.id, e.text.toString()); render() }
            .setNegativeButton("Hủy", null).show()
    }

    private fun askDelete(d: Fonts.Def) {
        AlertDialog.Builder(this).setTitle("Xóa “${d.family}”?")
            .setMessage("Font bị xóa khỏi Mykara (file gốc trên máy bạn không bị đụng tới). Dự án đang dùng font này sẽ chuyển sang font dự phòng.")
            .setPositiveButton("Xóa") { _, _ -> UserFonts.delete(d.id); typefaces.clear(); expanded = null; render() }
            .setNegativeButton("Hủy", null).show()
    }

    /** Đọc file, kiểm tra, cho xem trước rồi mới lưu. */
    private fun confirmImport(uri: android.net.Uri) {
        Thread {
            val loaded = UserFonts.read(this, uri)
            val bytes = loaded.bytes
            val info = bytes?.let { FontInfo.parse(it) }
            var tf: Typeface? = null
            if (bytes != null && info != null) {
                try {
                    val tmp = File(cacheDir, "font-preview.${info.format}")
                    tmp.writeBytes(bytes)
                    tf = Typeface.createFromFile(tmp)
                } catch (_: Exception) {
                    tf = null
                }
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                when {
                    loaded.error != null -> toast(loaded.error)
                    bytes == null || info == null -> toast("File này không phải font TTF/OTF hợp lệ (hoặc font bị hỏng). WOFF/WOFF2 chưa được hỗ trợ.")
                    else -> showImportPreview(loaded.displayName, bytes, info, tf)
                }
            }
        }.start()
    }

    private fun showImportPreview(fileName: String, bytes: ByteArray, info: FontInfo.Info, tf: Typeface?) {
        val c = this
        val name = info.fullName ?: info.family ?: fileName.substringBeforeLast('.')
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(c, 20), Ui.dp(c, 8), Ui.dp(c, 20), 0) }
        box.addView(Ui.text(c, name, 18f, Ui.TEXT, true).apply { tf?.let { typeface = it } })
        val lines = ArrayList<String>()
        lines.add(info.format.uppercase() + " · " + (bytes.size / 1024) + " KB")
        info.author?.let { lines.add("Tác giả: $it") }
        info.license?.let { lines.add("Giấy phép: ${it.take(140)}") }
        box.addView(Ui.text(c, lines.joinToString("\n"), 12f, Ui.MUTED))
        box.addView(Ui.text(c, sample(), 22f, Ui.TEXT).apply { tf?.let { typeface = it }; setPadding(0, Ui.dp(c, 12), 0, Ui.dp(c, 8)) })
        when (info.vietnamese) {
            "full" -> box.addView(Ui.text(c, "✓ Đủ chữ tiếng Việt có dấu", 13f, Ui.OK))
            "partial" -> box.addView(Ui.text(c, "⚠ Thiếu ${info.vietnameseMissing}/${info.vietnameseTotal} chữ tiếng Việt có dấu; chữ thiếu sẽ dùng font dự phòng.", 13f, Ui.ERR))
            else -> box.addView(Ui.text(c, "✗ Không có chữ tiếng Việt có dấu — không dùng được cho lời bài hát.", 13f, Ui.ERR))
        }
        box.addView(Ui.text(c, "Bạn tự chịu trách nhiệm về quyền sử dụng font này.", 12f, Ui.MUTED).apply { setPadding(0, Ui.dp(c, 8), 0, 0) })
        val b = AlertDialog.Builder(c).setTitle("Thêm vào thư viện?").setView(box).setNegativeButton("Hủy", null)
        if (info.vietnamese != "none") {
            b.setPositiveButton("Thêm") { _, _ ->
                val r = UserFonts.importBytes(fileName, bytes)
                if (r.error != null) toast(r.error) else {
                    toast("Đã thêm “${r.font?.family}”." + (r.warning?.let { "\n$it" } ?: ""))
                    filter = "mine"; chipBuilder(); expanded = r.font?.id; render()
                }
            }
        }
        b.show()
    }

    private fun showHelp() {
        val c = this
        val msg = "1. TẢI FONT\n" +
            "Mở Google Fonts (nút bên dưới). Bấm bộ lọc “Language”, chọn “Vietnamese” để chỉ thấy font có đủ dấu tiếng Việt. " +
            "Chọn một font, bấm “Get font” rồi “Download all”. File tải về là một file .zip nằm trong thư mục Downloads (Tải xuống).\n\n" +
            "2. GIẢI NÉN\n" +
            "Mở ứng dụng Tệp (Files / My Files), vào Downloads, bấm vào file .zip rồi chọn Giải nén (Extract). " +
            "Bạn sẽ thấy các file .ttf; nếu có thư mục “static” thì dùng file trong đó (ví dụ …-Regular.ttf, …-Bold.ttf).\n\n" +
            "3. THÊM VÀO MYKARA\n" +
            "Quay lại đây, bấm “＋ Thêm font từ máy”, vào Downloads (hoặc thư mục vừa giải nén) và chọn file .ttf hoặc .otf. " +
            "Mykara hiện bản xem trước và báo font có đủ chữ tiếng Việt không; bấm “Thêm” để lưu.\n\n" +
            "4. DÙNG FONT\n" +
            "Vào dự án → Xem trước → nút “Font” → “Font lời” (hoặc “Font tiêu đề”) → chọn font của bạn trong mục “Của tôi”.\n\n" +
            "LƯU Ý\n" +
            "• Chỉ nhận .ttf và .otf (không nhận .zip, .woff, .woff2).\n" +
            "• Font trên Google Fonts thường theo giấy phép OFL, dùng tự do. Font từ nguồn khác có thể cấm dùng thương mại hoặc đăng video; bạn tự kiểm tra giấy phép.\n" +
            "• Font chỉ nằm trong máy bạn, Mykara không tải lên đâu cả."
        val tv = Ui.text(c, msg, 14f, Ui.TEXT).apply { setPadding(Ui.dp(c, 20), Ui.dp(c, 8), Ui.dp(c, 20), 0) }
        AlertDialog.Builder(c).setTitle("Cách tải và thêm font")
            .setView(ScrollView(c).apply { addView(tv) })
            .setPositiveButton("Mở Google Fonts") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://fonts.google.com/")))
                } catch (_: Exception) {
                    toast("Không mở được trình duyệt. Hãy tự vào fonts.google.com.")
                }
            }
            .setNegativeButton("Đóng", null).show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object {
        const val SAMPLE = "Trăm năm trong cõi người ta,\nChữ tài chữ mệnh khéo là ghét nhau."
    }
}
