// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import io.github.aspershupadhyay.latch.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Where the in-app update stands, for Home and Settings. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateState
    /** Android's own installer has the APK; it may show its Update screen. */
    data class Installing(val info: UpdateInfo) : UpdateState
    /** The owner has to allow Latch to install apps once (Android's "Install unknown apps"). */
    data class NeedsPermission(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String, val info: UpdateInfo?) : UpdateState
}

/**
 * Updates Latch from its GitHub releases, keeping pairing and settings.
 *
 * Safety comes from Android itself: an update installs only when it is signed
 * with the same key as the installed app, and Android shows its own Update
 * screen unless the system decides Latch may update itself. On top of that,
 * Latch downloads only from this repository's releases over https, checks the
 * size and SHA-256 from the release's `latch-update.json`, and checks the
 * package name, version, and signer before handing the file to Android.
 */
class Updater(
    private val context: Context,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val manifestUrl: String,
    private val downloadPrefix: String,
    private val currentVersion: Long,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var lastCheckAt = 0L
    private var job: Job? = null

    init {
        // A finished update restarts Latch; the downloaded file is no longer needed.
        scope.launch(Dispatchers.IO) { File(context.cacheDir, "update").deleteRecursively() }
    }

    /** On opening Latch: checks at most every [CHECK_EVERY_MS], quietly. */
    fun checkIfDue() {
        if (lastCheckAt != 0L && SystemClock.elapsedRealtime() - lastCheckAt < CHECK_EVERY_MS) return
        check(manual = false)
    }

    /** Asks GitHub for the newest build. A failed automatic check stays silent. */
    fun check(manual: Boolean) {
        if (job?.isActive == true) return
        when (_state.value) {
            is UpdateState.Downloading, is UpdateState.Installing -> return
            else -> Unit
        }
        lastCheckAt = SystemClock.elapsedRealtime()
        if (manual) _state.value = UpdateState.Checking
        job = scope.launch {
            _state.value = try {
                val info = fetchManifest()
                if (info.versionCode > currentVersion) UpdateState.Available(info) else UpdateState.UpToDate
            } catch (e: UpdateException) {
                if (manual) UpdateState.Failed(e.message ?: "Could not check for updates.", null) else UpdateState.Idle
            } catch (e: IOException) {
                if (manual) UpdateState.Failed("Could not reach GitHub. Check the connection and try again.", null) else UpdateState.Idle
            }
        }
    }

    /** Downloads, checks, and hands the update to Android's installer. */
    fun install(info: UpdateInfo) {
        if (job?.isActive == true) return
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(info)
            return
        }
        job = scope.launch {
            try {
                _state.value = UpdateState.Downloading(info, 0)
                val apk = download(info)
                verifyArchive(apk, info)
                _state.value = UpdateState.Installing(info)
                withContext(Dispatchers.IO) { commit(apk) }
            } catch (e: UpdateException) {
                _state.value = UpdateState.Failed(e.message ?: "The update could not be installed.", info)
            } catch (e: IOException) {
                _state.value = UpdateState.Failed("The download stopped. Check the connection and try again.", info)
            }
        }
    }

    /**
     * Called by [MainActivity] with the installer's answer. Returns Android's
     * Update screen when the owner has to confirm, for the activity to open.
     */
    fun onInstallStatus(intent: Intent): Intent? {
        val info = (_state.value as? UpdateState.Installing)?.info
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm == null) _state.value = UpdateState.Failed("Android did not show its Update screen. Try again.", info)
                return confirm
            }
            // On success Android restarts Latch as the new version; nothing to do here.
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = info?.let { UpdateState.Available(it) } ?: UpdateState.Idle
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                _state.value = UpdateState.Failed(KEY_CHANGED, info)
            PackageInstaller.STATUS_FAILURE_STORAGE -> _state.value = UpdateState.Failed("Not enough free space for the update.", info)
            else -> _state.value = UpdateState.Failed("Android did not install the update. Try again.", info)
        }
        return null
    }

    private suspend fun fetchManifest(): UpdateInfo = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(manifestUrl).cacheControl(CacheControl.FORCE_NETWORK).build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404) throw UpdateException("No build is published yet.")
            if (!response.isSuccessful) throw UpdateException("GitHub answered ${response.code}. Try again later.")
            UpdateManifest.parse(response.body.string(), downloadPrefix)
        }
    }

    private suspend fun download(info: UpdateInfo): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "latch-${info.versionCode}.apk")
        val request = Request.Builder().url(info.apkUrl).build()
        val hash = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw UpdateException("GitHub answered ${response.code} for the download. Try again later.")
            file.outputStream().use { out ->
                copyHashing(response.body.byteStream(), out, info.size) { done ->
                    _state.value = UpdateState.Downloading(info, (done * 100 / info.size).toInt().coerceIn(0, 100))
                }
            }
        }
        if (file.length() != info.size || hash != info.sha256) {
            file.delete()
            throw UpdateException("The download does not match its fingerprint, so Latch threw it away. Try again.")
        }
        file
    }

    /** Checks what Android would refuse anyway, so the owner gets a clear reason. */
    private fun verifyArchive(apk: File, info: UpdateInfo) {
        val pm = context.packageManager
        val archive = archiveInfo(pm, apk.path) ?: throw UpdateException("The download is not an Android app.")
        if (archive.packageName != context.packageName) throw UpdateException("The download is a different app.")
        if (archive.longVersionCode != info.versionCode || archive.longVersionCode <= currentVersion) {
            throw UpdateException("The download is not newer than this version.")
        }
        val installed = signers(installedInfo(pm))
        val incoming = signers(archive)
        // Some Android versions do not report an archive's signer; Android still checks it at install.
        if (installed.isNotEmpty() && incoming.isNotEmpty() && installed.intersect(incoming).isEmpty()) {
            throw UpdateException(KEY_CHANGED)
        }
    }

    private fun commit(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            // Android decides; when it still needs the owner's tap it answers PENDING_USER_ACTION.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("latch.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val status = Intent(context, MainActivity::class.java)
                    .setAction(ACTION_INSTALL_STATUS)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                // Mutable so the installer can add its status; explicit, so only MainActivity receives it.
                val pending = PendingIntent.getActivity(context, id, status, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(pending.intentSender)
            }
        } catch (e: IOException) {
            installer.abandonSession(id)
            throw e
        } catch (e: SecurityException) {
            installer.abandonSession(id)
            throw UpdateException("Android did not let Latch install the update. Allow Latch to install apps and try again.")
        }
    }

    private fun archiveInfo(pm: PackageManager, path: String): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    private fun installedInfo(pm: PackageManager): PackageInfo =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    /** SHA-256 of every certificate in the signing history. */
    private fun signers(info: PackageInfo): Set<String> {
        val signing = info.signingInfo ?: return emptySet()
        val certs = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        return certs.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHex() }.toSet()
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "io.github.aspershupadhyay.latch.INSTALL_STATUS"
        private const val CHECK_EVERY_MS = 30 * 60 * 1000L
        const val KEY_CHANGED =
            "This build is signed with a different key than the Latch on this phone, so Android will not install it over it. " +
                "Uninstall Latch once and install the new build from GitHub; after that, updates work from here."
    }
}
