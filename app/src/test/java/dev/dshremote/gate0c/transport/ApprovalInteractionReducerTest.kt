package dev.dshremote.gate0c.transport

import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalInteractionReducerTest {
    @Test
    fun replacesOneExactPendingIncarnationAndIgnoresStaleResolution() {
        val first = interaction("revision-1")
        val second = interaction("revision-2")

        val replaced = ApprovalInteractionReducer.upsert(listOf(first), second)
        assertEquals(listOf(second), replaced)
        assertEquals(replaced, ApprovalInteractionReducer.resolve(replaced, second.approvalId, "revision-1"))
        assertEquals(emptyList<ApprovalInteractionState>(), ApprovalInteractionReducer.resolve(
            replaced,
            second.approvalId,
            second.revision,
        ))
    }

    private fun interaction(revision: String) = ApprovalInteractionState(
        approvalId = "approval-1",
        revision = revision,
        sessionId = "session-1",
        toolName = "edit",
        callId = "call-1",
        reason = "Update one file",
        workspaceLabel = "workspace",
        allowOnce = true,
        deny = true,
        evidence = ApprovalEvidence(
            available = true,
            summary = "Edit README",
            risk = ApprovalRisk.SENSITIVE,
            resources = listOf("README.md"),
            consequence = "Changes one file",
            source = "tool-owner",
            unavailableReason = null,
        ),
    )
}
