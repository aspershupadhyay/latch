// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.accessibility

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hands the running accessibility service to the session, and tells the UI
 * whether the owner has the service switched on.
 */
class DeviceBridge {
    private val _service = MutableStateFlow<LatchAccessibilityService?>(null)
    val service: StateFlow<LatchAccessibilityService?> = _service.asStateFlow()

    fun attach(service: LatchAccessibilityService) {
        _service.value = service
    }

    fun detach(service: LatchAccessibilityService) {
        _service.compareAndSet(service, null)
    }
}
