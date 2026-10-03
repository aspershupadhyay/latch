package io.github.aspershupadhyay.latch.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomyTest {
    private class MemoryStore(var saved: Set<String> = emptySet()) : Autonomy.Store {
        override fun load() = saved
        override fun save(allowed: Set<String>) {
            saved = allowed
        }
    }

    @Test
    fun appsAskUntilSwitchedOnAndTheSwitchIsKept() {
        val store = MemoryStore()
        val autonomy = Autonomy(store)
        assertEquals(AppDecision.ASK, autonomy.decide("com.whatsapp"))
        autonomy.setAllowed("com.whatsapp", true)
        assertEquals(AppDecision.ALLOWED, autonomy.decide("com.whatsapp"))
        assertEquals(setOf("com.whatsapp"), store.saved)
        // A restart reads the saved switches.
        assertEquals(AppDecision.ALLOWED, Autonomy(store).decide("com.whatsapp"))
        autonomy.setAllowed("com.whatsapp", false)
        assertEquals(AppDecision.ASK, autonomy.decide("com.whatsapp"))
    }

    @Test
    fun sessionAnswersEndWithTheSessionAndAreNeverSaved() {
        val store = MemoryStore()
        val autonomy = Autonomy(store)
        autonomy.allowForSession("com.android.settings")
        assertEquals(AppDecision.ALLOWED, autonomy.decide("com.android.settings"))
        assertTrue(store.saved.isEmpty())
        autonomy.endSession()
        assertEquals(AppDecision.ASK, autonomy.decide("com.android.settings"))
    }

    @Test
    fun switchingAnAppOffAlsoEndsItsSessionAnswer() {
        val autonomy = Autonomy(MemoryStore())
        autonomy.allowForSession("com.instagram.android")
        autonomy.setAllowed("com.instagram.android", false)
        assertEquals(AppDecision.ASK, autonomy.decide("com.instagram.android"))
    }

    @Test
    fun switchAllOffForgetsEverything() {
        val store = MemoryStore(setOf("a.b", "c.d"))
        val autonomy = Autonomy(store)
        autonomy.allowForSession("e.f")
        autonomy.clearAll()
        listOf("a.b", "c.d", "e.f").forEach { assertEquals(AppDecision.ASK, autonomy.decide(it)) }
        assertTrue(store.saved.isEmpty())
    }
}
