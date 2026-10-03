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

/** An action to approve, or an app the AI wants to use (answered "this session" or "always"). */
enum class ApprovalKind { ACTION, APP }

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
)

/**
 * Holds at most one outstanding approval. Answers are matched by a random
 * nonce, so a stale button press (or a replayed intent) cannot approve a
 * different request.
 */
class ApprovalBroker {
    private val _pending = MutableStateFlow<PendingApproval?>(null)
    val pending: StateFlow<PendingApproval?> = _pending.asStateFlow()
    private var answer: CompletableDeferred<ApprovalChoice>? = null
    private val random = SecureRandom()

    suspend fun request(
        title: String,
        detail: String,
        risk: String,
        timeoutMs: Long,
        rememberable: Boolean = false,
        appName: String? = null,
        kind: ApprovalKind = ApprovalKind.ACTION,
    ): ApprovalOutcome {
        cancel()
        val nonce = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val deferred = CompletableDeferred<ApprovalChoice>()
        answer = deferred
        _pending.value = PendingApproval(nonce, title, detail, risk, System.currentTimeMillis() + timeoutMs, rememberable, appName, kind)
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

    fun answer(nonce: String, approve: Boolean) = answer(nonce, if (approve) ApprovalChoice.ONCE else ApprovalChoice.DENY)

    /** Treats any outstanding request as denied (stop, pause, disconnect, cancel). */
    fun cancel() {
        answer?.complete(ApprovalChoice.DENY)
        answer = null
        _pending.value = null
    }
}
