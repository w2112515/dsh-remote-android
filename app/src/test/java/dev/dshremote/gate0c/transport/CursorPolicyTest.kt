package dev.dshremote.gate0c.transport

import org.junit.Assert.assertEquals
import org.junit.Test

class CursorPolicyTest {
    @Test
    fun `accepts only the next contiguous sequence`() {
        assertEquals(ApplyDecision.Contiguous, CursorPolicy.decide(current = 40, incoming = 41))
    }

    @Test
    fun `suppresses a duplicate or older sequence`() {
        assertEquals(ApplyDecision.Duplicate, CursorPolicy.decide(current = 41, incoming = 41))
        assertEquals(ApplyDecision.Duplicate, CursorPolicy.decide(current = 41, incoming = 39))
    }

    @Test
    fun `reports a gap without advancing the cursor`() {
        assertEquals(
            ApplyDecision.Gap(expected = 42, actual = 43),
            CursorPolicy.decide(current = 41, incoming = 43),
        )
    }
}

