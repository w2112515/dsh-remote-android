package dev.dshremote.gate0c.ui.v2

import org.junit.Assert.assertEquals
import org.junit.Test

class V2BackNavigationTest {
    @Test
    fun artifactBeatsChatAndToolDetail() {
        assertEquals(
            V2SystemBackAction.CLOSE_ARTIFACT,
            v2SystemBackAction(
                artifactOpen = true,
                toolDetailOpen = true,
                replayOpen = true,
                chatOpen = true,
            ),
        )
    }

    @Test
    fun toolDetailBeatsReplayAndChat() {
        assertEquals(
            V2SystemBackAction.CLOSE_TOOL_DETAIL,
            v2SystemBackAction(
                artifactOpen = false,
                toolDetailOpen = true,
                replayOpen = true,
                chatOpen = true,
            ),
        )
    }

    @Test
    fun replayBeatsChat() {
        assertEquals(
            V2SystemBackAction.CLOSE_REPLAY,
            v2SystemBackAction(
                artifactOpen = false,
                toolDetailOpen = false,
                replayOpen = true,
                chatOpen = true,
            ),
        )
    }

    @Test
    fun chatReturnsToSessionList() {
        assertEquals(
            V2SystemBackAction.LEAVE_CHAT,
            v2SystemBackAction(
                artifactOpen = false,
                toolDetailOpen = false,
                replayOpen = false,
                chatOpen = true,
            ),
        )
    }

    @Test
    fun rootTabsFinishTheActivity() {
        assertEquals(
            V2SystemBackAction.FINISH,
            v2SystemBackAction(
                artifactOpen = false,
                toolDetailOpen = false,
                replayOpen = false,
                chatOpen = false,
            ),
        )
    }
}
