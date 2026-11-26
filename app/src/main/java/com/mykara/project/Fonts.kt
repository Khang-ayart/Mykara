package com.mykara.project

/** Font miễn phí (giấy phép OFL) có sẵn trong app, đều hỗ trợ đủ dấu tiếng Việt. File nằm ở assets/fonts. */
object Fonts {
    /**
     * family = tên hiển thị; css = danh sách font-family gửi cho WebView (font của người dùng kèm font dự phòng
     * để ký tự thiếu vẫn hiện được). vi: "full" | "partial" | "unknown" — độ phủ dấu tiếng Việt.
     */
    class Def(
        val id: String, val family: String, val weight: String, val label: String, val script: Boolean,
        val style: String = "sans", val builtIn: Boolean = true, val css: String = family,
        val author: String = "", val license: String = "SIL Open Font License 1.1 (Google Fonts)", val vi: String = "full", val variable: Boolean = false,
    )

    /** Phong cách dùng để phân loại trong thư viện. */
    val styles: List<Pair<String, String>> = listOf(
        "sans" to "Sans-serif", "serif" to "Serif", "handwriting" to "Viết tay", "calligraphy" to "Thư pháp",
        "gothic" to "Gothic", "display" to "Trang trí / tiêu đề", "mono" to "Monospace",
    )

    fun styleLabel(id: String): String = styles.firstOrNull { it.first == id }?.second ?: id

    /** Font người dùng nhập và font mặc định do người dùng chọn; UserFonts gắn vào lúc app khởi động. */
    @Volatile var userDefs: () -> List<Def> = { emptyList() }
    @Volatile var defaultId: () -> String? = { null }

    private val STYLE = mapOf(
        "montserrat" to "sans", "bevietnam" to "sans", "baloo" to "sans", "quicksand" to "sans", "oswald" to "sans", "nunito" to "sans",
        "playfair" to "serif", "yeseva" to "serif",
        "sriracha" to "handwriting", "merienda" to "handwriting", "playwrite" to "handwriting", "mali" to "handwriting",
        "pattaya" to "handwriting", "pacifico" to "handwriting",
        "dancing" to "calligraphy", "charm" to "calligraphy", "greatvibes" to "calligraphy", "playball" to "calligraphy",
        "italianno" to "calligraphy", "cormorant" to "calligraphy", "lobster" to "calligraphy",
        "anton" to "display", "bangers" to "display", "alfa" to "display", "bungee" to "display",
        "grenze" to "gothic", "jetbrains" to "mono",
    )

    private val base: List<Def> = listOf(
        Def("montserrat", "Montserrat", "800", "Montserrat (đậm, hiện đại)", false),
        Def("bevietnam", "Be Vietnam Pro", "700", "Be Vietnam Pro (gọn, dễ đọc)", false),
        Def("baloo", "Baloo 2", "700", "Baloo 2 (tròn, thân thiện)", false),
        Def("quicksand", "Quicksand", "700", "Quicksand (mảnh, tròn)", false),
        Def("oswald", "Oswald", "600", "Oswald (cao, hẹp)", false),
        Def("nunito", "Nunito", "800", "Nunito (mềm)", false),
        Def("dancing", "Dancing Script", "700", "Dancing Script (nét bút)", true),
        Def("charm", "Charm", "700", "Charm (thư pháp)", true),
        Def("lobster", "Lobster", "400", "Lobster (đậm, bay bướm)", true),
        Def("pacifico", "Pacifico", "400", "Pacifico (vui, tròn)", true),
        Def("playfair", "Playfair Display", "700", "Playfair Display (sang trọng)", false),
        Def("anton", "Anton", "400", "Anton (cao, đậm, mạnh)", false),
        Def("bangers", "Bangers", "400", "Bangers (truyện tranh)", false),
        Def("alfa", "Alfa Slab One", "400", "Alfa Slab One (chân dày)", false),
        Def("yeseva", "Yeseva One", "400", "Yeseva One (cổ điển)", false),
        Def("sriracha", "Sriracha", "400", "Sriracha (viết tay mộc)", true),
        Def("merienda", "Merienda", "700", "Merienda (thư pháp vui)", true),
        Def("greatvibes", "Great Vibes", "400", "Great Vibes (thư pháp mảnh)", true),
        Def("pattaya", "Pattaya", "400", "Pattaya (tròn, bay bướm)", true),
        Def("playwrite", "Playwrite VN", "400", "Playwrite VN (chữ học trò)", true),
        Def("playball", "Playball", "400", "Playball (bay bướm, nghiêng)", true),
        Def("italianno", "Italianno", "400", "Italianno (thanh mảnh, cổ điển)", true),
        Def("mali", "Mali", "600", "Mali (viết tay nghiêng)", true),
        Def("cormorant", "Cormorant Infant", "600", "Cormorant (sang, nghiêng)", true),
        Def("grenze", "Grenze Gotisch", "600", "Grenze Gotisch (gothic, huyền bí)", false),
        Def("jetbrains", "JetBrains Mono", "600", "JetBrains Mono (máy đánh chữ)", false),
        Def("bungee", "Bungee", "400", "Bungee (biển hiệu, đậm)", false),
    )

