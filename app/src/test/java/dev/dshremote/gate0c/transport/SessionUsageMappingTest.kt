package dev.dshremote.gate0c.transport

import dev.dshremote.protocol.v1alpha.ContextPressure
import dev.dshremote.protocol.v1alpha.SessionStats
import dev.dshremote.protocol.v1alpha.SessionSummary
import dev.dshremote.protocol.v1alpha.SessionUsage
import dev.dshremote.protocol.v1alpha.TokenUsage
import dev.dshremote.protocol.v1alpha.UsageChanged
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionUsageMappingTest {
    @Test
    fun directoryEntryMapsEveryProvidedUnitAndKeepsAbsence() {
        val metered = sessionDirectoryEntries(
            listOf(
                SessionSummary.newBuilder()
                    .setSessionId("metered")
                    .setRunning(true)
                    .setUpdatedAtMs(7)
                    .setUsage(
                        SessionUsage.newBuilder()
                            .setTokenUsage(tokenUsage(96, 6, 640, 4))
                            .setContextPressure(
                                ContextPressure.newBuilder()
                                    .setPressureTokens(740)
                                    .setProjectedTokens(748)
                                    .setContextWindow(128_000),
                            )
                            .setStats(stats(turns = 2, steps = 5, llmMs = 9_000, toolMs = 300)),
                    )
                    .build(),
                SessionSummary.newBuilder()
                    .setSessionId("unmetered")
                    .setRunning(false)
                    .setUpdatedAtMs(3)
                    .build(),
            ),
        )

        val usage = metered.single { it.sessionId == "metered" }.usage
        assertEquals(742L + 4, usage?.tokens?.totalTokens)
        assertEquals(644L, usage?.tokens?.cacheTokens)
        assertEquals(128_000L - 748L, usage?.pressure?.contextLeft)
        assertEquals(5L, usage?.stats?.steps)
        assertNull(metered.single { it.sessionId == "unmetered" }.usage)
    }

    @Test
    fun contextPressureKeepsPerFieldAbsence() {
        val usage = sessionUsageProjectionOf(
            SessionUsage.newBuilder()
                .setContextPressure(ContextPressure.newBuilder().setProjectedTokens(100))
                .build(),
        )

        assertNull(usage.pressure?.pressureTokens)
        assertEquals(100L, usage.pressure?.projectedTokens)
        assertNull(usage.pressure?.contextWindow)
        assertNull(usage.pressure?.contextLeft)
        assertNull(usage.tokens)
        assertNull(usage.stats)
    }

    @Test
    fun usageChangedMergesOnlyTheUnitsItCarries() {
        val base = SessionUsageProjection(
            tokens = TokenUsageProjection(
                uncachedInputTokens = 96,
                outputTokens = 6,
                cacheReadTokens = 640,
                cacheWriteTokens = 0,
            ),
            pressure = ContextPressureProjection(
                pressureTokens = 736,
                projectedTokens = 740,
                contextWindow = 128_000,
            ),
            stats = SessionStatsProjection(turns = 1, steps = 1, llmMs = 1_200, toolMs = 0),
        )

        // A token-only update replaces tokens but keeps pressure and stats.
        val tokenOnly = base.mergedWith(
            UsageChanged.newBuilder().setTokenUsage(tokenUsage(106, 7, 640, 4)).build(),
        )
        assertEquals(106L, tokenOnly.tokens?.uncachedInputTokens)
        assertEquals(4L, tokenOnly.tokens?.cacheWriteTokens)
        assertEquals(128_000L, tokenOnly.pressure?.contextWindow)
        assertEquals(1L, tokenOnly.stats?.turns)

        // A stats-only update on an unmetered base introduces stats without
        // inventing zero token buckets.
        val statsOnly = null.mergedWith(
            UsageChanged.newBuilder().setStats(stats(turns = 2, steps = 2, llmMs = 2_000, toolMs = 10)).build(),
        )
        assertNull(statsOnly.tokens)
        assertNull(statsOnly.pressure)
        assertEquals(2L, statsOnly.stats?.turns)
        assertEquals(10L, statsOnly.stats?.toolMs)
    }

    @Test
    fun replaceSessionUsageSetsAndClearsWithoutTouchingOrder() {
        val entries = listOf(
            SessionDirectoryEntry("a", null, running = true, updatedAtMs = 2, workspaceLabel = null),
            SessionDirectoryEntry("b", null, running = false, updatedAtMs = 1, workspaceLabel = null),
        )
        val usage = SessionUsageProjection(
            tokens = TokenUsageProjection(1, 2, 3, 4),
            pressure = null,
            stats = null,
        )

        val set = entries.replaceSessionUsage("b", usage)
        assertEquals(listOf("a", "b"), set.map { it.sessionId })
        assertEquals(10L, set.single { it.sessionId == "b" }.usage?.tokens?.totalTokens)
        assertNull(set.single { it.sessionId == "a" }.usage)

        val cleared = set.replaceSessionUsage("b", null)
        assertNull(cleared.single { it.sessionId == "b" }.usage)
    }

    private fun tokenUsage(
        uncachedInput: Long,
        output: Long,
        cacheRead: Long,
        cacheWrite: Long,
    ): TokenUsage = TokenUsage.newBuilder()
        .setUncachedInputTokens(uncachedInput)
        .setOutputTokens(output)
        .setCacheReadTokens(cacheRead)
        .setCacheWriteTokens(cacheWrite)
        .build()

    private fun stats(turns: Long, steps: Long, llmMs: Long, toolMs: Long): SessionStats =
        SessionStats.newBuilder()
            .setTurns(turns)
            .setSteps(steps)
            .setLlmMs(llmMs)
            .setToolMs(toolMs)
            .build()
}
