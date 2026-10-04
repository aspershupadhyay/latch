package io.github.aspershupadhyay.latch.ui

import io.github.aspershupadhyay.latch.protocol.Capability
import org.junit.Assert.assertEquals
import org.junit.Test

class AccessGroupTest {
    /** Grouping the Access tab must not hide a switch: each capability sits in exactly one group. */
    @Test fun everyCapabilityHasExactlyOneGroup() {
        Capability.entries.filter { it != Capability.DEVICE_INFO }.forEach { c ->
            assertEquals("$c", 1, AccessGroup.entries.count { c in it.capabilities })
        }
        assertEquals(0, AccessGroup.entries.count { Capability.DEVICE_INFO in it.capabilities })
    }

    @Test fun filesGroupCoversReadAndWrite() {
        assertEquals(listOf(Capability.FILE_READ, Capability.FILE_WRITE), AccessGroup.FILES.capabilities)
    }
}
