package io.github.aspershupadhyay.latch.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Reads the same fixtures as the Rust tests (`packages/schemas/v1/fixtures`),
 * so the two implementations cannot silently disagree about the wire format.
 */
class ProtocolFixturesTest {
    private fun fixtures(kind: String): List<Pair<String, String>> {
        val url = javaClass.classLoader!!.getResource("fixtures/$kind") ?: error("fixtures/$kind is not on the test classpath")
        val files = File(url.toURI()).listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        assertTrue("no $kind fixtures", files.isNotEmpty())
        return files.map { it.name to it.readText() }
    }

    @Test
    fun validGatewayMessagesParseAndValidate() {
        for ((name, json) in fixtures("valid").filter { it.first.startsWith("g2d-") }) {
            try {
                GatewayParser.parse(json)
            } catch (e: ProtocolException) {
                fail("$name was rejected: ${e.code} ${e.message}")
            }
        }
    }

    @Test
    fun invalidGatewayMessagesAreRejected() {
        for ((name, json) in fixtures("invalid").filter { it.first.startsWith("g2d-") }) {
            try {
                GatewayParser.parse(json)
                fail("$name was accepted")
            } catch (expected: ProtocolException) {
                // The command id is still recoverable so the phone can answer with an error.
                if (name.contains("command")) assertTrue(GatewayParser.commandId(json) != null)
            }
        }
    }

    @Test
    fun deviceMessagesUseTheSharedShapes() {
        val all = fixtures("valid").toMap()
        val hello = Protocol.json.decodeFromString(HelloMessage.serializer(), all.getValue("d2g-hello.json"))
        assertEquals("1.0", hello.protocol)
        assertEquals("needs_permission", hello.capabilities.last().status)
        val reencoded = Protocol.json.decodeFromString(HelloMessage.serializer(), Protocol.json.encodeToString(HelloMessage.serializer(), hello))
        assertEquals(hello, reencoded)

        val state = Protocol.json.decodeFromString(StateMessage.serializer(), all.getValue("d2g-state.json"))
        assertTrue(state.session.paused)

        val result = Protocol.json.parseToJsonElement(all.getValue("d2g-result-observation.json"))
        val data = (result as kotlinx.serialization.json.JsonObject)["outcome"]!!.let { (it as kotlinx.serialization.json.JsonObject)["data"]!! }
        val observation = Protocol.json.decodeFromJsonElement(Observation.serializer(), data)
        assertEquals("o_7", observation.observationId)
        assertTrue(observation.nodes.last().sensitive)
        assertEquals(null, observation.nodes.last().text)
    }

    @Test
    fun parsesConfirmationRequests() {
        val all = fixtures("valid").toMap()
        val message = GatewayParser.parse(all.getValue("g2d-command-tap-point-confirm.json")) as GatewayMessage.CommandMessage
        assertEquals("high", message.envelope.confirm?.risk)
        assertEquals(Target.Point(980, 2300), (message.envelope.command as Command.Tap).target)
    }

    @Test
    fun outgoingResultsHaveTheWireShape() {
        val ok = Protocol.json.parseToJsonElement(Outgoing.error("c_1", ErrorCode.STALE_OBSERVATION, "changed")).toString()
        assertEquals("""{"type":"result","id":"c_1","outcome":{"status":"error","error":{"code":"stale_observation","message":"changed"}}}""", ok)
    }

    @Test
    fun versionCompatibility() {
        assertTrue(Protocol.isCompatible("1.0"))
        assertTrue(Protocol.isCompatible("1.9"))
        assertTrue(!Protocol.isCompatible("2.0"))
        assertTrue(!Protocol.isCompatible("1.0.0"))
    }
}
