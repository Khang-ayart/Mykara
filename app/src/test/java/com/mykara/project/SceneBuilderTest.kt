package com.mykara.project

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SceneBuilderTest {
    private fun lib() = SemanticLibrary(JSONObject(File("../data/semantic_library.json").readText(Charsets.UTF_8)))
    private val url = { p: String -> "https://mykara.local/p/p1/$p" }

    private fun sample(timed: Boolean = true): JSONObject {
        val d = ProjectDoc.newProject("Rượu Đắng")
        ProjectDoc.setAudio(d, "assets/audio.m4a", "sha256:" + "ab".repeat(32), 60000)
        ProjectDoc.addImage(d, "assets/img1.jpg", "sha256:" + "01".repeat(32))
        ProjectDoc.addImage(d, "assets/img2.jpg", "sha256:" + "02".repeat(32))
        ProjectDoc.setLyrics(d, "Mưa rơi trên mây trắng\nTrái tim vỡ tan\nGió thổi qua đồi vắng")
        if (timed) {
            ProjectTiming.setFromTaps(
                d, TapSync.compute(listOf("l1", "l2", "l3"), listOf(22, 15, 19), listOf(2000, 8000, 14000), 60000),
            )
        }
        AutoDesign.applySemantic(d, lib())
        ProjectDesign.setTemplate(d, "cinematic", "user") // các kiểm thử bố cục dưới đây dựa trên template điện ảnh
        return d
    }

    private fun layers(scene: JSONObject, type: String): List<JSONObject> {
        val a = scene.getJSONArray("layers")
        return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.getString("type") == type }
    }

    private fun layer(scene: JSONObject, id: String): JSONObject =
        layers(scene, "text").first { it.getString("id") == id }

    private fun ints(a: JSONArray) = (0 until a.length()).map { a.getInt(it) }

    @Test
    fun buildsExpectedStructure() {
        val b = SceneBuilder.build(sample(), 1920, 1080, null, url)
        val s = b.scene
        assertFalse(b.tempTiming)
        assertEquals(0, b.untimedLines)
        assertEquals(1920, s.getInt("width"))
        assertEquals(60000L, s.getLong("durationMs"))
        assertEquals("https://mykara.local/p/p1/assets/audio.m4a", s.getJSONObject("audio").getString("file"))
        assertEquals(2, layers(s, "image").size)
        assertEquals(3, layers(s, "text").size)
        assertEquals(1, layers(s, "semantic").size)
        // thứ tự vẽ: ảnh nền trước, chữ sau, lớp phủ letterbox (điện ảnh) cuối cùng
        val types = (0 until s.getJSONArray("layers").length()).map { s.getJSONArray("layers").getJSONObject(it).getString("type") }
        assertTrue(types.indexOf("image") < types.indexOf("text"))
        assertTrue(types.indexOf("text") < types.indexOf("semantic"))
        assertEquals("overlay", types.last())
    }

    @Test
    fun imagesCrossfadeAndCoverTheWholeSong() {
        val s = SceneBuilder.build(sample(), 1280, 720, null, url).scene
        val imgs = layers(s, "image")
        assertEquals(0L, imgs[0].getLong("fromMs"))
        assertEquals(60000L, imgs[1].getLong("toMs"))
        assertTrue(imgs[1].getLong("fromMs") < imgs[0].getLong("toMs")) // chồng lên để mờ chuyển
        assertEquals("https://mykara.local/p/p1/assets/img1.jpg", imgs[0].getString("source"))
    }

    @Test
    fun wordsAreEvenlyTimedInsideTheLine() {
        val s = SceneBuilder.build(sample(), 1280, 720, null, url).scene
        val l = layer(s, "t-l1")
        val w = l.getJSONArray("words")
        assertEquals(5, w.length())
        assertEquals(1850L, w.getJSONObject(0).getLong("startMs")) // 2000 - 150 độ trễ gõ nhịp
        for (i in 1 until w.length()) {
            assertEquals(w.getJSONObject(i - 1).getLong("endMs"), w.getJSONObject(i).getLong("startMs"))
        }
        assertTrue(l.getLong("fromMs") < w.getJSONObject(0).getLong("startMs"))
        assertTrue(l.getLong("toMs") > w.getJSONObject(4).getLong("endMs") - 1)
    }

    @Test
    fun replacedWordsAreHiddenAndIconsAnchoredInline() {
        val s = SceneBuilder.build(sample(), 1280, 720, null, url).scene
        assertEquals(listOf(0, 3), ints(layer(s, "t-l1").getJSONArray("hideText")))
        val items = layers(s, "semantic")[0].getJSONArray("items")
        val rain = (0 until items.length()).map { items.getJSONObject(it) }.first { it.getJSONObject("visual").getString("ref") == "rain" }
        assertEquals("inline", rain.getJSONObject("anchor").getString("mode"))
        assertEquals("t-l1", rain.getJSONObject("anchor").getString("layer"))
        // hình bắt đầu đúng lúc từ "Mưa" bắt đầu
        assertEquals(1850L, rain.getLong("fromMs"))
    }

    @Test
    fun brokenHeartIsHeroAndShattersOnTheWordVo() {
        val s = SceneBuilder.build(sample(), 1280, 720, null, url).scene
        val items = layers(s, "semantic")[0].getJSONArray("items")
        val heart = (0 until items.length()).map { items.getJSONObject(it) }.first { it.getJSONObject("visual").getString("ref") == "heart" }
        assertEquals("hero", heart.getJSONObject("anchor").getString("mode"))
        val words = layer(s, "t-l2").getJSONArray("words")
        val shatter = heart.getJSONArray("phases").getJSONObject(1)
        assertEquals("shatter", shatter.getString("preset"))
        assertEquals(words.getJSONObject(2).getLong("startMs"), shatter.getLong("atMs")) // từ thứ 3 = "vỡ"
        // chữ vẫn hiện (textMode beside) nên không có hideText ở câu này
        assertFalse(layer(s, "t-l2").has("hideText"))
    }

    @Test
    fun withoutAnyTimingItBuildsATemporaryPreview() {
        val b = SceneBuilder.build(sample(timed = false), 1280, 720, null, url)
        assertTrue(b.tempTiming)
        assertEquals(3, layers(b.scene, "text").size)
        for (l in layers(b.scene, "text")) assertTrue(l.getLong("toMs") <= 60000L)
    }

    @Test
    fun partiallyTimedLinesAreSkippedAndReported() {
        val d = sample()
        val arr = d.getJSONObject("timing").getJSONArray("lines")
        val keep = JSONArray().put(arr.getJSONObject(0))
        d.getJSONObject("timing").put("lines", keep)
        val b = SceneBuilder.build(d, 1280, 720, null, url)
        assertFalse(b.tempTiming)
        assertEquals(2, b.untimedLines)
        assertEquals(1, layers(b.scene, "text").size)
    }

    @Test
    fun turningTheSemanticLayerOffBringsTextBack() {
        val d = sample()
        ProjectDesign.setSemanticEnabled(d, false)
        val s = SceneBuilder.build(d, 1280, 720, null, url).scene
        assertEquals(0, layers(s, "semantic").size)
        assertFalse(layer(s, "t-l1").has("hideText"))
    }

    @Test
    fun hiddenAndDisabledItemsAreNotBuilt() {
        val d = sample()
        ProjectDesign.editItem(d, "s1") { it.put("textMode", "hidden") }
        val s = SceneBuilder.build(d, 1280, 720, null, url).scene
        assertEquals(listOf(3), ints(layer(s, "t-l1").getJSONArray("hideText"))) // chỉ còn "mây"
        val items = layers(s, "semantic")[0].getJSONArray("items")
        assertTrue((0 until items.length()).none { items.getJSONObject(it).getString("id") == "s1" })
    }

    @Test
    fun besideModeKeepsTextAndPlacesIconAbove() {
        val d = sample()
        ProjectDesign.editItem(d, "s1") { it.put("textMode", "beside") }
        val s = SceneBuilder.build(d, 1280, 720, null, url).scene
        assertEquals(listOf(3), ints(layer(s, "t-l1").getJSONArray("hideText")))
        val items = layers(s, "semantic")[0].getJSONArray("items")
        val rain = (0 until items.length()).map { items.getJSONObject(it) }.first { it.getString("id") == "s1" }
        assertEquals("above", rain.getJSONObject("anchor").getString("mode"))
    }

    @Test
    fun concurrencyBudgetDropsExtraItemsAndKeepsTheirText() {
        val d = sample()
        ProjectDesign.ensureSemanticLayer(d).getJSONObject("budget").put("maxConcurrent", 1)
        val b = SceneBuilder.build(d, 1280, 720, null, url)
        assertTrue(b.droppedByBudget >= 1)
        // "mưa" (s1) giữ, "mây" (s2) bị bỏ → "mây" vẫn hiện chữ
        assertEquals(listOf(0), ints(layer(b.scene, "t-l1").getJSONArray("hideText")))
    }

    @Test
    fun userImageVisualResolvesToItsUrl() {
        val d = sample()
        ProjectDesign.editItem(d, "s2") { it.put("visual", JSONObject().put("kind", "userImage").put("ref", "img2")) }
        val s = SceneBuilder.build(d, 1280, 720, null, url).scene
        val items = layers(s, "semantic")[0].getJSONArray("items")
        val v = (0 until items.length()).map { items.getJSONObject(it) }.first { it.getString("id") == "s2" }.getJSONObject("visual")
        assertEquals("userImage", v.getString("kind"))
        assertEquals("https://mykara.local/p/p1/assets/img2.jpg", v.getString("ref"))
    }

    @Test
    fun portraitUsesWidthBasedFontSize() {
        val s = SceneBuilder.build(sample(), 720, 1280, null, url).scene
        val fs = layer(s, "t-l1").getJSONObject("style").getInt("fontSize")
        assertEquals(Math.round(0.075 * 720 * 0.56).toInt(), fs)
        assertEquals(619, layer(s, "t-l1").getJSONObject("style").getInt("maxWidth")) // 86% chiều rộng
    }

    @Test
    fun templatesChangeTheLookWithoutTouchingTiming() {
        val d = sample()
        ProjectDesign.setTemplate(d, "kinetic", "user")
        val k = SceneBuilder.build(d, 1280, 720, null, url).scene
        assertEquals("kinetic", k.getString("template"))
        assertTrue(layers(k, "reactive").any { it.getString("source") == "beatPulse" })
        val firstEffects = (1..3).map { i ->
            val anims = layer(k, "t-l$i").getJSONArray("animations")
            anims.getJSONObject(0).getString("effect")
        }
        assertEquals(3, firstEffects.toSet().size) // mỗi câu một kiểu vào khác nhau
        ProjectDesign.setTemplate(d, "karaoke", "user")
        val ka = SceneBuilder.build(d, 1280, 720, null, url).scene
        assertTrue(layer(ka, "t-l1").getJSONObject("params").getBoolean("karaoke"))
        // timing không đổi giữa hai template
        assertEquals(
            layer(k, "t-l2").getJSONArray("words").toString(),
            layer(ka, "t-l2").getJSONArray("words").toString(),
        )
    }

    @Test
    fun scriptTemplateUsesCollageAccentAndSliceEffects() {
        val d = sample()
        ProjectDesign.setTemplate(d, "script", "user")
        val s = SceneBuilder.build(d, 1280, 720, null, url).scene
        assertEquals("script", s.getString("template"))
        val l1 = layer(s, "t-l1")
        assertEquals("Dancing Script", l1.getJSONObject("style").getString("font"))
        assertEquals("collage", l1.getJSONObject("params").getString("layout"))
        // "Mưa rơi trên mây trắng": mưa(0) và mây(3) bị thay bằng hình → nhấn từ cuối "trắng"
        assertEquals(listOf(4), ints(l1.getJSONObject("params").getJSONArray("accent")))
        assertEquals("sliceIn", l1.getJSONArray("animations").getJSONObject(0).getString("effect"))
        // câu 2 chỉ có 3 từ, từ cuối "tan" đủ dài → nhấn "tan"
        assertEquals(listOf(3), ints(layer(s, "t-l2").getJSONObject("params").getJSONArray("accent")))
    }

    @Test
    fun accentPicksLastLongWordElseLongest() {
        assertEquals(null, SceneBuilder.accentIndex(listOf("em", "yêu"), emptySet()))
        assertEquals(3, SceneBuilder.accentIndex(listOf("anh", "nhớ", "em", "lắm"), emptySet()))
        assertEquals(1, SceneBuilder.accentIndex(listOf("anh", "thương", "em", "ơi"), emptySet()))
        assertEquals(1, SceneBuilder.accentIndex(listOf("anh", "nhớ", "em"), setOf(2)))
        assertEquals(null, SceneBuilder.accentIndex(listOf("a", "b", "c"), setOf(0, 1, 2)))
    }

    @Test
    fun flyingItemsStayLongEnoughToCrossTheScreen() {
        val d = ProjectDoc.newProject("A")
        ProjectDoc.setAudio(d, "assets/audio.m4a", "sha256:" + "ab".repeat(32), 60000)
        ProjectDoc.addImage(d, "assets/img1.jpg", "sha256:" + "01".repeat(32))
        ProjectDoc.setLyrics(d, "Như loài chim bay\nMột đàn chim bay")
        ProjectTiming.setFromTaps(d, TapSync.compute(listOf("l1", "l2"), listOf(14, 14), listOf(2000, 4000), 60000))
        AutoDesign.applySemantic(d, lib())
        val s = SceneBuilder.build(d, 1280, 720, null, url).scene
        val items = layers(s, "semantic")[0].getJSONArray("items")
        val flyAcross = (0 until items.length()).map { items.getJSONObject(it) }.first { it.getJSONArray("phases").getJSONObject(0).getString("preset") == "flyAcross" }
        assertTrue(flyAcross.getLong("toMs") - flyAcross.getJSONArray("phases").getJSONObject(0).getLong("atMs") >= 3200)
        // chữ "chim" còn nguyên vì textMode beside
        assertFalse(layer(s, "t-l1").has("hideText"))
    }

    @Test
    fun frameResolvesImageUrlAndKeepsVariant() {
        val d = ProjectDoc.newProject("A")
        ProjectDoc.setAudio(d, "assets/audio.m4a", "sha256:" + "ab".repeat(32), 60000)
        ProjectDoc.addImage(d, "assets/img1.jpg", "sha256:" + "01".repeat(32))
        ProjectDoc.setLyrics(d, "Bộ phim cũ")
        ProjectTiming.setFromTaps(d, TapSync.compute(listOf("l1"), listOf(8), listOf(2000), 60000))
        AutoDesign.applySemantic(d, lib())
        val s = SceneBuilder.build(d, 1280, 720, null, url).scene
        val v = layers(s, "semantic")[0].getJSONArray("items").getJSONObject(0).getJSONObject("visual")
        assertEquals("frame", v.getString("kind"))
        assertEquals("film", v.getString("variant"))
        assertEquals("https://mykara.local/p/p1/assets/img1.jpg", v.getString("ref"))
    }

    @Test
    fun pickerOffersThreeStylesAndOldOnesFallBackToTheFirst() {
        assertEquals(listOf("karaoke", "sweep", "script"), Templates.picker.map { it.id })
        assertEquals("sweep", Templates.next("karaoke").id)
        assertEquals("script", Templates.next("sweep").id)
        assertEquals("karaoke", Templates.next("script").id)
        assertEquals("karaoke", Templates.next("cinematic").id)
    }

    @Test
    fun sweepUsesSwipeEffects() {
        val d = sample()
        ProjectDesign.setTemplate(d, "sweep", "user")
        val l = layer(SceneBuilder.build(d, 1280, 720, null, url).scene, "t-l1")
        assertEquals("swipeIn", l.getJSONArray("animations").getJSONObject(0).getString("effect"))
        assertEquals("swipeOut", l.getJSONArray("animations").getJSONObject(1).getString("effect"))
        assertTrue(l.getJSONObject("params").getBoolean("karaoke"))
    }

    @Test
    fun fontOverrideChangesFamilyAndWeightAndOnlyFitsTheTemplateKind() {
        val d = sample()
        ProjectDesign.setTemplate(d, "sweep", "user")
        val k = Templates.get("sweep")
        assertTrue(Fonts.forTemplate(k).any { !it.script } && Fonts.forTemplate(k).any { it.script })
        ProjectDesign.setFont(d, Fonts.next(k, null)!!.id)
        val st = layer(SceneBuilder.build(d, 1280, 720, null, url).scene, "t-l1").getJSONObject("style")
        assertEquals("Be Vietnam Pro", st.getString("font")) // font sau Montserrat trong danh sách
        assertEquals("700", st.getString("weight"))
        ProjectDesign.setTemplate(d, "script", "user")
        assertTrue(Fonts.forTemplate(Templates.get("script")).all { it.script })
        assertEquals(null, ProjectDesign.fontId(d)) // đổi template thì font bắt đầu lại
    }

    @Test
    fun karaokeKeepsItsFontAndBouncesWithIconsThatFollowTheIconSet() {
        val d = sample()
        ProjectDesign.setTemplate(d, "karaoke", "user")
        val k = Templates.get("karaoke")
        assertTrue(Fonts.forTemplate(k).isEmpty())
        assertEquals(null, Fonts.next(k, null))
        ProjectDesign.setFont(d, "oswald") // dù có lưu font thì karaoke vẫn dùng font cố định
        val l1 = layer(SceneBuilder.build(d, 1280, 720, null, url).scene, "t-l1")
        assertEquals("Baloo 2", l1.getJSONObject("style").getString("font"))
        assertTrue(l1.getJSONObject("style").has("strokeColor"))
        val p = l1.getJSONObject("params")
        assertTrue(p.getBoolean("bounce"))
        assertEquals(2, p.getJSONArray("icons").length())
        val before = p.getJSONArray("icons").getString(0)
        ProjectDesign.setIconSet(d, "hoa")
        val after = layer(SceneBuilder.build(d, 1280, 720, null, url).scene, "t-l1").getJSONObject("params").getJSONArray("icons").getString(0)
        assertTrue(before != after)
        assertTrue(IconSets.get("hoa").emojis.contains(after))
    }

    @Test
    fun regenerateVariantChangesChoicesButNotTiming() {
        val d = sample()
        ProjectDesign.setTemplate(d, "karaoke", "user")
        val a = SceneBuilder.build(d, 1280, 720, null, url).scene
        ProjectDesign.bumpVariant(d)
        val b = SceneBuilder.build(d, 1280, 720, null, url).scene
        assertEquals(1, ProjectDesign.variant(d))
        assertEquals(layer(a, "t-l2").getJSONArray("words").toString(), layer(b, "t-l2").getJSONArray("words").toString())
        val ea = layer(a, "t-l1").getJSONArray("animations").getJSONObject(0).getString("effect")
        val eb = layer(b, "t-l1").getJSONArray("animations").getJSONObject(0).getString("effect")
        assertTrue("kiểu vào phải đổi sau khi làm lại", ea != eb)
        assertTrue(layers(a, "image")[0].getString("source") != layers(b, "image")[0].getString("source"))
        // cùng template thì giữ lần làm lại; đổi template thì bắt đầu mới
        ProjectDesign.setTemplate(d, "karaoke", "user")
        assertEquals(1, ProjectDesign.variant(d))
        ProjectDesign.setTemplate(d, "sweep", "user")
        assertEquals(0, ProjectDesign.variant(d))
    }

    @Test
    fun accentRotatesWithVariant() {
        val toks = listOf("anh", "thương", "em", "mãi", "mãi")
        val a0 = SceneBuilder.accentIndex(toks, emptySet(), 0)
        val a1 = SceneBuilder.accentIndex(toks, emptySet(), 1)
        assertTrue(a0 != a1)
    }

    @Test
    fun noImagesUsesATemplateGradient() {
        val d = sample()
        ProjectDoc.removeImage(d, "img1")
        ProjectDoc.removeImage(d, "img2")
        val s = SceneBuilder.build(d, 1280, 720, null, url).scene
        assertEquals(0, layers(s, "image").size)
        assertTrue(layers(s, "overlay").any { it.getString("source") == "gradient" })
    }

    @Test
    fun designOverridesWin() {
        val d = sample()
        d.getJSONObject("design").put(
            "lines",
            JSONArray().put(
                JSONObject().put("lineId", "l1").put("origin", "user")
                    .put("enter", JSONObject().put("effect", "typewriter"))
                    .put("layout", JSONObject().put("y", 0.3).put("color", "#ff0000")),
            ),
        )
        val l = layer(SceneBuilder.build(d, 1280, 720, null, url).scene, "t-l1")
        assertEquals("typewriter", l.getJSONArray("animations").getJSONObject(0).getString("effect"))
        assertEquals(0.3, l.getJSONObject("style").getDouble("y"), 1e-9)
        assertEquals("#ff0000", l.getJSONObject("style").getString("color"))
    }

    @Test
    fun analysisIsPassedThrough() {
        val a = JSONObject().put("beatsMs", JSONArray().put(500).put(1000))
        val s = SceneBuilder.build(sample(), 1280, 720, a, url).scene
        assertEquals(2, s.getJSONObject("analysis").getJSONArray("beatsMs").length())
    }

    /** Ghi cảnh mẫu để CI kiểm tra bằng scene.schema.json và để xem thử bằng engine. */
    @Test
    fun writeSamplesForSchemaCheck() {
        val dir = File("build/test-output").apply { mkdirs() }
        val analysis = JSONObject().put("beatsMs", JSONArray((500..59500 step 500).toList()))
            .put("bpm", 120.0)
            .put("bassEnvelope", JSONObject().put("hz", 30).put("values", JSONArray(List(1800) { i -> ((i % 15) / 15.0 * 0.9) })))
        val land = SceneBuilder.build(sample(), 1280, 720, analysis, url).scene
        File(dir, "sample_scene.json").writeText(land.toString(2), Charsets.UTF_8)
        val d = sample()
        ProjectDesign.setAspect(d, "9:16")
        ProjectDesign.setTemplate(d, "karaoke", "user")
        File(dir, "sample_scene_portrait.json").writeText(
            SceneBuilder.build(d, 720, 1280, analysis, url).scene.toString(2), Charsets.UTF_8,
        )
        val ds = sample()
        ProjectDesign.setTemplate(ds, "script", "user")
        File(dir, "sample_scene_script.json").writeText(
            SceneBuilder.build(ds, 1280, 720, analysis, url).scene.toString(2), Charsets.UTF_8,
        )
        // dự án đầy đủ (có timing và lớp hình) cũng được kiểm tra bằng project.schema.json
        File(dir, "sample_project_full.json").writeText(sample().toString(2), Charsets.UTF_8)
        assertTrue(File(dir, "sample_scene.json").length() > 0)
    }

    @Test
    fun userEmphasisOverridesAccentAndAppliesToKaraokeToo() {
        val d = sample()
        ProjectDesign.setTemplate(d, "karaoke", "user")
        val id = "l1"
        assertEquals(null, ProjectDesign.emphasis(d, id))
        assertFalse(layer(SceneBuilder.build(d, 1280, 720, null, url).scene, "t-l1").getJSONObject("params").has("accent"))
        ProjectDesign.setEmphasis(d, id, listOf(3, 1))
        val k = layer(SceneBuilder.build(d, 1280, 720, null, url).scene, "t-l1")
        assertEquals(listOf(1, 3), ints(k.getJSONObject("params").getJSONArray("accent")))
        assertTrue(k.getJSONObject("style").has("emphasisColor"))
        // nét bút: lựa chọn của người dùng thay cách tự động, mảng rỗng = không nhấn từ nào
        ProjectDesign.setTemplate(d, "script", "user")
        ProjectDesign.setEmphasis(d, id, listOf(0))
        assertEquals(listOf(0), ints(layer(SceneBuilder.build(d, 1280, 720, null, url).scene, "t-l1").getJSONObject("params").getJSONArray("accent")))
        ProjectDesign.setEmphasis(d, id, emptyList())
        assertEquals(0, layer(SceneBuilder.build(d, 1280, 720, null, url).scene, "t-l1").getJSONObject("params").getJSONArray("accent").length())
        ProjectDesign.setEmphasis(d, id, null)
        assertEquals(null, ProjectDesign.emphasis(d, id))
    }

    @Test
    fun karaokeAndSweepAlsoGetATitleCardWithCredits() {
        for (tpl in listOf("karaoke", "sweep")) {
            val d = sample()
            ProjectDoc.setMeta(d, "Rượu Đắng", "Khang", "Nam")
            ProjectDesign.setTemplate(d, tpl, "user")
            val cards = layers(SceneBuilder.build(d, 1280, 720, null, url).scene, "title")
            assertTrue("$tpl phải có thẻ tiêu đề", cards.isNotEmpty())
            assertTrue(cards[0].getJSONObject("style").getBoolean("plain"))
            assertEquals(2, cards[0].getJSONArray("credits").length())
        }
    }
}
