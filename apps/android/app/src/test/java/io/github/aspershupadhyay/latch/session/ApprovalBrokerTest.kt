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
}
