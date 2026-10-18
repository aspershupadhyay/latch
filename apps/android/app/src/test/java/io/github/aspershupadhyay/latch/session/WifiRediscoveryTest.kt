// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentLinkedQueue

class WifiRediscoveryTest {
    private val phone = InetAddress.getByName("192.168.1.40") as Inet4Address
    private val pin = "A".repeat(43)

    @Test fun triesTheNeighboursOfTheOldAddressFirstAndNeverItselfOrThePhone() {
        val order = WifiRediscovery.candidates("https://192.168.1.5:8788", phone)
        assertEquals(listOf("192.168.1.4", "192.168.1.6", "192.168.1.3", "192.168.1.7"), order.take(4))
        assertFalse("192.168.1.5" in order)
        assertFalse("192.168.1.40" in order)
        assertEquals(252, order.size)
        assertTrue(order.all { it.startsWith("192.168.1.") })
    }

    @Test fun anOldAddressOnAnotherNetworkScansThisOneInOrder() {
        val order = WifiRediscovery.candidates("https://10.0.0.9:8788", phone)
        assertEquals("192.168.1.1", order.first())
        assertEquals(253, order.size)
    }

    @Test fun findsTheGatewayAtItsNewAddressOnTheSamePort() = runBlocking {
        val asked = ConcurrentLinkedQueue<Pair<String, Int>>()
        val found = WifiRediscovery.find("https://192.168.1.5:8788", pin, phone) { host, port, p ->
            asked += host to port
            host == "192.168.1.23" && p == pin
        }
        assertEquals("https://192.168.1.23:8788", found)
        assertTrue(asked.all { it.second == 8788 })
    }

    @Test fun nothingIsFoundWhenNoHostHasThePinnedKey() = runBlocking {
        assertNull(WifiRediscovery.find("https://192.168.1.5:8788", pin, phone) { _, _, _ -> false })
    }

    @Test fun theProbeAcceptsOnlyAServerWithThePinnedKey() = runBlocking {
        val pair = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
        val cert = io.github.aspershupadhyay.latch.session.adb.X509SelfSigned.create(pair, commonName = "latch-gateway.local")
        val store = java.security.KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("gw", pair.private, CharArray(0), arrayOf(cert))
        }
        val keys = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, CharArray(0)) }
        val tls = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
        val server = tls.serverSocketFactory.createServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val serving = Thread {
            runCatching {
                while (true) {
                    val s = server.accept() as javax.net.ssl.SSLSocket
                    runCatching { s.startHandshake() }
                    s.close()
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(WifiRediscovery.servesPin("127.0.0.1", server.localPort, GatewayPins.pinOf(cert)))
            assertFalse(WifiRediscovery.servesPin("127.0.0.1", server.localPort, pin))
        } finally {
            server.close()
            serving.join(2_000)
        }
    }

    @Test fun aClosedPortIsNotTheGateway() = runBlocking {
        // Nothing listens on port 1 of the loopback; the probe must answer false, not throw.
        assertFalse(WifiRediscovery.servesPin("127.0.0.1", 1, pin))
    }
}
