// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import io.github.aspershupadhyay.latch.session.adb.pairingCode
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * R29 (c): pair full-access ADB without leaving Android's pairing dialog.
 *
 * Android closes Developer options → Wireless debugging → Pair device with a
 * code as soon as another app comes to the front, and the code goes with it.
 * So Latch puts a reply box in a notification instead: the owner pulls down the
 * shade over the dialog, types the six digits, and Latch pairs in the
 * background, finding the pairing service over mDNS as the in-app form does.
 * The result replaces the notification; a wrong code can be typed again there.
 */
object AdbPairingNotice {
    const val CHANNEL = "adb_pairing"
    private const val NOTIFICATION = 4
    private const val KEY_CODE = "code"
    private const val ACTION_CODE = "io.github.aspershupadhyay.latch.ADB_PAIRING_CODE"

    /** Shows (or updates) the reply box; [status] is the last attempt's result. Returns false without notifications. */
    fun show(context: Context, status: String? = null): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        val input = RemoteInput.Builder(KEY_CODE).setLabel(context.getString(R.string.pairing_code_label)).build()
        // Mutable: Android writes the typed text into this intent.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val reply = PendingIntent.getBroadcast(
            context, 5, Intent(context, AdbPairingReceiver::class.java).setAction(ACTION_CODE), flags,
        )
        val action = NotificationCompat.Action.Builder(0, context.getString(R.string.pairing_code_action), reply)
            .addRemoteInput(input)
            .setAllowGeneratedReplies(false)
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_latch)
            .setContentTitle(context.getString(R.string.pairing_notice_title))
            .setContentText(status ?: context.getString(R.string.pairing_notice_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(status ?: context.getString(R.string.pairing_notice_text)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(status != null)
            .addAction(action)
            .build()
        return try {
            manager.notify(NOTIFICATION, notification)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    /** Pairing finished: says so, without the reply box. */
    fun done(context: Context, message: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_latch)
            .setContentTitle(context.getString(R.string.pairing_notice_title))
            .setContentText(message)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION, notification)
        } catch (e: SecurityException) {
            // Notifications switched off meanwhile; Access shows the pairing state.
        }
    }

    internal fun code(intent: Intent): CharSequence? = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)

    internal fun isCode(intent: Intent) = intent.action == ACTION_CODE
}

/** Receives the code typed into [AdbPairingNotice] and pairs in the background. */
class AdbPairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!AdbPairingNotice.isCode(intent)) return
        val code = pairingCode(AdbPairingNotice.code(intent))
        if (code == null) {
            AdbPairingNotice.show(context, context.getString(R.string.pairing_notice_bad_code))
            return
        }
        AdbPairingNotice.show(context, context.getString(R.string.pairing_notice_working))
        val app = LatchApp.get(context)
        val pending = goAsync()
        app.scope.launch {
            try {
                // A receiver may run about 10 s; discovery plus pairing usually takes 1-3 s.
                val message = withTimeoutOrNull(9_000) { app.session.pairAdb(code, null, null) }
                when {
                    message == null -> AdbPairingNotice.show(context, context.getString(R.string.pairing_notice_slow))
                    app.settings.preferences.value.adbEndpoint != null -> AdbPairingNotice.done(context, message)
                    else -> AdbPairingNotice.show(context, message)
                }
            } catch (e: Exception) {
                AdbPairingNotice.show(context, "Pairing failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }
}
