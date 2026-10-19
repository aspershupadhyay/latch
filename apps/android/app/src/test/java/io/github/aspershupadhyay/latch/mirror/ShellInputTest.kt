// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-057: the lines the app sends the input helper, and what it can type as keys. */
class ShellInputTest {
    @Test fun theHelperStartsFromTheAppsOwnApk() {
        val token = "ab".repeat(32)
        assertEquals(
            "CLASSPATH='/data/app/~~x==/io.github.aspershupadhyay.latch-1/base.apk' exec app_process / " +
                "io.github.aspershupadhyay.latch.session.ShellInput 40123 $token",
            ShellInputCommands.start("/data/app/~~x==/io.github.aspershupadhyay.latch-1/base.apk", 40123, token),
        )
        assertThrows(IllegalArgumentException::class.java) { ShellInputCommands.start("/a'b/base.apk", 1, token) }
        assertThrows(IllegalArgumentException::class.java) { ShellInputCommands.start("/base.apk", 1, "x; reboot") }
        assertTrue(ShellInputCommands.randomToken().matches(Regex("[0-9a-f]{64}")))
    }

    @Test fun touchesScrollsAndKeysAreOneLineEach() {
        assertEquals("d 10 20", ShellInputCommands.touch(Phase.DOWN, 10, 20))
        assertEquals("m 11 21", ShellInputCommands.touch(Phase.MOVE, 11, 21))
        assertEquals("u 12 22", ShellInputCommands.touch(Phase.UP, 12, 22))
        assertEquals("c 0 0", ShellInputCommands.touch(Phase.CANCEL, 0, 0))
        assertEquals("s 540 1200 0 -400", ShellInputCommands.scroll(540, 1200, 0, -400))
        assertEquals("k 67 0", ShellInputCommands.key(ShellInputCommands.KEYCODE_DEL))
        assertEquals("t aMOpbGxv", ShellInputCommands.text("héllo"))
    }

    @Test fun pageKeysBecomeAndroidKeys() {
        assertEquals(67 to 0, ShellInputCommands.key(MirrorKey.BACKSPACE))
        assertEquals(29 to ShellInputCommands.META_CTRL, ShellInputCommands.key(MirrorKey.SELECT_ALL))
        assertEquals(67 to ShellInputCommands.META_CTRL, ShellInputCommands.key(MirrorKey.WORD_BACKSPACE))
        assertEquals(122 to 0, ShellInputCommands.key(MirrorKey.HOME))
        assertEquals(66 to 0, ShellInputCommands.key(MirrorKey.NEWLINE))
        // Enter is the field's own action (Send, Search), pressed through accessibility.
        assertNull(ShellInputCommands.key(MirrorKey.ENTER))
        for (key in MirrorKey.entries) if (key != MirrorKey.ENTER) assertTrue("$key", ShellInputCommands.key(key) != null)
    }

    @Test fun textSplitsIntoKeyRunsAndOthers() {
        assertEquals(listOf("hi there!" to true), ShellInputCommands.runs("hi there!"))
        assertEquals(
            listOf("ok " to true, "😀👍" to false, " done" to true, "é" to false),
            ShellInputCommands.runs("ok 😀👍 doneé"),
        )
        assertEquals(emptyList<Pair<String, Boolean>>(), ShellInputCommands.runs(""))
    }
}
