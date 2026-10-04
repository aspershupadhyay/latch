package io.github.aspershupadhyay.latch.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ActivityLogTest {
    private fun dir(): File = Files.createTempDirectory("activity").toFile()

    @Test
    fun entriesSurviveARestartAndOldOnesAreDropped() {
        val file = File(dir(), "activity.jsonl")
        var now = 1_000_000_000_000L
        val first = ActivityLog(file) { now }
        first.flush()
        first.add(ActivityKind.APP, "You switched on Instagram for the AI")
        now += 1_000
        first.add(ActivityKind.ACTION, "Tap “Next”", app = "Instagram")
        first.flush()

        val second = ActivityLog(file) { now }
        second.flush()
        assertEquals(listOf("Tap “Next”", "You switched on Instagram for the AI"), second.entries.value.map { it.summary })
        assertEquals("Instagram", second.entries.value.first().app)

        // A week later the old lines are gone.
        now += ActivityLog.RETENTION_MS + 1
        val third = ActivityLog(file) { now }
        third.flush()
        assertTrue(third.entries.value.isEmpty())
    }

    @Test
    fun queryFiltersByKindAndTimeNewestFirst() {
        var now = 10_000L
        val log = ActivityLog(null) { now }
        log.add(ActivityKind.FILE, "Saved “post.png” to your Latch folder")
        now += 10
        log.add(ActivityKind.REFUSAL, "You denied: Send the message", app = "WhatsApp")
        now += 10
        log.add(ActivityKind.FILE, "Deleted “old.png”")

        val files = log.query(10, listOf("file"), null)
        assertEquals(2, files.total)
        assertEquals(listOf("Deleted “old.png”", "Saved “post.png” to your Latch folder"), files.entries.map { it.summary })
        assertEquals("file", files.entries.first().kind)

        val recent = log.query(1, emptyList(), 10_010)
        assertEquals(2, recent.total)
        assertEquals(1, recent.entries.size)
        assertEquals("refusal", log.query(5, listOf("refusal"), null).entries.single().kind)
    }

    @Test
    fun clearDeletesTheFile() {
        val file = File(dir(), "activity.jsonl")
        val log = ActivityLog(file)
        log.add(ActivityKind.SESSION, "Session started for 30 minutes")
        log.flush()
        assertTrue(file.exists())
        log.clear()
        log.flush()
        assertFalse(file.exists())
        assertTrue(log.entries.value.isEmpty())
    }
}
