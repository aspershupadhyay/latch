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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Process-wide singletons, wired by hand: the app is small enough that a
 * dependency-injection framework would add more than it removes.
 */
class LatchApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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
    val http = Pairing.client()

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        grants = ApprovalGrants(PrefsGrantStore(this))
        val consequences = Consequences(PolicyWords.parse(assets.open("words.json").bufferedReader().use { it.readText() }))
        session = SessionController(scope, settings, bridge, approvals, log, http, grants, consequences, ::appLabel)
        setup = GatewaySetup(http)
        createChannels()
        observeIndicators()
    }

    /** The name an app shows in the launcher, or null if it has none. */
    fun appLabel(packageName: String): String? = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString().take(40)
    }.getOrNull()

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SESSION, getString(R.string.channel_session), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.channel_session_description)
            },
        )
    }

    /** Keeps the overlay, the approval card, and the ongoing notification in step with the session. */
    private fun observeIndicators() {
        scope.launch {
            combine(session.state, bridge.service) { state, service -> state to service }.collect { (state, service) ->
                val live = state is SessionState.Active || state is SessionState.Reconnecting || state is SessionState.Connecting
                if (live) {
                    service?.showOverlay { session.stop() }
                    showSessionNotification(state)
                } else {
                    service?.hideOverlay()
                    NotificationManagerCompat.from(this@LatchApp).cancel(NOTIFICATION_SESSION)
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

    companion object {
        const val CHANNEL_SESSION = "session"
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
