package io.github.aspershupadhyay.latch.session

import io.github.aspershupadhyay.latch.protocol.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.security.SecureRandom

/** A failure with a sentence the owner can act on. */
class SetupException(message: String) : Exception(message)

@Serializable
data class GatewayInfo(
    val product: String,
    val implementation: String = "unknown",
    val version: String = "",
    val protocol: String,
    val transports: List<String> = emptyList(),
    @SerialName("setup_required") val setupRequired: Boolean = false,
)

@Serializable
data class McpClient(
    val id: String,
    val name: String,
    @SerialName("created_at_ms") val createdAtMs: Long,
    @SerialName("last_used_ms") val lastUsedMs: Long? = null,
    /** "oauth" when the app signed in with the MCP URL; "key" for a key or secret link. */
    val kind: String = "key",
)

/** An AI app asking to sign in. The owner approves it when [match] equals the code on its sign-in page. */
@Serializable
data class SignInRequest(
    val id: String,
    @SerialName("client_name") val clientName: String,
    val match: String,
    @SerialName("return_to") val returnTo: String = "",
    @SerialName("created_at_ms") val createdAtMs: Long,
)

/** A new MCP client credential. [token] is shown once and never stored by the app. */
@Serializable
data class NewMcpClient(val id: String, val token: String, @SerialName("mcp_url") val mcpUrl: String) {
    /** For MCP clients that only accept a URL. A credential: treat it like a password. */
    val secretLink: String get() = "$mcpUrl/$token"
}

@Serializable
private data class PairResponse(
    @SerialName("device_id") val deviceId: String,
    val name: String,
    val token: String,
    val protocol: String,
)

/** What a successful pairing produced; the caller stores it. */
data class Paired(val gatewayUrl: String, val deviceId: String, val name: String, val deviceToken: String)

/**
 * Talks to a gateway outside a session: discovery, pairing, and the owner
 * API. Plain Kotlin so it is tested on the JVM against real gateways.
 */
class GatewaySetup(private val http: OkHttpClient) {
    private val jsonType = "application/json".toMediaType()

    companion object {
        const val REPOSITORY = "https://github.com/aspershupadhyay/latch"

        /**
         * One-click deploy of the Vercel gateway into the owner's own Vercel
         * account, with an Upstash Redis store from the Vercel Marketplace.
         * Vercel asks for LATCH_ADMIN_TOKEN; the owner pastes the key the app made.
         */
        fun vercelDeployUrl(): String {
            fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
            val stores = """[{"type":"integration","integrationSlug":"upstash","productSlug":"upstash-kv","protocol":"storage"}]"""
            return "https://vercel.com/new/clone" +
                "?repository-url=${enc(REPOSITORY)}" +
                "&root-directory=${enc("servers/vercel")}" +
                "&project-name=latch-gateway&repository-name=latch-gateway" +
                "&env=LATCH_ADMIN_TOKEN" +
                "&envDescription=${enc("Paste the owner key shown in the Latch app. It stays in your Vercel project and on your phone.")}" +
                "&envLink=${enc("$REPOSITORY/blob/main/docs/operations/vercel.md")}" +
                "&stores=${enc(stores)}"
        }

        /** A fresh 256-bit owner key for LATCH_ADMIN_TOKEN. */
        fun newOwnerKey(): String {
            val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
            return "lok_" + bytes.joinToString("") { "%02x".format(it) }
        }
    }

    private fun get(url: String, token: String? = null) = Request.Builder().url(url).apply { token?.let { header("Authorization", "Bearer $it") } }.build()

    private fun post(url: String, body: String, token: String? = null) =
        Request.Builder().url(url).post(body.toRequestBody(jsonType)).apply { token?.let { header("Authorization", "Bearer $it") } }.build()

    private suspend fun <T> call(request: Request, handle: (Int, String) -> T): T = withContext(Dispatchers.IO) {
        try {
            http.newCall(request).execute().use { handle(it.code, it.body.string()) }
        } catch (e: IOException) {
            throw SetupException("Could not reach the gateway. Check the address and your connection.")
        }
    }

    private fun obj(text: String): JsonObject = try {
        Protocol.json.parseToJsonElement(text).jsonObject
    } catch (e: Exception) {
        throw SetupException("That address answered, but it is not a Latch gateway.")
    }

    /** Checks that [gatewayUrl] is a compatible Latch gateway. */
    suspend fun probe(gatewayUrl: String): GatewayInfo = call(get("$gatewayUrl/v1/info")) { code, body ->
        if (code == 404) throw SetupException("That address answered, but it is not a Latch gateway (or it is an old version).")
        if (code != 200) throw SetupException("The gateway answered with HTTP $code.")
        val info = try {
            Protocol.json.decodeFromString(GatewayInfo.serializer(), body)
        } catch (e: Exception) {
            throw SetupException("That address answered, but it is not a Latch gateway.")
        }
        if (info.product != "latch-gateway") throw SetupException("That address answered, but it is not a Latch gateway.")
        if (!Protocol.isCompatible(info.protocol)) throw SetupException("The gateway speaks protocol ${info.protocol.take(8)}; this app needs ${Protocol.VERSION}. Update one of them.")
        if ("poll" !in info.transports) throw SetupException("This gateway is too old for the app. Update it.")
        info
    }

