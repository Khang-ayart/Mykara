package com.mykara.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FontInfoTest {
    private fun asset(name: String) = File("src/main/assets/fonts/$name").readBytes()
    private fun resource(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()

    @Test
    fun readsNameAndFullVietnameseCoverageOfABuiltInFont() {
        val i = FontInfo.parse(asset("Anton-Regular.ttf"))!!
        assertEquals("ttf", i.format)
        assertEquals("Anton", i.family)
        assertEquals("full", i.vietnamese)
        assertEquals(0, i.vietnameseMissing)
        assertFalse(i.variable)
    }

    @Test
    fun everyBuiltInFontIsAValidFontWithFullVietnamese() {
        for (f in File("src/main/assets/fonts").listFiles { x -> x.extension == "ttf" }!!) {
            val i = FontInfo.parse(f.readBytes())
            assertNotNull("${f.name} không đọc được", i)
            assertEquals("${f.name} thiếu chữ Việt", "full", i!!.vietnamese)
        }
    }

    @Test
    fun detectsVariableFonts() {
        assertTrue(FontInfo.parse(asset("JetBrainsMono.ttf"))!!.variable)
    }

    @Test
    fun reportsMissingVietnameseLetters() {
        assertEquals("none", FontInfo.parse(resource("ascii-only.ttf"))!!.vietnamese)
        val p = FontInfo.parse(resource("partial-vn.ttf"))!!
        assertEquals("partial", p.vietnamese)
        assertTrue(p.vietnameseMissing in 1 until p.vietnameseTotal)
    }

    @Test
    fun otfSignatureIsRecognised() {
        val b = asset("Anton-Regular.ttf").copyOf()
        b[0] = 'O'.code.toByte(); b[1] = 'T'.code.toByte(); b[2] = 'T'.code.toByte(); b[3] = 'O'.code.toByte()
        assertEquals("otf", FontInfo.parse(b)!!.format)
    }

    @Test
    fun brokenOrForeignFilesAreRejectedWithoutThrowing() {
        assertNull(FontInfo.parse(ByteArray(0)))
        assertNull(FontInfo.parse(ByteArray(500) { 7 }))
        assertNull(FontInfo.parse("wOFF".toByteArray() + ByteArray(200)))
        assertNull(FontInfo.parse("wOF2".toByteArray() + ByteArray(200)))
        assertNull(FontInfo.parse(asset("Anton-Regular.ttf").copyOf(300))) // cắt cụt
        val corrupt = asset("Anton-Regular.ttf").copyOf()
        for (k in 12 until 200) corrupt[k] = 0xFF.toByte() // bảng bị phá
        assertNull(FontInfo.parse(corrupt))
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0, 0, 13)
        assertNull(FontInfo.parse(png))
    }
}
