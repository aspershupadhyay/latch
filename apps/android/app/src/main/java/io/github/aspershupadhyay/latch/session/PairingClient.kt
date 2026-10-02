package io.github.aspershupadhyay.latch.session

import io.github.aspershupadhyay.latch.BuildConfig
import io.github.aspershupadhyay.latch.data.Pairing as SavedPairing
import io.github.aspershupadhyay.latch.data.Settings
import io.github.aspershupadhyay.latch.protocol.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
private data class PairRequest(val code: String, val platform: String, val model: String)

@Serializable
private data class PairResponse(
    @SerialName("device_id") val deviceId: String,
    val name: String,
    val token: String,
    val protocol: String,
)

/** Exchanges a one-time code for this phone's credential. */
class PairingClient(private val http: OkHttpClient, private val settings: Settings) {

    sealed interface Result {
        data class Paired(val name: String) : Result
        data class Failed(val message: String) : Result
    }

    suspend fun pair(gatewayInput: String, code: String): Result = withContext(Dispatchers.IO) {
        val gateway = Pairing.normalize(gatewayInput, BuildConfig.ALLOW_CLEARTEXT)
            ?: return@withContext Result.Failed(
                if (gatewayInput.trim().startsWith("http://")) "Use an https:// address. Plain http is only allowed in debug builds."
                else "That does not look like a gateway address.",
            )
        val cleanCode = code.trim().uppercase()
        if (cleanCode.count { it.isLetterOrDigit() } != 8) return@withContext Result.Failed("Pairing codes have 8 letters and digits.")

        val body = Protocol.json.encodeToString(
            PairRequest.serializer(),
            PairRequest(cleanCode, "android", deviceDescriptor().model),
        ).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$gateway/v1/pair").post(body).build()
        try {
            http.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val parsed = Protocol.json.decodeFromString(PairResponse.serializer(), response.body.string())
                        if (!Protocol.isCompatible(parsed.protocol)) {
                            return@withContext Result.Failed("The gateway speaks a different protocol version. Update Latch or the gateway.")
                        }
                        settings.savePairing(SavedPairing(gateway, parsed.deviceId, parsed.name), parsed.token)
                        Result.Paired(parsed.name)
                    }
                    403 -> Result.Failed("That code is wrong or expired. Create a new one in the gateway console.")
                    429 -> Result.Failed("Too many wrong codes. Wait a minute and try again.")
                    else -> Result.Failed("The gateway answered with HTTP ${response.code}.")
                }
            }
        } catch (e: java.io.IOException) {
            Result.Failed("Could not reach the gateway. Check the address and your connection.")
        } catch (e: kotlinx.serialization.SerializationException) {
            Result.Failed("That address answered, but it is not a Latch gateway.")
        }
    }
}
