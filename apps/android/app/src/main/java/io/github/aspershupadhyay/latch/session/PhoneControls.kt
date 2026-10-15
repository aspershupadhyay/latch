// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import io.github.aspershupadhyay.latch.protocol.Capability
import io.github.aspershupadhyay.latch.protocol.CapabilityStatus
import io.github.aspershupadhyay.latch.protocol.ErrorCode
import io.github.aspershupadhyay.latch.protocol.PhoneControl
import io.github.aspershupadhyay.latch.protocol.ProtocolException
import kotlin.math.roundToInt

/**
 * Phone controls (protocol 1.11, roadmap R16), each behind its own owner
 * switch: volume and media keys, brightness, the flashlight, and the settings
 * panels. Only public Android APIs; nothing here reads anything back.
 */
object PhoneControls {
    /** Applies an off-screen control. Panels open over the app instead: see [panelIntent]. */
    fun apply(context: Context, control: PhoneControl) {
        when (control) {
            is PhoneControl.Volume -> volume(context, control.stream, control.percent)
            is PhoneControl.Media -> media(context, control.action)
            is PhoneControl.Brightness -> brightness(context, control.percent)
            is PhoneControl.Flashlight -> flashlight(context, control.on)
            is PhoneControl.Panel -> error("panels open as a screen action")
        }
    }

    /** The settings panel to start, shown over the current app. */
    fun panelIntent(panel: String): Intent = Intent(
        when (panel) {
            "wifi" -> Settings.Panel.ACTION_WIFI
            "internet" -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY
            "volume" -> Settings.Panel.ACTION_VOLUME
            // Android has no Bluetooth panel; its settings page is the closest.
            else -> Settings.ACTION_BLUETOOTH_SETTINGS
        },
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

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

    private fun volume(context: Context, stream: String, percent: Int) {
        val audio = context.getSystemService(AudioManager::class.java) ?: unsupported("this phone has no audio service")
        val type = when (stream) {
            "ring" -> AudioManager.STREAM_RING
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "alarm" -> AudioManager.STREAM_ALARM
            else -> AudioManager.STREAM_MUSIC
        }
        val min = audio.getStreamMinVolume(type)
        val max = audio.getStreamMaxVolume(type)
        val index = (min + (max - min) * percent / 100.0).roundToInt()
        try {
            audio.setStreamVolume(type, index, 0)
        } catch (_: SecurityException) {
            // Android refuses ring and notification changes that would turn Do Not Disturb on or off.
            throw ProtocolException(ErrorCode.POLICY_REFUSED, "Android did not allow this volume change while Do Not Disturb is on")
        }
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

    private fun brightness(context: Context, percent: Int) {
        if (!Settings.System.canWrite(context)) {
            throw ProtocolException(
                ErrorCode.PERMISSION_MISSING,
                "the owner has not let Latch change the brightness (Latch → Access → Phone controls)",
            )
        }
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
}
