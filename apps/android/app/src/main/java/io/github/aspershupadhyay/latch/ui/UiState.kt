// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.ui

import io.github.aspershupadhyay.latch.update.UpdateInfo
import io.github.aspershupadhyay.latch.update.UpdateState
import io.github.aspershupadhyay.latch.data.ActivityEntry
import io.github.aspershupadhyay.latch.data.ConnectionKind
import io.github.aspershupadhyay.latch.protocol.Capability
import io.github.aspershupadhyay.latch.session.McpClient
import io.github.aspershupadhyay.latch.session.RelayPhone
import io.github.aspershupadhyay.latch.session.NewMcpClient
import io.github.aspershupadhyay.latch.session.ApprovalChoice
import io.github.aspershupadhyay.latch.session.PendingApproval
import io.github.aspershupadhyay.latch.session.SignInRequest

/** Plain state for each screen, so screens are previewable and snapshot-tested. */

enum class Phase { IDLE, CONNECTING, ACTIVE, PAUSED, RECONNECTING, REVOKED, FAILED }

/** What one finished session did, for the card Home shows when it ends. */
data class SessionSummary(val minutes: Long, val actions: Int, val apps: Int, val approvals: Int, val refused: Int)

/** Counts the log lines from [startMs] to [endMs]: the AI's actions, the apps they were in, approvals, and refusals. */
fun summarizeSession(entries: List<ActivityEntry>, startMs: Long, endMs: Long): SessionSummary {
    val during = entries.filter { it.atMs in startMs..endMs }
    val actions = during.filter { it.kind == io.github.aspershupadhyay.latch.data.ActivityKind.ACTION && it.by == io.github.aspershupadhyay.latch.data.ActivityActor.AI }
    return SessionSummary(
        minutes = ((endMs - startMs).coerceAtLeast(0) + 59_999) / 60_000,
        actions = actions.size,
        apps = actions.mapNotNull { it.app }.distinct().size,
        approvals = during.count { it.kind == io.github.aspershupadhyay.latch.data.ActivityKind.APPROVAL },
        refused = during.count { it.kind == io.github.aspershupadhyay.latch.data.ActivityKind.REFUSAL },
    )
}

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
    /** Apps switched on for the AI, and whether Auto mode opens every app. */
    val appsOn: Int = 0,
    val autoAll: Boolean = false,
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
    /** A computer that watched before comes back without a new code (ADR-058). */
    val mirrorRemembered: Boolean = false,
    /** The session that just ended, until the owner closes the card or starts another. */
    val lastSession: SessionSummary? = null,
)

class HomeActions(
    val start: () -> Unit = {},
    val stop: () -> Unit = {},
    val dismissSummary: () -> Unit = {},
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
    val openSettings: () -> Unit = {},
    val reviewSignIns: () -> Unit = {},
    val openGatewayUpdateHelp: () -> Unit = {},
    val openRelaySpeedHelp: () -> Unit = {},
    val installUpdate: (UpdateInfo) -> Unit = {},
    val startMirror: () -> Unit = {},
    val stopMirror: () -> Unit = {},
    val copyMirrorLink: () -> Unit = {},
    val forgetMirrorComputers: () -> Unit = {},
    /** ADR-069: scan the computer's mirror page; share the link; send files to the computer. */
    val scanMirror: () -> Unit = {},
    val shareMirrorLink: () -> Unit = {},
    val sendFilesToComputer: () -> Unit = {},
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
    /** How the AI reaches this phone now, and the other way if this phone keeps one (host by kind). */
    val connection: ConnectionKind? = null,
    val host: String = "",
    val saved: Map<ConnectionKind, String> = emptyMap(),
    val bothConnected: Boolean = true,
)

/** An app on this phone, by package name and the label the owner sees. */
data class PhoneApp(val packageName: String, val label: String)

class ConnectActions(
    val create: (String) -> Unit = {},
    val revoke: (String) -> Unit = {},
    val dismissCreated: () -> Unit = {},
    val copy: (label: String, value: String, sensitive: Boolean) -> Unit = { _, _, _ -> },
    /** Hands the AI link to another app through Android's share sheet. */
    val share: (String) -> Unit = {},
    val refresh: () -> Unit = {},
    val answer: (id: String, approve: Boolean) -> Unit = { _, _ -> },
    /** R18: limit an AI app to some phone apps (package names), or null for any app switched on. */
    val limit: (id: String, apps: List<String>?) -> Unit = { _, _ -> },
    val removePhone: (id: String) -> Unit = {},
    /** Switch to the kept connection of a kind, or set one up (keeping this one), or forget a kept one. */
    val switchTo: (ConnectionKind) -> Unit = {},
    val setUp: (ConnectionKind) -> Unit = {},
    val forgetSaved: (ConnectionKind) -> Unit = {},
    val setBothConnected: (Boolean) -> Unit = {},
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
    /** ADR-069: scan the QR code the relay's own page shows. */
    val scan: () -> Unit = {},
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
