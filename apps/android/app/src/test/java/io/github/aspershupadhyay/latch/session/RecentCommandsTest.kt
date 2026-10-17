// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session

import io.github.aspershupadhyay.latch.session.RecentCommands.Seen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RecentCommandsTest {
    private fun cancelled(seen: Seen) =
        seen is Seen.Answered && seen.reply.contains("\"code\":\"cancelled\"") && seen.reply.contains("did not run it again")

    @Test
    fun aCommandSentAgainIsNeverRunTwice() {
        val recent = RecentCommands()
        assertEquals(Seen.New, recent.claim("c_1"))
        // Sent again while it still runs: that run answers, nothing new starts.
        assertEquals(Seen.Running, recent.claim("c_1"))
        recent.finish("c_1", "{\"answer\":1}")
        // Sent again after it answered: the same answer, not a second run.
        assertEquals(Seen.Answered("{\"answer\":1}"), recent.claim("c_1"))
        assertEquals(Seen.New, recent.claim("c_2"))
    }

    @Test
    fun anIdWhoseAnswerWasDroppedIsAnsweredCancelled() {
        val recent = RecentCommands(keptAnswers = 2)
        for (i in 1..3) {
            recent.claim("c_$i")
            recent.finish("c_$i", "answer $i")
        }
        assertTrue(cancelled(recent.claim("c_1")))
        assertEquals(Seen.Answered("answer 3"), recent.claim("c_3"))
    }

    @Test
    fun largeAnswersAreDroppedFirstByTotalSize() {
        val recent = RecentCommands(keptChars = 100)
        recent.claim("c_big")
        recent.finish("c_big", "x".repeat(90))
        recent.claim("c_next")
        recent.finish("c_next", "y".repeat(20))
        assertTrue("the screenshot-sized answer went", cancelled(recent.claim("c_big")))
        assertEquals(Seen.Answered("y".repeat(20)), recent.claim("c_next"))
    }

    @Test
    fun aCommandFromBeforeTheAppRestartedIsNotRunAgain() {
        val file = Files.createTempDirectory("recent").resolve("session/recent-commands.txt").toFile()
        val before = RecentCommands(file)
        before.claim("c_tapped_send")
        // Android stopped the app before the answer went out. The restarted app:
        val after = RecentCommands(file)
        assertTrue(cancelled(after.claim("c_tapped_send")))
        assertEquals(Seen.New, after.claim("c_new"))
    }

    @Test
    fun theJournalIgnoresDamageAndStaysBounded() {
        val file = Files.createTempFile("recent", ".txt").toFile()
        file.writeText("c_ok\n\u0000garbage\n${"z".repeat(200)}\nc_also\n")
        val recent = RecentCommands(file, keptIds = 3)
        assertTrue(cancelled(recent.claim("c_ok")))
        assertTrue(cancelled(recent.claim("c_also")))
        assertEquals(Seen.New, recent.claim("\u0000garbage"))
        for (i in 1..10) recent.claim("c_$i")
        // Rewritten to the newest ids once it grew past twice the limit.
        assertTrue(file.readLines().size <= 6)
        val reopened = RecentCommands(file, keptIds = 3)
        assertTrue(cancelled(reopened.claim("c_10")))
        assertEquals("only the newest ids are kept", Seen.New, reopened.claim("c_1"))
    }
}
