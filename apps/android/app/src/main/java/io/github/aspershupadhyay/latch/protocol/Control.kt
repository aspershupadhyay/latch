// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Phone controls (protocol 1.11): volume, media keys, brightness, the Wi-Fi
 * and Bluetooth panels, and the flashlight. Each group is its own
 * capability, off until the owner switches it on. Mirrors
 * `crates/protocol/src/control.rs`, including [describe]'s words.
 */
sealed interface PhoneControl {
    val capability: Capability

    /** Only a panel changes what is on screen. */
    val changesScreen: Boolean get() = false

    enum class Stream(val wire: String) { MEDIA("media"), RING("ring"), NOTIFICATION("notification"), ALARM("alarm") }
    enum class Step(val wire: String) { UP("up"), DOWN("down"), MUTE("mute"), UNMUTE("unmute") }
    enum class MediaAction(val wire: String) {
        PLAY_PAUSE("play_pause"), PLAY("play"), PAUSE("pause"), NEXT("next"), PREVIOUS("previous"), STOP("stop"),
    }
    enum class Panel(val wire: String) { WIFI("wifi"), BLUETOOTH("bluetooth") }

    /** Exactly one of [percent] (0-100) and [step]. */
    data class Volume(val stream: Stream, val percent: Int?, val step: Step?) : PhoneControl {
        override val capability get() = Capability.CONTROL_VOLUME
    }

    data class Media(val action: MediaAction) : PhoneControl {
        override val capability get() = Capability.CONTROL_MEDIA
    }

    /** Exactly one of [percent] (0-100) and [auto]. */
    data class Brightness(val percent: Int?, val auto: Boolean?) : PhoneControl {
        override val capability get() = Capability.CONTROL_BRIGHTNESS
    }

    data class OpenPanel(val panel: Panel) : PhoneControl {
        override val capability get() = Capability.CONTROL_PANELS
        override val changesScreen get() = true
    }

    data class Flashlight(val on: Boolean) : PhoneControl {
        override val capability get() = Capability.CONTROL_FLASHLIGHT
    }

    companion object {
        private fun invalid(message: String): Nothing = throw ProtocolException(ErrorCode.INVALID_REQUEST, message)

        private fun <T> JsonObject.pick(key: String, values: Array<T>, wire: (T) -> String): T? {
            val raw = this[key] ?: return null
            if (raw is JsonNull) return null
            val text = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid("$key must be a string")
            return values.firstOrNull { wire(it) == text } ?: invalid("unknown $key")
        }

        private fun JsonObject.optInt(key: String): Int? {
            val raw = this[key] ?: return null
            if (raw is JsonNull) return null
            return (raw as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: invalid("$key must be an integer")
        }

        private fun JsonObject.optBool(key: String): Boolean? {
            val raw = this[key] ?: return null
            if (raw is JsonNull) return null
            return (raw as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: invalid("$key must be a boolean")
        }

        /** Reads `{"kind": ..., ...}`; [validate] checks the ranges. */
        fun parse(o: JsonObject): PhoneControl {
            val kind = (o["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid("control.kind must be a string")
            return when (kind) {
                "volume" -> Volume(
                    o.pick("stream", Stream.entries.toTypedArray()) { it.wire } ?: invalid("volume needs stream"),
                    o.optInt("percent"),
                    o.pick("step", Step.entries.toTypedArray()) { it.wire },
                )
                "media" -> Media(o.pick("action", MediaAction.entries.toTypedArray()) { it.wire } ?: invalid("media needs action"))
                "brightness" -> Brightness(o.optInt("percent"), o.optBool("auto"))
                "panel" -> OpenPanel(o.pick("panel", Panel.entries.toTypedArray()) { it.wire } ?: invalid("panel needs panel"))
                "flashlight" -> Flashlight(o.optBool("on") ?: invalid("flashlight needs on"))
                else -> invalid("unknown control kind")
            }
        }

        fun validate(control: PhoneControl) {
            when (control) {
                is Volume -> {
                    if ((control.percent == null) == (control.step == null)) invalid("volume takes exactly one of percent and step")
                    if (control.percent != null && control.percent !in 0..100) invalid("percent must be between 0 and 100")
                }
                is Brightness -> {
                    if ((control.percent == null) == (control.auto == null)) invalid("brightness takes exactly one of percent and auto")
                    if (control.percent != null && control.percent !in 0..100) invalid("percent must be between 0 and 100")
                }
                is Media, is OpenPanel, is Flashlight -> Unit
            }
        }
    }
}

/** Plain words for approvals, the cursor, and the activity log; the same as the gateways'. */
fun PhoneControl.describe(): String = when (this) {
    is PhoneControl.Volume -> {
        val name = stream.wire
        when {
            percent != null -> "Set the $name volume to $percent%"
            step == PhoneControl.Step.UP -> "Turn the $name volume up"
            step == PhoneControl.Step.DOWN -> "Turn the $name volume down"
            step == PhoneControl.Step.MUTE -> "Mute the $name volume"
            step == PhoneControl.Step.UNMUTE -> "Unmute the $name volume"
            else -> "Change the $name volume"
        }
    }
    is PhoneControl.Media -> when (action) {
        PhoneControl.MediaAction.PLAY_PAUSE -> "Play or pause media"
        PhoneControl.MediaAction.PLAY -> "Play media"
        PhoneControl.MediaAction.PAUSE -> "Pause media"
        PhoneControl.MediaAction.NEXT -> "Skip to the next track"
        PhoneControl.MediaAction.PREVIOUS -> "Go to the previous track"
        PhoneControl.MediaAction.STOP -> "Stop media"
    }
    is PhoneControl.Brightness -> when {
        percent != null -> "Set the screen brightness to $percent%"
        auto == true -> "Turn automatic brightness on"
        auto == false -> "Turn automatic brightness off"
        else -> "Change the screen brightness"
    }
    is PhoneControl.OpenPanel -> if (panel == PhoneControl.Panel.WIFI) "Open the Wi-Fi panel" else "Open the Bluetooth panel"
    is PhoneControl.Flashlight -> if (on) "Turn the flashlight on" else "Turn the flashlight off"
}

/** What a control reads after the change, in [ActionResult.control] (since 1.11). */
@Serializable
data class ControlReading(
    /** Volume or brightness after the change, 0-100. */
    val percent: Int? = null,
    /** Flashlight on, volume muted, or automatic brightness on. */
    val on: Boolean? = null,
)
