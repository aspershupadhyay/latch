// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Find my computer (ADR-060): links this phone to the owner's Wi-Fi gateway
 * with no QR code. The phone finds gateways on its /24 at the default port,
 * remembering each one's certificate pin from the first handshake and
 * pinning every later request to it. Both sides then show a 6-digit number
 * from a commit-then-reveal exchange; the owner approves on the computer,
 * and the phone receives a single-use owner setup code to pair with.
 */
object NearbyLink {
    /** The Wi-Fi listener's default port (`latch-gateway` LATCH_WIFI_BIND). */
    const val PORT = 8788

    data class Found(val url: String, val pin: String, val name: String) {
        /** The setup link a scan would have given, with [code]. */
        fun setupLink(code: String) = "$url#pin=$pin&code=$code"

        /** [base] trusting only this computer's key; the shared pins stay as they are. */
        fun client(base: OkHttpClient): OkHttpClient = GatewayPins.pinnedTo(base.newBuilder(), pin).build()
    }

    class NearbyException(message: String) : Exception(message)

    // ---- The number (shared fixture: packages/schemas/v1/nearby/number.json) ----

    fun commitment(gatewayNonce: ByteArray): ByteArray = sha256("latch-nearby-commit-v1".toByteArray(), gatewayNonce)

    /** First 4 bytes of SHA-256("latch-nearby-sas-v1" ‖ pin ‖ phone nonce ‖ gateway nonce), big-endian, mod 1 000 000. */
    fun number(pin: String, phoneNonce: ByteArray, gatewayNonce: ByteArray): Int {
        val d = sha256("latch-nearby-sas-v1".toByteArray(), pin.toByteArray(), phoneNonce, gatewayNonce)
        val n = ((d[0].toLong() and 0xff) shl 24) or ((d[1].toLong() and 0xff) shl 16) or ((d[2].toLong() and 0xff) shl 8) or (d[3].toLong() and 0xff)
        return (n % 1_000_000).toInt()
    }

