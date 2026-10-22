// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Every QR code Latch reads (ADR-054, ADR-069), told apart by its text:
 * - [WifiSetup]: the computer's gateway, `https://IP:PORT#pin=…&code=…`;
 * - [Hosted]: the owner's hosted relay page, `https://relay.example#connect`;
 * - [Mirror]: the computer's mirror page, `http(s)://HOST:PORT/mirror#k=…[&pin=…]`.
 */
sealed interface ScanLink {
    /** Pairs as the Wi-Fi gateway's terminal code does; [text] goes to the Wi-Fi setup whole. */
    data class WifiSetup(val text: String) : ScanLink

    /** The hosted relay at [url]; the phone pairs with the owner key it made for the deploy. */
    data class Hosted(val url: String) : ScanLink

    /** A mirror page at [origin] that waits for the phone to mirror with [key]. */
    data class Mirror(val origin: String, val key: String, val pin: String?) : ScanLink {
        /**
         * Whether the page belongs to the gateway this phone mirrors through: the
         * Wi-Fi certificate's pin when the page names one (the page may be on
         * 127.0.0.1 of that computer), else the same address.
         */
        fun isFor(gatewayUrl: String, gatewayPin: String?): Boolean {
            if (pin != null && gatewayPin != null) return pin == gatewayPin
            return origin.equals(gatewayUrl.trimEnd('/'), ignoreCase = true)
        }
    }

    companion object {
        private val KEY = Regex("[A-Za-z0-9_-]{43}")

        fun parse(text: String, allowCleartext: Boolean): ScanLink? {
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() } || '#' !in trimmed) return null
            val base = trimmed.substringBefore('#')
            val fragment = trimmed.substringAfter('#')
            if (base.endsWith("/mirror")) return mirror(base.removeSuffix("/mirror"), fragment)
            if (fragment == "connect") {
                val url = Pairing.normalize(base, allowCleartext) ?: return null
                val parsed = url.toHttpUrlOrNull() ?: return null
                if (parsed.encodedPath != "/" || parsed.query != null) return null
                return Hosted(url)
            }
            val setup = Pairing.splitSetup(trimmed)
            return if (setup.code != null && Pairing.parse(setup.address, allowCleartext)?.pin != null) WifiSetup(trimmed) else null
        }

        private fun mirror(origin: String, fragment: String): Mirror? {
            val parsed = origin.toHttpUrlOrNull() ?: return null
            if (parsed.encodedPath != "/" || parsed.query != null || origin.endsWith("/")) return null
            val params = fragment.split('&').mapNotNull { part ->
                part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
            }.toMap()
            val key = params["k"]?.takeIf { KEY.matches(it) } ?: return null
            val pin = params["pin"]?.let { if (GatewayPins.isPin(it)) it else return null }
            return Mirror(origin, key, pin)
        }
    }
}
