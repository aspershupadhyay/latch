package io.github.aspershupadhyay.latch.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class UpdateManifestTest {
    private val prefix = "https://github.com/aspershupadhyay/latch/releases/download/"
    private val sha = "a".repeat(64)

    private fun manifest(
        code: String = "142",
        url: String = "${prefix}beta/latch-android.apk",
        hash: String = sha,
        size: String = "33554432",
        extra: String = "",
    ) = """{"version_code":$code,"version_name":"0.1.0-beta.42","apk_url":"$url","sha256":"$hash","size":$size,"commit":"633d63c"$extra}"""

    @Test
    fun readsTheManifestTheTestBuildWorkflowWrites() {
        val info = UpdateManifest.parse(manifest(extra = ""","future_field":true"""), prefix)
        assertEquals(142L, info.versionCode)
        assertEquals("0.1.0-beta.42", info.versionName)
        assertEquals(33_554_432L, info.size)
        assertEquals("633d63c", info.commit)
    }

    @Test
    fun refusesDownloadsFromAnywhereElse() {
        listOf(
            "https://example.com/latch.apk",
            "http://github.com/aspershupadhyay/latch/releases/download/test-build/latch.apk",
            "https://github.com/someone-else/latch/releases/download/test-build/latch.apk",
            "https://github.com/aspershupadhyay/latch/releases/download/../../../evil/latch.apk",
            "https://github.com/aspershupadhyay/latch/releases/download/x/latch.apk?redirect=evil",
            "https://user@github.com/aspershupadhyay/latch/releases/download/x/latch.apk",
            "https://github.com:8443/aspershupadhyay/latch/releases/download/x/latch.apk",
            prefix,
        ).forEach { url ->
            assertFalse(url, UpdateManifest.isAllowedUrl(url, prefix))
            assertThrows(url, UpdateException::class.java) { UpdateManifest.parse(manifest(url = url), prefix) }
        }
        assertTrue(UpdateManifest.isAllowedUrl("${prefix}v0.2.0/latch-android-v0.2.0.apk", prefix))
    }

    @Test
    fun refusesBadFields() {
        listOf(
            manifest(code = "0"),
            manifest(code = "-5"),
            manifest(hash = "A".repeat(64)),
            manifest(hash = "abc"),
            manifest(size = "0"),
            manifest(size = "${UpdateManifest.MAX_APK_BYTES + 1}"),
            "not json",
            "{}",
            "x".repeat(20_000),
        ).forEach { text ->
            assertThrows(text.take(60), UpdateException::class.java) { UpdateManifest.parse(text, prefix) }
        }
    }

    @Test
    fun hashesWhatItCopies() {
        val data = "hello".toByteArray()
        val out = ByteArrayOutputStream()
        val hash = copyHashing(ByteArrayInputStream(data), out, maxBytes = 5)
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", hash)
        assertEquals("hello", out.toString(Charsets.UTF_8))
    }

    @Test
    fun stopsADownloadLargerThanAnnounced() {
        assertThrows(UpdateException::class.java) {
            copyHashing(ByteArrayInputStream(ByteArray(10)), ByteArrayOutputStream(), maxBytes = 9)
        }
    }
}
