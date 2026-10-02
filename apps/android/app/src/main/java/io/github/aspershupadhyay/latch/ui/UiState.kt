package io.github.aspershupadhyay.latch.ui

import io.github.aspershupadhyay.latch.data.ActivityEntry
import io.github.aspershupadhyay.latch.protocol.Capability
import io.github.aspershupadhyay.latch.session.McpClient
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
)

class HomeActions(
    val start: () -> Unit = {},
    val stop: () -> Unit = {},
    val setPaused: (Boolean) -> Unit = {},
    val setMinutes: (Int) -> Unit = {},
    val answer: (String, ApprovalChoice) -> Unit = { _, _ -> },
    val openAccessibilitySettings: () -> Unit = {},
    val openAppInfo: () -> Unit = {},
    val goCapabilities: () -> Unit = {},
    val goConnect: () -> Unit = {},
    val goActivity: () -> Unit = {},
    val openSetup: () -> Unit = {},
    val reviewSignIns: () -> Unit = {},
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
)

class ConnectActions(
    val create: (String) -> Unit = {},
    val revoke: (String) -> Unit = {},
    val dismissCreated: () -> Unit = {},
    val copy: (label: String, value: String, sensitive: Boolean) -> Unit = { _, _, _ -> },
    val refresh: () -> Unit = {},
    val answer: (id: String, approve: Boolean) -> Unit = { _, _ -> },
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
