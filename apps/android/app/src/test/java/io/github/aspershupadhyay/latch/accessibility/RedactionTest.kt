// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.accessibility

import android.text.InputType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactionTest {
    private fun facts(
        text: String? = null,
        hint: String? = null,
        viewId: String? = null,
        editable: Boolean = true,
        password: Boolean = false,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        dataSensitive: Boolean = false,
    ) = NodeFacts("android.widget.EditText", text, null, hint, viewId, password, editable, inputType, dataSensitive)

    @Test
    fun platformSignalsAreAlwaysSensitive() {
        assertTrue(Redaction.isSensitive(facts(password = true)))
        assertTrue(Redaction.isSensitive(facts(dataSensitive = true, editable = false)))
        assertTrue(Redaction.isSensitive(facts(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)))
        assertTrue(Redaction.isSensitive(facts(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)))
        assertTrue(Redaction.isSensitive(facts(inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)))
    }

    @Test
    fun labelledSecretFieldsAreSensitive() {
        assertTrue(Redaction.isSensitive(facts(hint = "Enter PIN")))
        assertTrue(Redaction.isSensitive(facts(hint = "One-time code")))
        assertTrue(Redaction.isSensitive(facts(hint = "Card number")))
        assertTrue(Redaction.isSensitive(facts(viewId = "com.bank:id/etPassword")))
        assertTrue(Redaction.isSensitive(facts(viewId = "com.app:id/otp_input")))
        assertTrue(Redaction.isSensitive(facts(hint = "CVV")))
    }

    @Test
    fun ordinaryFieldsAndButtonsAreNot() {
        assertFalse(Redaction.isSensitive(facts(hint = "Search")))
        assertFalse(Redaction.isSensitive(facts(hint = "Message")))
        assertFalse(Redaction.isSensitive(facts(text = "Change PIN", editable = false)))
        assertFalse(Redaction.isSensitive(facts(text = "Forgot password?", editable = false)))
        assertFalse(Redaction.isSensitive(facts(hint = "Pinned notes title")))
    }
}
