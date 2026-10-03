package io.github.aspershupadhyay.latch.session

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalBrokerTest {
    private suspend fun answered(broker: ApprovalBroker, rememberable: Boolean, choice: ApprovalChoice): ApprovalOutcome = kotlinx.coroutines.coroutineScope {
        val outcome = async { broker.request("Tap “Send”", "detail", "high", 60_000, rememberable) }
        yield()
        val pending = broker.pending.value!!
        assertEquals(rememberable, pending.rememberable)
        broker.answer(pending.nonce, choice)
        outcome.await()
    }

    @Test
    fun rememberChoicesNeedARememberableRequest() = runTest {
        val broker = ApprovalBroker()
        assertEquals(ApprovalOutcome.APPROVED_ALWAYS, answered(broker, true, ApprovalChoice.ALWAYS))
        assertEquals(ApprovalOutcome.APPROVED_SESSION, answered(broker, true, ApprovalChoice.SESSION))
        // A critical request can only ever be approved once, whatever button is forged.
        assertEquals(ApprovalOutcome.APPROVED_ONCE, answered(broker, false, ApprovalChoice.ALWAYS))
        assertEquals(ApprovalOutcome.APPROVED_ONCE, answered(broker, false, ApprovalChoice.SESSION))
        assertEquals(ApprovalOutcome.DENIED, answered(broker, true, ApprovalChoice.DENY))
    }

    @Test
    fun wrongNonceIsIgnored() = runTest {
        val broker = ApprovalBroker()
        val outcome = async { broker.request("t", "d", "high", 60_000, true) }
        yield()
        broker.answer("not-the-nonce", ApprovalChoice.ALWAYS)
        val pending = broker.pending.value!!
        broker.answer(pending.nonce, ApprovalChoice.DENY)
        assertEquals(ApprovalOutcome.DENIED, outcome.await())
    }

    @Test
    fun remoteAnswersMustQuoteTheNonceAndOfferedChoice() = runTest {
        val broker = ApprovalBroker()
        var offered: PendingApproval? = null
        broker.onRequested = { offered = it }
        val outcome = async { broker.request("Tap “Send”", "d", "high", 60_000, rememberable = false, commandId = "c_1") }
        yield()
        val pending = broker.pending.value!!
        assertEquals("c_1", offered?.commandId)
        assertEquals(listOf("once", "deny"), offered?.choices)
        // A critical request offers no "always", so a remote "always" is refused.
        assertEquals(false, broker.answerRemote(pending.nonce, "always"))
        assertEquals(false, broker.answerRemote("0".repeat(32), "once"))
        assertEquals(true, broker.answerRemote(pending.nonce, "once"))
        assertEquals(ApprovalOutcome.APPROVED_ONCE, outcome.await())
    }

    /** Protocol 1.5: a step handed to the owner is answered on the phone only. */
    @Test
    fun ownerTasksCannotBeAnsweredRemotely() = runTest {
        val broker = ApprovalBroker()
        val outcome = async {
            broker.request("Please log in", "d", "medium", 60_000, kind = ApprovalKind.OWNER_TASK, commandId = "c_2")
        }
        yield()
        val pending = broker.pending.value!!
        assertEquals(emptyList<String>(), pending.choices)
        for (choice in listOf("once", "session", "always", "deny")) assertEquals(false, broker.answerRemote(pending.nonce, choice))
        broker.answer(pending.nonce, ApprovalChoice.ONCE)
        assertEquals(ApprovalOutcome.APPROVED_ONCE, outcome.await())
    }

    @Test
    fun appQuestionsOfferSessionAlwaysOrDeny() = runTest {
        val broker = ApprovalBroker()
        val outcome = async { broker.request("Let the AI use Maps?", "d", "medium", 60_000, rememberable = true, kind = ApprovalKind.APP) }
        yield()
        val pending = broker.pending.value!!
        assertEquals(listOf("session", "always", "deny"), pending.choices)
        assertEquals(true, broker.answerRemote(pending.nonce, "always"))
        assertEquals(ApprovalOutcome.APPROVED_ALWAYS, outcome.await())
    }
}
