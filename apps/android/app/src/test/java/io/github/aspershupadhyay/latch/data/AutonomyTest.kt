package io.github.aspershupadhyay.latch.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomyTest {
    private class MemoryStore(var saved: Set<String> = emptySet(), var trust: Boolean = false, var auto: Boolean = false) : Autonomy.Store {
        override fun load() = saved
        override fun save(allowed: Set<String>) {
            saved = allowed
        }
        override fun loadTrustCritical() = trust
        override fun saveTrustCritical(value: Boolean) {
            trust = value
        }
        override fun loadAutoAlways() = auto
        override fun saveAutoAlways(value: Boolean) {
            auto = value
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

    @Test
    fun trustingPaymentsIsOffUntilChosenKeptAndClearedBySwitchAllOff() {
        val store = MemoryStore()
        val autonomy = Autonomy(store)
        assertTrue(!autonomy.state.value.trustCritical)
        autonomy.setTrustCritical(true)
        assertTrue(Autonomy(store).state.value.trustCritical)
        autonomy.clearAll()
        assertTrue(!store.trust && !autonomy.state.value.trustCritical)
    }

    @Test
    fun autoModeIsOffUntilChosenAndThenAllowsEveryAppAndPayments() {
        val autonomy = Autonomy(MemoryStore())
        assertEquals(AutoMode.OFF, autonomy.state.value.auto)
        assertEquals(AppDecision.ASK, autonomy.decide("com.bank.app"))
        assertTrue(!autonomy.state.value.trustsCritical)
        autonomy.setAuto(AutoMode.SESSION)
        assertEquals(AppDecision.ALLOWED, autonomy.decide("com.bank.app"))
        assertTrue(autonomy.state.value.trustsCritical)
    }

    @Test
    fun sessionAutoModeEndsWithTheSessionAndIsNeverSaved() {
        val store = MemoryStore()
        val autonomy = Autonomy(store)
        autonomy.setAuto(AutoMode.SESSION)
        assertTrue(!store.auto)
        autonomy.endSession()
        assertEquals(AutoMode.OFF, autonomy.state.value.auto)
        assertEquals(AppDecision.ASK, autonomy.decide("com.bank.app"))
    }

    @Test
    fun alwaysAutoModeOutlivesSessionsAndRestartsButNotSwitchAllOff() {
        val store = MemoryStore()
        val autonomy = Autonomy(store)
        autonomy.setAuto(AutoMode.ALWAYS)
        autonomy.endSession()
        assertEquals(AutoMode.ALWAYS, autonomy.state.value.auto)
        assertEquals(AutoMode.ALWAYS, Autonomy(store).state.value.auto)
        autonomy.clearAll()
        assertEquals(AutoMode.OFF, autonomy.state.value.auto)
        assertTrue(!store.auto)
        assertEquals(AutoMode.OFF, Autonomy(store).state.value.auto)
    }
}
