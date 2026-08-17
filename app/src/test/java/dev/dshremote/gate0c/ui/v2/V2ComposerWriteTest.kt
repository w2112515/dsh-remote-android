package dev.dshremote.gate0c.ui.v2

import dev.dshremote.gate0c.transport.ConnectionPhase
import dev.dshremote.gate0c.transport.ControlLeaseStatus
import dev.dshremote.gate0c.transport.Gate0CState
import dev.dshremote.gate0c.transport.ModelEntryProjection
import dev.dshremote.gate0c.transport.ModelProviderGroupProjection
import dev.dshremote.gate0c.transport.PendingCommandOperation
import dev.dshremote.gate0c.transport.PendingCommandProgress
import dev.dshremote.gate0c.transport.PendingCommandStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V2ComposerWriteTest {
    @Test
    fun writeAuthorizedWithoutLeaseShowsSendNotAcquire() {
        val primary = composerPrimary(
            readyWrite().copy(localDraft = "继续"),
            nowMs = 1_000L,
        )

        assertEquals("发送", primary.label)
        assertEquals(ComposerPrimaryKind.SEND, primary.kind)
        assertTrue(primary.enabled)
        assertFalse(primary.label.contains("取得控制"))
        assertEquals("开始输入后会写入这台电脑上的会话", primary.settlement)
        assertTrue(primary.showSettlement)
    }

    @Test
    fun emptyDraftDoesNotNagAboutWriteRight() {
        val primary = composerPrimary(readyWrite(), nowMs = 1_000L)

        assertEquals("发送", primary.label)
        assertFalse(primary.enabled)
        assertFalse(primary.showSettlement)
    }

    @Test
    fun heldByOtherDisablesWriteAndNamesTheWebSide() {
        val primary = composerPrimary(
            readyWrite().copy(
                localDraft = "继续",
                controlHeldByOther = true,
                sessionRunning = true,
                activityRevision = 4,
                grantedCapabilities = 79uL,
                modelCatalog = catalog(),
            ),
            nowMs = 1_000L,
        )

        assertEquals("发送", primary.label)
        assertFalse(primary.enabled)
        assertEquals("电脑上的网页正在发送这条会话…", primary.settlement)
        assertTrue(primary.showSettlement)
        assertFalse(primary.stopEnabled)
        assertFalse(primary.modelSelectable)
    }

    @Test
    fun modelAndStopDoNotWaitForAVisibleLease() {
        val primary = composerPrimary(
            readyWrite().copy(
                grantedCapabilities = 79uL,
                sessionRunning = true,
                activityRevision = 4,
                modelCatalog = catalog(),
            ),
            nowMs = 1_000L,
        )

        assertTrue(primary.modelSelectable)
        assertTrue(primary.stopEnabled)
    }

    @Test
    fun usableLeaseKeepsSendQuiet() {
        val primary = composerPrimary(
            readyWrite().copy(
                localDraft = "继续",
                controlLease = ControlLeaseStatus(
                    sessionId = "session-1",
                    epoch = "9",
                    expiresAtMs = 10_000L,
                ),
            ),
            nowMs = 1_000L,
        )

        assertEquals("发送", primary.label)
        assertTrue(primary.enabled)
        assertFalse(primary.showSettlement)
        assertTrue(primary.settlement.contains("epoch 9"))
    }

    @Test
    fun staleViewStillAsksToReconnect() {
        val primary = composerPrimary(
            readyWrite().copy(phase = ConnectionPhase.OFFLINE, offlineSnapshot = true),
            nowMs = 1_000L,
        )

        assertEquals(ComposerPrimaryKind.RECONNECT, primary.kind)
        assertEquals("重新连接", primary.label)
        assertTrue(primary.settlement.contains("恢复连接后再发送"))
    }

    @Test
    fun pendingCommandStillAsksToReconcile() {
        val primary = composerPrimary(
            readyWrite().copy(
                pendingCommand = PendingCommandStatus(
                    commandId = "android-1",
                    sessionId = "session-1",
                    operation = PendingCommandOperation.SEND_INPUT,
                    expectedActivityRevision = null,
                    progress = PendingCommandProgress.UNKNOWN,
                    createdAtMs = 1_000L,
                ),
            ),
            nowMs = 1_000L,
        )

        assertEquals(ComposerPrimaryKind.RECONCILE, primary.kind)
        assertEquals("对账", primary.label)
        assertTrue(primary.enabled)
    }

    private fun readyWrite() = Gate0CState(
        phase = ConnectionPhase.READY,
        grantedCapabilities = 68uL,
        sessionId = "session-1",
    )

    private fun catalog() = listOf(
        ModelProviderGroupProjection(
            id = "openai",
            name = "OpenAI",
            models = listOf(
                ModelEntryProjection(id = "gpt", name = "GPT"),
            ),
        ),
    )
}
