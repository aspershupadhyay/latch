package io.github.aspershupadhyay.latch

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.aspershupadhyay.latch.accessibility.DeviceBridge
import io.github.aspershupadhyay.latch.data.ActivityLog
import io.github.aspershupadhyay.latch.data.ApprovalGrants
import io.github.aspershupadhyay.latch.data.Autonomy
import io.github.aspershupadhyay.latch.data.PrefsAutonomyStore
import io.github.aspershupadhyay.latch.data.PrefsGrantStore
import io.github.aspershupadhyay.latch.policy.Consequences
import io.github.aspershupadhyay.latch.policy.PolicyWords
import io.github.aspershupadhyay.latch.data.Settings
import io.github.aspershupadhyay.latch.session.ApprovalBroker
import io.github.aspershupadhyay.latch.session.Pairing
import io.github.aspershupadhyay.latch.session.GatewaySetup
import io.github.aspershupadhyay.latch.session.SessionController
import io.github.aspershupadhyay.latch.session.SessionState
import io.github.aspershupadhyay.latch.ui.MainActivity
import io.github.aspershupadhyay.latch.update.Updater
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import io.github.aspershupadhyay.latch.data.ActivityKind
import io.github.aspershupadhyay.latch.session.Crashes
import kotlinx.coroutines.Dispatchers
import io.github.aspershupadhyay.latch.files.PhoneFiles
import io.github.aspershupadhyay.latch.files.PhoneTransfers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Process-wide singletons, wired by hand: the app is small enough that a
 * dependency-injection framework would add more than it removes.
 */
