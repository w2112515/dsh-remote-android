package dev.dshremote.gate0c.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumePolicyTest {
    @Test
    fun resumesOnlyTheSameHostGenerationSessionAndProjectionVersion() {
        val projection = projection()

        assertEquals(
            ResumePlan("session-1", "stream-1", 1, 42),
            resumePlanFor("host-a", "host-a", "session-1", projection, expectedProjectionVersion = 1),
        )
        assertNull(resumePlanFor("host-a", "host-b", "session-1", projection, 1))
        assertNull(resumePlanFor("host-a", "host-a", "session-2", projection, 1))
        assertNull(resumePlanFor("host-a", "host-a", "session-1", projection, 2))
        assertNull(resumePlanFor(null, "host-a", "session-1", projection, 1))
    }

    @Test
    fun rejectsMissingStreamIdentity() {
        assertNull(
            resumePlanFor(
                "host-a",
                "host-a",
                "session-1",
                projection().copy(streamId = ""),
                1,
            ),
        )
    }

    @Test
    fun validatesTheExplicitResumeDomainAndReplayWindow() {
        val plan = ResumePlan("session-1", "stream-1", 1, 42)

        assertTrue(isValidResumeAcceptance(plan, "stream-1", 1, 42, 45))
        assertTrue(isValidResumeAcceptance(plan, "stream-1", 1, 42, 42))
        assertFalse(isValidResumeAcceptance(plan, "stream-2", 1, 42, 45))
        assertFalse(isValidResumeAcceptance(plan, "stream-1", 2, 42, 45))
        assertFalse(isValidResumeAcceptance(plan, "stream-1", 1, 41, 45))
        assertFalse(isValidResumeAcceptance(plan, "stream-1", 1, 42, 41))
    }

    @Test
    fun projectedEventsCannotEscapeTheAcceptedStreamDomain() {
        assertTrue(isExpectedProjectedEventDomain("stream-1", 1, "session-1", "stream-1", 1, "session-1"))
        assertFalse(isExpectedProjectedEventDomain("stream-1", 1, "session-1", "stream-2", 1, "session-1"))
        assertFalse(isExpectedProjectedEventDomain("stream-1", 1, "session-1", "stream-1", 2, "session-1"))
        assertFalse(isExpectedProjectedEventDomain("stream-1", 1, "session-1", "stream-1", 1, "session-2"))
        assertFalse(isExpectedProjectedEventDomain(null, 1, "session-1", "stream-1", 1, "session-1"))
    }

    private fun projection() = CachedSessionProjection(
        sessionId = "session-1",
        title = "Session",
        running = false,
        streamId = "stream-1",
        projectionVersion = 1,
        cursor = 42,
        timeline = emptyList(),
        historyTruncated = false,
        cacheTruncated = false,
        savedAtMs = 1_000,
    )
}
