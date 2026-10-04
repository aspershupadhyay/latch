// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.accessibility

import android.text.InputType

/**
 * Platform-independent facts about one on-screen element, so sensitivity
 * rules can be unit tested without a device.
 */
data class NodeFacts(
    val className: String?,
    val text: String?,
    val description: String?,
    val hint: String?,
    val viewId: String?,
    val isPassword: Boolean,
    val isEditable: Boolean,
    val inputType: Int,
    /** Android 14+ `isAccessibilityDataSensitive`. */
    val dataSensitive: Boolean,
)

/**
 * Decides which elements are sensitive. Sensitive elements leave the phone
 * with no text or description and can never be tapped or typed into.
 *
 * Errs on the side of redaction: a false positive costs the agent one field,
 * a false negative could leak a password.
 */
object Redaction {
    private val secretWords = setOf(
        "password", "passwort", "contraseña", "passcode", "pin", "otp", "cvv", "cvc", "2fa", "mfa", "totp",
        "seed", "mnemonic", "ssn",
    )
    private val secretPhrases = listOf(
        "one time", "one-time", "verification code", "security code", "card number", "credit card",
        "recovery phrase", "private key", "secret key", "auth code", "sms code", "expiry date", "expiration date",
    )

    private fun words(value: String): List<String> =
        value.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    private fun mentionsSecret(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val ws = words(value)
        if (ws.any { it in secretWords }) return true
        val joined = " " + ws.joinToString(" ") + " "
        return secretPhrases.any { joined.contains(" " + words(it).joinToString(" ") + " ") } ||
            // View ids like `etPassword` or `otp_input` collapse into one token.
            ws.any { token -> token.contains("password") || token.contains("passcode") || token.startsWith("otp") }
    }

    private fun isPasswordInputType(inputType: Int): Boolean {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val klass = inputType and InputType.TYPE_MASK_CLASS
        return (klass == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            )) ||
            (klass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    fun isSensitive(facts: NodeFacts): Boolean {
        if (facts.isPassword || facts.dataSensitive || isPasswordInputType(facts.inputType)) return true
        // Labels only matter for fields that accept input; a "Change PIN" button is not secret.
        if (facts.isEditable) {
            return listOf(facts.hint, facts.viewId, facts.description, facts.text).any(::mentionsSecret)
        }
        return false
    }
}
