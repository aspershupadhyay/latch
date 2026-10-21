// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import javax.net.ssl.SSLSocket

/** ADR-060: the phone computes the same commitment and number as the gateway (`packages/schemas/v1/nearby`). */
class NearbyLinkTest {
    @Test fun theNumberMatchesTheGatewaysFixture() {
        val url = javaClass.classLoader!!.getResource("nearby/number.json") ?: error("nearby/number.json is not on the test classpath")
        val cases = (Json.parseToJsonElement(File(url.toURI()).readText()) as JsonObject)["cases"]!!.jsonArray
        assertTrue(cases.size >= 3)
        for (case in cases.map { it.jsonObject }) {
            val p = NearbyLink.unhex(case["phone_nonce"]!!.jsonPrimitive.content)!!
            val g = NearbyLink.unhex(case["gateway_nonce"]!!.jsonPrimitive.content)!!
            assertEquals(case["commit"]!!.jsonPrimitive.content, NearbyLink.hex(NearbyLink.commitment(g)))
            assertEquals(case["number"]!!.jsonPrimitive.content, NearbyLink.shown(NearbyLink.number(case["pin"]!!.jsonPrimitive.content, p, g)))
        }
    }

    @Test fun hexAndShown() {
        assertEquals("000 007", NearbyLink.shown(7))
        assertEquals("999 999", NearbyLink.shown(999_999))
        assertArrayEquals(byteArrayOf(0, 15, -1), NearbyLink.unhex("000fff"))
        assertNull(NearbyLink.unhex("0g"))
        assertNull(NearbyLink.unhex("abc"))
    }

    @Test fun aFoundComputerGivesTheSameLinkAsItsQrCode() {
        val found = NearbyLink.Found("https://192.168.1.5:8788", "A".repeat(43), "MacBook")
        val link = found.setupLink("K7QXM2PD")
        val setup = Pairing.splitSetup(link)
        assertEquals("https://192.168.1.5:8788#pin=${"A".repeat(43)}", setup.address)
        assertEquals("K7QXM2PD", setup.code)
    }

    /**
     * Regression (2026-10-21): discovery wrote every answering host's pin into the
     * shared pins, replacing the pin of a gateway the phone already trusted. A
     * device answering at that address (ARP spoofing on the Wi-Fi) then became
     * trusted for the phone's own reconnects.
     */
    @Test fun findingComputersLeavesTheSharedPinsAlone() = runBlocking {
        val gateway = InetAddress.getByName("127.0.0.2")
        val server = runCatching { fakeGateway(gateway) }.getOrNull()
        assumeTrue("this system cannot listen on 127.0.0.2", server != null)
        val (socket, pin) = server!!
        val port = socket.localPort
        val url = "https://127.0.0.2:$port"
        val paired = "B".repeat(43)
        GatewayPins.set(url, paired)
        try {
            val found = NearbyLink.discover(InetAddress.getByName("127.0.0.1") as Inet4Address, OkHttpClient(), port)
            assertEquals(listOf(NearbyLink.Found(url, pin, "Desk")), found)
            assertEquals("the pin of the gateway already trusted stays", paired, GatewayPins.forHost("127.0.0.2", port))

            // The linking's own client trusts this computer's key only.
            found.single().client(OkHttpClient()).newCall(Request.Builder().url("$url/v1/nearby").build()).execute().use {
                assertTrue(it.isSuccessful)
            }
            try {
                NearbyLink.Found(url, "C".repeat(43), "Desk").client(OkHttpClient())
                    .newCall(Request.Builder().url("$url/v1/nearby").build()).execute().close()
                fail("a client pinned to another key must refuse this computer")
            } catch (_: java.io.IOException) {
            }
        } finally {
            GatewayPins.set(url, null)
            socket.close()
        }
    }

    /** A TLS listener at [address] that answers `GET /v1/nearby` like a gateway; and its pin. */
    private fun fakeGateway(address: InetAddress): Pair<java.net.ServerSocket, String> {
        val pair = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
        val cert = io.github.aspershupadhyay.latch.session.adb.X509SelfSigned.create(pair, commonName = "latch-gateway.local")
        val store = java.security.KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("gw", pair.private, CharArray(0), arrayOf(cert))
        }
        val keys = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, CharArray(0)) }
        val tls = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
        val server = tls.serverSocketFactory.createServerSocket(0, 50, address)
        Thread {
            runCatching {
                while (true) {
                    val s = server.accept() as SSLSocket
                    Thread {
                        runCatching {
                            s.use { c ->
                                val reader = c.inputStream.bufferedReader()
                                do { val line = reader.readLine() } while (!line.isNullOrEmpty())
                                val body = """{"latch":"nearby","v":1,"name":"Desk"}"""
                                c.outputStream.write(
                                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray(),
                                )
                                c.outputStream.flush()
                            }
                        }
                    }.apply { isDaemon = true }.start()
                }
            }
        }.apply { isDaemon = true }.start()
        return server to GatewayPins.pinOf(cert)
    }
}
