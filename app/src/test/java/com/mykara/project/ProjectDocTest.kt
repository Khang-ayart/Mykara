package com.mykara.project

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProjectDocTest {

    private fun ids(doc: JSONObject): List<String> {
        val arr = doc.getJSONObject("lyrics").getJSONArray("lines")
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
    }

    private fun addTiming(doc: JSONObject, lineId: String, s: Long, e: Long, origin: String = "auto") {
        doc.getJSONObject("timing").getJSONArray("lines").put(
            JSONObject().put("lineId", lineId).put("startMs", s).put("endMs", e)
                .put("confidence", 0.9).put("origin", origin),
        )
    }

    private fun addSemantic(doc: JSONObject, itemId: String, lineId: String, kind: String = "emoji", ref: String = "❤️") {
        val design = doc.getJSONObject("design")
        val sem = design.optJSONObject("semanticLayer") ?: JSONObject().put("enabled", true).put("items", JSONArray())
            .also { design.put("semanticLayer", it) }
        sem.getJSONArray("items").put(
            JSONObject().put("id", itemId).put("lineId", lineId).put("tokenStart", 0).put("tokenEnd", 1)
                .put("visual", JSONObject().put("kind", kind).put("ref", ref))
                .put("phases", JSONArray().put(JSONObject().put("atToken", 0).put("preset", "popIn")))
                .put("textMode", "replace").put("enabled", true).put("origin", "auto"),
        )
    }

    private fun timingIds(doc: JSONObject): List<String> {
        val arr = doc.getJSONObject("timing").getJSONArray("lines")
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("lineId") }
    }

    private fun semanticIds(doc: JSONObject): List<String> {
        val sem = doc.getJSONObject("design").optJSONObject("semanticLayer") ?: return emptyList()
        val arr = sem.getJSONArray("items")
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
    }

    @Test
    fun newProjectHasAllRequiredBlocks() {
        val d = ProjectDoc.newProject("  Bài thử ")
        for (k in listOf("version", "meta", "assets", "lyrics", "timing", "design", "export")) assertTrue(k, d.has(k))
        assertEquals("Bài thử", ProjectDoc.title(d))
        assertEquals("Dự án mới", ProjectDoc.title(ProjectDoc.newProject("   ")))
    }

    @Test
    fun metaOptionalFieldsAreRemovedWhenBlank() {
        val d = ProjectDoc.newProject("A")
        ProjectDoc.setMeta(d, "A", "Ca sĩ", "Nhạc sĩ")
        assertEquals("Ca sĩ", ProjectDoc.artist(d))
        ProjectDoc.setMeta(d, "", "  ", "Nhạc sĩ")
        assertEquals("A", ProjectDoc.title(d)) // tên trống: giữ tên cũ
        assertFalse(d.getJSONObject("meta").has("artist"))
        assertEquals("Nhạc sĩ", ProjectDoc.composer(d))
    }

    @Test
    fun splitLyricsTrimsCollapsesAndDropsBlankLines() {
        assertEquals(listOf("a b", "c"), ProjectDoc.splitLyrics("  a   b \r\n\r\n c\n"))
        assertEquals(emptyList<String>(), ProjectDoc.splitLyrics(" \n\n "))
    }

    @Test
    fun setLyricsAssignsSequentialIds() {
        val d = ProjectDoc.newProject("A")
        val c = ProjectDoc.setLyrics(d, "một\nhai\nba")
        assertEquals(listOf("l1", "l2", "l3"), ids(d))
        assertEquals(3, c.added)
        assertEquals("một\nhai\nba", ProjectDoc.lyricsText(d))
    }

    @Test
    fun unchangedLyricsKeepIdsTimingAndDesign() {
        val d = ProjectDoc.newProject("A")
        ProjectDoc.setLyrics(d, "a b\nc d")
        addTiming(d, "l1", 1000, 2000, "user")
        addSemantic(d, "s1", "l2")
        val c = ProjectDoc.setLyrics(d, "a b\nc d")
        assertEquals(0, c.added + c.removed + c.edited.size)
        assertEquals(listOf("l1"), timingIds(d))
        assertEquals("user", d.getJSONObject("timing").getJSONArray("lines").getJSONObject(0).getString("origin"))
        assertEquals(listOf("s1"), semanticIds(d))
    }

    @Test
    fun editInPlaceKeepsIdFlagsTimingAndDropsSemantic() {
        val d = ProjectDoc.newProject("A")
        ProjectDoc.setLyrics(d, "a b\nc d\ne f")
        for ((i, id) in listOf("l1", "l2", "l3").withIndex()) addTiming(d, id, i * 1000L, i * 1000L + 900)
        d.getJSONObject("timing").getJSONArray("lines").getJSONObject(1)
            .put("words", JSONArray().put(JSONObject().put("text", "c").put("startMs", 1000).put("endMs", 1400)))
        addSemantic(d, "s2", "l2")
        addSemantic(d, "s3", "l3")

        val c = ProjectDoc.setLyrics(d, "a b\nc X\ne f")
        assertEquals(listOf("l1", "l2", "l3"), ids(d))
        assertEquals(listOf("l2"), c.edited)
        val t2 = d.getJSONObject("timing").getJSONArray("lines").getJSONObject(1)
        assertTrue(t2.getBoolean("flagged"))
        assertFalse(t2.has("words"))
        assertEquals(listOf("s3"), semanticIds(d)) // chỉ lớp của câu sửa bị bỏ
    }

    @Test
    fun insertingALineKeepsOtherIds() {
        val d = ProjectDoc.newProject("A")
        ProjectDoc.setLyrics(d, "a\nb")
        addTiming(d, "l1", 0, 500)
        addTiming(d, "l2", 600, 900)
        val c = ProjectDoc.setLyrics(d, "mới\na\nb")
        assertEquals(1, c.added)
        assertEquals(listOf("l3", "l1", "l2"), ids(d))
        assertEquals(listOf("l1", "l2"), timingIds(d))
    }

    @Test
    fun removingALineDropsItsTimingAndSemantics() {
        val d = ProjectDoc.newProject("A")
        ProjectDoc.setLyrics(d, "a\nb\nc")
        addTiming(d, "l1", 0, 500)
        addTiming(d, "l2", 600, 900)
        addSemantic(d, "s2", "l2")
        val c = ProjectDoc.setLyrics(d, "a\nc")
        assertEquals(1, c.removed)
        assertEquals(listOf("l1", "l3"), ids(d))
        assertEquals(listOf("l1"), timingIds(d))
        assertEquals(emptyList<String>(), semanticIds(d))
    }

    @Test
    fun newLineIdsContinueFromLargestExisting() {
        val d = ProjectDoc.newProject("A")
        ProjectDoc.setLyrics(d, "a\nb")
        ProjectDoc.setLyrics(d, "a")
        ProjectDoc.setLyrics(d, "a\nz")
        assertEquals(listOf("l1", "l2"), ids(d)) // id mới tính từ số lớn nhất đang còn; l2 cũ đã bị xóa cùng timing của nó
    }

    @Test
    fun imagesAddDedupeAndRemoveCleanReferences() {
        val d = ProjectDoc.newProject("A")
        val a = ProjectDoc.addImage(d, "assets/img1.jpg", "sha256:aa")
        val b = ProjectDoc.addImage(d, "assets/img2.jpg", "sha256:bb")
        assertEquals("img1", a)
        assertEquals("img2", b)
        assertEquals(a, ProjectDoc.addImage(d, "assets/x.jpg", "sha256:aa")) // cùng hash: không thêm
        assertEquals(2, ProjectDoc.images(d).size)

        d.getJSONObject("design").put(
            "background",
            JSONArray().put(JSONObject().put("imageId", "img1").put("fromMs", 0).put("toMs", 1000).put("origin", "auto"))
                .put(JSONObject().put("imageId", "img2").put("fromMs", 1000).put("toMs", 2000).put("origin", "auto")),
        )
        ProjectDoc.setLyrics(d, "a")
        addSemantic(d, "s1", "l1", kind = "userImage", ref = "img1")
        addSemantic(d, "s2", "l1", kind = "emoji", ref = "☁️")

        assertEquals("assets/img1.jpg", ProjectDoc.removeImage(d, "img1"))
        assertNull(ProjectDoc.removeImage(d, "img1"))
        assertEquals(listOf("img2"), ProjectDoc.images(d).map { it.id })
        assertEquals(1, d.getJSONObject("design").getJSONArray("background").length())
        assertEquals(listOf("s2"), semanticIds(d))
        assertEquals("img3", ProjectDoc.addImage(d, "assets/img3.jpg", "sha256:cc")) // không dùng lại img1
    }

    @Test
    fun changingAudioFlagsExistingTiming() {
        val d = ProjectDoc.newProject("A")
        ProjectDoc.setLyrics(d, "a")
        ProjectDoc.setAudio(d, "assets/audio.mp3", "sha256:11", 215000)
        addTiming(d, "l1", 0, 500, "user")
        ProjectDoc.setAudio(d, "assets/audio.mp3", "sha256:11", 215000) // cùng nhạc: không gắn cờ
        assertFalse(d.getJSONObject("timing").getJSONArray("lines").getJSONObject(0).has("flagged"))
        ProjectDoc.setAudio(d, "assets/audio.m4a", "sha256:22", null)
        val t = d.getJSONObject("timing").getJSONArray("lines").getJSONObject(0)
        assertTrue(t.getBoolean("flagged"))
        assertEquals("user", t.getString("origin")) // không bị ghi đè
        assertEquals("assets/audio.m4a", ProjectDoc.audioFile(d))
        assertNull(ProjectDoc.audioDurationMs(d))
    }

    /** Ghi một dự án mẫu để CI kiểm tra bằng schema thật (tools/validate.py). */
    @Test
    fun writeSampleProjectForSchemaCheck() {
        val d = ProjectDoc.newProject("Rượu Đắng")
        ProjectDoc.setMeta(d, "Rượu Đắng", "Ca sĩ", "Nhạc sĩ")
        ProjectDoc.setAudio(d, "assets/audio.m4a", "sha256:" + "ab".repeat(32), 215000)
        val img = ProjectDoc.addImage(d, "assets/img1.jpg", "sha256:" + "cd".repeat(32))
        ProjectDoc.setLyrics(d, "Mưa rơi trên mây trắng\nTrái tim vỡ tan\nGió thổi qua đồi")
        addTiming(d, "l1", 12400, 15800)
        addTiming(d, "l2", 16100, 19900, "user")
        d.getJSONObject("design").put(
            "background",
            JSONArray().put(JSONObject().put("imageId", img).put("fromMs", 0).put("toMs", 215000).put("origin", "auto")),
        )
        addSemantic(d, "s1", "l2")
        val out = File("build/test-output/sample_project.json")
        out.parentFile.mkdirs()
        out.writeText(d.toString(2), Charsets.UTF_8)
        assertTrue(out.length() > 0)
    }
}
