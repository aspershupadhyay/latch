// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.update

import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import io.github.aspershupadhyay.latch.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Regression (2026-10-21): any app could send MainActivity an install status
 * with an intent of its choosing in EXTRA_INTENT, and Latch started it as
 * itself (intent redirection).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InstallStatusTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun status(token: String?, inner: Intent) = Intent(context, MainActivity::class.java)
        .setAction(Updater.ACTION_INSTALL_STATUS)
        .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
        .putExtra(Intent.EXTRA_INTENT, inner)
        .apply { if (token != null) putExtra(InstallStatus.EXTRA_TOKEN, token) }

    private fun updater() = Updater(context, okhttp3.OkHttpClient(), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), "https://example.invalid/m.json", "https://example.invalid/", 1)

    @Test fun aStatusWithoutLatchsTokenStartsNothing() {
        val evil = Intent(Intent.ACTION_VIEW, Uri.parse("content://io.github.aspershupadhyay.latch.secret/x"))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertNull(updater().onInstallStatus(status(null, evil)))
        assertNull(updater().onInstallStatus(status("guess", evil)))
    }

    @Test fun onlyTheMatchingTokenIsAuthentic() {
        val token = InstallStatus.newToken()
        assertEquals(64, token.length)
        assertTrue(InstallStatus.authentic(status(token, Intent()), token))
        assertFalse(InstallStatus.authentic(status(token, Intent()), InstallStatus.newToken()))
        assertFalse(InstallStatus.authentic(status(token, Intent()), null))
        assertFalse(InstallStatus.authentic(status(null, Intent()), token))
    }

    @Test fun theConfirmationScreenIsPassedOnWithoutUriGrants() {
        val confirm = Intent("android.content.pm.action.CONFIRM_INSTALL")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        confirm.clipData = ClipData.newRawUri("x", Uri.parse("content://example/x"))
        val passed = InstallStatus.confirmation(confirm)
        assertEquals("android.content.pm.action.CONFIRM_INSTALL", passed.action)
        assertEquals(0, passed.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        assertTrue(passed.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertNull(passed.clipData)
    }
}
