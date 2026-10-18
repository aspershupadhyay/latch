// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
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
/** [pin]: for a gateway on the owner's Wi-Fi (R24), the SHA-256 of its certificate's key. */
data class Pairing(val gatewayUrl: String, val deviceId: String, val name: String, val pin: String? = null) {
    val kind: ConnectionKind get() = ConnectionKind.of(this)
}

/**
 * How the AI reaches this phone. The owner can keep one of each and switch
 * between them without pairing again: the one not in use is kept, with its
 * keys, in its own Keystore-sealed slot.
 */
enum class ConnectionKind(val title: String, val short: String, val inSentence: String) {
    /** A relay on the internet, such as the owner's Vercel relay: works on any network. */
    HOSTED("Hosted relay", "Hosted", "hosted relay"),
    /** The gateway on the owner's computer on the same Wi-Fi (ADR-042), trusted by its pin. */
    WIFI("Computer on Wi-Fi", "Wi-Fi", "computer on Wi-Fi"),
    ;

    companion object {
        fun of(pairing: Pairing) = if (pairing.pin != null) WIFI else HOSTED
    }
}

/** A folder the owner shares with the AI: its Storage Access Framework tree URI and its name. */
data class FolderGrant(val uri: String, val name: String)

/** Light or dark look; SYSTEM follows the phone's own setting. */
enum class ThemeChoice(val label: String) {
    SYSTEM("System"), LIGHT("Light"), DARK("Dark");

