// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.view.Surface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import io.github.aspershupadhyay.latch.protocol.Capability
import io.github.aspershupadhyay.latch.protocol.ControlLevel
import io.github.aspershupadhyay.latch.protocol.CapabilityStatus
import io.github.aspershupadhyay.latch.protocol.DeviceStatus
import io.github.aspershupadhyay.latch.protocol.ErrorCode
import io.github.aspershupadhyay.latch.protocol.PhoneControl
import io.github.aspershupadhyay.latch.protocol.ProtocolException
import kotlin.math.roundToInt

/**
 * Phone controls (protocol 1.11, roadmap R16; more in 1.15, R25), each group
 * behind its own owner switch: volume, media keys, the ringer, and Do Not
 * Disturb (Sound); brightness, rotation, and the screen timeout (Screen); the
 * flashlight; and settings panels and pages. Only public Android APIs.
 */
object PhoneControls {
    /**
     * Applies an off-screen control. Panels open over the app instead: see [panelIntent].
     * Answers the volume step Android actually set, for a volume change.
     */
    fun apply(context: Context, control: PhoneControl): ControlLevel? {
        when (control) {
            is PhoneControl.Volume -> return volume(context, control.stream, control.percent)
            is PhoneControl.Media -> media(context, control.action)
            is PhoneControl.Brightness -> brightness(context, control.percent)
            is PhoneControl.Flashlight -> flashlight(context, control.on)
            is PhoneControl.Rotation -> rotation(context, control.mode)
            is PhoneControl.ScreenTimeout -> systemSetting(context, Settings.System.SCREEN_OFF_TIMEOUT, control.seconds * 1000)
            is PhoneControl.Ringer -> ringer(context, control.mode)
            is PhoneControl.DoNotDisturb -> doNotDisturb(context, control.on)
            is PhoneControl.Panel -> error("panels open as a screen action")
        }
        return null
    }

    /** Refuses a panel this phone cannot show, e.g. NFC on a phone without NFC. */
    fun requirePanel(context: Context, panel: String) {
        if (panel == "nfc" && android.nfc.NfcAdapter.getDefaultAdapter(context) == null) {
            unsupported("this phone has no NFC")
        }
        if (panelIntent(panel).resolveActivity(context.packageManager) == null) {
            unsupported("this phone has no ${panel.replace('_', ' ')} settings panel")
        }
    }