    /** Tên file trong assets/fonts của từng font có sẵn (theo family). */
    private val FILES = mapOf(
        "Dancing Script" to "DancingScript.ttf", "Charm" to "Charm-Bold.ttf", "Montserrat" to "Montserrat.ttf",
        "Be Vietnam Pro" to "BeVietnamPro-Bold.ttf", "Baloo 2" to "Baloo2.ttf", "Quicksand" to "Quicksand.ttf",
        "Oswald" to "Oswald.ttf", "Nunito" to "Nunito.ttf", "Grenze Gotisch" to "GrenzeGotisch.ttf",
        "JetBrains Mono" to "JetBrainsMono.ttf", "Bungee" to "Bungee-Regular.ttf", "Playball" to "Playball-Regular.ttf",
        "Italianno" to "Italianno-Regular.ttf", "Mali" to "Mali-SemiBoldItalic.ttf", "Cormorant Infant" to "CormorantInfant-Italic.ttf",
        "Lobster" to "Lobster-Regular.ttf", "Pacifico" to "Pacifico-Regular.ttf", "Playfair Display" to "PlayfairDisplay.ttf",
        "Anton" to "Anton-Regular.ttf", "Bangers" to "Bangers-Regular.ttf", "Alfa Slab One" to "AlfaSlabOne-Regular.ttf",
        "Yeseva One" to "YesevaOne-Regular.ttf", "Sriracha" to "Sriracha-Regular.ttf", "Merienda" to "Merienda.ttf",
        "Great Vibes" to "GreatVibes-Regular.ttf", "Pattaya" to "Pattaya-Regular.ttf", "Playwrite VN" to "PlaywriteVN.ttf",
    )

    /** Đường dẫn trong assets của font có sẵn; null với font người dùng. */
    fun assetPath(d: Def): String? = if (d.builtIn) FILES[d.family]?.let { "fonts/$it" } else null

    /** Font có sẵn trong app, đã gắn phong cách. */
    val all: List<Def> = base.map {
        Def(it.id, it.family, it.weight, it.label, it.script, STYLE[it.id] ?: "sans")
    }

    /** Có sẵn + của người dùng. */
    fun library(): List<Def> = all + userDefs()

    fun get(id: String?): Def? = if (id == null) null else all.firstOrNull { it.id == id } ?: userDefs().firstOrNull { it.id == id }

    /** Font mặc định người dùng đã chọn trong thư viện (nếu còn tồn tại). */
    fun defaultFont(): Def? = get(defaultId())

    /** Karaoke dùng một font cố định (chỉ đổi biểu tượng); các kiểu khác cho đổi font. */
    fun adjustable(t: Template): Boolean = t.id != "karaoke"

    /**
     * Danh sách font hợp với template: nét bút chỉ dùng font viết tay; lyric lướt (subkara) dùng tất cả
     * để chọn thoải mái; karaoke không đổi font nên danh sách rỗng.
     */
    fun forTemplate(t: Template): List<Def> = when {
        !adjustable(t) -> emptyList()
        t.layout == "collage" -> library().filter { it.script || it.style == "handwriting" || it.style == "calligraphy" }
        else -> library()
    }

    /** Font kế tiếp trong danh sách hợp với template; id không hợp lệ thì lấy font đầu. null nếu template không đổi font. */
    fun next(t: Template, currentId: String?): Def? {
        val l = forTemplate(t)
        if (l.isEmpty()) return null
        // Chưa chọn font nào = đang dùng font mặc định của template.
        val cur = currentId ?: l.firstOrNull { it.family == t.font }?.id
        val i = l.indexOfFirst { it.id == cur }
        return l[(i + 1) % l.size]
    }
}
