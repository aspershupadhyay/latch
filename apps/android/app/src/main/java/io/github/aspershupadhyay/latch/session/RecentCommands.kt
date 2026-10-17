// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session

import io.github.aspershupadhyay.latch.protocol.ErrorCode
import io.github.aspershupadhyay.latch.protocol.Outgoing
import java.io.File

/**
 * Protocol 1.19 (ADR-048): each command id runs at most once on this phone.
 *
 * When the link drops while a command runs, the gateway sends the same command
 * again, with the same id, after the phone reconnects, so that an answer lost
 * on the way still reaches the AI. Running it again would tap Send twice. So
 * the phone remembers recent ids:
 *
 * - an id it is still running is ignored (that run answers when it finishes);
 * - an id it answered gets the same answer again, from memory;
 * - an id it ran but whose answer it no longer keeps is answered `cancelled`.
 *
 * Answers stay in memory only (they hold screen content). The ids alone, random
 * tokens with nothing personal in them, are also appended to [journal], so a
 * command that ran just before Android stopped the app is not run again by the
 * restarted app. Plain Kotlin and thread-safe: the link's poll loop claims ids
 * while its workers finish them.
 */
class RecentCommands(
    private val journal: File? = null,
    private val keptAnswers: Int = 64,
    /** Answers may hold screenshots; the oldest are dropped past this many characters. */
    private val keptChars: Long = 8L * 1024 * 1024,
    private val keptIds: Int = 1024,
) {
    sealed interface Seen {
        /** Never seen: run it. The id is now claimed. */
        data object New : Seen
        /** Still running: its answer goes out when it finishes. */
        data object Running : Seen
        /** Already answered: send [reply] again instead of running it. */
        data class Answered(val reply: String) : Seen
    }

    private val running = HashSet<String>()
    /** Answers by id, oldest first. */
    private val answers = LinkedHashMap<String, String>()
    private var answerChars = 0L
    /** Ids that ran, oldest first, including those whose answer was dropped. */
    private val ids = LinkedHashSet<String>()
    private var journalLines = 0

    init {
        journal?.let { file ->
            runCatching { file.readLines() }.getOrDefault(emptyList())
                .filter(::isId)
                .takeLast(keptIds)
                .forEach { ids.add(it) }
            journalLines = ids.size
        }
    }

    /** What to do with a command whose id is [id]; claims it when it is new. */
    @Synchronized
    fun claim(id: String): Seen {
        answers[id]?.let { return Seen.Answered(it) }
        if (id in running) return Seen.Running
        if (id in ids) return Seen.Answered(Outgoing.error(id, ErrorCode.CANCELLED, FORGOTTEN))
        running.add(id)
        remember(id)
        return Seen.New
    }

    /** The run of [id] ended with [reply], which may be sent again later. */
    @Synchronized
    fun finish(id: String, reply: String) {
        running.remove(id)
        answers.remove(id)?.let { answerChars -= it.length }
        answers[id] = reply
        answerChars += reply.length
        val oldest = answers.keys.iterator()
        while ((answers.size > keptAnswers || answerChars > keptChars) && oldest.hasNext()) {
            val key = oldest.next()
            answerChars -= answers.getValue(key).length
            oldest.remove()
        }
    }

    private fun remember(id: String) {
        ids.add(id)
        while (ids.size > keptIds) ids.remove(ids.first())
        val file = journal ?: return
        // Written before the command runs: a crash mid-command must not lose the id.
        // A failing disk only costs the restart case; memory still protects this run.
        runCatching {
            if (journalLines >= keptIds * 2) {
                file.parentFile?.mkdirs()
                val compacted = File(file.path + ".tmp")
                compacted.writeText(ids.joinToString("\n", postfix = "\n"))
                if (!compacted.renameTo(file)) compacted.delete()
                journalLines = ids.size
            } else {
                file.parentFile?.mkdirs()
                file.appendText("$id\n")
                journalLines++
            }
        }
    }

    private fun isId(line: String) = line.length in 1..MAX_ID && line.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }

    private companion object {
        const val MAX_ID = 64
        const val FORGOTTEN = "the phone already received this command and no longer has its answer, so it did not run it again; observe to see what happened"
    }
}
