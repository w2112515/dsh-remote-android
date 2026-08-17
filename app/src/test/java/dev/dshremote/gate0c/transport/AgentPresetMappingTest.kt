package dev.dshremote.gate0c.transport

import dev.dshremote.protocol.v1alpha.AgentPresetEntry
import dev.dshremote.protocol.v1alpha.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S-mode-select mapping: the connect-time preset roster and per-session
 * log-resolved preset keep their per-half absence semantics — a missing name
 * falls back to the id, and a missing preset is never rendered as a default.
 */
class AgentPresetMappingTest {
    @Test
    fun rosterMapsTrustAndOptionalHalvesWithoutInventingFields() {
        val presets = agentPresetProjections(
            listOf(
                AgentPresetEntry.newBuilder()
                    .setId("standard")
                    .setTrust(AgentPresetEntry.Trust.TRUST_SYSTEM)
                    .setIsDefault(true)
                    .setName("标准模式")
                    .setDescription("功能完整的编码 Agent")
                    .build(),
                AgentPresetEntry.newBuilder()
                    .setId("code")
                    .setTrust(AgentPresetEntry.Trust.TRUST_SYSTEM)
                    .setIsDefault(false)
                    .setName("PTC 模式")
                    .build(),
                AgentPresetEntry.newBuilder()
                    .setId("my-experiment")
                    .setTrust(AgentPresetEntry.Trust.TRUST_USER)
                    .setIsDefault(false)
                    .setBroken("composition text is not valid YAML")
                    .build(),
            ),
        )

        assertEquals(
            AgentPresetProjection(
                id = "standard",
                userTrust = false,
                isDefault = true,
                name = "标准模式",
                description = "功能完整的编码 Agent",
            ),
            presets[0],
        )
        assertEquals("PTC 模式", presets[1].displayName)
        assertNull(presets[1].description)
        assertNull(presets[1].broken)
        assertTrue(presets[1].selectable)
        // A locally authored preset is exactly as privileged as the plugins it
        // names — the trust fact survives the mapping so the UI can say so.
        assertTrue(presets[2].userTrust)
        assertEquals("my-experiment", presets[2].displayName)
        assertFalse(presets[2].selectable)
    }

    @Test
    fun labelFallsBackToTheRawIdAndNeverToADefault() {
        val presets = listOf(
            AgentPresetProjection(id = "minimal", userTrust = false, isDefault = false, name = "极简模式"),
        )

        assertEquals("极简模式", agentPresetLabel(presets, "minimal"))
        assertEquals("cordis", agentPresetLabel(presets, "cordis"))
        assertNull(agentPresetLabel(presets, null))
    }

    @Test
    fun directoryRowsCarryTheLogResolvedPresetOnlyWhenPresent() {
        val entries = sessionDirectoryEntries(
            listOf(
                summary(id = "with-preset", agentPreset = "code"),
                summary(id = "without-preset"),
            ),
        )

        assertEquals("code", entries.single { it.sessionId == "with-preset" }.agentPreset)
        assertNull(entries.single { it.sessionId == "without-preset" }.agentPreset)
    }

    @Test
    fun replaceAgentPresetTouchesOnlyTheTargetRow() {
        val entries = sessionDirectoryEntries(
            listOf(
                summary(id = "one", agentPreset = "code"),
                summary(id = "two"),
            ),
        )

        val selected = entries.replaceSessionAgentPreset("two", "minimal")
        assertEquals("code", selected.single { it.sessionId == "one" }.agentPreset)
        assertEquals("minimal", selected.single { it.sessionId == "two" }.agentPreset)

        val cleared = selected.replaceSessionAgentPreset("one", null)
        assertNull(cleared.single { it.sessionId == "one" }.agentPreset)
        assertEquals("minimal", cleared.single { it.sessionId == "two" }.agentPreset)
    }

    @Test
    fun blankPredicateHoldsOnlyForAKnownIdleUntruncatedEmptyLog() {
        val base = Gate0CState(sessionRunning = false)
        assertTrue(base.sessionBlank)
        assertFalse(base.copy(sessionRunning = true).sessionBlank)
        assertFalse(base.copy(sessionRunning = null).sessionBlank)
        assertFalse(base.copy(historyTruncated = true).sessionBlank)
        assertFalse(
            base.copy(
                timeline = listOf(
                    TimelineEntry(id = "e1", sourceSequence = 1, kind = TimelineKind.USER, text = "hi"),
                ),
            ).sessionBlank,
        )
    }

    private fun summary(id: String, agentPreset: String? = null): SessionSummary =
        SessionSummary.newBuilder()
            .setSessionId(id)
            .setRunning(false)
            .setUpdatedAtMs(1)
            .apply { agentPreset?.let(::setAgentPreset) }
            .build()
}