    fun shown(number: Int): String = "%03d %03d".format(number / 1000, number % 1000)

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun unhex(text: String): ByteArray? {
        if (text.length % 2 != 0 || text.any { Character.digit(it, 16) < 0 }) return null
        return ByteArray(text.length / 2) { i -> text.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    }

    private fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").apply { parts.forEach { update(it) } }.digest()

    // ---- Finding gateways ----

    /** Every Latch gateway answering on [port] on [phone]'s /24, nearest addresses first. */
    suspend fun discover(phone: Inet4Address, client: OkHttpClient, port: Int = PORT): List<Found> {
        val b = phone.address
        val prefix = "${b[0].toInt() and 255}.${b[1].toInt() and 255}.${b[2].toInt() and 255}."
        val self = b[3].toInt() and 255
        val gate = Semaphore(PARALLEL)
        return coroutineScope {
            (1..254).filter { it != self }.map { "$prefix$it" }.map { host ->
                async { gate.withPermit { probe(host, port, client) } }
            }.awaitAll().filterNotNull()
        }
    }

    /**
     * [host] when it completes TLS and says it is a Latch gateway. Its pin is
     * trusted only by this linking's own client ([Found.client]), never written to
     * the shared pins: a device answering at a paired gateway's address must not
     * become trusted there.
     */
    private suspend fun probe(host: String, port: Int, client: OkHttpClient): Found? {
        val pin = pinAt(host, port) ?: return null
        val url = "https://$host:$port"
        val hello = runCatching { get(GatewayPins.pinnedTo(client.quick().newBuilder(), pin).build(), "$url/v1/nearby") }.getOrNull()
        if (hello == null || hello["latch"]?.jsonPrimitive?.contentOrNull != "nearby") return null
        val name = hello["name"]?.jsonPrimitive?.contentOrNull?.filterNot { it.isISOControl() }?.take(40)
        return Found(url, pin, name?.ifBlank { null } ?: host)
    }

    /** The pin of the certificate [host] shows, or null when nothing completes a TLS handshake there. */
    private suspend fun pinAt(host: String, port: Int): String? = withContext(Dispatchers.IO) {
        var seen: String? = null
        runCatching {
            val record = object : X509TrustManager {
                override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                    // Only recorded here; nothing is sent until every later request is pinned to it.
                    seen = GatewayPins.pinOf(chain.firstOrNull() ?: throw CertificateException("no certificate"))
                }
                override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = throw CertificateException("server only")
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(record), null) }
            Socket().use { raw ->
                raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                raw.soTimeout = HANDSHAKE_TIMEOUT_MS
                (tls.socketFactory.createSocket(raw, host, port, true) as SSLSocket).use { it.startHandshake() }
            }
        }
        seen?.takeIf { GatewayPins.isPin(it) }
    }

    // ---- Linking ----

    /**
     * Asks [found] to link as [device]: shows the number with [onNumber],
     * then waits for the owner's answer on the computer. Returns the setup
     * code; throws [NearbyException] when refused, expired, or not a match.
     */
    suspend fun link(found: Found, device: String, base: OkHttpClient, onNumber: (String) -> Unit): String {
        val client = found.client(base)
        val started = post(client, "${found.url}/v1/nearby", obj("device" to device.take(40)))
        val id = started.text("id") ?: throw NearbyException("The computer did not start linking.")
        val commit = started.text("commit")?.let(::unhex) ?: throw NearbyException("The computer sent an unexpected answer.")
        val phoneNonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val revealed = post(client, "${found.url}/v1/nearby/$id/nonce", obj("nonce" to hex(phoneNonce)))
        val gatewayNonce = revealed.text("nonce")?.let(::unhex)?.takeIf { it.size == 32 }
            ?: throw NearbyException("The computer sent an unexpected answer.")
        // The reveal must match what the computer committed to before it saw this phone's nonce.
        if (!MessageDigest.isEqual(commitment(gatewayNonce), commit)) throw NearbyException("That computer's answer did not check out. Use the QR code instead.")
        onNumber(shown(number(found.pin, phoneNonce, gatewayNonce)))
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val answer = get(client, "${found.url}/v1/nearby/$id")
            when (answer.text("state")) {
                "approved" -> return answer.text("code")?.filter { it.isLetterOrDigit() }?.uppercase()?.takeIf { it.length == 8 }
                    ?: throw NearbyException("The computer sent an unexpected answer.")
                "denied" -> throw NearbyException("The computer said no. If the numbers matched, try again and type y there.")
                "done" -> throw NearbyException("This request was already used. Start again.")
            }
        }
        cancel(found, id, client)
        throw NearbyException("No answer from the computer. Look for the number in the latch-start window, or run latch-gateway pair.")
    }

    private fun cancel(found: Found, id: String, client: OkHttpClient) {
        runCatching { client.newCall(Request.Builder().url("${found.url}/v1/nearby/$id").delete().build()).execute().close() }
    }

    private fun obj(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) }).toString()

    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull

    private suspend fun post(client: OkHttpClient, url: String, body: String): JsonObject =
        call(client, Request.Builder().url(url).post(body.toRequestBody(JSON)).build())

    private suspend fun get(client: OkHttpClient, url: String): JsonObject = call(client, Request.Builder().url(url).get().build())

    private suspend fun call(client: OkHttpClient, request: Request): JsonObject = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            val json = runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrNull()
            when {
                response.code == 404 -> throw NearbyException("This computer has linking without a QR code turned off. Use the QR code.")
                response.code == 429 -> throw NearbyException("Too many phones are asking right now. Try again in a minute.")
                !response.isSuccessful || json == null -> throw NearbyException(json?.text("error") ?: "The computer answered HTTP ${response.code}.")
                else -> json
            }
        }
    }

    private fun OkHttpClient.quick() = newBuilder().connectTimeout(1, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build()

    private val JSON = "application/json".toMediaType()
    private const val PARALLEL = 48
    private const val CONNECT_TIMEOUT_MS = 400
    private const val HANDSHAKE_TIMEOUT_MS = 1_500
    /** A request lives 2 minutes on the gateway. */
    private const val WAIT_MS = 115_000L
}
