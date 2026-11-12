package com.mykara.project

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 * Đồng bộ lời bằng gõ nhịp: nghe nhạc, chạm mỗi khi một câu bắt đầu. Mốc chạm được trừ độ trễ tay và "hít" về
 * điểm bắt đầu cụm âm gần nhất (trong ±0,25 s) nếu dò được. Sau đó xem lại, chỉnh ±0,1 s từng câu, nghe thử, lưu.
 */
class SyncActivity : Activity() {
    private class Row(val lineId: String, val text: String, var start: Long, var end: Long)

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
    private val taps = ArrayList<Long>()
    private var rows = ArrayList<Row>()
    private var timeLabel: TextView? = null
    private var stopAt = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ProjectStore(this)
        id = intent.getStringExtra("id") ?: run { finish(); return }
        doc = store.load(id)
        container = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        setContentView(container)

        val lines = doc.getJSONObject("lyrics").getJSONArray("lines")
        for (i in 0 until lines.length()) {
            val o = lines.getJSONObject(i)
            lineIds.add(o.getString("id")); lineTexts.add(o.getString("text"))
            lineChars.add(o.getString("text").count { !it.isWhitespace() })
        }
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

    private fun title(t: String) = TextView(this).apply { text = t; textSize = 24f; setTextColor(Color.BLACK); setPadding(0, 0, 0, (8 * dp).toInt()) }
    private fun body(t: String, size: Float = 16f, color: Int = 0xFF444444.toInt()) =
        TextView(this).apply { text = t; textSize = size; setTextColor(color); setPadding(0, (4 * dp).toInt(), 0, (8 * dp).toInt()) }
    private fun button(t: String, onClick: () -> Unit) = Button(this).apply { text = t; setOnClickListener { onClick() } }

    private fun showReady() {
        val n = lineIds.size
        val hint = if (onsets.isEmpty()) "Không dò được điểm bắt đầu cụm, app sẽ dùng đúng lúc bạn chạm."
        else "Đã dò ${onsets.size} điểm bắt đầu cụm: mốc của bạn sẽ được kéo về điểm gần nhất (tối đa 0,25 giây)."
        page(
            title("Đồng bộ lời bằng gõ nhịp"),
            body("Nhạc sẽ phát. Mỗi khi một câu BẮT ĐẦU được hát, chạm nút lớn một lần. Có $n câu; câu đang chờ chạm hiện ngay trên nút."),
            body(hint, 14f, 0xFF777777.toInt()),
            body("Chạm hơi trễ cũng được, app bù khoảng 0,15 giây. Chạm nhầm thì bấm Hoàn tác.", 14f, 0xFF777777.toInt()),
            button("▶ Bắt đầu") { startTapping() },
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
        taps.clear()
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

    @SuppressLint("ClickableViewAccessibility")
    private fun renderTapping() {
        val cur = taps.size
        val prev = TextView(this).apply { text = if (cur > 0) lineTexts[cur - 1] else ""; textSize = 14f; setTextColor(0xFF999999.toInt()); gravity = Gravity.CENTER }
        val now = TextView(this).apply {
            text = if (cur < lineTexts.size) lineTexts[cur] else "Xong!"
            textSize = 22f; setTypeface(null, Typeface.BOLD); setTextColor(Color.BLACK); gravity = Gravity.CENTER
        }
        val nxt = TextView(this).apply { text = if (cur + 1 < lineTexts.size) lineTexts[cur + 1] else ""; textSize = 14f; setTextColor(0xFF999999.toInt()); gravity = Gravity.CENTER }
        timeLabel = body("", 14f)
        val big = Button(this).apply {
            text = "CHẠM khi câu này bắt đầu\n(${cur + 1}/${lineTexts.size})"
            textSize = 20f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = (12 * dp).toInt(); bottomMargin = (12 * dp).toInt() }
            // dùng ACTION_DOWN để không mất thêm độ trễ lúc nhả tay
            setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) { onTap(); true } else true
            }
        }
        page(
            prev, now, nxt, timeLabel!!, big,
            button("↶ Hoàn tác chạm cuối") { undo() },
            button("Dừng và xem lại") { finishTapping() },
        )
    }

    private fun onTap() {
        val p = player ?: return
        if (taps.size >= lineIds.size) return
        taps.add(p.currentPosition.toLong())
        if (taps.size >= lineIds.size) finishTapping() else renderTapping()
    }

    private fun undo() {
        if (taps.isEmpty()) return
        taps.removeAt(taps.size - 1)
        player?.seekTo(((taps.lastOrNull() ?: 0L) - 1500L).coerceAtLeast(0L).toInt())
        renderTapping()
    }

    private fun finishTapping() {
        ui.removeCallbacksAndMessages(null)
        player?.pause()
        if (taps.isEmpty()) { showReady(); return }
        val n = taps.size
        val es = TapSync.compute(lineIds, lineChars, taps, durationMs, onsetsMs = onsets)
        rows = ArrayList(es.mapIndexed { i, e -> Row(e.lineId, lineTexts[i], e.startMs, e.endMs) })
        showReview(n)
    }

    private fun showReview(tapped: Int) {
        container.removeAllViews()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (20 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
            addView(title("Xem lại mốc thời gian"))
            addView(body("Đã đồng bộ $tapped/${lineIds.size} câu. Chỉnh ±0,1 giây hoặc bấm ▶ để nghe lại từng câu.", 14f))
        }
        root.addView(head)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0) }
        val scroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(list) }
        root.addView(scroll)
        fun fill() {
            list.removeAllViews()
            for ((i, r) in rows.withIndex()) {
                list.addView(TextView(this).apply {
                    text = "${i + 1}. ${fmt(r.start)} – ${fmt(r.end)}\n${r.text}"
                    textSize = 15f; setTextColor(Color.BLACK); setPadding(0, (10 * dp).toInt(), 0, 0)
                })
                list.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(smallBtn("−0,1") { shiftRow(i, -100L); fill() })
                    addView(smallBtn("+0,1") { shiftRow(i, 100L); fill() })
                    addView(smallBtn("▶ nghe") { playRow(r) })
                })
            }
            for (i in rows.size until lineIds.size) {
                list.addView(TextView(this).apply {
                    text = "${i + 1}. (chưa có thời gian)\n${lineTexts[i]}"
                    textSize = 15f; setTextColor(0xFF999999.toInt()); setPadding(0, (10 * dp).toInt(), 0, 0)
                })
            }
        }
        fill()
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), (8 * dp).toInt())
            addView(smallBtn("Gõ lại") { startTapping() })
            addView(smallBtn("Lưu") { save() })
        }
        root.addView(bar)
        container.addView(root)
    }

    private fun smallBtn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun shiftRow(i: Int, delta: Long) {
        val r = rows[i]
        val dur = durationMs ?: Long.MAX_VALUE
        val d = delta.coerceIn(-r.start, dur - r.end)
        r.start += d; r.end += d
    }

    private fun playRow(r: Row) {
        val p = ensurePlayer()
        p.seekTo((r.start - 300L).coerceAtLeast(0L).toInt())
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

    private fun save() {
        val es = rows.map { TapSync.Entry(it.lineId, it.start, it.end) }
        ProjectTiming.setFromTaps(doc, es)
        store.save(id, doc)
        Toast.makeText(this, "Đã lưu mốc thời gian của ${es.size} câu", Toast.LENGTH_SHORT).show()
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
