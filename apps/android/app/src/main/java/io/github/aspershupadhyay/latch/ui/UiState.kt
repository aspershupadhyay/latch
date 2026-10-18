// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.ui

import io.github.aspershupadhyay.latch.update.UpdateInfo
import io.github.aspershupadhyay.latch.update.UpdateState
import io.github.aspershupadhyay.latch.data.ActivityEntry
import io.github.aspershupadhyay.latch.protocol.Capability
import io.github.aspershupadhyay.latch.session.McpClient
import io.github.aspershupadhyay.latch.session.RelayPhone
import io.github.aspershupadhyay.latch.session.NewMcpClient
import io.github.aspershupadhyay.latch.session.ApprovalChoice
import io.github.aspershupadhyay.latch.session.PendingApproval
import io.github.aspershupadhyay.latch.session.SignInRequest

/** Plain state for each screen, so screens are previewable and snapshot-tested. */

enum class Phase { IDLE, CONNECTING, ACTIVE, PAUSED, RECONNECTING, REVOKED, FAILED }

data class HomeState(
    val phase: Phase,
    val headline: String,
    val detail: String,
    val minutesLeft: Long?,
    val sessionMinutes: Int,
    val enabled: List<Capability>,
    val accessibilityOn: Boolean,
    val gatewayHost: String,
    val encrypted: Boolean,
    val phoneName: String,
    val pending: PendingApproval?,
    val lastActivity: ActivityEntry?,
    val activityCount: Int,
    val isOwner: Boolean,
    val reducedMotion: Boolean,
    /** AI apps waiting for the owner's approval. */
    val signInRequests: List<SignInRequest> = emptyList(),
    /** The gateway's protocol when it is older than the app's; the AI then misses newer tools. */
    val outdatedGateway: String? = null,
    /** R3: milliseconds per database round trip on the owner's relay, when slow. */
    val slowRelayMs: Int? = null,
    /** In-app update of Latch itself. */
    val update: UpdateState = UpdateState.Idle,
    /** Live mirror to the owner's browser (R30). */
    val mirror: io.github.aspershupadhyay.latch.mirror.MirrorStatus = io.github.aspershupadhyay.latch.mirror.MirrorStatus(),
)

class HomeActions(
    val start: () -> Unit = {},
    val stop: () -> Unit = {},
    val setPaused: (Boolean) -> Unit = {},
    val extend: () -> Unit = {},
    val setMinutes: (Int) -> Unit = {},
    val answer: (String, ApprovalChoice) -> Unit = { _, _ -> },
    val openAccessibilitySettings: () -> Unit = {},
    val openAppInfo: () -> Unit = {},
    val goCapabilities: () -> Unit = {},
    val goConnect: () -> Unit = {},
    val goActivity: () -> Unit = {},
    val openSetup: () -> Unit = {},
    val reviewSignIns: () -> Unit = {},
    val openGatewayUpdateHelp: () -> Unit = {},
    val openRelaySpeedHelp: () -> Unit = {},
    val installUpdate: (UpdateInfo) -> Unit = {},
    val startMirror: () -> Unit = {},
    val stopMirror: () -> Unit = {},
    val copyMirrorLink: () -> Unit = {},
)

data class ConnectState(
    val mcpUrl: String,
    val isOwner: Boolean,
    val clients: List<McpClient>,
    val loading: Boolean,
    val error: String?,
    val created: NewMcpClient?,
    /** AI apps waiting for the owner to approve their sign-in. */
    val requests: List<SignInRequest> = emptyList(),
    /** R18: this phone's apps, offered when limiting an AI app; empty until read. */
    val phoneApps: List<PhoneApp> = emptyList(),
    /** The relay's other paired phones, so the owner can remove old or lost ones. */
    val otherPhones: List<RelayPhone> = emptyList(),
)

/** An app on this phone, by package name and the label the owner sees. */
data class PhoneApp(val packageName: String, val label: String)

class ConnectActions(
    val create: (String) -> Unit = {},
    val revoke: (String) -> Unit = {},
    val dismissCreated: () -> Unit = {},
    val copy: (label: String, value: String, sensitive: Boolean) -> Unit = { _, _, _ -> },
    val refresh: () -> Unit = {},
    val answer: (id: String, approve: Boolean) -> Unit = { _, _ -> },
    /** R18: limit an AI app to some phone apps (package names), or null for any app switched on. */
    val limit: (id: String, apps: List<String>?) -> Unit = { _, _ -> },
    val removePhone: (id: String) -> Unit = {},
)

data class CreateGatewayState(
    val ownerKey: String,
    val keyCopied: Boolean,
    val deployOpened: Boolean,
    val address: String,
    val busy: Boolean,
    val error: String?,
)

class CreateGatewayActions(
    val copyKey: () -> Unit = {},
    val openDeploy: () -> Unit = {},
    val setAddress: (String) -> Unit = {},
    val connect: () -> Unit = {},
    val back: () -> Unit = {},
)

enum class JoinMode { OWNER_KEY, PAIRING_CODE }

data class JoinState(
    val address: String,
    val mode: JoinMode,
    val secret: String,
    val busy: Boolean,
    val error: String?,
)

class JoinActions(
    val setAddress: (String) -> Unit = {},
    val setMode: (JoinMode) -> Unit = {},
    val setSecret: (String) -> Unit = {},
    val connect: () -> Unit = {},
    val back: () -> Unit = {},
)
