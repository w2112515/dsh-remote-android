package dev.dshremote.gate0c.transport

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandReceiptReducerTest {
    @Test
    fun terminalOutcomeReplacesReceivedForTheSameCommandAndPreservesErrorCode() {
        val received = CommandReceipt("command-1", "RECEIVED", false, "ERROR_CODE_UNSPECIFIED", "")
        val committed = CommandReceipt("command-1", "COMMITTED", false, "ERROR_CODE_UNSPECIFIED", "")
        val unknown = CommandReceipt(
            "command-2",
            "UNKNOWN",
            true,
            "ERROR_CODE_COMMAND_OUTCOME_UNKNOWN",
            "Retry with the same command id",
        )

        val receipts = CommandReceiptReducer.upsert(
            CommandReceiptReducer.upsert(listOf(received), committed),
            unknown,
        )

        assertEquals(listOf(committed, unknown), receipts)
        assertEquals("ERROR_CODE_COMMAND_OUTCOME_UNKNOWN", receipts.last().errorCode)
    }

    @Test
    fun boundDropsTheOldestDistinctCommand() {
        val receipts = (1..3).fold(emptyList<CommandReceipt>()) { current, index ->
            CommandReceiptReducer.upsert(
                current,
                CommandReceipt("command-$index", "RECEIVED", false, "ERROR_CODE_UNSPECIFIED", ""),
                maxEntries = 2,
            )
        }

        assertEquals(listOf("command-2", "command-3"), receipts.map(CommandReceipt::commandId))
    }

    @Test
    fun stoppedReplacesRequestedForTheSameExactTurnCommand() {
        val requested = CommandReceipt("stop-1", "REQUESTED", false, "ERROR_CODE_UNSPECIFIED", "")
        val stopped = CommandReceipt("stop-1", "STOPPED", true, "ERROR_CODE_UNSPECIFIED", "")

        assertEquals(
            listOf(stopped),
            CommandReceiptReducer.upsert(listOf(requested), stopped),
        )
    }
}
