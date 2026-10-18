// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.mirror

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import io.github.aspershupadhyay.latch.LatchApp

/**
 * Holds the screen capture in the foreground while mirroring runs (R30).
 * Android requires a `mediaProjection` foreground service, started before the
 * capture, with a notification the owner can stop it from. The work itself is
 * in [MirrorController]. Not exported: only Latch starts it.
 */
class MirrorService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mirror = LatchApp.get(this).mirror
        if (intent?.action == ACTION_STOP) {
            mirror.stop("You stopped mirroring")
            stopSelf()
            return START_NOT_STICKY
        }
        val data = intent?.let {
            if (Build.VERSION.SDK_INT >= 33) {
                it.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                it.getParcelableExtra(EXTRA_DATA)
            }
        }
        if (data == null) {
            // Restarted by Android without the owner's consent: a capture needs a fresh one.
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(this, LatchApp.NOTIFICATION_MIRROR, mirror.notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        mirror.begin(this, intent.getIntExtra(EXTRA_RESULT, 0), data)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        LatchApp.get(this).mirror.serviceGone()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RESULT = "result"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "io.github.aspershupadhyay.latch.MIRROR_STOP"
    }
}
