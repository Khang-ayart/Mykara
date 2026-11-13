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
    )

    fun get(id: String?): Def? = all.firstOrNull { it.id == id }

    /** Danh sách font hợp với template (nét bút dùng font viết tay, còn lại dùng font không chân). */
    fun forTemplate(t: Template): List<Def> = all.filter { it.script == (t.layout == "collage") }

    /** Font kế tiếp trong danh sách hợp với template; id không hợp lệ thì lấy font đầu. */
    fun next(t: Template, currentId: String?): Def {
        val l = forTemplate(t)
        // Chưa chọn font nào = đang dùng font mặc định của template.
        val cur = currentId ?: l.firstOrNull { it.family == t.font }?.id
        val i = l.indexOfFirst { it.id == cur }
        return l[(i + 1) % l.size]
    }
}
