package dev.dshremote.gate0c.transport

import dev.dshremote.protocol.v1alpha.ApprovalRule as ProtoApprovalRule
import dev.dshremote.protocol.v1alpha.SessionBudget as ProtoSessionBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S-policy wire mapping: rules and budget are exact Host facts. The proto3
 * `optional class_mode` presence bit must survive the mapping — an absent
 * mode is null (a tool-level rule), never an empty string masquerading as
 * a mode.
 */
class PolicyMappingTest {
    @Test
    fun escalationRuleKeepsItsModeAndDerivesTheEscalationLabel() {
        val rule = approvalRuleStateOf(
            ProtoApprovalRule.newBuilder()
                .setRuleId("9c1e04d548f7bda3")
                .setClassKind("escalate")
                .setToolName("shell")
                .setClassMode("workspace-write")
                .setGrantedBy("user")
                .setGrantedAtMs(1_755_000_000_000)
                .build(),
        )

        assertEquals("9c1e04d548f7bda3", rule.ruleId)
        assertEquals("workspace-write", rule.classMode)
        assertEquals("shell · 升级到 workspace-write", rule.classLabel)
        assertEquals(1_755_000_000_000, rule.grantedAtMs)
    }

    @Test
    fun toolRuleWithoutModeStaysModeLessAndLabelsAsTheBareTool() {
        val rule = approvalRuleStateOf(
            ProtoApprovalRule.newBuilder()
                .setRuleId("aaaa04d548f7bda3")
                .setClassKind("tool")
                .setToolName("apply_patch")
                .setGrantedBy("operator")
                .setGrantedAtMs(7)
                .build(),
        )

        assertNull(rule.classMode)
        assertEquals("apply_patch", rule.classLabel)
    }

    @Test
    fun budgetCarriesTheHostVerdictNotAClientRecomputation() {
        val budget = sessionBudgetStateOf(
            ProtoSessionBudget.newBuilder()
                .setMaxTotalTokens(200_000)
                .setExhausted(true)
                .build(),
        )

        assertEquals(SessionBudgetState(maxTotalTokens = 200_000, exhausted = true), budget)
    }
}
