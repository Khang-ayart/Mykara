package com.mykara.project

import java.nio.ByteBuffer

/**
 * Đọc thông tin cơ bản từ file TTF/OTF (bảng name, cmap): tên, tác giả, giấy phép và độ phủ dấu tiếng Việt.
 * Thuần Kotlin, không phụ thuộc Android. Không bao giờ ném lỗi: file hỏng trả về null.
 * Chỉ đọc, không thay đổi file.
 */
object FontInfo {
    class Info(
        val format: String,          // "ttf" | "otf"
        val family: String?,
        val fullName: String?,
        val author: String?,
        val license: String?,
        val vietnameseMissing: Int,  // số chữ cái tiếng Việt font không có
        val vietnameseTotal: Int,
        val variable: Boolean,       // có trục độ đậm (fvar)
    ) {
        /** "full" | "partial" | "none" */
        val vietnamese: String
            get() = when {
                vietnameseMissing == 0 -> "full"
                vietnameseMissing >= vietnameseTotal -> "none"
                else -> "partial"
            }
    }

    /** Mọi chữ cái tiếng Việt có dấu/biến âm (chữ hoa và thường). */
    val VIETNAMESE: List<Int> = buildList {
        addAll(0x1EA0..0x1EF9)
        addAll(listOf(0xC0, 0xC1, 0xC2, 0xC3, 0xC8, 0xC9, 0xCA, 0xCC, 0xCD, 0xD2, 0xD3, 0xD4, 0xD5, 0xD9, 0xDA, 0xDD))
        addAll(listOf(0xE0, 0xE1, 0xE2, 0xE3, 0xE8, 0xE9, 0xEA, 0xEC, 0xED, 0xF2, 0xF3, 0xF4, 0xF5, 0xF9, 0xFA, 0xFD))
        addAll(listOf(0x102, 0x103, 0x110, 0x111, 0x128, 0x129, 0x168, 0x169, 0x1A0, 0x1A1, 0x1AF, 0x1B0))
    }

    private class Table(val offset: Int, val length: Int)

    fun parse(bytes: ByteArray): Info? = try {
        parseOrThrow(bytes)
    } catch (_: Exception) {
        null
    }

    private fun parseOrThrow(bytes: ByteArray): Info? {
        if (bytes.size < 12) return null
        val b = ByteBuffer.wrap(bytes) // big-endian
        val tag = b.getInt(0)
        val format = when (tag) {
            0x00010000, 0x74727565 -> "ttf" // "true"
            0x4F54544F -> "otf"             // "OTTO"
            else -> return null            // gồm cả "ttcf", woff, woff2
        }
        val n = b.getShort(4).toInt() and 0xFFFF
        if (n == 0 || 12 + n * 16 > bytes.size) return null
        val tables = HashMap<String, Table>()
        for (i in 0 until n) {
            val o = 12 + i * 16
            val name = String(bytes, o, 4, Charsets.ISO_8859_1)
            val off = b.getInt(o + 8)
            val len = b.getInt(o + 12)
            if (off < 0 || len < 0 || off.toLong() + len > bytes.size) return null
            tables[name] = Table(off, len)
        }
        // Font dùng được phải có bảng ký tự, đầu và dữ liệu nét.
        if (!tables.containsKey("cmap") || !tables.containsKey("head")) return null
        if (!tables.containsKey("glyf") && !tables.containsKey("CFF ") && !tables.containsKey("CFF2")) return null

        val has = glyphLookup(b, tables.getValue("cmap")) ?: return null
        val missing = VIETNAMESE.count { !has(it) }
        val names = tables["name"]?.let { readNames(b, it) } ?: emptyMap()
        val family = names[16] ?: names[1]
        return Info(
            format = format,
            family = family,
            fullName = names[4],
            author = names[9] ?: names[8],
            license = names[13] ?: names[14],
            vietnameseMissing = missing,
            vietnameseTotal = VIETNAMESE.size,
            variable = tables.containsKey("fvar"),
        )
    }

