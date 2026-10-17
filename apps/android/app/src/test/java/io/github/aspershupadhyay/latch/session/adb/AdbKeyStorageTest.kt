// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The ADB identity survives restarts and recovers from a damaged file (R27). */
class AdbKeyStorageTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun theSameKeyComesBackAfterARestart() {
        val file = File(temp.root, "adb/adbkey")
        val first = AdbKey.loadOrCreate(file)
        val again = AdbKey.loadOrCreate(file)
        assertArrayEquals(first.androidPublicKey(), again.androidPublicKey())
        assertFalse("no temporary file is left behind", File(file.parentFile, "adbkey.tmp").exists())
    }

    @Test fun aDamagedKeyFileIsReplacedInsteadOfDisablingAdbForGood() {
        val file = File(temp.root, "adbkey")
        file.writeText("not a key")
        val key = AdbKey.loadOrCreate(file)
        assertTrue(file.readText() != "not a key")
        assertArrayEquals(key.androidPublicKey(), AdbKey.loadOrCreate(file).androidPublicKey())
    }
}