    companion object {
        fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/** The owner's choices. Everything except device information starts switched off. */
data class Preferences(
    val enabled: Set<Capability> = setOf(Capability.DEVICE_INFO),
    val approveEveryAction: Boolean = false,
    val sessionMinutes: Int = 30,
    /** The owner went through the permission setup after pairing (or chose to finish it later). */
    val setupDone: Boolean = false,
    /** Show a pointer where the AI taps and swipes. */
    val showCursor: Boolean = true,
    /** Keep the screen on during a session so a task is not cut off by the lock screen. */
    val keepAwake: Boolean = true,
    /** Look for a newer Latch on GitHub when the app opens. */
    val checkUpdates: Boolean = true,
    /** Approvals may also be answered in the AI app (protocol 1.4, ADR-023). */
    val remoteApprovals: Boolean = false,
    /** The folders the owner shares with the AI, in the order they were added. */
    val folders: List<FolderGrant> = emptyList(),
    /** Latch's own switch for photos; Android's photo permission is needed too. */
    val photosOn: Boolean = true,
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    /** ADR-032: read the words on unlabeled buttons on the phone (Google ML Kit) so approvals see them. */
    val readButtons: Boolean = true,
    /** ADR-041: whole files move straight between the AI's computer and the phone on the same Wi-Fi. */
    val localTransfers: Boolean = false,
    /**
     * ADR-046 (R27): the device's own `adbd` endpoint as `host:port`, set once
     * the owner completes Android wireless-debugging pairing. Null until then,
     * so full-access ADB reports needs_permission even when the switch is on.
     */
    val adbEndpoint: String? = null,
    /**
     * R29 (b): the owner asked Latch to also open adbd on [ADB_FIXED_PORT] so
     * full ADB keeps working without Wi-Fi (on mobile data), until the phone
     * restarts. Off by default; the risk is explained where it is switched on.
     */
    val adbFixedPort: Boolean = false,
    /** ADR-053: during a session, also connect the kept connection of the other kind. */
    val bothConnected: Boolean = true,
)

/** R29 (b): the port `adb tcpip` opens, reached by Latch on the phone's own loopback. */
const val ADB_FIXED_PORT = 5555

/**
 * Small settings store on SharedPreferences, exposed as flows. Holds no
 * screen data, ever.
 */
class Settings(private val context: Context) {
    private val prefs = context.getSharedPreferences("latch", Context.MODE_PRIVATE)
    private val vault = TokenVault(context, "latch_device_token")
    /** Present only when this phone's owner also owns the gateway. */
    private val ownerVault = TokenVault(context, "latch_owner_key")

    private val _isOwner = MutableStateFlow(ownerVault.load() != null)
    val isOwner: StateFlow<Boolean> = _isOwner.asStateFlow()
    private val _ownerKeyVersion = MutableStateFlow(0)
    /** Changes whenever a new owner key is saved, so screens drop clients holding the old one. */
    val ownerKeyVersion: StateFlow<Int> = _ownerKeyVersion.asStateFlow()

    private val _pairing = MutableStateFlow(readPairing())
    val pairing: StateFlow<Pairing?> = _pairing.asStateFlow()

    private val _saved = MutableStateFlow(ConnectionKind.entries.mapNotNull { k -> readSaved(k)?.let { k to it } }.toMap())
    /** Connections kept for switching back, by kind; never the one in use. */
    val saved: StateFlow<Map<ConnectionKind, Pairing>> = _saved.asStateFlow()

    private val _preferences = MutableStateFlow(readPreferences())
    val preferences: StateFlow<Preferences> = _preferences.asStateFlow()

    private fun readPairing(): Pairing? {
        val url = prefs.getString("gateway_url", null) ?: return null
        val id = prefs.getString("device_id", null) ?: return null
        return Pairing(url, id, prefs.getString("device_name", null) ?: "This phone", prefs.getString("gateway_pin", null))
    }

    private fun slot(kind: ConnectionKind, field: String) = "saved_${kind.name.lowercase()}_$field"
    private fun savedToken(kind: ConnectionKind) = TokenVault(context, "latch_saved_${kind.name.lowercase()}_token")
    private fun savedOwnerKey(kind: ConnectionKind) = TokenVault(context, "latch_saved_${kind.name.lowercase()}_owner")

    private fun readSaved(kind: ConnectionKind): Pairing? {
        val url = prefs.getString(slot(kind, "gateway_url"), null) ?: return null
        val id = prefs.getString(slot(kind, "device_id"), null) ?: return null
        return Pairing(url, id, prefs.getString(slot(kind, "device_name"), null) ?: "This phone", prefs.getString(slot(kind, "pin"), null))
    }

    /**
     * Keeps the connection in use in its kind's slot, with its device token and
     * owner key, and leaves this phone unpaired so another can be set up. The
     * phone's own choices (switches, setup) stay as they are.
     */
    fun keepActive() {
        val active = _pairing.value ?: return
        val token = vault.load() ?: return
        val kind = active.kind
        savedToken(kind).store(token)
        val owner = ownerVault.load()
        if (owner != null) savedOwnerKey(kind).store(owner) else savedOwnerKey(kind).clear()
        prefs.edit {
            putString(slot(kind, "gateway_url"), active.gatewayUrl)
            putString(slot(kind, "device_id"), active.deviceId)
            putString(slot(kind, "device_name"), active.name)
            if (active.pin != null) putString(slot(kind, "pin"), active.pin) else remove(slot(kind, "pin"))
        }
        _saved.value = _saved.value + (kind to active)
        clearActive()
    }

    /**
     * Makes the kept [kind] the connection in use, keeping the current one in
     * its own slot first. False when nothing of that kind is kept.
     */
    fun restore(kind: ConnectionKind): Boolean {
        val target = _saved.value[kind] ?: return false
        val token = savedToken(kind).load() ?: return false.also { dropSaved(kind) }
        val owner = savedOwnerKey(kind).load()
        dropSaved(kind)
        if (_pairing.value != null) keepActive()
        vault.store(token)
        if (owner != null) ownerVault.store(owner) else ownerVault.clear()
        _isOwner.value = owner != null
        _ownerKeyVersion.value += 1
        writeActive(target)
        return true
    }

    /** Forgets a kept connection. Returns it, so its device key can be deleted too. */
    fun dropSaved(kind: ConnectionKind): Pairing? {
        val gone = _saved.value[kind]
        savedToken(kind).clear()
        savedOwnerKey(kind).clear()
        prefs.edit { listOf("gateway_url", "device_id", "device_name", "pin").forEach { remove(slot(kind, it)) } }
        _saved.value = _saved.value - kind
        return gone
    }

    /** The device token of the kept [kind], for connecting it beside the one in use (ADR-053). */
    fun keptToken(kind: ConnectionKind): String? = if (_saved.value[kind] != null) savedToken(kind).load() else null

    /** The kept Wi-Fi gateway moved to a new address. */
    fun moveKept(kind: ConnectionKind, url: String) {
        val kept = _saved.value[kind] ?: return
        prefs.edit { putString(slot(kind, "gateway_url"), url) }
        _saved.value = _saved.value + (kind to kept.copy(gatewayUrl = url))
    }

    /** The gateway on the Wi-Fi moved to a new address (R24): same keys, new address. */
    fun moveGateway(url: String) {
        val active = _pairing.value ?: return
        writeActive(active.copy(gatewayUrl = url))
    }

    private fun writeActive(pairing: Pairing) {
        prefs.edit {
            putString("gateway_url", pairing.gatewayUrl)
            putString("device_id", pairing.deviceId)
            putString("device_name", pairing.name)
            if (pairing.pin != null) putString("gateway_pin", pairing.pin) else remove("gateway_pin")
        }
        _pairing.value = pairing
    }

    private fun clearActive() {
        vault.clear()
        ownerVault.clear()
        _isOwner.value = false
        _ownerKeyVersion.value += 1
        prefs.edit {
            remove("gateway_url")
            remove("device_id")
            remove("device_name")
            remove("gateway_pin")
        }
        _pairing.value = null
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
            showCursor = prefs.getBoolean("show_cursor", true),
            keepAwake = prefs.getBoolean("keep_awake", true),
            checkUpdates = prefs.getBoolean("check_updates", true),
            readButtons = prefs.getBoolean("read_buttons", true),
            remoteApprovals = prefs.getBoolean("remote_approvals", false),
            folders = readFolders(),
            photosOn = prefs.getBoolean("photos_on", true),
            localTransfers = prefs.getBoolean("local_transfers", false),
            adbEndpoint = prefs.getString("adb_endpoint", null),
            adbFixedPort = prefs.getBoolean("adb_fixed_port", false),
            bothConnected = prefs.getBoolean("both_connected", true),
            theme = ThemeChoice.fromName(prefs.getString("theme", null)),
        )
    }

    /** One "uri<TAB>name" line per folder; the single folder of older versions becomes the first. */
    private fun readFolders(): List<FolderGrant> {
        prefs.getString("files_folders", null)?.let { saved ->
            return saved.lines().mapNotNull { line ->
                val uri = line.substringBefore('\t').takeIf { it.isNotBlank() } ?: return@mapNotNull null
                FolderGrant(uri, line.substringAfter('\t', "Folder").ifBlank { "Folder" })
            }
        }
        val legacy = prefs.getString("files_folder", null) ?: return emptyList()
        return listOf(FolderGrant(legacy, prefs.getString("files_folder_name", null) ?: "Folder"))
    }

    fun savePairing(pairing: Pairing, token: String) {
        vault.store(token)
        writeActive(pairing)
    }

    fun token(): String? = vault.load()

    /** The gateway owner key (LATCH_ADMIN_TOKEN), when this phone set the gateway up. */
    fun ownerKey(): String? = ownerVault.load()

    fun saveOwnerKey(key: String) {
        ownerVault.store(key)
        _isOwner.value = true
        _ownerKeyVersion.value += 1
    }

    /** Forgets the connection in use. A kept connection of the other kind stays kept. */
    fun forgetPairing() {
        clearActive()
        prefs.edit { remove("setup_done") }
        _preferences.value = _preferences.value.copy(setupDone = false)
    }

    fun update(transform: (Preferences) -> Preferences) {
        val next = transform(_preferences.value).let { it.copy(enabled = it.enabled + Capability.DEVICE_INFO) }
        prefs.edit {
            putStringSet("enabled", next.enabled.map { it.wire }.toSet())
            putBoolean("approve_every_action", next.approveEveryAction)
            putInt("session_minutes", next.sessionMinutes)
            putBoolean("setup_done", next.setupDone)
            putBoolean("show_cursor", next.showCursor)
            putBoolean("keep_awake", next.keepAwake)
            putBoolean("check_updates", next.checkUpdates)
            putBoolean("read_buttons", next.readButtons)
            putBoolean("remote_approvals", next.remoteApprovals)
            putString("files_folders", next.folders.joinToString("\n") { "${it.uri}\t${it.name.replace('\t', ' ').replace('\n', ' ')}" })
            remove("files_folder")
            remove("files_folder_name")
            putBoolean("photos_on", next.photosOn)
            putBoolean("local_transfers", next.localTransfers)
            if (next.adbEndpoint != null) putString("adb_endpoint", next.adbEndpoint) else remove("adb_endpoint")
            putBoolean("adb_fixed_port", next.adbFixedPort)
            putBoolean("both_connected", next.bothConnected)
            putString("theme", next.theme.name)
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
