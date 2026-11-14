package com.mykara.project

/**
 * Template = bộ thiết lập (không phải video dựng sẵn): font, màu, cách chữ vào/ra, lớp phủ, hạt, phản ứng nhạc.
 * SceneBuilder biên dịch template + dữ liệu dự án thành Scene JSON. Thêm template mới = thêm một dòng ở đây.
 */
class Template(
    val id: String,
    val label: String,
    val font: String,
    val weight: String,
    val color: String,
    val highlight: String?,
    /** Cỡ chữ theo tỷ lệ so với cạnh cơ sở của khung (xem SceneBuilder). */
    val fontFrac: Double,
    val textY: Double,
    val enters: List<String>,
    val exits: List<String>,
    val hold: String?,
    val karaoke: Boolean,
    val gradient: Pair<String, String>,
    val darken: Double,
    val tint: Pair<String, Double>?,
    val vignette: Double?,
    val letterbox: Double?,
    val particles: Pair<String, Int>?,
    /** (nguồn, JSON tham số) */
    val reactive: List<Pair<String, String>>,
    val reactZoom: Double,
    val kenBurns: Boolean,
    /** "flow" = chữ xuống dòng bình thường; "collage" = mỗi từ một cỡ/góc nghiêng, xếp lệch nhau. */
    val layout: String = "flow",
    /** Màu quầng sáng quanh chữ (null = bóng đen mặc định). */
    val glow: String? = null,
    /** Câu cũ còn nằm lại mờ dần sau khi hết (ms), chồng lên câu mới như bản Lyric Kara gốc. 0 = tắt. */
    val trailMs: Long = 0L,
    /** Hiện thẻ tiêu đề (tên bài + nghệ sĩ) ở đoạn dạo đầu/giữa/cuối. */
    val titleCard: Boolean = false,
    /** Cứ vài câu dài thì xếp chữ vòng tròn quanh từ nhấn (0 = không dùng). */
    val ringEvery: Int = 0,
    /** Màu viền chữ (kiểu sub kara); null = không viền. */
    val stroke: String? = null,
    /** Từ đang hát nảy lên (karaoke). */
    val bounce: Boolean = false,
    /** Có biểu tượng xuất hiện hai bên câu (bộ biểu tượng chọn trong IconSets). */
    val icons: Boolean = false,
    /** Màu của từ người dùng chọn nhấn (kiểu karaoke / lyric lướt). */
    val emph: String? = null,
)

/** Bộ biểu tượng cho karaoke: mỗi câu lấy hai biểu tượng liên tiếp trong bộ, đặt hai bên câu. */
object IconSets {
    class Def(val id: String, val label: String, val emojis: List<String>)

    val all = listOf(
        Def("tim", "Trái tim", listOf("💜", "💗", "💖", "💙")),
        Def("sao", "Ngôi sao", listOf("✨", "⭐", "🌟", "💫")),
        Def("nhac", "Nốt nhạc", listOf("🎵", "🎶", "🎤", "🎧")),
        Def("hoa", "Hoa", listOf("🌸", "🌼", "🌷", "🌺")),
        Def("bay", "Bay bổng", listOf("🦋", "🕊️", "🪽", "☁️")),
        Def("trang", "Đêm trăng", listOf("🌙", "⭐", "🌌", "💫")),
    )

    fun get(id: String?): Def = all.firstOrNull { it.id == id } ?: all.first()

    /** Bộ biểu tượng đang dùng của dự án, kể cả bộ người dùng tự chọn ("custom"). */
    fun forDoc(doc: org.json.JSONObject): Def {
        val id = ProjectDesign.iconSetId(doc)
        if (id == "custom") {
            val l = ProjectDesign.customIcons(doc)
            if (l.isNotEmpty()) return Def("custom", "Của tôi", l)
        }
        return get(id)
    }

    fun next(id: String?): Def {
        val i = all.indexOfFirst { it.id == id }.coerceAtLeast(0)
        return all[(i + 1) % all.size]
    }
}