    /** Exchanges a pairing code for this phone's credential. */
    suspend fun pairWithCode(gatewayUrl: String, code: String, model: String): Paired {
        probe(gatewayUrl)
        val clean = code.trim().uppercase()
        if (clean.count { it.isLetterOrDigit() } != 8) throw SetupException("Pairing codes have 8 letters and digits.")
        val body = """{"code":${Protocol.json.encodeToString(String.serializer(), clean)},"platform":"android","model":${Protocol.json.encodeToString(String.serializer(), model.take(64))}}"""
        return call(post("$gatewayUrl/v1/pair", body)) { status, text ->
            when (status) {
                200 -> {
                    val r = try {
                        Protocol.json.decodeFromString(PairResponse.serializer(), text)
                    } catch (e: Exception) {
                        throw SetupException("The gateway sent an unexpected answer.")
                    }
                    Paired(gatewayUrl, r.deviceId, r.name, r.token)
                }
                403 -> throw SetupException("That code is wrong or expired. Create a new one.")
                429 -> throw SetupException("Too many wrong codes. Wait a minute and try again.")
                503 -> throw SetupException("This gateway is not set up yet: its owner key (LATCH_ADMIN_TOKEN) is missing.")
                else -> throw SetupException("The gateway answered with HTTP $status.")
            }
        }
    }

    /**
     * Owner path: with the gateway's owner key the phone creates its own
     * pairing code and pairs itself, so nobody has to type a code.
     */
    suspend fun pairAsOwner(gatewayUrl: String, ownerKey: String, phoneName: String, model: String): Paired {
        val info = probe(gatewayUrl)
        if (info.setupRequired) {
            throw SetupException("The gateway is running but has no owner key yet. Add LATCH_ADMIN_TOKEN in your Vercel project settings, redeploy, then try again.")
        }
        val code = OwnerClient(http, gatewayUrl, ownerKey).createPairingCode(phoneName)
        return pairWithCode(gatewayUrl, code, model)
    }
}

/** The gateway owner API (under `/v1/admin`), used from the phone. */
class OwnerClient(private val http: OkHttpClient, private val gatewayUrl: String, private val ownerKey: String) {
    private val jsonType = "application/json".toMediaType()

    private suspend fun send(method: String, path: String, body: String? = null): Pair<Int, String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$gatewayUrl/v1/admin/$path")
            .header("Authorization", "Bearer $ownerKey")
            .method(method, body?.toRequestBody(jsonType))
            .build()
        try {
            http.newCall(request).execute().use { it.code to it.body.string() }
        } catch (e: IOException) {
            throw SetupException("Could not reach the gateway. Check your connection.")
        }
    }

    private fun check(status: Int, ok: Int = 200) {
        when (status) {
            ok -> Unit
            401 -> throw SetupException("The gateway did not accept the owner key. It must match LATCH_ADMIN_TOKEN in your gateway settings exactly.")
            503 -> throw SetupException("The gateway has no owner key yet. Add LATCH_ADMIN_TOKEN in your gateway settings and redeploy.")
            429 -> throw SetupException("Too many at once. Revoke unused keys or wait a minute.")
            else -> throw SetupException("The gateway answered with HTTP $status.")
        }
    }

    private fun quoted(s: String) = Protocol.json.encodeToString(String.serializer(), s)

    suspend fun createPairingCode(name: String): String {
        val (status, body) = send("POST", "pairings", """{"name":${quoted(name.take(40))}}""")
        check(status)
        return Protocol.json.parseToJsonElement(body).jsonObject.getValue("code").jsonPrimitive.content
    }

    suspend fun clients(): List<McpClient> {
        val (status, body) = send("GET", "clients")
        check(status)
        val list = Protocol.json.parseToJsonElement(body).jsonObject.getValue("clients").jsonArray
        return Protocol.json.decodeFromJsonElement(ListSerializer(McpClient.serializer()), list)
    }

    suspend fun createClient(name: String): NewMcpClient {
        val (status, body) = send("POST", "clients", """{"name":${quoted(name.take(40))}}""")
        check(status)
        return Protocol.json.decodeFromString(NewMcpClient.serializer(), body)
    }

    suspend fun revokeClient(id: String) {
        val (status, _) = send("DELETE", "clients/${URLEncoder.encode(id, "UTF-8")}")
        check(status, ok = 204)
    }

    /** AI apps waiting for the owner's approval. Empty on gateways without sign-in support. */
    suspend fun signInRequests(): List<SignInRequest> {
        val (status, body) = send("GET", "oauth/requests")
        if (status == 404) return emptyList()
        check(status)
        val list = Protocol.json.parseToJsonElement(body).jsonObject.getValue("requests").jsonArray
        return Protocol.json.decodeFromJsonElement(ListSerializer(SignInRequest.serializer()), list)
    }

    suspend fun answerSignIn(id: String, approve: Boolean) {
        val (status, _) = send("POST", "oauth/requests/${URLEncoder.encode(id, "UTF-8")}", """{"approve":$approve}""")
        if (status == 404) throw SetupException("That request expired. Connect again from your AI app.")
        check(status, ok = 204)
    }
}

