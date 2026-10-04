package io.github.aspershupadhyay.latch.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom

/** The owner's answer. SESSION and ALWAYS are only possible when the request was rememberable. */
enum class ApprovalChoice { DENY, ONCE, SESSION, ALWAYS }

enum class ApprovalOutcome { APPROVED_ONCE, APPROVED_SESSION, APPROVED_ALWAYS, DENIED, EXPIRED }

/**
 * An action to approve, an app the AI wants to use (answered "this session"
 * or "always"), or a step the AI hands to the owner (answered Done or I
 * can't, only on the phone; since 1.5).
 */
enum class ApprovalKind { ACTION, APP, OWNER_TASK }

/** What the owner is being asked, exactly as shown. */
data class PendingApproval(
    val nonce: String,
    val title: String,
    val detail: String,
    val risk: String,
    val expiresAtMs: Long,
    /** The owner may answer "for this session" or "always in this app". */
    val rememberable: Boolean = false,
    /** App the action happens in, for the "Always in …" button. */
    val appName: String? = null,
    val kind: ApprovalKind = ApprovalKind.ACTION,
    /** The gateway command waiting on this answer, if any. */
    val commandId: String? = null,
) {
    /** What the owner may answer, in protocol words (protocol 1.4). */
    val choices: List<String>
        get() = when {
            // Only the owner, on the phone, can say a step is done.
            kind == ApprovalKind.OWNER_TASK -> emptyList()
            kind == ApprovalKind.APP -> listOf("session", "always", "deny")
            rememberable -> listOf("once", "session", "always", "deny")
            else -> listOf("once", "deny")
        }
}

/**
 * Holds at most one outstanding approval. Answers are matched by a random
 * nonce, so a stale button press (or a replayed intent) cannot approve a
 * different request.
 */
class ApprovalBroker {
    private val _pending = MutableStateFlow<PendingApproval?>(null)
    val pending: StateFlow<PendingApproval?> = _pending.asStateFlow()
    @Volatile private var answer: CompletableDeferred<ApprovalChoice>? = null
    private val random = SecureRandom()

    /** Told about every new request, e.g. to offer it in the AI app as well (protocol 1.4). */
    var onRequested: ((PendingApproval) -> Unit)? = null

    suspend fun request(
        title: String,
        detail: String,
        risk: String,
        timeoutMs: Long,
        rememberable: Boolean = false,
        appName: String? = null,
        kind: ApprovalKind = ApprovalKind.ACTION,
        commandId: String? = null,
    ): ApprovalOutcome {
        cancel()
        val nonce = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val deferred = CompletableDeferred<ApprovalChoice>()
        answer = deferred
        _pending.value = PendingApproval(nonce, title, detail, risk, System.currentTimeMillis() + timeoutMs, rememberable, appName, kind, commandId)
        _pending.value?.let { p -> onRequested?.invoke(p) }
        try {
            val choice = withTimeoutOrNull(timeoutMs) { deferred.await() }
            return when (choice) {
                null -> ApprovalOutcome.EXPIRED
                ApprovalChoice.DENY -> ApprovalOutcome.DENIED
                ApprovalChoice.ONCE -> ApprovalOutcome.APPROVED_ONCE
                // A forged or stale "remember" on a request that may not be remembered counts once.
                ApprovalChoice.SESSION -> if (rememberable) ApprovalOutcome.APPROVED_SESSION else ApprovalOutcome.APPROVED_ONCE
                ApprovalChoice.ALWAYS -> if (rememberable) ApprovalOutcome.APPROVED_ALWAYS else ApprovalOutcome.APPROVED_ONCE
            }
        } finally {
            if (_pending.value?.nonce == nonce) {
                _pending.value = null
                answer = null
            }
        }
    }

    /** Called by the overlay card or the in-app card. */
    fun answer(nonce: String, choice: ApprovalChoice) {
        val current = _pending.value ?: return
        if (current.nonce != nonce || System.currentTimeMillis() > current.expiresAtMs) return
        answer?.complete(choice)
    }

    /** An answer given in the AI app (protocol 1.4), in protocol words. */
    fun answerRemote(nonce: String, choice: String): Boolean {
        val pending = _pending.value ?: return false
        if (pending.nonce != nonce || choice !in pending.choices) return false
        answer(
            nonce,
            when (choice) {
                "once" -> ApprovalChoice.ONCE
                "session" -> ApprovalChoice.SESSION
                "always" -> ApprovalChoice.ALWAYS
                else -> ApprovalChoice.DENY
            },
        )
        return true
    }

    fun answer(nonce: String, approve: Boolean) = answer(nonce, if (approve) ApprovalChoice.ONCE else ApprovalChoice.DENY)

    /** Treats any outstanding request as denied (stop, pause, disconnect, cancel). */
    fun cancel() {
        answer?.complete(ApprovalChoice.DENY)
        answer = null
        _pending.value = null
    }
}
