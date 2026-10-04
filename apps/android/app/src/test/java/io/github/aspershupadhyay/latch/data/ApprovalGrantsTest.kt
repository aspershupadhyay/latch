// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalGrantsTest {
    private class MemoryStore(var keys: Set<String> = emptySet()) : ApprovalGrants.Store {
        override fun load() = keys
        override fun save(keys: Set<String>) {
            this.keys = keys
        }
    }

    @Test
    fun sessionAnswersEndWithTheSession() {
        val grants = ApprovalGrants(MemoryStore())
        grants.allowForSession("tap|com.whatsapp|send")
        assertTrue(grants.allows("tap|com.whatsapp|send"))
        assertFalse(grants.allows("tap|com.whatsapp|call"))
        grants.endSession()
        assertFalse(grants.allows("tap|com.whatsapp|send"))
    }

    @Test
    fun alwaysAnswersPersistAndCanBeRemoved() {
        val store = MemoryStore()
        ApprovalGrants(store).allowAlways("tap|com.instagram.android|share")
        val reloaded = ApprovalGrants(store)
        assertTrue(reloaded.allows("tap|com.instagram.android|share"))
        reloaded.endSession()
        assertTrue(reloaded.allows("tap|com.instagram.android|share"))
        reloaded.remove("tap|com.instagram.android|share")
        assertFalse(ApprovalGrants(store).allows("tap|com.instagram.android|share"))
    }

    @Test
    fun alwaysListIsBounded() {
        val grants = ApprovalGrants(MemoryStore())
        repeat(ApprovalGrants.MAX_ALWAYS + 5) { grants.allowAlways("tap|p|$it") }
        assertEquals(ApprovalGrants.MAX_ALWAYS, grants.always.value.size)
        assertFalse(grants.allows("tap|p|0"))
        assertTrue(grants.allows("tap|p|${ApprovalGrants.MAX_ALWAYS + 4}"))
    }

    @Test
    fun savedKeysDisplayQuotedLabels() {
        val saved = SavedApproval("long-press|com.example|send now")
        assertEquals("Long-press “send now”", saved.action)
        assertEquals("com.example", saved.packageName)
        assertEquals("Swipe", SavedApproval("swipe|com.android.incallui|").action)
    }
}
