// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session

import org.junit.Assert.assertEquals
import org.junit.Test

/** Protocol 1.9 `effect`: what an action visibly did. */
class ActionEffectTest {
    @Test
    fun anotherAppInFrontIsANewApp() {
        assertEquals("new_app", effectOf(42, "com.android.launcher", 42, "com.android.settings"))
    }

    @Test
    fun aDifferentScreenInTheSameAppIsAChange() {
        assertEquals("changed", effectOf(42, "org.example", 43, "org.example"))
    }

    @Test
    fun theSameScreenIsNoEffect() {
        assertEquals("none", effectOf(42, "org.example", 42, "org.example"))
    }

    @Test
    fun anUnreadableScreenIsNeverReportedAsAMiss() {
        assertEquals("changed", effectOf(0, "org.example", 0, "org.example"))
        assertEquals("changed", effectOf(42, null, 0, null))
    }
}
