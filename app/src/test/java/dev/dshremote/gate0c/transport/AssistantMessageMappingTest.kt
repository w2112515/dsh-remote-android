package dev.dshremote.gate0c.transport

import dev.dshremote.protocol.v1alpha.AssistantMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantMessageMappingTest {
    @Test
    fun `distinguishes authoritative final and interrupted partial history`() {
        val final = assistantTimelineEntry(
            "final-event",
            18,
            AssistantMessage.newBuilder().setMessageId("assistant-a-1").setText("complete").setFinal(true).build(),
        )
        val partial = assistantTimelineEntry(
            "partial-event",
            23,
            AssistantMessage.newBuilder().setMessageId("assistant-a-2").setText("interrupted").setFinal(false).build(),
        )

        assertEquals(TimelineEntry("assistant:assistant-a-1", 18, TimelineKind.ASSISTANT, "complete"), final)
        assertEquals(TimelineEntry("assistant:assistant-a-2", 23, TimelineKind.ASSISTANT, "interrupted", final = false), partial)
    }
}