    /** The settings panel or page to start, shown over the current app. */
    fun panelIntent(panel: String): Intent = Intent(
        when (panel) {
            "wifi" -> Settings.Panel.ACTION_WIFI
            "internet" -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY
            "volume" -> Settings.Panel.ACTION_VOLUME
            "nfc" -> Settings.Panel.ACTION_NFC
            "airplane_mode" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            // The phone's notification settings, never the notification shade.
            "notifications" -> "android.settings.NOTIFICATION_SETTINGS"
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "date_time" -> Settings.ACTION_DATE_SETTINGS
            // Android has no Bluetooth panel; its settings page is the closest.
            else -> Settings.ACTION_BLUETOOTH_SETTINGS
        },
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Android's own screen where the owner lets Latch change the ringer and Do Not Disturb. */
    fun doNotDisturbAccessIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Whether the owner let Latch change the ringer and Do Not Disturb. */
    fun canChangeDoNotDisturb(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true

    /** What the phone can offer for [capability] beyond the owner's switch, or null when nothing is missing. */
    fun limit(context: Context, capability: Capability): CapabilityStatus? = when (capability) {
        Capability.CONTROL_BRIGHTNESS -> if (Settings.System.canWrite(context)) null else CapabilityStatus.NEEDS_PERMISSION
        Capability.CONTROL_FLASHLIGHT -> if (torchCamera(context) != null) null else CapabilityStatus.UNSUPPORTED
        else -> null
    }

    /** Android's own screen where the owner lets Latch change the brightness. */
    fun writeSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun volume(context: Context, stream: String, percent: Int): ControlLevel {
        val audio = context.getSystemService(AudioManager::class.java) ?: unsupported("this phone has no audio service")
        val type = when (stream) {
            "ring" -> AudioManager.STREAM_RING
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "alarm" -> AudioManager.STREAM_ALARM
            else -> AudioManager.STREAM_MUSIC
        }
        val max = audio.getStreamMaxVolume(type)
        // Percent of the whole slider, from 0, the way phones draw it. Any level above 0
        // is at least the first step: a small percentage never mutes the stream.
        val index = (max * percent / 100.0).roundToInt().coerceIn(if (percent > 0) 1 else 0, max)
        try {
            // A muted stream stays silent at any level until it is unmuted.
            if (index > 0 && audio.isStreamMute(type)) audio.adjustStreamVolume(type, AudioManager.ADJUST_UNMUTE, 0)
            audio.setStreamVolume(type, index, 0)
            // Some phones (realme) report a lowest step for the alarm above the one their own
            // slider reaches, and raise a direct setting to it. Stepping down one at a time
            // reaches the lower steps on those phones; elsewhere Android simply stops.
            var guard = 0
            while (audio.getStreamVolume(type) > index && guard++ < max) {
                val before = audio.getStreamVolume(type)
                audio.adjustStreamVolume(type, AudioManager.ADJUST_LOWER, 0)
                if (audio.getStreamVolume(type) >= before) break
            }
        } catch (_: SecurityException) {
            // Android refuses ring and notification changes that would turn Do Not Disturb on or off.
            throw ProtocolException(ErrorCode.POLICY_REFUSED, "Android did not allow this volume change while Do Not Disturb is on")
        }
        val now = audio.getStreamVolume(type)
        if (index > 0 && (now == 0 || audio.isStreamMute(type))) {
            throw ProtocolException(
                ErrorCode.POLICY_REFUSED,
                "Android kept the $stream volume muted (Do Not Disturb, a silent mode, or a connected device may hold it); nothing changed",
            )
        }
        return ControlLevel(step = now, maxStep = max, percent = if (max > 0) (now * 100.0 / max).roundToInt() else 0)
    }

    private fun media(context: Context, action: String) {
        val audio = context.getSystemService(AudioManager::class.java) ?: unsupported("this phone has no audio service")
        val code = when (action) {
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }

    private fun requireWriteSettings(context: Context) {
        if (!Settings.System.canWrite(context)) {
            throw ProtocolException(
                ErrorCode.PERMISSION_MISSING,
                "the owner has not let Latch change system settings (Latch → Access → Phone controls)",
            )
        }
    }

    private fun systemSetting(context: Context, name: String, value: Int) {
        requireWriteSettings(context)
        Settings.System.putInt(context.contentResolver, name, value)
    }

    private fun rotation(context: Context, mode: String) {
        requireWriteSettings(context)
        val resolver = context.contentResolver
        if (mode == "auto") {
            Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 1)
            return
        }
        Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0)
        Settings.System.putInt(resolver, Settings.System.USER_ROTATION, if (mode == "landscape") Surface.ROTATION_90 else Surface.ROTATION_0)
    }

    private fun requireDoNotDisturbAccess(context: Context) {
        if (!canChangeDoNotDisturb(context)) {
            throw ProtocolException(
                ErrorCode.PERMISSION_MISSING,
                "the owner has not let Latch change Do Not Disturb (Latch → Access → Phone controls)",
            )
        }
    }

    private fun ringer(context: Context, mode: String) {
        val audio = context.getSystemService(AudioManager::class.java) ?: unsupported("this phone has no audio service")
        requireDoNotDisturbAccess(context)
        try {
            audio.ringerMode = when (mode) {
                "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                "silent" -> AudioManager.RINGER_MODE_SILENT
                else -> AudioManager.RINGER_MODE_NORMAL
            }
        } catch (_: SecurityException) {
            throw ProtocolException(ErrorCode.PERMISSION_MISSING, "Android did not let Latch change the ringer")
        }
    }

    private fun doNotDisturb(context: Context, on: Boolean) {
        val notifications = context.getSystemService(NotificationManager::class.java) ?: unsupported("this phone has no notification service")
        requireDoNotDisturbAccess(context)
        notifications.setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL,
        )
    }

    private fun brightness(context: Context, percent: Int) {
        requireWriteSettings(context)
        val resolver = context.contentResolver
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, (percent * 255 / 100.0).roundToInt().coerceIn(1, 255))
    }

    private fun flashlight(context: Context, on: Boolean) {
        val cameras = context.getSystemService(CameraManager::class.java) ?: unsupported("this phone has no camera service")
        val id = torchCamera(context) ?: unsupported("this phone has no flashlight")
        try {
            cameras.setTorchMode(id, on)
        } catch (_: android.hardware.camera2.CameraAccessException) {
            // Another app is using the camera.
            throw ProtocolException(ErrorCode.INTERNAL, "the flashlight is busy: another app is using the camera")
        }
    }

    private fun torchCamera(context: Context): String? {
        val cameras = context.getSystemService(CameraManager::class.java) ?: return null
        return runCatching {
            cameras.cameraIdList.firstOrNull { cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        }.getOrNull()
    }

    private fun unsupported(message: String): Nothing = throw ProtocolException(ErrorCode.UNSUPPORTED_CAPABILITY, message)

    /**
     * The phone's state for `device.info` (protocol 1.15): battery, network
     * type, free storage, orientation, lock screen, Do Not Disturb. Nothing
     * personal: no network names, addresses, or app content. Whatever Android
     * will not say is left out.
     */
    fun status(context: Context): DeviceStatus {
        val battery = context.getSystemService(BatteryManager::class.java)
        val percent = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = runCatching {
            val caps = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
            when {
                connectivity == null -> null
                caps == null -> "none"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> null
            }
        }.getOrNull()
        val free = runCatching { StatFs(Environment.getDataDirectory().path).availableBytes / (1024 * 1024) }.getOrNull()
        val orientation = when (context.resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> "landscape"
            Configuration.ORIENTATION_PORTRAIT -> "portrait"
            else -> null
        }
        val filter = context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
        return DeviceStatus(
            batteryPercent = percent,
            charging = battery?.isCharging,
            network = network,
            freeStorageMb = free,
            orientation = orientation,
            locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked,
            doNotDisturb = filter?.takeIf { it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN }
                ?.let { it != NotificationManager.INTERRUPTION_FILTER_ALL },
        )
    }
}
