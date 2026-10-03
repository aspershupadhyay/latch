package io.github.aspershupadhyay.latch.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest

/**
 * What `latch-update.json` on a GitHub release says about the newest build.
 * Written by `.github/workflows/test-build.yml` and `release.yml`.
 */
@Serializable
data class UpdateInfo(
    @SerialName("version_code") val versionCode: Long,
    @SerialName("version_name") val versionName: String,
    @SerialName("apk_url") val apkUrl: String,
    /** Lowercase hex SHA-256 of the APK. */
    val sha256: String,
    /** APK size in bytes. */
    val size: Long,
    /** Short commit the build came from, shown to the owner. */
    val commit: String? = null,
)

/** A plain-language reason the update cannot go ahead. */
class UpdateException(message: String) : Exception(message)

object UpdateManifest {
    /** Far above a real APK, low enough that a bad file cannot fill the phone. */
    const val MAX_APK_BYTES = 200L * 1024 * 1024
    private const val MAX_MANIFEST_CHARS = 16_384
    private val HEX64 = Regex("^[0-9a-f]{64}$")
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Reads a manifest and checks every field. The APK must come from under
     * [downloadPrefix] (this repository's GitHub releases), over https.
     */
    fun parse(text: String, downloadPrefix: String): UpdateInfo {
        if (text.length > MAX_MANIFEST_CHARS) throw UpdateException("The update description is too large.")
        val info = try {
            json.decodeFromString(UpdateInfo.serializer(), text)
        } catch (e: SerializationException) {
            throw UpdateException("The update description is not readable.")
        } catch (e: IllegalArgumentException) {
            throw UpdateException("The update description is not readable.")
        }
        if (info.versionCode <= 0) throw UpdateException("The update has no valid version number.")
        if (info.versionName.isBlank() || info.versionName.length > 64) throw UpdateException("The update has no valid version name.")
        if (!HEX64.matches(info.sha256)) throw UpdateException("The update has no valid fingerprint.")
        if (info.size <= 0 || info.size > MAX_APK_BYTES) throw UpdateException("The update has an impossible size.")
        if (info.commit != null && info.commit.length > 40) throw UpdateException("The update description is not readable.")
        if (!isAllowedUrl(info.apkUrl, downloadPrefix)) throw UpdateException("The update points somewhere other than Latch's GitHub releases.")
        return info
    }

    /** True when [url] is a plain https address under [prefix]: no credentials, query, fragment, or "..". */
    fun isAllowedUrl(url: String, prefix: String): Boolean {
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            return false
        }
        val path = uri.rawPath ?: return false
        return uri.scheme == "https" &&
            uri.rawUserInfo == null &&
            uri.rawQuery == null &&
            uri.rawFragment == null &&
            uri.port == -1 &&
            !path.split('/').any { it == ".." || it == "." } &&
            url.startsWith(prefix) &&
            url.length > prefix.length
    }
}

/**
 * Copies [input] to [output] and returns the SHA-256 of what was copied, in
 * lowercase hex. Stops with an [UpdateException] past [maxBytes].
 */
fun copyHashing(input: InputStream, output: OutputStream, maxBytes: Long, onProgress: (Long) -> Unit = {}): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        total += n
        if (total > maxBytes) throw UpdateException("The download is larger than the update said.")
        digest.update(buffer, 0, n)
        output.write(buffer, 0, n)
        onProgress(total)
    }
    return digest.digest().toHex()
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
