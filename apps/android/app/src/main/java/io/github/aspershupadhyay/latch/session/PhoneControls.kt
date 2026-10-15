// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.session

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import io.github.aspershupadhyay.latch.protocol.ControlReading
import io.github.aspershupadhyay.latch.protocol.ErrorCode
import io.github.aspershupadhyay.latch.protocol.PhoneControl
import io.github.aspershupadhyay.latch.protocol.ProtocolException
import kotlin.math.roundToInt

/**
 * Carries out phone controls (protocol 1.11) with public Android APIs only:
 * [AudioManager] for volume and media keys, the system brightness setting
 * (which needs the owner's "Modify system settings" grant), the camera's
 * torch, and the Wi-Fi and Bluetooth settings panels. Android does not let
 * apps switch Wi-Fi or Bluetooth themselves, so those open on screen.
 */
class PhoneControls(private val context: Context) {
    private val audio get() = context.getSystemService(AudioManager::class.java)
        ?: throw ProtocolException(ErrorCode.UNSUPPORTED_CAPABILITY, "this phone has no audio controls")

    /** Applies a control that stays off screen and reads its state back. */
    fun apply(control: PhoneControl): ControlReading = when (control) {
        is PhoneControl.Volume -> volume(control)
        is PhoneControl.Media -> media(control.action)
        is PhoneControl.Brightness -> brightness(control)
        is PhoneControl.Flashlight -> flashlight(control.on)
        is PhoneControl.OpenPanel -> {
            openPanel(control.panel)
            ControlReading()
        }
    }

    private fun stream(stream: PhoneControl.Stream) = when (stream) {
        PhoneControl.Stream.MEDIA -> AudioManager.STREAM_MUSIC
        PhoneControl.Stream.RING -> AudioManager.STREAM_RING
        PhoneControl.Stream.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
        PhoneControl.Stream.ALARM -> AudioManager.STREAM_ALARM
    }

    private fun volume(control: PhoneControl.Volume): ControlReading {
        val audio = audio
        val stream = stream(control.stream)
        val max = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
        val min = audio.getStreamMinVolume(stream)
        try {
            // FLAG_SHOW_UI: the owner sees the volume change like a button press.
            if (control.percent != null) {
                val index = volumeIndex(control.percent, min, max)
                audio.setStreamVolume(stream, index, AudioManager.FLAG_SHOW_UI)
            } else {
                val direction = when (control.step) {
                    PhoneControl.Step.UP -> AudioManager.ADJUST_RAISE
                    PhoneControl.Step.DOWN -> AudioManager.ADJUST_LOWER
                    PhoneControl.Step.MUTE -> AudioManager.ADJUST_MUTE
                    PhoneControl.Step.UNMUTE, null -> AudioManager.ADJUST_UNMUTE
                }
                audio.adjustStreamVolume(stream, direction, AudioManager.FLAG_SHOW_UI)
            }
        } catch (_: SecurityException) {
            // Android refuses ring and notification changes that would leave Do Not Disturb.
            throw ProtocolException(
                ErrorCode.POLICY_REFUSED,
                "Android does not let apps change this volume while Do Not Disturb is on",
            )
        }
        val muted = audio.isStreamMute(stream)
        return ControlReading(percent = if (muted) 0 else volumePercent(audio.getStreamVolume(stream), max), on = muted)
    }

    private fun media(action: PhoneControl.MediaAction): ControlReading {
        val code = when (action) {
            PhoneControl.MediaAction.PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            PhoneControl.MediaAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            PhoneControl.MediaAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            PhoneControl.MediaAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            PhoneControl.MediaAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            PhoneControl.MediaAction.STOP -> KeyEvent.KEYCODE_MEDIA_STOP
        }
        val now = SystemClock.uptimeMillis()
        val audio = audio
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        return ControlReading()
    }

    private fun brightness(control: PhoneControl.Brightness): ControlReading {
        if (!canWriteSettings(context)) {
            throw ProtocolException(
                ErrorCode.PERMISSION_MISSING,
                "the owner has not let Latch modify system settings, which brightness needs",
            )
        }
        val resolver = context.contentResolver
        if (control.percent != null) {
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, brightnessLevel(control.percent))
        } else {
            val mode = if (control.auto == true) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, mode)
        }
        val level = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        val auto = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        return ControlReading(percent = brightnessPercent(level), on = auto)
    }

    private fun flashlight(on: Boolean): ControlReading {
        val cameras = context.getSystemService(CameraManager::class.java)
            ?: throw ProtocolException(ErrorCode.UNSUPPORTED_CAPABILITY, "this phone has no flashlight")
        // The back camera's flash is the flashlight; setTorchMode needs no camera permission.
        val id = cameras.cameraIdList.firstOrNull { id ->
            val c = cameras.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: throw ProtocolException(ErrorCode.UNSUPPORTED_CAPABILITY, "this phone has no flashlight")
        try {
            cameras.setTorchMode(id, on)
        } catch (_: android.hardware.camera2.CameraAccessException) {
            throw ProtocolException(ErrorCode.TARGET_NOT_FOUND, "the flashlight is busy: an app is using the camera")
        }
        return ControlReading(on = on)
    }

    /** Opens the panel over the current app; the AI then taps the switch in it. */
    fun openPanel(panel: PhoneControl.Panel) {
        val primary = when (panel) {
            PhoneControl.Panel.WIFI -> Settings.Panel.ACTION_WIFI
            PhoneControl.Panel.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS
        }
        val fallback = when (panel) {
            PhoneControl.Panel.WIFI -> Settings.ACTION_WIFI_SETTINGS
            PhoneControl.Panel.BLUETOOTH -> Settings.ACTION_SETTINGS
        }
        // Accessibility services are exempt from background activity start limits.
        try {
            context.startActivity(Intent(primary).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(Intent(fallback).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
                throw ProtocolException(ErrorCode.UNSUPPORTED_CAPABILITY, "this phone has no such settings panel")
            }
        }
    }

    companion object {
        /** Brightness writes need the owner's "Modify system settings" grant. */
        fun canWriteSettings(context: Context): Boolean = Settings.System.canWrite(context)

        /** Opens the system page where the owner grants "Modify system settings" to Latch. */
        fun writeSettingsIntent(context: Context): Intent =
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** The stream index closest to [percent] of [max], never below the stream's [min]. */
        fun volumeIndex(percent: Int, min: Int, max: Int): Int =
            (percent * max / 100.0).roundToInt().coerceIn(min, max)

        fun volumePercent(index: Int, max: Int): Int = (index * 100.0 / max.coerceAtLeast(1)).roundToInt().coerceIn(0, 100)

        /** Android's brightness setting runs from 0 to 255; 1 is the dimmest that still shows the screen. */
        fun brightnessLevel(percent: Int): Int = (percent * 255 / 100.0).roundToInt().coerceIn(1, 255)

        fun brightnessPercent(level: Int): Int = (level * 100 / 255.0).roundToInt().coerceIn(0, 100)
    }
}
