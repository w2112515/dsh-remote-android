package dev.dshremote.gate0c.transport

import dev.dshremote.protocol.v1alpha.MessageSource
import dev.dshremote.protocol.v1alpha.SessionStatusChanged
import dev.dshremote.protocol.v1alpha.SessionSummary
import dev.dshremote.protocol.v1alpha.SubagentView
import dev.dshremote.protocol.v1alpha.ToolPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionVocabMappingTest {
    @Test
    fun injectedUserMessageBecomesItsOwnRowKindWithBoundedProvenance() {
        val injected = userTimelineEntry(
            eventId = "event-1",
            sourceSequence = 9,
            text = "AGENTS.md + git status",
            messageId = "message-1",
            source = MessageSourceProjection(kind = "plugin", plugin = "hooks-codex", form = "snapshot"),
        )
        assertEquals(TimelineKind.INJECT, injected.kind)
        assertEquals("hooks-codex", injected.source?.plugin)

        val human = userTimelineEntry(
            eventId = "event-2",
            sourceSequence = 10,
            text = "hello",
            source = MessageSourceProjection(kind = "user"),
        )
        assertEquals(TimelineKind.USER, human.kind)
        assertNull(human.source?.plugin)

        val source = messageSourceProjectionOf(
            MessageSource.newBuilder().setKind("plugin").setPlugin("p").setForm("recall").build(),
        )
        assertEquals("plugin", source.kind)
        assertEquals("recall", source.form)
    }

    @Test
    fun subagentToolCallMapsToSubagentRowOnlyByToolName() {
        val delegation = toolTimelineEntry(
            eventId = "event-3",
            sourceSequence = 11,
            presentation = ToolPresentation.newBuilder()
                .setCallId("call-1")
                .setToolName("subagent")
                .setSummary("梳理调用链")
                .setKind(ToolPresentation.Kind.KIND_GENERIC)
                .build(),
        )
        assertEquals(TimelineKind.SUBAGENT, delegation.kind)

        val ordinary = toolTimelineEntry(
            eventId = "event-4",
            sourceSequence = 12,
            presentation = ToolPresentation.newBuilder()
                .setCallId("call-2")
                .setToolName("subagent_helper")
                .setSummary("not a delegation")
                .setKind(ToolPresentation.Kind.KIND_TERMINAL)
                .build(),
        )
        assertEquals(TimelineKind.TOOL_TERMINAL, ordinary.kind)
    }

    @Test
    fun turnEndReasonLabelsOnlyMappedKindsAndKeepsExtensionAbsent() {
        assertEquals(
            "completed",
            turnEndReasonLabel(
                SessionStatusChanged.newBuilder()
                    .setTurnEndReason(SessionStatusChanged.TurnEndReason.TURN_END_REASON_COMPLETED)
                    .build(),
            ),
        )
        assertEquals(
            "max-tokens",
            turnEndReasonLabel(
                SessionStatusChanged.newBuilder()
                    .setTurnEndReason(SessionStatusChanged.TurnEndReason.TURN_END_REASON_MAX_TOKENS)
                    .build(),
            ),
        )
        // No reason carried → absent, not fabricated.
        assertNull(turnEndReasonLabel(SessionStatusChanged.newBuilder().build()))
        // Plugin-extended kinds a v5 client cannot name stay honestly absent.
        assertNull(
            turnEndReasonLabel(
                SessionStatusChanged.newBuilder()
                    .setTurnEndReason(SessionStatusChanged.TurnEndReason.TURN_END_REASON_UNSPECIFIED)
                    .build(),
            ),
        )
    }

    @Test
    fun subagentViewMapsPerHalfAbsence() {
        val identityOnly = subagentProjectionOf(
            SubagentView.newBuilder().setMode("continuable").setLabel("explore auth").build(),
        )
        assertEquals("continuable", identityOnly.mode)
        assertEquals("可继续", identityOnly.modeZh)
        assertNull(identityOnly.settledMs)
        assertNull(identityOnly.activeSinceMs)

        val timingOnly = subagentProjectionOf(
            SubagentView.newBuilder().setSettledMs(41_200).build(),
        )
        assertNull(timingOnly.mode)
        assertEquals(41_200L, timingOnly.settledMs)
    }

    @Test
    fun subagentChangedMergesOnlyTheHalfItCarries() {
        val base = SubagentProjection(
            mode = "one-shot",
            label = "old label",
            settledMs = null,
            activeSinceMs = 100,
            activeThroughMs = null,
        )

        // Timing-only frame: identity survives, timing replaces.
        val timingFrame = base.mergedWith(
            SubagentView.newBuilder().setSettledMs(41_200).setActiveThroughMs(500).build(),
        )
        assertEquals("one-shot", timingFrame.mode)
        assertEquals("old label", timingFrame.label)
        assertEquals(41_200L, timingFrame.settledMs)
        assertNull(timingFrame.activeSinceMs)
        assertEquals(500L, timingFrame.activeThroughMs)

        // Identity-only frame on an absent base introduces identity without
        // inventing timing.
        val identityFrame = null.mergedWith(
            SubagentView.newBuilder().setMode("continuable").setLabel("new label").build(),
        )
        assertEquals("continuable", identityFrame.mode)
        assertNull(identityFrame.settledMs)
    }

    @Test
    fun directoryEntryKeepsLineageAndSubagentView() {
        val entries = sessionDirectoryEntries(
            listOf(
                SessionSummary.newBuilder()
                    .setSessionId("child")
                    .setRunning(false)
                    .setUpdatedAtMs(2)
                    .setParentSessionId("parent")
                    .setOrigin("subagent")
                    .setSubagent(SubagentView.newBuilder().setMode("one-shot"))
                    .build(),
                SessionSummary.newBuilder()
                    .setSessionId("parent")
                    .setRunning(true)
                    .setUpdatedAtMs(1)
                    .build(),
            ),
        )

        val child = entries.single { it.sessionId == "child" }
        assertEquals("parent", child.parentSessionId)
        assertEquals("subagent", child.origin)
        assertEquals("one-shot", child.subagent?.mode)
        assertEquals("一次性", child.subagent?.modeZh)
        assertNull(entries.single { it.sessionId == "parent" }.subagent)
        assertNull(entries.single { it.sessionId == "parent" }.origin)

        val replaced = entries.replaceSessionSubagent("child", null)
        assertNull(replaced.single { it.sessionId == "child" }.subagent)
        assertEquals(entries.map { it.sessionId }, replaced.map { it.sessionId })
    }
}
