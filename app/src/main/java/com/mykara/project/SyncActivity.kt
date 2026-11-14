package com.mykara.project

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Đồng bộ lời bằng cách GIỮ nút: nghe nhạc, nhấn xuống khi một đoạn hát bắt đầu và thả khi ca sĩ dứt đoạn đó. Câu
 * có thể chia thành nhiều đoạn (sau từ kết thúc bằng "…" hoặc do người dùng chia) để khoảng nghỉ giữa câu được giữ
 * nguyên. Điểm nhấn được trừ độ trễ tay và "hít" về điểm bắt đầu cụm âm gần nhất (±0,25 s). Nhấn rất ngắn thì
 * coi là chạm (điểm cuối ước lượng). Sau đó xem lại, chỉnh Đầu/Cuối từng đoạn, nghe thử, lưu.
 */
class SyncActivity : Activity() {
    /** Một đoạn hát: các từ [from, to) của câu thứ lineIdx. */
    private class Part(val lineIdx: Int, val from: Int, val to: Int, val text: String, val chars: Int)
    private class Row(val lineIdx: Int, val lineId: String, var from: Int, var to: Int, var text: String, var start: Long, var end: Long)

    private lateinit var store: ProjectStore
    private lateinit var id: String
    private lateinit var doc: JSONObject
    private lateinit var container: FrameLayout
    private val ui = Handler(Looper.getMainLooper())
    private val dp by lazy { resources.displayMetrics.density }

    private val lineIds = ArrayList<String>()
    private val lineTexts = ArrayList<String>()
    private val lineChars = ArrayList<Int>()
    private var audioFile: File? = null
    private var durationMs: Long? = null
    private var onsets: List<Long> = emptyList()
    private var player: MediaPlayer? = null
    private val lineObjs = ArrayList<JSONObject>()
    private val lineTokens = ArrayList<List<String>>()
    private val parts = ArrayList<Part>()
    private val downs = ArrayList<Long>()
    private val ups = ArrayList<Long?>()
    private var holding = false
    private var rows = ArrayList<Row>()
    private var timeLabel: TextView? = null
    private var stopAt = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ProjectStore(this)
        id = intent.getStringExtra("id") ?: run { finish(); return }
        doc = store.load(id)
        container = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        setContentView(container)

        val lines = doc.getJSONObject("lyrics").getJSONArray("lines")
        for (i in 0 until lines.length()) {
            val o = lines.getJSONObject(i)
            lineIds.add(o.getString("id")); lineTexts.add(o.getString("text"))
            lineChars.add(o.getString("text").count { !it.isWhitespace() })
            lineObjs.add(o); lineTokens.add(Segments.tokens(o.getString("text")))
        }
        buildParts()
        val rel = ProjectDoc.audioFile(doc)
        val f = rel?.let { store.assetFile(id, it) }
        if (lines.length() == 0 || f == null || !f.exists()) {
            page(
                title("Chưa thể đồng bộ"),
                body("Dự án cần có cả nhạc và lời. Quay lại, thêm nhạc và dán lời rồi bấm Lưu."),
                button("Quay lại") { finish() },
            )
            return
        }
        audioFile = f
        durationMs = ProjectDoc.audioDurationMs(doc)

