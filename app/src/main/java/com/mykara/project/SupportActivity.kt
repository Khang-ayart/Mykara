package com.mykara.project

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity

/**
 * Về Mykara và ủng hộ tác giả. Ảnh QR đặt tại assets/support/qr.png, link tại assets/support/link.txt
 * (một dòng). Thiếu file nào thì phần đó hiện thông báo thay vì lỗi.
 */
class SupportActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = this
        val col = Ui.column(c, padTop = 24)

        val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.text(c, "←", 24f).apply {
            setPadding(0, Ui.dp(c, 8), Ui.dp(c, 16), Ui.dp(c, 8))
            setOnClickListener { finish() }
        })
        head.addView(Ui.text(c, "Ủng hộ Mykara", 20f, Ui.TEXT, true))
        col.addView(head)

        col.addView(Ui.text(c, "Mykara miễn phí, mã nguồn mở và không quảng cáo. Nếu app giúp ích cho bạn, một ly cà phê sẽ giúp mình có thêm thời gian làm tiếp. Cảm ơn bạn! ☕", 15f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(c, 16), 0, Ui.dp(c, 8))
        })

        val card = Ui.card(c, 18).apply { gravity = Gravity.CENTER_HORIZONTAL }
        val qr = readAsset("support/qr.png")?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        if (qr != null) {
            card.addView(
                ImageView(c).apply {
                    setImageBitmap(qr)
                    setBackgroundColor(0xFFFFFFFF.toInt())
                    setPadding(Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 10))
                    adjustViewBounds = true
                },
                LinearLayout.LayoutParams(Ui.dp(c, 240), ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL },
            )
            card.addView(Ui.text(c, "Quét mã bằng app ngân hàng hoặc camera", 13f, Ui.MUTED).apply {
                gravity = Gravity.CENTER
                setPadding(0, Ui.dp(c, 10), 0, 0)
            })
        } else {
            card.addView(Ui.text(c, "Chưa có mã QR (tác giả sẽ bổ sung).", 14f, Ui.MUTED))
        }
        col.addView(card)

        val link = readAsset("support/link.txt")?.toString(Charsets.UTF_8)?.trim().orEmpty()
        if (link.startsWith("https://")) {
            col.addView(Ui.button(c, "☕  Mở trang ủng hộ", Ui.PRIMARY) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
            })
        }

        col.addView(Ui.section(c, "Về Mykara"))
        val about = Ui.card(c)
        about.addView(Ui.text(c, "Phiên bản: build ${Updater.installedBuild(c)}", 14f))
        about.addView(Ui.text(c, "Giấy phép: GNU GPL v3 — được dùng, sửa, chia sẻ tự do.", 14f).apply { setPadding(0, Ui.dp(c, 6), 0, 0) })
        about.addView(Ui.text(c, "Phông chữ: " + Fonts.all.joinToString(", ") { it.family } + " (SIL Open Font License).", 13f, Ui.MUTED).apply { setPadding(0, Ui.dp(c, 6), 0, 0) })
        col.addView(about)
        col.addView(Ui.button(c, "Mã nguồn trên GitHub", Ui.TEXT_ONLY) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Khang-ayart/Mykara")))
        })

        setContentView(ScrollView(c).apply { setBackgroundColor(Ui.BG); addView(col) })
    }

    private fun readAsset(path: String): ByteArray? = try {
        assets.open(path).use { it.readBytes() }
    } catch (_: Exception) {
        null
    }
}