class LatchApp : Application() {
    /**
     * A failure in one piece of work is recorded and shown in Activity, never
     * allowed to close the app: a closed app takes the accessibility service
     * with it, and Android then leaves the service switched off.
     */
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, error ->
            Crashes.record(error)
            log.add(ActivityKind.REFUSAL, "Latch recovered from a problem: ${Crashes.describe(error)}")
        },
    )
    lateinit var settings: Settings
        private set
    val log = ActivityLog()
    val bridge = DeviceBridge()
    val approvals = ApprovalBroker()
    lateinit var session: SessionController
        private set
    /** The owner's saved approval answers. */
    lateinit var grants: ApprovalGrants
        private set
    lateinit var setup: GatewaySetup
        private set
    /** The shared word lists, also used by screens to flag money and password apps. */
    lateinit var consequences: Consequences
        private set
    /** Which apps the AI may use (Access → Apps). */
    lateinit var autonomy: Autonomy
        private set
    val http = Pairing.client()
    /** Photos, Downloads, and the folder the owner picked (protocol 1.6). */
    lateinit var files: PhoneFiles
        private set
    lateinit var updater: Updater
        private set
    /** Whole files moving by encrypted link (protocol 1.7). */
    lateinit var transfers: PhoneTransfers
        private set

    /** Latch's own screen is in front; agents' screen commands move it aside first. */
    @Volatile var ownScreenShown = false

    override fun onCreate() {
        super.onCreate()
        Crashes.install(this)
        settings = Settings(this)
        grants = ApprovalGrants(PrefsGrantStore(this))
        consequences = Consequences(PolicyWords.parse(assets.open("words.json").bufferedReader().use { it.readText() }))
        autonomy = Autonomy(PrefsAutonomyStore(this))
        files = PhoneFiles(this) { settings.preferences.value.filesFolder?.let(android.net.Uri::parse) }
        transfers = PhoneTransfers(files, http) { settings.pairing.value?.gatewayUrl }
        session = SessionController(scope, settings, bridge, approvals, log, http, grants, consequences, ::appLabel, autonomy, files, transfers)
        setup = GatewaySetup(http)
        updater = Updater(this, http, scope, BuildConfig.UPDATE_MANIFEST_URL, BuildConfig.UPDATE_DOWNLOAD_PREFIX, BuildConfig.VERSION_CODE.toLong())
        createChannels()
        observeIndicators()
    }

    /** The name an app shows in the launcher, or null if it has none. */
    fun appLabel(packageName: String): String? = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString().take(40)
    }.getOrNull()

    /** Every app with a launcher icon except Latch, as (package, label). Call off the main thread. */
    fun launchableApps(): List<Pair<String, String>> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val found = packageManager.queryIntentActivities(intent, 0)
        return found.map { it.activityInfo.packageName to it.loadLabel(packageManager).toString().take(60) }
            .filter { it.first != packageName }
            .distinctBy { it.first }
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATES, getString(R.string.channel_updates), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = getString(R.string.channel_updates_description)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SESSION, getString(R.string.channel_session), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.channel_session_description)
            },
        )
    }

    /** Keeps the overlay, the approval card, and the ongoing notification in step with the session. */
    private fun observeIndicators() {
        scope.launch {
            combine(session.state, bridge.service, settings.preferences) { state, service, prefs -> Triple(state, service, prefs) }.collect { (state, service, prefs) ->
                val live = state is SessionState.Active || state is SessionState.Reconnecting || state is SessionState.Connecting
                if (live) {
                    service?.showOverlay { session.stop() }
                    service?.applyOverlayPreferences(prefs.showCursor, prefs.keepAwake)
                    showSessionNotification(state)
                } else {
                    service?.hideOverlay()
                    NotificationManagerCompat.from(this@LatchApp).cancel(NOTIFICATION_SESSION)
                }
            }
        }
        scope.launch {
            combine(transfers.progress, bridge.service) { moving, service -> moving to service }.collect { (moving, service) ->
                val first = moving.firstOrNull()
                if (first == null) {
                    service?.cursorTransfer(null, null)
                    NotificationManagerCompat.from(this@LatchApp).cancel(NOTIFICATION_TRANSFER)
                } else {
                    val fraction = first.totalBytes?.takeIf { it > 0 }?.let { first.doneBytes.toFloat() / it }
                    val verb = if (first.download) "Saving" else "Sending"
                    val name = if (first.name.length <= 24) first.name else first.name.take(21) + "…"
                    service?.cursorTransfer("$verb “$name”" + (fraction?.let { " · ${(it * 100).toInt()}%" } ?: ""), fraction)
                    showTransferNotification(first, fraction, moving.size)
                }
            }
        }
        scope.launch {
            combine(approvals.pending, bridge.service) { pending, service -> pending to service }.collect { (pending, service) ->
                if (pending != null) {
                    service?.showApproval(pending) { choice -> approvals.answer(pending.nonce, choice) }
                } else {
                    service?.hideApproval()
                }
            }
        }
    }

    private fun showSessionNotification(state: SessionState) {
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getBroadcast(
            this, 1, Intent(this, StopReceiver::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when (state) {
            is SessionState.Active -> if (state.paused) getString(R.string.notification_paused) else getString(R.string.notification_active)
            else -> getString(R.string.notification_connecting)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_SESSION)
            .setSmallIcon(R.drawable.ic_stat_latch)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        try {
            manager.notify(NOTIFICATION_SESSION, notification)
        } catch (e: SecurityException) {
            // Notification permission withdrawn; the on-screen overlay still shows the session.
        }
    }

    /** A file moving by link: name, progress bar, and how much has moved. Never its contents. */
    private fun showTransferNotification(first: PhoneTransfers.Progress, fraction: Float?, count: Int) {
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return
        val mb = { bytes: Long -> "%.1f MB".format(bytes / (1024.0 * 1024.0)) }
        val title = (if (first.download) "Saving “${first.name}” on this phone" else "Sending “${first.name}” to your AI") +
            if (count > 1) " (+${count - 1})" else ""
        val text = first.totalBytes?.let { "${mb(first.doneBytes)} of ${mb(it)} · encrypted" } ?: "${mb(first.doneBytes)} · encrypted"
        val notification = NotificationCompat.Builder(this, CHANNEL_SESSION)
            .setSmallIcon(R.drawable.ic_stat_latch)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        try {
            manager.notify(NOTIFICATION_TRANSFER, notification)
        } catch (e: SecurityException) {
            // Notification permission withdrawn; the cursor still shows the progress.
        }
    }

    companion object {
        const val NOTIFICATION_TRANSFER = 3
        const val CHANNEL_SESSION = "session"
        const val CHANNEL_UPDATES = "updates"
        const val NOTIFICATION_UPDATED = 2
        const val NOTIFICATION_SESSION = 1
        const val ACTION_STOP = "io.github.aspershupadhyay.latch.STOP"

        fun get(context: Context): LatchApp = context.applicationContext as LatchApp
    }
}

/** Stop action on the ongoing notification. Not exported; only our PendingIntent reaches it. */
class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == LatchApp.ACTION_STOP) LatchApp.get(context).session.stop()
    }
}

/**
 * Android closes Latch while it replaces itself, so the app cannot say the
 * update worked. The system tells this receiver once the new version is in.
 */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(context, 2, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, LatchApp.CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_stat_latch)
            .setContentTitle(context.getString(R.string.notification_updated, BuildConfig.VERSION_NAME))
            .setContentText(context.getString(R.string.notification_updated_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(LatchApp.NOTIFICATION_UPDATED, notification)
        } catch (e: SecurityException) {
            // Notifications switched off; Settings still shows the new version.
        }
    }
}
