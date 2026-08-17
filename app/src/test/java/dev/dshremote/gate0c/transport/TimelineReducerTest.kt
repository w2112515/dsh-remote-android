package dev.dshremote.gate0c.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineReducerTest {
    @Test
    fun `coalesces streamed deltas under one stable message identity`() {
        val user = userTimelineEntry("event-user", 10, "Explain the change", "user-1")
        val first = TimelineReducer.assistantDelta(listOf(user), "chunk-1", 11, "assistant-1", "First ")
        val second = TimelineReducer.assistantDelta(first, "chunk-2", 12, "assistant-1", "second")

        assertEquals(2, second.size)
        assertEquals("assistant:assistant-1", second[1].id)
        assertEquals("First second", second[1].text)
        assertEquals(12, second[1].sourceSequence)
        assertFalse(second[1].final)
    }

    @Test
    fun `completion replaces the active row in place without reordering committed history`() {
        val user = userTimelineEntry("event-user", 10, "Explain the change", "user-1")
        val partial = TimelineReducer.assistantDelta(listOf(user), "chunk-1", 11, "assistant-1", "Working")
        val completed = TimelineReducer.assistantCompleted(partial, "final-event", 14, "assistant-1", "Done")

        assertEquals(listOf("user:user-1", "assistant:assistant-1"), completed.map(TimelineEntry::id))
        assertEquals("Done", completed[1].text)
        assertEquals(14, completed[1].sourceSequence)
        assertTrue(completed[1].final)
    }

    @Test
    fun `late delta cannot mutate an authoritative final response`() {
        val final = TimelineReducer.assistantCompleted(emptyList(), "final", 20, "assistant-1", "Done")
        val unchanged = TimelineReducer.assistantDelta(final, "late", 21, "assistant-1", " duplicated")

        assertEquals(final, unchanged)
    }
}
