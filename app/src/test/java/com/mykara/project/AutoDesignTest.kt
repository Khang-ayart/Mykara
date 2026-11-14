package com.mykara.project

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AutoDesignTest {
    private fun lib() = SemanticLibrary(JSONObject(File("../data/semantic_library.json").readText(Charsets.UTF_8)))

    private fun doc(lyrics: String): JSONObject {
        val d = ProjectDoc.newProject("A")
        ProjectDoc.setLyrics(d, lyrics)
        return d
    }

    private fun items(d: JSONObject, lineId: String) = ProjectDesign.semanticItems(d).filter { it.getString("lineId") == lineId }

    private fun presets(item: JSONObject): List<String> {
        val a = item.getJSONArray("phases")
        return (0 until a.length()).map { a.getJSONObject(it).getString("preset") }
    }

    @Test
    fun libraryLoads() {
        val l = lib()
        assertEquals(17, l.visuals.size)
        assertTrue(l.chains.size >= 8)
        assertTrue(l.entries.size >= 30)
        assertEquals("Trái tim", l.visual("heart")!!.label)
        // cụm dài đứng trước cụm ngắn
        assertTrue(l.entries.first().tokens.size >= l.entries.last().tokens.size)
    }

    @Test
    fun picksRainAndCloudInsideSentence() {
        val d = doc("Mưa rơi trên mây trắng")
        val r = AutoDesign.applySemantic(d, lib())
        assertEquals(2, r.added)
        val its = items(d, "l1")
        assertEquals(listOf(0, 3), its.map { it.getInt("tokenStart") })
        assertEquals(listOf("rain", "cloud"), its.map { it.getJSONObject("visual").getString("ref") })
        assertTrue(its.all { it.getString("textMode") == "replace" && it.getBoolean("enabled") && it.getString("origin") == "auto" })
        assertEquals(listOf("popIn", "rainFall"), presets(its[0]))
    }

    @Test
    fun brokenHeartShattersOnTheWordVo() {
        val d = doc("Trái tim vỡ tan")
        AutoDesign.applySemantic(d, lib())
        val it = items(d, "l1").single()
        assertEquals("heart", it.getJSONObject("visual").getString("ref"))
        assertEquals(0, it.getInt("tokenStart"))
        assertEquals(4, it.getInt("tokenEnd"))
        assertEquals("beside", it.getString("textMode")) // phủ cả câu: giữ chữ bên cạnh
        val ph = it.getJSONArray("phases")
        assertEquals("shatter", ph.getJSONObject(1).getString("preset"))
        assertEquals(2, ph.getJSONObject(1).getInt("atToken")) // đúng từ "vỡ"
    }

    @Test
    fun matchesIgnoreCaseAndPunctuation() {
        val d = doc("MƯA, mưa!")
        AutoDesign.applySemantic(d, lib())
        assertEquals(2, items(d, "l1").size)
    }

    @Test
    fun ambiguousWordIsSuggestedButDisabled() {
        val d = doc("Sao đêm nay")
        AutoDesign.applySemantic(d, lib())
        val it = items(d, "l1").single()
        assertFalse(it.getBoolean("enabled"))
        assertTrue(it.getDouble("confidence") < 0.5)
        // "ngôi sao" thì rõ nghĩa nên bật
        val d2 = doc("Ngôi sao đêm")
        AutoDesign.applySemantic(d2, lib())
        assertTrue(items(d2, "l1").single().getBoolean("enabled"))
    }

    @Test
    fun wordsInsideOtherWordsDoNotMatch() {
        val d = doc("Mưa rào mây mù hoàng hôn") // "hoa" không được khớp trong "hoàng"
        AutoDesign.applySemantic(d, lib())
        assertEquals(listOf("rain", "cloud"), items(d, "l1").map { it.getJSONObject("visual").getString("ref") })
    }

    @Test
    fun rerunKeepsUserItemsAndNeverDoublesUp() {
        val d = doc("Mưa rơi trên mây trắng")
        AutoDesign.applySemantic(d, lib())
        // người dùng đổi hình của "mưa" thành sao và tắt "mây"
        ProjectDesign.editItem(d, "s1") { it.getJSONObject("visual").put("ref", "star") }
        ProjectDesign.editItem(d, "s2") { it.put("enabled", false) }
        val r = AutoDesign.applySemantic(d, lib())
        assertEquals(2, r.keptUser)
        assertEquals(0, r.added) // cả hai vị trí đã do người dùng quyết định
        val its = items(d, "l1")
        assertEquals(2, its.size)
        assertEquals("star", its[0].getJSONObject("visual").getString("ref"))
        assertFalse(its[1].getBoolean("enabled"))
        assertEquals(its.map { it.getString("id") }.toSet().size, its.size)
    }

    @Test
    fun rerunReplacesAutoItemsWithoutDuplicates() {
        val d = doc("Mưa rơi trên mây trắng\nGió thổi")
        AutoDesign.applySemantic(d, lib())
        val n = ProjectDesign.semanticItems(d).size
        AutoDesign.applySemantic(d, lib())
        AutoDesign.applySemantic(d, lib())
        val all = ProjectDesign.semanticItems(d)
        assertEquals(n, all.size)
        assertEquals(all.map { it.getString("id") }.toSet().size, all.size)
    }

    @Test
    fun atMostThreeItemsPerLine() {
        val d = doc("mưa mây gió nắng trăng lửa hoa")
        AutoDesign.applySemantic(d, lib())
        assertEquals(3, items(d, "l1").size)
    }

    @Test
    fun birdFliesAcrossAndFlockFliesAsGroup() {
        val d = doc("Như loài chim bay\nMột đàn chim bay")
        AutoDesign.applySemantic(d, lib())
        val a = items(d, "l1").single()
        assertEquals("bird", a.getJSONObject("visual").getString("ref"))
        assertEquals(listOf("flyAcross"), presets(a))
        assertEquals("beside", a.getString("textMode")) // chim bay ngang, chữ vẫn còn
        val b = items(d, "l2").single()
        assertEquals(listOf("flock"), presets(b))
        assertEquals(1, b.getInt("tokenStart")) // "đàn chim" là cụm 2 từ, không phải "chim" lẻ
        assertEquals(3, b.getInt("tokenEnd"))
    }

    @Test
    fun filmFrameTakesUserImagesInTurn() {
        val d = ProjectDoc.newProject("A")
        ProjectDoc.addImage(d, "assets/a.jpg", "sha256:" + "01".repeat(32))
        ProjectDoc.addImage(d, "assets/b.jpg", "sha256:" + "02".repeat(32))
        ProjectDoc.setLyrics(d, "Bộ phim cũ\nTấm ảnh xưa\nBức ảnh nhòe")
        AutoDesign.applySemantic(d, lib())
        val v = ProjectDesign.semanticItems(d).map { it.getJSONObject("visual") }
        assertEquals(listOf("film", "polaroid", "polaroid"), v.map { it.getString("variant") })
        assertEquals(listOf("frame", "frame", "frame"), v.map { it.getString("kind") })
        assertEquals(listOf("img1", "img2", "img1"), v.map { it.getString("ref") })
    }

    @Test
    fun bareFilmWordIsOnlyASuggestion() {
        val d = doc("Đi xem phim")
        AutoDesign.applySemantic(d, lib())
        assertFalse(items(d, "l1").single().getBoolean("enabled"))
    }

    @Test
    fun templateFollowsTempoUnlessUserChose() {
        val d = ProjectDoc.newProject("A")
        assertEquals("sweep", AutoDesign.applyTemplate(d, 130.0))
        assertEquals("karaoke", AutoDesign.applyTemplate(d, 90.0))
        assertEquals("karaoke", AutoDesign.applyTemplate(d, null))
        ProjectDesign.setTemplate(d, "karaoke", "user")
        assertEquals("karaoke", AutoDesign.applyTemplate(d, 150.0))
        assertNotNull(Templates.get("không-có")) // id lạ: rơi về template đầu
    }

    @Test
    fun rerunDropsUserItemsOfDeletedOrShortenedLines() {
        val d = doc("trời mưa rơi\nmây gió kéo về")
        val l = lib()
        AutoDesign.applySemantic(d, l)
        for (it in ProjectDesign.semanticItems(d)) ProjectDesign.editItem(d, it.getString("id")) { o -> o.put("enabled", false) }
        val before = ProjectDesign.semanticItems(d).size
        assertTrue(before > 0)
        ProjectDoc.setLyrics(d, "nắng") // câu 1 ngắn lại, câu 2 bị xóa
        val r = AutoDesign.applySemantic(d, l)
        assertEquals(0, r.keptUser)
        assertTrue(ProjectDesign.semanticItems(d).none { it.optString("origin") == "user" })
    }
}
