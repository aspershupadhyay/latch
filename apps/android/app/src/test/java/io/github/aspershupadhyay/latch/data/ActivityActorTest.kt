// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.data

import io.github.aspershupadhyay.latch.session.actorOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Every Activity line says who did it: the AI, the owner, or Latch. */
class ActivityActorTest {
    @Test
    fun whoDidItSurvivesARestartAndReachesTheGateway() {
        val file = File(Files.createTempDirectory("activity").toFile(), "activity.jsonl")
        val log = ActivityLog(file) { 1_000L }
        log.flush()
        log.add(ActivityKind.ACTION, "Tap “Send”", app = "WhatsApp", by = ActivityActor.AI)
        log.add(ActivityKind.ACTION, "You tapped the screen", app = "WhatsApp", by = ActivityActor.OWNER)
        log.add(ActivityKind.CONNECTION, "Connected to the gateway")
        log.flush()

        val again = ActivityLog(file) { 2_000L }
        again.flush()
        assertEquals(setOf(ActivityActor.AI, ActivityActor.OWNER, ActivityActor.LATCH), again.entries.value.map { it.by }.toSet())
        assertEquals(listOf("latch", "owner", "ai"), again.query(10, emptyList(), null).entries.map { it.by })
    }

    @Test
    fun linesSavedBeforeTheFieldExistedGetABestGuess() {
        val file = File(Files.createTempDirectory("activity").toFile(), "activity.jsonl")
        file.writeText(
            """{"at_ms":1000,"kind":"action","summary":"Tap “Next”"}""" + "\n" +
                """{"at_ms":1001,"kind":"approval","summary":"You approved: Send"}""" + "\n" +
                """{"at_ms":1002,"kind":"connection","summary":"Connected to the gateway"}""" + "\n",
        )
        val log = ActivityLog(file) { 2_000L }
        log.flush()
        assertEquals(listOf(ActivityActor.LATCH, ActivityActor.OWNER, ActivityActor.AI), log.entries.value.map { it.by })
    }

    @Test
    fun wordsDecideTheActorOfACommandLine() {
        assertEquals(ActivityActor.OWNER, actorOf("You denied: Send the message"))
        assertEquals(ActivityActor.LATCH, actorOf("Asked you: Send the message"))
        assertEquals(ActivityActor.LATCH, actorOf("Expired without an answer: Send"))
        assertEquals(ActivityActor.AI, actorOf("Done without asking (app switched on): Send"))
        assertEquals(ActivityActor.AI, actorOf("Tap “Next”"))
        assertNull(ActivityActor.fromWire("someone"))
    }
}
