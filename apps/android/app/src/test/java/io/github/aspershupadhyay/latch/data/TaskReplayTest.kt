// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.data

import io.github.aspershupadhyay.latch.protocol.ActivityList
import io.github.aspershupadhyay.latch.protocol.Protocol
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskReplayTest {
    private fun shared(path: String) = javaClass.classLoader!!.getResource(path)!!.readText()

    private fun entries(list: ActivityList) = list.entries.map { item ->
        val kind = ActivityKind.fromWire(item.kind)!!
        ActivityEntry(item.atMs, kind, item.summary, item.app, ActivityActor.fromWire(item.by) ?: ActivityLog.legacyActor(kind, item.summary))
    }

    /** The phone's "By task" view cuts the same latest task as both gateways' `get_activity {task: "latest"}`. */
    @Test
    fun `the latest task matches the gateways' shared replay`() {
        val cases = Protocol.json.parseToJsonElement(shared("mcp/replay-input.json")).jsonObject["cases"]!!.jsonArray
            .map { Protocol.json.decodeFromJsonElement(ActivityList.serializer(), it) }
        val expected = shared("mcp/replay-expected.txt").split("---\n")
        assertEquals(cases.size, expected.size)
        val header = Regex("""^Latest AI task on the phone, oldest first: (.+?), (\d+) entries over (\S+ s);""")
        cases.zip(expected).forEach { (list, text) ->
            val latest = taskRuns(entries(list)).firstOrNull()
            val m = header.find(text)
            if (m == null) {
                assertTrue(text, text.startsWith("No AI task"))
                assertEquals(null, latest)
                return@forEach
            }
            val end = when (latest!!.end) {
                TaskEnd.FINISHED -> "finished"
                TaskEnd.SESSION_ENDED -> "cut short when the session ended"
                TaskEnd.OPEN -> "still going (no finish_task yet)"
            }
            assertEquals(m.groupValues[1], end)
            assertEquals(m.groupValues[2].toInt(), latest.entries.size)
            assertEquals(m.groupValues[3], tenths(latest.durationMs))
            // The steps, oldest first, with seconds from the start, as the gateways list them.
            val steps = text.lines().filter { it.startsWith("- +") }.map { it.removePrefix("- +").substringBefore(" s ") + " s" }
            assertEquals(steps, latest.entries.map { tenths(it.atMs - latest.startMs) })
        }
    }

    @Test
    fun `tasks come newest first and owner lines between tasks are left out`() {
        fun e(at: Long, kind: ActivityKind, by: ActivityActor) = ActivityEntry(at, kind, "x", by = by)
        val log = listOf(
            e(1, ActivityKind.ACTION, ActivityActor.AI),
            e(2, ActivityKind.TASK, ActivityActor.AI),
            e(3, ActivityKind.APP, ActivityActor.OWNER),
            e(4, ActivityKind.SCREEN, ActivityActor.AI),
            e(5, ActivityKind.APPROVAL, ActivityActor.OWNER),
            e(6, ActivityKind.TASK, ActivityActor.AI),
        ).asReversed()
        val runs = taskRuns(log)
        assertEquals(listOf(listOf(4L, 5L, 6L), listOf(1L, 2L)), runs.map { r -> r.entries.map { it.atMs } })
        assertEquals(1, runs.first().count(ActivityKind.APPROVAL))
        assertEquals("12.3 s", tenths(12_345))
    }
}