object Templates {
    val all: List<Template> = listOf(
        Template(
            "cinematic", "Điện ảnh", "serif", "normal", "#f7f0e4", null, 0.075, 0.82,
            listOf("fade", "slideUp"), listOf("fade"), "glow", false,
            Pair("#14213d", "#3a506b"), 0.22, null, 0.6, 0.09, Pair("dust", 25),
            listOf(Pair("bassReaction", """{"intensity":0.5}""")), 0.02, true,
        ),
        Template(
            "fantasy", "Mơ mộng", "sans-serif", "normal", "#ffffff", null, 0.08, 0.5,
            listOf("blurIn", "fade"), listOf("dissolve"), "float", false,
            Pair("#2b1055", "#7597de"), 0.1, Pair("#7a5cff", 0.18), 0.45, null, Pair("dust", 60),
            listOf(Pair("backgroundReaction", """{"intensity":0.6,"color":"#b388ff"}""")), 0.02, true,
        ),
        Template(
            "kinetic", "Chữ chuyển động", "sans-serif", "bold", "#ffffff", null, 0.11, 0.5,
            listOf("zoomIn", "slideLeft", "rotateIn", "glitchIn"), listOf("zoomOut", "fade", "slideUp"), "pulse", false,
            Pair("#111111", "#c2185b"), 0.35, null, 0.5, null, null,
            listOf(Pair("beatPulse", """{"intensity":0.7}"""), Pair("bassReaction", """{"intensity":0.9}""")), 0.05, false,
        ),
        Template(
            "minimal", "Tối giản", "sans-serif", "normal", "#ffffff", null, 0.07, 0.5,
            listOf("fade"), listOf("fade"), null, false,
            Pair("#101010", "#303030"), 0.45, null, null, null, null,
            emptyList(), 0.0, false,
        ),
        Template(
            "karaoke", "Karaoke", "Baloo 2", "800", "#ffffff", "#ff5c8a", 0.078, 0.78,
            listOf("fade", "slideUp", "zoomIn"), listOf("fade", "slideUp"), null, true,
            Pair("#1b1f3b", "#6a3d9a"), 0.4, null, 0.4, null, null,
            listOf(Pair("beatPulse", """{"intensity":0.3}""")), 0.0, true,
            stroke = "#12082b", bounce = true, icons = true, titleCard = true, emph = "#ffd84d",
        ),
        Template(
            "sweep", "Lyric lướt", "Montserrat", "800", "#ffffff", "#ffd54f", 0.1, 0.5,
            listOf("swipeIn", "swipeInUp"), listOf("swipeOut", "swipeOutUp"), null, true,
            Pair("#0f2027", "#2c5364"), 0.35, null, 0.5, null, null,
            listOf(Pair("beatPulse", """{"intensity":0.5}"""), Pair("bassReaction", """{"intensity":0.6}""")), 0.03, true,
            stroke = "rgba(0,0,0,0.55)", titleCard = true, emph = "#7df0ff",
        ),
        Template(
            "script", "Nét bút", "Dancing Script", "700", "#f4f8ff", "#ff4b5c", 0.1, 0.5,
            listOf("sliceIn", "blurIn", "fade"), listOf("sliceOut", "fade", "dissolve"), "glow", false,
            Pair("#050b1f", "#16305e"), 0.3, null, 0.65, null, Pair("dust", 40),
            listOf(Pair("bassReaction", """{"intensity":0.4}""")), 0.02, true,
            layout = "collage", glow = "rgba(110,180,255,0.95)",
            trailMs = 1100L, titleCard = true, ringEvery = 7,
        ),
    )

    /** Ba kiểu hiển thị cho người dùng chọn. Các template cũ vẫn hiểu được để mở dự án đã lưu. */
    val picker: List<Template> get() = listOf("karaoke", "sweep", "script").map { id -> all.first { it.id == id } }

    val ids: List<String> get() = all.map { it.id }

    fun get(id: String): Template = all.firstOrNull { it.id == id } ?: all.first()

    fun next(id: String): Template {
        val p = picker
        val i = p.indexOfFirst { it.id == id }
        return p[(i + 1) % p.size] // id cũ (không nằm trong 3 kiểu) rơi về kiểu đầu
    }
}
