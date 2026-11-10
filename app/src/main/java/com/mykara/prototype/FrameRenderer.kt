package com.mykara.prototype

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.util.Locale
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Nguồn khung hình. Chỉ phụ thuộc vào thời gian tMs (không giữ trạng thái),
 * nên seek/preview/export cho kết quả giống nhau. Sau này có thể thay bằng
 * bộ vẽ dựa trên WebView mà không sửa phần mã hóa.
 */
interface FrameRenderer {
    fun draw(canvas: Canvas, tMs: Long, width: Int, height: Int)
}

/**
 * Cảnh thử: nền chuyển màu, vòng xung theo nhịp 500ms (khớp tiếng bíp của nhạc thử),
 * 3 câu lời có hiệu ứng vào/ra, emoji bay vào, và trái tim vỡ thành mảnh
 * (thử ý tưởng Semantic Visual Replacement: hình dựng sẵn một lần, chỉ biến đổi mỗi khung).
 */
class SampleLyricRenderer : FrameRenderer {

    private class Line(
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val emoji: String?,
        val shatterAtMs: Long = -1L,
    )

    private val lines = listOf(
        Line(600, 3600, "Mưa rơi trên mây trắng", "☁️"),
        Line(3800, 7000, "Trái tim vỡ tan", "❤️", shatterAtMs = 5600),
        Line(7400, 9800, "Mykara prototype", null),
    )

    private val bg = Paint()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hud = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255) }
    private val pulse = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val spritePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val sprites = HashMap<String, Bitmap>()
    private val hsv = FloatArray(3)
    private val src = Rect()
    private val dst = RectF()

    override fun draw(canvas: Canvas, tMs: Long, width: Int, height: Int) {
        val w = width.toFloat()
        val h = height.toFloat()

        hsv[0] = (220f + tMs / 1000f * 6f) % 360f
        hsv[1] = 0.55f
        hsv[2] = 0.35f
        val c1 = Color.HSVToColor(hsv)
        hsv[0] = (hsv[0] + 60f) % 360f
        hsv[2] = 0.65f
        val c2 = Color.HSVToColor(hsv)
        bg.shader = LinearGradient(0f, 0f, w, h, c1, c2, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, bg)

        val phase = (tMs % 500L) / 500f
        pulse.strokeWidth = h * 0.006f
        pulse.alpha = (255 * (1f - phase)).toInt()
        canvas.drawCircle(w / 2f, h / 2f, h * (0.18f + 0.25f * phase), pulse)

        for (l in lines) {
            if (tMs >= l.startMs && tMs < l.endMs) drawLine(canvas, l, tMs, w, h)
        }

        hud.textSize = h * 0.03f
        canvas.drawText(String.format(Locale.US, "t=%.2fs", tMs / 1000f), w * 0.03f, h * 0.06f, hud)
    }

    private fun drawLine(canvas: Canvas, l: Line, tMs: Long, w: Float, h: Float) {
        val inP = ((tMs - l.startMs) / 400f).coerceIn(0f, 1f)
        val outP = ((l.endMs - tMs) / 300f).coerceIn(0f, 1f)
        val a = min(inP, outP)
        val ease = 1f - (1f - inP) * (1f - inP)
        val scale = 0.85f + 0.15f * ease

        textPaint.textSize = h * 0.075f
        textPaint.alpha = (255 * a).toInt()
        textPaint.setShadowLayer(h * 0.02f, 0f, 0f, Color.argb((200 * a).toInt(), 255, 255, 255))
        canvas.save()
        canvas.scale(scale, scale, w / 2f, h * 0.78f)
        canvas.drawText(l.text, w / 2f, h * 0.78f, textPaint)
        canvas.restore()

        val e = l.emoji
        if (e != null) drawEmoji(canvas, e, l, tMs, w, h, a, ease)
    }

    private fun drawEmoji(
        canvas: Canvas, emoji: String, l: Line, tMs: Long,
        w: Float, h: Float, a: Float, ease: Float,
    ) {
        val bmp = sprite(emoji)
        val size = h * 0.30f
        val cx = w / 2f
        val cy = h * 0.38f - (1f - ease) * h * 0.05f

        if (l.shatterAtMs >= 0 && tMs >= l.shatterAtMs) {
            val p = ((tMs - l.shatterAtMs) / 900f).coerceIn(0f, 1f)
            val n = 4
            val cell = bmp.width / n
            val piece = size / n
            spritePaint.alpha = (255 * (1f - p) * a).toInt()
            for (gy in 0 until n) {
                for (gx in 0 until n) {
                    val idx = gy * n + gx
                    val ox = ((gx + 0.5f) / n - 0.5f) * size
                    val oy = ((gy + 0.5f) / n - 0.5f) * size
                    val rx = pseudo(idx * 2)
                    val ry = pseudo(idx * 2 + 1)
                    val dx = (ox * 2.2f + rx * 0.6f * size) * p
                    val dy = (oy * 2.2f + ry * 0.6f * size) * p + 1.2f * size * p * p
                    src.set(gx * cell, gy * cell, (gx + 1) * cell, (gy + 1) * cell)
                    canvas.save()
                    canvas.translate(cx + ox + dx, cy + oy + dy)
                    canvas.rotate(rx * 120f * p)
                    dst.set(-piece / 2f, -piece / 2f, piece / 2f, piece / 2f)
                    canvas.drawBitmap(bmp, src, dst, spritePaint)
                    canvas.restore()
                }
            }
        } else {
            dst.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
            spritePaint.alpha = (255 * a).toInt()
            canvas.drawBitmap(bmp, null, dst, spritePaint)
        }
    }

    /** Dựng emoji thành bitmap một lần rồi dùng lại. */
    private fun sprite(emoji: String): Bitmap = sprites.getOrPut(emoji) {
        val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        emojiPaint.textSize = 200f
        val fm = emojiPaint.fontMetrics
        c.drawText(emoji, 128f, 128f - (fm.ascent + fm.descent) / 2f, emojiPaint)
        bmp
    }

    /** Số giả ngẫu nhiên xác định theo chỉ số, trong khoảng [-1, 1]. */
    private fun pseudo(i: Int): Float {
        val x = sin(i * 12.9898f) * 43758.5453f
        return (x - floor(x)) * 2f - 1f
    }
}
