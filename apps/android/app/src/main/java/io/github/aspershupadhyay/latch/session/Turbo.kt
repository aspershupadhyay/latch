// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/** Where system settings are read and written: `Settings.Global` on the phone, a map in tests. */
interface GlobalSettings {
    fun get(key: String): Float?
    /** False when Android refused the write (the permission was taken back). */
    fun put(key: String, value: Float): Boolean
}

/** Where the values from before Turbo wait until they are put back; survives a crash. */
interface Snapshots {
    fun load(): Map<String, Float>?
    fun save(values: Map<String, Float>)
    fun clear()
}

/**
 * The Turbo tier (Latch Turbo R72, ADR-074): system animations off while the
 * AI works, so each screen change lands 300–500 ms sooner. The values from
 * before are saved first, then set to 0, and put back when the session ends;
 * if Latch dies in between, the next start puts them back ([restore]). Pure,
 * so the guarantee is tested on the JVM.
 */
class Turbo(private val settings: GlobalSettings, private val snapshots: Snapshots) {
    /** True while animations are off for the AI (a snapshot is waiting to be restored). */
    val active: Boolean get() = snapshots.load() != null

    /**
     * Turns animations off, saving what was there first. Already on: nothing
     * changes. When Android refuses a write, everything is put back and the
     * answer is false: the session runs at the standard tier.
     */
    fun engage(): Boolean {
        if (snapshots.load() != null) return true
        val before = KEYS.associateWith { settings.get(it) ?: 1f }
        // Saved before anything changes, so a crash right after still restores.
        snapshots.save(before)
        if (KEYS.all { settings.put(it, 0f) }) return true
        restore()
        return false
    }

    /** Puts the saved values back. False when there was nothing to restore. */
    fun restore(): Boolean {
        val saved = snapshots.load() ?: return false
        for ((key, value) in saved) settings.put(key, value)
        snapshots.clear()
        return true
    }

    companion object {
        /** `Settings.Global` animation scales, as Developer options names them. */
        val KEYS = listOf(
            Settings.Global.WINDOW_ANIMATION_SCALE,
            Settings.Global.TRANSITION_ANIMATION_SCALE,
            Settings.Global.ANIMATOR_DURATION_SCALE,
        )

        /** Android's permission for these settings, granted once over ADB (`pm grant`). */
        const val PERMISSION = Manifest.permission.WRITE_SECURE_SETTINGS

        /** Whether the owner granted the one-time permission Turbo needs. */
        fun ready(context: Context): Boolean =
            context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

        /** The shell line that grants it, run by Latch's own full ADB or typed by the owner. */
        fun grantCommand(context: Context): String = "pm grant ${context.packageName} $PERMISSION"

        /** The phone's Turbo: `Settings.Global` and a private preferences file. */
        fun of(context: Context): Turbo {
            val resolver = context.applicationContext.contentResolver
            val prefs = context.applicationContext.getSharedPreferences("turbo", Context.MODE_PRIVATE)
            return Turbo(
                object : GlobalSettings {
                    override fun get(key: String): Float? =
                        runCatching { Settings.Global.getFloat(resolver, key) }.getOrNull()

                    override fun put(key: String, value: Float): Boolean =
                        runCatching { Settings.Global.putFloat(resolver, key, value) }.getOrDefault(false)
                },
                object : Snapshots {
                    override fun load(): Map<String, Float>? {
                        if (!prefs.getBoolean("active", false)) return null
                        return KEYS.associateWith { prefs.getFloat(it, 1f) }
                    }

                    override fun save(values: Map<String, Float>) {
                        // commit, not apply: the snapshot must be on disk before a setting changes.
                        prefs.edit().apply {
                            values.forEach { (k, v) -> putFloat(k, v) }
                            putBoolean("active", true)
                        }.commit()
                    }

                    override fun clear() {
                        prefs.edit().clear().commit()
                    }
                },
            )
        }
    }
}
