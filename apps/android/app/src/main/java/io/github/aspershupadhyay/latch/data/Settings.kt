package io.github.aspershupadhyay.latch.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import io.github.aspershupadhyay.latch.protocol.Capability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Where this phone is paired. The token itself lives in [TokenVault]. */
data class Pairing(val gatewayUrl: String, val deviceId: String, val name: String)

/** The owner's choices. Everything except device information starts switched off. */
data class Preferences(
    val enabled: Set<Capability> = setOf(Capability.DEVICE_INFO),
    val approveEveryAction: Boolean = false,
    val sessionMinutes: Int = 30,
    /** The owner went through the permission setup after pairing (or chose to finish it later). */
    val setupDone: Boolean = false,
)

/**
 * Small settings store on SharedPreferences, exposed as flows. Holds no
 * screen data, ever.
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("latch", Context.MODE_PRIVATE)
    private val vault = TokenVault(context, "latch_device_token")
    /** Present only when this phone's owner also owns the gateway. */
    private val ownerVault = TokenVault(context, "latch_owner_key")

    private val _isOwner = MutableStateFlow(ownerVault.load() != null)
    val isOwner: StateFlow<Boolean> = _isOwner.asStateFlow()

    private val _pairing = MutableStateFlow(readPairing())
    val pairing: StateFlow<Pairing?> = _pairing.asStateFlow()

    private val _preferences = MutableStateFlow(readPreferences())
    val preferences: StateFlow<Preferences> = _preferences.asStateFlow()

    private fun readPairing(): Pairing? {
        val url = prefs.getString("gateway_url", null) ?: return null
        val id = prefs.getString("device_id", null) ?: return null
        return Pairing(url, id, prefs.getString("device_name", null) ?: "This phone")
    }

    private fun readPreferences(): Preferences {
        val enabled = prefs.getStringSet("enabled", null)
            ?.mapNotNull(Capability::fromWire)?.toSet()
            ?: setOf(Capability.DEVICE_INFO)
        return Preferences(
            enabled = enabled + Capability.DEVICE_INFO,
            approveEveryAction = prefs.getBoolean("approve_every_action", false),
            sessionMinutes = prefs.getInt("session_minutes", 30),
            setupDone = prefs.getBoolean("setup_done", false),
        )
    }

    fun savePairing(pairing: Pairing, token: String) {
        vault.store(token)
        prefs.edit {
            putString("gateway_url", pairing.gatewayUrl)
            putString("device_id", pairing.deviceId)
            putString("device_name", pairing.name)
        }
        _pairing.value = pairing
    }

    fun token(): String? = vault.load()

    /** The gateway owner key (LATCH_ADMIN_TOKEN), when this phone set the gateway up. */
    fun ownerKey(): String? = ownerVault.load()

    fun saveOwnerKey(key: String) {
        ownerVault.store(key)
        _isOwner.value = true
    }

    fun forgetPairing() {
        vault.clear()
        ownerVault.clear()
        _isOwner.value = false
        prefs.edit {
            remove("gateway_url")
            remove("device_id")
            remove("device_name")
            remove("setup_done")
        }
        _preferences.value = _preferences.value.copy(setupDone = false)
        _pairing.value = null
    }

    fun update(transform: (Preferences) -> Preferences) {
        val next = transform(_preferences.value).let { it.copy(enabled = it.enabled + Capability.DEVICE_INFO) }
        prefs.edit {
            putStringSet("enabled", next.enabled.map { it.wire }.toSet())
            putBoolean("approve_every_action", next.approveEveryAction)
            putInt("session_minutes", next.sessionMinutes)
            putBoolean("setup_done", next.setupDone)
        }
        _preferences.value = next
    }
}

/**
 * Keeps the device token encrypted with a non-exportable AES key in the
 * Android Keystore. Backups and other apps only ever see ciphertext.
 */
class TokenVault(context: Context, private val alias: String) {
    private val prefs = context.getSharedPreferences("${alias}_vault", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun store(token: String) {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs.edit {
            putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            putString("token", Base64.encodeToString(sealed, Base64.NO_WRAP))
        }
    }

    fun load(): String? {
        val iv = prefs.getString("iv", null) ?: return null
        val sealed = prefs.getString("token", null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(sealed, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }

    fun clear() {
        prefs.edit { clear() }
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
    }

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
