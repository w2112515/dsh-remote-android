package dev.dshremote.gate0c.transport

import dev.dshremote.protocol.v1alpha.ToolPresentation
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolPresentationMappingTest {
    @Test
    fun `preserves terminal and diff semantics for diagnostics`() {
        val terminal = toolTimelineEntry(
            "terminal-event",
            7,
            presentation(ToolPresentation.Kind.KIND_TERMINAL, "pwsh", "Print marker", "terminal-call"),
        )
        val diff = toolTimelineEntry(
            "diff-event",
            9,
            presentation(ToolPresentation.Kind.KIND_DIFF, "str_replace_editor", "Edit sample.txt", "diff-call"),
        )

        assertEquals(
            TimelineEntry("tool:terminal-call", 7, TimelineKind.TOOL_TERMINAL, "pwsh · Print marker", callId = "terminal-call", toolName = "pwsh"),
            terminal,
        )
        assertEquals(
            TimelineEntry("tool:diff-call", 9, TimelineKind.TOOL_DIFF, "str_replace_editor · Edit sample.txt", callId = "diff-call", toolName = "str_replace_editor"),
            diff,
        )
    }

    @Test
    fun `maps unspecified presentation to an honest unsupported fallback`() {
        assertEquals(
            TimelineEntry("tool:unknown-event", 11, TimelineKind.TOOL_UNSUPPORTED, "tool", toolName = "tool"),
            toolTimelineEntry(
                "unknown-event",
                11,
                presentation(ToolPresentation.Kind.KIND_UNSPECIFIED, "tool", "", ""),
            ),
        )
    }

    private fun presentation(
        kind: ToolPresentation.Kind,
        toolName: String,
        summary: String,
        callId: String,
    ): ToolPresentation = ToolPresentation.newBuilder()
        .setKind(kind)
        .setCallId(callId)
        .setToolName(toolName)
        .setSummary(summary)
        .build()
}