        page(title("Đang phân tích nhạc…"), body("Dò điểm bắt đầu các cụm hát để mốc gõ nhịp chính xác hơn. Vài giây."))
        Thread {
            onsets = try { loadOnsets(f) } catch (e: Exception) { emptyList() }
            ui.post { if (!isDestroyed) showReady() }
        }.start()
    }

    /** Chia mỗi câu thành các đoạn theo chỗ ngắt (do người dùng chia, hoặc mặc định sau từ có "…"). */
    private fun buildParts() {
        parts.clear()
        for (i in lineIds.indices) {
            val toks = lineTokens[i]
            for ((f, t) in Segments.ranges(toks.size, Segments.breaksOf(lineObjs[i], toks))) {
                val text = toks.subList(f, t).joinToString(" ")
                parts.add(Part(i, f, t, text, text.count { !it.isWhitespace() }))
            }
        }
    }

    private fun loadOnsets(f: File): List<Long> {
        val cache = File(store.dir(id), "cache/onsets.json")
        val key = "${f.length()}-${f.lastModified()}"
        if (cache.isFile) {
            try {
                val j = JSONObject(cache.readText(Charsets.UTF_8))
                if (j.optString("key") == key) {
                    val a = j.getJSONArray("onsets")
                    return (0 until a.length()).map { a.getLong(it) }
                }
            } catch (_: Exception) {}
        }
        val res = AudioStream.phraseOnsets(f)
        cache.parentFile?.mkdirs()
        cache.writeText(JSONObject().put("key", key).put("onsets", JSONArray(res)).toString(), Charsets.UTF_8)
        return res
    }

    // ---------- giao diện ----------

    private fun page(vararg views: android.view.View) {
        container.removeAllViews()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (24 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
        }
        for (v in views) col.addView(v)
        container.addView(col)
    }

    private fun title(t: String) = TextView(this).apply { text = t; textSize = 24f; setTextColor(Ui.TEXT); typeface = Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, (8 * dp).toInt()) }
    private fun body(t: String, size: Float = 16f, color: Int = Ui.TEXT) =
        TextView(this).apply { text = t; textSize = size; setTextColor(color); setPadding(0, (4 * dp).toInt(), 0, (8 * dp).toInt()) }
    private fun button(t: String, onClick: () -> Unit) = Ui.button(this, t, Ui.SECONDARY, onClick)

    private fun showReady() {
        val n = lineIds.size
        val hint = if (onsets.isEmpty()) "Không dò được điểm bắt đầu cụm, app sẽ dùng đúng lúc bạn chạm."
        else "Đã dò ${onsets.size} điểm bắt đầu cụm: mốc của bạn sẽ được kéo về điểm gần nhất (tối đa 0,25 giây)."
        page(
            title("Đồng bộ lời bằng gõ nhịp"),
            body("Nhạc sẽ phát. GIỮ nút lớn khi ca sĩ hát đoạn đang sáng màu, THẢ khi họ dứt đoạn đó. Ca sĩ nghỉ giữa câu thì thả ra, câu có nhiều đoạn sẽ chờ bạn giữ tiếp. Có $n câu, ${parts.size} đoạn."),
            body(hint, 14f, Ui.MUTED),
            body("Câu có dấu … (hoặc ..) giữa câu được tự chia đoạn; chia thêm hoặc gộp lại ở màn xem lại. Chỉ chạm nhanh (không giữ) cũng được: app tự ước lượng điểm cuối. App bù khoảng 0,15 giây độ trễ tay. Nhầm thì bấm Hoàn tác.", 14f, Ui.MUTED),
            Ui.button(this, "▶ Bắt đầu", Ui.PRIMARY) { startTapping() },
            *(if (ProjectTiming.lines(doc).isNotEmpty()) arrayOf(button("Chỉnh lại mốc đã đồng bộ") { editExisting() }) else emptyArray()),
            button("Đóng") { finish() },
        )
    }

    private fun ensurePlayer(): MediaPlayer {
        player?.let { return it }
        return MediaPlayer().apply { setDataSource(audioFile!!.absolutePath); prepare() }.also {
            player = it
            if (durationMs == null) durationMs = it.duration.toLong()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun startTapping() {
        downs.clear(); ups.clear(); holding = false
        val p = ensurePlayer()
        p.seekTo(0)
        p.start()
        renderTapping()
        ui.removeCallbacksAndMessages(null)
        ui.post(object : Runnable {
            override fun run() {
                timeLabel?.text = fmt(player?.currentPosition?.toLong() ?: 0L)
                if (stopAt >= 0 && (player?.currentPosition ?: 0) >= stopAt) { player?.pause(); stopAt = -1 }
                ui.postDelayed(this, 50)
            }
        })
    }

    /** Cả câu, đoạn đang chờ giữ nút tô màu nhấn, phần còn lại mờ. */
    private fun partSpan(part: Part): CharSequence {
        val toks = lineTokens[part.lineIdx]
        val sb = SpannableStringBuilder()
        for ((k, t) in toks.withIndex()) {
            val st = sb.length
            sb.append(t)
            val on = k >= part.from && k < part.to
            sb.setSpan(ForegroundColorSpan(if (on) Ui.ACCENT else Ui.MUTED), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (on) sb.setSpan(StyleSpan(Typeface.BOLD), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (k < toks.size - 1) sb.append(' ')
        }
        return sb
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun renderTapping() {
        val cur = downs.size
        val part = parts.getOrNull(cur)
        val prev = TextView(this).apply { text = if (cur > 0) parts[cur - 1].text else ""; textSize = 14f; setTextColor(Ui.MUTED); gravity = Gravity.CENTER }
        val now = TextView(this).apply {
            text = if (part != null) partSpan(part) else "Xong!"
            textSize = 22f; setTextColor(Ui.TEXT); gravity = Gravity.CENTER
        }
        val nxt = TextView(this).apply { text = if (cur + 1 < parts.size) parts[cur + 1].text else ""; textSize = 14f; setTextColor(Ui.MUTED); gravity = Gravity.CENTER }
        timeLabel = body("", 14f)
        val idle = "GIỮ khi đoạn này được hát,\nTHẢ khi dứt\n(${cur + 1}/${parts.size})"
        val big = Button(this).apply {
            text = idle
            textSize = 20f
            setTextColor(Color.WHITE)
            background = Ui.rounded(Ui.ACCENT, 20, this@SyncActivity)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = (12 * dp).toInt(); bottomMargin = (12 * dp).toInt() }
            // ACTION_DOWN/UP để lấy đúng lúc nhấn và lúc thả, không chờ hệ thống nhận ra "một cú chạm"
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> if (onDown()) {
                        (v as Button).text = "ĐANG GIỮ…\nthả khi ca sĩ dứt đoạn này"
                        v.background = Ui.rounded(Ui.OK, 20, this@SyncActivity)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onUp()
                }
                true
            }
        }
        page(
            prev, now, nxt, timeLabel!!, big,
            button("↶ Hoàn tác đoạn cuối") { undo() },
            button("Dừng và xem lại") { finishTapping() },
        )
    }

    private fun onDown(): Boolean {
        val p = player ?: return false
        if (holding || downs.size >= parts.size) return false
        downs.add(p.currentPosition.toLong()); ups.add(null)
        holding = true
        return true
    }

    private fun onUp() {
        val p = player ?: return
        if (!holding) return
        holding = false
        ups[ups.size - 1] = p.currentPosition.toLong()
        if (downs.size >= parts.size) ui.post { finishTapping() } else ui.post { renderTapping() }
    }

    private fun undo() {
        if (downs.isEmpty()) return
        downs.removeAt(downs.size - 1); ups.removeAt(ups.size - 1); holding = false
        player?.seekTo(((downs.lastOrNull() ?: 0L) - 1500L).coerceAtLeast(0L).toInt())
        renderTapping()
    }

    private fun finishTapping() {
        ui.removeCallbacksAndMessages(null)
        player?.pause()
        holding = false
        if (downs.isEmpty()) { showReady(); return }
        val res = TapSync.computeHold(parts.map { it.chars }, downs, ups, durationMs, onsetsMs = onsets)
        rows = ArrayList(res.mapIndexed { i, r ->
            val pt = parts[i]
            Row(pt.lineIdx, lineIds[pt.lineIdx], pt.from, pt.to, pt.text, r[0], r[1])
        })
        showReview()
    }

    /** Mở lại màn xem lại với mốc đã lưu để chỉnh đầu/cuối từng đoạn mà không phải gõ lại. */
    private fun editExisting() {
        val byId = ProjectTiming.lines(doc).associateBy { it.lineId }
        val out = ArrayList<Row>()
        for (i in lineIds.indices) {
            val t = byId[lineIds[i]] ?: continue
            val toks = lineTokens[i]
            if (toks.isEmpty()) continue
            val segs = if (Segments.valid(t.segments, toks.size)) t.segments else listOf(Segments.Seg(0, toks.size, t.startMs, t.endMs))
            for (g in segs) out.add(Row(i, lineIds[i], g.from, g.to, toks.subList(g.from, g.to).joinToString(" "), g.startMs, g.endMs))
        }
        rows = out
        showReview()
    }

    private fun showReview() {
        val tapped = rows.map { it.lineIdx }.distinct().size
        container.removeAllViews()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (20 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
            addView(title("Xem lại mốc thời gian"))
            addView(body("Đã đồng bộ $tapped/${lineIds.size} câu. Mỗi đoạn chỉnh được điểm Đầu và điểm Cuối riêng (−0,5 / −0,1 / +0,1 / +0,5 giây); bấm ▶ nghe để kiểm tra có đủ chưa. Bấm ✂ để chia hoặc gộp đoạn trong câu (chỗ ca sĩ nghỉ giữa câu).", 14f))
        }
        root.addView(head)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0) }
        val scroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(list) }
        root.addView(scroll)
        fun fill() {
            list.removeAllViews()
            for ((i, r) in rows.withIndex()) {
                val mates = rows.filter { it.lineIdx == r.lineIdx }
                val tag = if (mates.size > 1) " · đoạn ${mates.indexOf(r) + 1}/${mates.size}" else ""
                list.addView(TextView(this).apply {
                    text = "Câu ${r.lineIdx + 1}$tag  ${fmt(r.start)} – ${fmt(r.end)}  (${String.format("%.1f", (r.end - r.start) / 1000.0)} s)\n${r.text}"
                    textSize = 15f; setTextColor(Ui.TEXT); setPadding(0, (14 * dp).toInt(), 0, (4 * dp).toInt())
                })
                // Hai đầu mút chỉnh riêng: kéo điểm bắt đầu lùi lại hoặc điểm kết thúc ra sau để lấy trọn câu.
                list.addView(endRow("Đầu", { d -> moveStart(i, d); fill() }))
                list.addView(endRow("Cuối", { d -> moveEnd(i, d); fill() }))
                list.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(smallBtn("◀ dời câu") { shiftRow(i, -100L); fill() })
                    addView(smallBtn("dời câu ▶") { shiftRow(i, 100L); fill() })
                    addView(smallBtn("▶ nghe") { playRow(r) })
                    addView(smallBtn("✂ chia") { showSplit(r.lineIdx) { fill() } })
                })
            }
            val have = rows.map { it.lineIdx }.toSet()
            for (i in lineIds.indices.filter { it !in have }) {
                list.addView(TextView(this).apply {
                    text = "Câu ${i + 1}: (chưa có thời gian)\n${lineTexts[i]}"
                    textSize = 15f; setTextColor(Ui.MUTED); setPadding(0, (10 * dp).toInt(), 0, 0)
                })
            }
        }
        fill()
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), (8 * dp).toInt())
            addView(smallBtn("Gõ lại") { startTapping() })
            addView(smallBtn("Lưu") { save(openPreview = false) })
            addView(smallBtn("Lưu & xem trước ▶") { save(openPreview = true) }.apply {
                setTextColor(Color.WHITE)
                background = Ui.rounded(Ui.ACCENT, 10, this@SyncActivity)
            })
        }
        root.addView(bar)
        container.addView(root)
    }

    private fun smallBtn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; setOnClickListener { onClick() }
        setTextColor(Ui.TEXT)
        background = Ui.rounded(Ui.PANEL_HI, 10, this@SyncActivity)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    /** Hàng nút chỉnh một đầu mút: nhãn + bốn bước. */
    private fun endRow(label: String, onMove: (Long) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            TextView(this@SyncActivity).apply { text = label; textSize = 13f; setTextColor(Ui.MUTED) },
            LinearLayout.LayoutParams((44 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        for ((t, d) in listOf("−0,5" to -500L, "−0,1" to -100L, "+0,1" to 100L, "+0,5" to 500L)) addView(smallBtn(t) { onMove(d) })
    }

    private val minLenMs = 300L

    /** Đoạn liền trước / liền sau trong cùng một câu (các đoạn của một câu không được chồng nhau). */
    private fun prevInLine(i: Int) = rows.getOrNull(i - 1)?.takeIf { it.lineIdx == rows[i].lineIdx }
    private fun nextInLine(i: Int) = rows.getOrNull(i + 1)?.takeIf { it.lineIdx == rows[i].lineIdx }

    private fun moveStart(i: Int, delta: Long) {
        val r = rows[i]
        val lo = prevInLine(i)?.end ?: 0L
        r.start = (r.start + delta).coerceIn(lo, maxOf(lo, r.end - minLenMs))
    }

    private fun moveEnd(i: Int, delta: Long) {
        val r = rows[i]
        val hi = nextInLine(i)?.start ?: (durationMs ?: Long.MAX_VALUE)
        r.end = (r.end + delta).coerceIn(r.start + minLenMs, maxOf(r.start + minLenMs, hi))
    }

    /** Dời cả câu (mọi đoạn của câu) giữ nguyên khoảng nghỉ giữa các đoạn. */
    private fun shiftRow(i: Int, delta: Long) {
        val mates = rows.filter { it.lineIdx == rows[i].lineIdx }
        val dur = durationMs ?: Long.MAX_VALUE
        val d = delta.coerceIn(-mates.first().start, dur - mates.last().end)
        for (m in mates) { m.start += d; m.end += d }
    }

    /** Hộp chọn chỗ ngắt: tick từ mà SAU nó ca sĩ nghỉ. Thời gian các đoạn mới lấy theo thời gian từng từ hiện có. */
    private fun showSplit(lineIdx: Int, refresh: () -> Unit) {
        val toks = lineTokens[lineIdx]
        if (toks.size < 2) { Toast.makeText(this, "Câu chỉ có một từ, không chia được", Toast.LENGTH_SHORT).show(); return }
        val old = rows.filter { it.lineIdx == lineIdx }
        val current = Segments.breaksFrom(old.map { it.from to it.to }).toSet()
        val labels = (0 until toks.size - 1).map { "…${toks[it]}  |  ngắt sau từ này" }.toTypedArray()
        val checked = BooleanArray(labels.size) { it in current }
        AlertDialog.Builder(this)
            .setTitle("Chia đoạn: ${lineTexts[lineIdx]}")
            .setMultiChoiceItems(labels, checked) { _, which, on -> checked[which] = on }
            .setPositiveButton("Áp dụng") { _, _ ->
                rebuildLine(lineIdx, checked.indices.filter { checked[it] })
                refresh()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun rebuildLine(lineIdx: Int, breaks: List<Int>) {
        val idxs = rows.indices.filter { rows[it].lineIdx == lineIdx }
        if (idxs.isEmpty()) return
        val old = idxs.map { rows[it] }
        val toks = lineTokens[lineIdx]
        val wt = Segments.wordTimes(toks, old.map { Segments.Seg(it.from, it.to, it.start, it.end) })
        val fresh = ArrayList<Row>()
        var prevEnd = 0L
        for ((f, t) in Segments.ranges(toks.size, breaks)) {
            val s = maxOf(wt[f].first, prevEnd)
            val e = maxOf(wt[t - 1].second, s + 100L)
            fresh.add(Row(lineIdx, lineIds[lineIdx], f, t, toks.subList(f, t).joinToString(" "), s, e))
            prevEnd = e
        }
        val at = idxs.first()
        for (k in idxs.reversed()) rows.removeAt(k)
        rows.addAll(at, fresh)
    }

    private fun playRow(r: Row) {
        val p = ensurePlayer()
        p.seekTo((r.start - 500L).coerceAtLeast(0L).toInt())
        p.start()
        stopAt = r.end
        ui.removeCallbacksAndMessages(null)
        ui.post(object : Runnable {
            override fun run() {
                if ((player?.currentPosition ?: 0) >= stopAt) { player?.pause(); stopAt = -1; return }
                ui.postDelayed(this, 50)
            }
        })
    }

    private fun save(openPreview: Boolean) {
        ProjectTiming.setFromParts(doc, rows.map { ProjectTiming.PartTiming(it.lineId, it.from, it.to, it.start, it.end) })
        // Nhớ chỗ ngắt đã chọn để lần đồng bộ sau chia đoạn y như vậy; trùng mặc định thì không cần lưu.
        for (i in lineIds.indices) {
            val mine = rows.filter { it.lineIdx == i }
            if (mine.isEmpty()) continue
            val br = Segments.breaksFrom(mine.map { it.from to it.to })
            if (br == Segments.defaultBreaks(lineTokens[i])) lineObjs[i].remove("breaks") else lineObjs[i].put("breaks", JSONArray(br))
        }
        store.save(id, doc)
        val nLines = rows.map { it.lineIdx }.distinct().size
        Toast.makeText(this, "Đã lưu mốc thời gian của $nLines câu (${rows.size} đoạn)", Toast.LENGTH_SHORT).show()
        if (openPreview) startActivity(Intent(this, PreviewActivity::class.java).putExtra("id", id))
        finish()
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000.0
        return String.format("%d:%04.1f", (s / 60).toInt(), s % 60)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacksAndMessages(null)
        player?.pause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        player?.release()
        super.onDestroy()
    }
}
