package com.mykara.project

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Bộ giao diện chung kiểu công cụ dựng phim (tối, nền xám than, một màu nhấn): mọi màn hình dùng chung
 * để nhìn đồng nhất. Chỉ dùng View thuần, không thêm thư viện.
 */
object Ui {
    const val BG = 0xFF161618.toInt()
    const val PANEL = 0xFF222226.toInt()
    const val PANEL_HI = 0xFF2E2E34.toInt()
    const val LINE = 0xFF3A3A42.toInt()
    const val TEXT = 0xFFEDEDF0.toInt()
    const val MUTED = 0xFF9A9AA6.toInt()
    const val ACCENT = 0xFFFF5C8A.toInt()   // hồng karaoke, cùng màu với icon
    const val BLUE = 0xFF4DA3FF.toInt()
    const val OK = 0xFF5BD68A.toInt()
    const val ERR = 0xFFFF6B6B.toInt()

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radiusDp: Int, c: Context, strokeColor: Int = 0, strokeDp: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(c, radiusDp).toFloat()
        if (strokeDp > 0) setStroke(dp(c, strokeDp), strokeColor)
    }

    private fun ripple(c: Context, base: GradientDrawable): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), base, null)

    fun text(c: Context, s: String, sizeSp: Float = 15f, color: Int = TEXT, bold: Boolean = false) = TextView(c).apply {
        text = s
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    fun title(c: Context, s: String) = text(c, s, 26f, TEXT, true)

    fun section(c: Context, s: String) = text(c, s.uppercase(), 12f, MUTED, true).apply {
        letterSpacing = 0.08f
        setPadding(0, dp(c, 20), 0, dp(c, 8))
    }

    fun column(c: Context, padH: Int = 16, padTop: Int = 24, padBottom: Int = 24) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(BG)
        setPadding(dp(c, padH), dp(c, padTop), dp(c, padH), dp(c, padBottom))
    }

    /** Thẻ bo góc chứa nội dung. */
    fun card(c: Context, pad: Int = 14) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(PANEL, 14, c)
        setPadding(dp(c, pad), dp(c, pad), dp(c, pad), dp(c, pad))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, dp(c, 10))
        }
    }

    /** Nút chính (màu nhấn), nút phụ (viền) hoặc nút chữ. */
    fun button(c: Context, s: String, style: Int = SECONDARY, onClick: () -> Unit) = TextView(c).apply {
        text = s
        textSize = 15f
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        minHeight = dp(c, 48)
        setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10))
        when (style) {
            PRIMARY -> { setTextColor(Color.WHITE); background = ripple(c, rounded(ACCENT, 12, c)) }
            SECONDARY -> { setTextColor(TEXT); background = ripple(c, rounded(PANEL_HI, 12, c)) }
            else -> { setTextColor(BLUE); background = ripple(c, rounded(0x00000000, 12, c)) }
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(c, 4), 0, dp(c, 4))
        }
    }

    const val PRIMARY = 0
    const val SECONDARY = 1
    const val TEXT_ONLY = 2

    /** Nút nhỏ trên thanh công cụ: biểu tượng chữ ở trên, nhãn ở dưới. */
    fun tool(c: Context, icon: String, label: String, selected: Boolean = false, onClick: () -> Unit) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(c, 6), dp(c, 8), dp(c, 6), dp(c, 8))
        background = ripple(c, rounded(if (selected) PANEL_HI else 0x00000000, 10, c))
        addView(text(c, icon, 20f, if (selected) ACCENT else TEXT).apply { gravity = Gravity.CENTER })
        addView(text(c, label, 11f, if (selected) ACCENT else MUTED).apply { gravity = Gravity.CENTER })
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /** Nhóm nút nằm ngang chia đều, dùng cho thanh công cụ dưới cùng. */
    fun toolbar(c: Context, vararg tools: View) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(PANEL)
        setPadding(dp(c, 6), dp(c, 4), dp(c, 6), dp(c, 4))
        for (t in tools) addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    fun edit(c: Context, value: String, hint: String = "", singleLine: Boolean = true) = EditText(c).apply {
        setText(value)
        this.hint = hint
        textSize = 16f
        setTextColor(TEXT)
        setHintTextColor(MUTED)
        background = rounded(PANEL, 10, c, LINE, 1)
        setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12))
        if (singleLine) {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        } else {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            gravity = Gravity.TOP
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** Dòng trạng thái ("✓ đã có nhạc"). */
    fun chip(c: Context, s: String, ok: Boolean) = text(c, (if (ok) "✓ " else "○ ") + s, 13f, if (ok) OK else MUTED)
}
