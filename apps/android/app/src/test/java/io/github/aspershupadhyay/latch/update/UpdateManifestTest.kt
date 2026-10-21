// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
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

    private fun abiApks(vararg entries: String) = ""","apks":[${entries.joinToString(",")}]"""

    private fun abiApk(abi: String, url: String = "${prefix}beta/latch-android-$abi.apk", hash: String = "b".repeat(64), size: Long = 20_000_000) =
        """{"abi":"$abi","url":"$url","sha256":"$hash","size":$size}"""

    @Test
    fun aPhoneTakesTheApkForItsOwnChipType() {
        val info = UpdateManifest.parse(manifest(extra = abiApks(abiApk("arm64-v8a"), abiApk("armeabi-v7a", size = 18_000_000), abiApk("x86_64"))), prefix)
        // A 64-bit ARM phone (which also runs 32-bit code) takes its first, preferred type.
        val arm64 = info.forAbis(listOf("arm64-v8a", "armeabi-v7a"))
        assertEquals("${prefix}beta/latch-android-arm64-v8a.apk", arm64.apkUrl)
        assertEquals("b".repeat(64), arm64.sha256)
        assertEquals(20_000_000L, arm64.size)
        assertEquals("${prefix}beta/latch-android-armeabi-v7a.apk", info.forAbis(listOf("armeabi-v7a")).apkUrl)
        // A chip type with no APK of its own, or an older release without the list: the universal APK.
        assertEquals("${prefix}beta/latch-android.apk", info.forAbis(listOf("x86")).apkUrl)
        assertEquals("${prefix}beta/latch-android.apk", UpdateManifest.parse(manifest(), prefix).forAbis(listOf("arm64-v8a")).apkUrl)
    }

    @Test
    fun everyChipTypesApkIsCheckedLikeTheUniversalOne() {
        listOf(
            abiApks(abiApk("arm64-v8a", url = "https://example.com/evil.apk")),
            abiApks(abiApk("arm64-v8a", hash = "nothex")),
            abiApks(abiApk("arm64-v8a", size = 0)),
            abiApks(abiApk("arm64-v8a", size = UpdateManifest.MAX_APK_BYTES + 1)),
            abiApks(abiApk("mips")),
            abiApks(abiApk("arm64-v8a"), abiApk("arm64-v8a")),
        ).forEach { extra ->
            assertThrows(extra, UpdateException::class.java) { UpdateManifest.parse(manifest(extra = extra), prefix) }
        }
    }
}
