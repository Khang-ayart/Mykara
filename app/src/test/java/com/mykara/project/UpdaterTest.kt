package com.mykara.project

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdaterTest {
    @Test
    fun parsesBuildNumberAndApkUrl() {
        val j = JSONObject(
            """{"body":"Bản dựng thử tự động từ commit abc (build 42)","assets":[{"name":"notes.txt","browser_download_url":"x"},{"name":"Mykara-prototype.apk","browser_download_url":"https://h/a.apk","size":123}]}""",
        )
        val l = Updater.parseRelease(j)!!
        assertEquals(42, l.build)
        assertEquals("https://h/a.apk", l.apkUrl)
        assertEquals(123L, l.sizeBytes)
    }

    @Test
    fun returnsNullWithoutBuildMarkerOrApk() {
        assertNull(Updater.parseRelease(JSONObject("""{"body":"không có số","assets":[]}""")))
        assertNull(Updater.parseRelease(JSONObject("""{"body":"(build 5)","assets":[{"name":"a.txt","browser_download_url":"x"}]}""")))
    }
}
