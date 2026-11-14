package com.mykara.project

/** Font miễn phí (giấy phép OFL) có sẵn trong app, đều hỗ trợ đủ dấu tiếng Việt. File nằm ở assets/fonts. */
object Fonts {
    class Def(val id: String, val family: String, val weight: String, val label: String, val script: Boolean)

    val all: List<Def> = listOf(
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
    )

    fun get(id: String?): Def? = all.firstOrNull { it.id == id }

    /** Karaoke dùng một font cố định (chỉ đổi biểu tượng); các kiểu khác cho đổi font. */
    fun adjustable(t: Template): Boolean = t.id != "karaoke"

    /**
     * Danh sách font hợp với template: nét bút chỉ dùng font viết tay; lyric lướt (subkara) dùng tất cả
     * để chọn thoải mái; karaoke không đổi font nên danh sách rỗng.
     */
    fun forTemplate(t: Template): List<Def> = when {
        !adjustable(t) -> emptyList()
        t.layout == "collage" -> all.filter { it.script }
        else -> all
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