    /** Trả về hàm kiểm tra "font có glyph cho ký tự này không" từ bảng cmap (định dạng 4 và 12). */
    private fun glyphLookup(b: ByteBuffer, cmap: Table): ((Int) -> Boolean)? {
        val base = cmap.offset
        val count = b.getShort(base + 2).toInt() and 0xFFFF
        var best = -1
        var bestRank = -1
        for (i in 0 until count) {
            val o = base + 4 + i * 8
            val platform = b.getShort(o).toInt() and 0xFFFF
            val enc = b.getShort(o + 2).toInt() and 0xFFFF
            val sub = base + b.getInt(o + 4)
            if (sub < base || sub + 4 > b.limit()) continue
            val fmt = b.getShort(sub).toInt() and 0xFFFF
            val rank = when {
                platform == 3 && enc == 10 && fmt == 12 -> 4
                platform == 0 && fmt == 12 -> 3
                platform == 3 && enc == 1 && fmt == 4 -> 2
                platform == 0 && fmt == 4 -> 1
                else -> -1
            }
            if (rank > bestRank) { bestRank = rank; best = sub }
        }
        if (best < 0) return null
        val fmt = b.getShort(best).toInt() and 0xFFFF
        return if (fmt == 12) {
            val groups = b.getInt(best + 12)
            if (groups < 0 || best + 16 + groups.toLong() * 12 > b.limit()) return null
            val f12: (Int) -> Boolean = { cp: Int ->
                var found = false
                for (g in 0 until groups) {
                    val o = best + 16 + g * 12
                    val s = b.getInt(o)
                    val e = b.getInt(o + 4)
                    if (cp in s..e) { found = b.getInt(o + 8) + (cp - s) != 0; break }
                }
                found
            }
            f12
        } else {
            val segX2 = b.getShort(best + 6).toInt() and 0xFFFF
            val seg = segX2 / 2
            val endO = best + 14
            val startO = endO + segX2 + 2
            val deltaO = startO + segX2
            val rangeO = deltaO + segX2
            if (rangeO + segX2 > b.limit()) return null
            val f4: (Int) -> Boolean = { cp: Int ->
                var found = false
                if (cp <= 0xFFFF) {
                    for (i in 0 until seg) {
                        val end = b.getShort(endO + i * 2).toInt() and 0xFFFF
                        if (cp > end) continue
                        val start = b.getShort(startO + i * 2).toInt() and 0xFFFF
                        if (cp < start) break
                        val delta = b.getShort(deltaO + i * 2).toInt()
                        val ro = b.getShort(rangeO + i * 2).toInt() and 0xFFFF
                        val gid = if (ro == 0) (cp + delta) and 0xFFFF else {
                            val p = rangeO + i * 2 + ro + (cp - start) * 2
                            if (p + 2 > b.limit()) 0 else {
                                val g = b.getShort(p).toInt() and 0xFFFF
                                if (g == 0) 0 else (g + delta) and 0xFFFF
                            }
                        }
                        found = gid != 0
                        break
                    }
                }
                found
            }
            f4
        }
    }

    /** Đọc các chuỗi trong bảng name; ưu tiên tiếng Anh (Windows 0x409), sau đó bất kỳ. */
    private fun readNames(b: ByteBuffer, t: Table): Map<Int, String> {
        val base = t.offset
        val count = b.getShort(base + 2).toInt() and 0xFFFF
        val strings = base + (b.getShort(base + 4).toInt() and 0xFFFF)
        val out = HashMap<Int, Pair<Int, String>>() // nameId -> (rank, text)
        for (i in 0 until count) {
            val o = base + 6 + i * 12
            if (o + 12 > b.limit()) break
            val platform = b.getShort(o).toInt() and 0xFFFF
            val lang = b.getShort(o + 4).toInt() and 0xFFFF
            val id = b.getShort(o + 6).toInt() and 0xFFFF
            val len = b.getShort(o + 8).toInt() and 0xFFFF
            val off = strings + (b.getShort(o + 10).toInt() and 0xFFFF)
            if (off < 0 || off + len > b.limit() || len == 0) continue
            val raw = ByteArray(len)
            for (k in 0 until len) raw[k] = b.get(off + k)
            val text = when (platform) {
                0, 3 -> String(raw, Charsets.UTF_16BE)
                1 -> String(raw, Charsets.ISO_8859_1)
                else -> continue
            }.trim().replace(Regex("\\s+"), " ")
            if (text.isEmpty()) continue
            val rank = when {
                platform == 3 && lang == 0x409 -> 3
                platform == 1 && lang == 0 -> 2
                else -> 1
            }
            if (out[id] == null || out.getValue(id).first < rank) out[id] = rank to text
        }
        return out.mapValues { it.value.second }
    }
}
