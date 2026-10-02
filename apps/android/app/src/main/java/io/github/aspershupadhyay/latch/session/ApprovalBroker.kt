package io.github.aspershupadhyay.latch.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom

enum class ApprovalOutcome { APPROVED, DENIED, EXPIRED }

/** What the owner is being asked, exactly as shown. */
data class PendingApproval(
    val nonce: String,
    val title: String,
    val detail: String,
    val risk: String,
    val expiresAtMs: Long,
)

/**
 * Holds at most one outstanding approval. Answers are matched by a random
 * nonce, so a stale button press (or a replayed intent) cannot approve a
 * different request.
 */
class ApprovalBroker {
    private val _pending = MutableStateFlow<PendingApproval?>(null)
    val pending: StateFlow<PendingApproval?> = _pending.asStateFlow()
    private var answer: CompletableDeferred<Boolean>? = null
    private val random = SecureRandom()

    suspend fun request(title: String, detail: String, risk: String, timeoutMs: Long): ApprovalOutcome {
        cancel()
        val nonce = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val deferred = CompletableDeferred<Boolean>()
        answer = deferred
        _pending.value = PendingApproval(nonce, title, detail, risk, System.currentTimeMillis() + timeoutMs)
        try {
            val approved = withTimeoutOrNull(timeoutMs) { deferred.await() }
            return when (approved) {
                true -> ApprovalOutcome.APPROVED
                false -> ApprovalOutcome.DENIED
                null -> ApprovalOutcome.EXPIRED
            }
        } finally {
            if (_pending.value?.nonce == nonce) {
                _pending.value = null
                answer = null
            }
        }
    }

    /** Called by the overlay card, the notification, or the in-app sheet. */
    fun answer(nonce: String, approve: Boolean) {
        val current = _pending.value ?: return
        if (current.nonce != nonce || System.currentTimeMillis() > current.expiresAtMs) return
        answer?.complete(approve)
    }

    /** Treats any outstanding request as denied (stop, pause, disconnect, cancel). */
    fun cancel() {
        answer?.complete(false)
        answer = null
        _pending.value = null
    }
}
