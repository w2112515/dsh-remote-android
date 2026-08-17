package dev.dshremote.gate0c.ui.v2

import dev.dshremote.gate0c.transport.Gate0CState
import dev.dshremote.gate0c.transport.TimelineEntry
import dev.dshremote.gate0c.transport.TimelineKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionExportTest {
    private fun state(timeline: List<TimelineEntry>) = Gate0CState(
        sessionId = "session-1",
        sessionTitle = "Export target",
        cursor = 42,
        timeline = timeline,
    )

    @Test
    fun `export leads with the honesty header and never claims an audit log`() {
        val export = buildProjectionExport(state(emptyList()))

        assertTrue(export.startsWith("DSH Remote 投影导出（客户端序列化）"))
        assertTrue(export.contains("这不是 append-only 审计日志"))
        assertTrue(export.contains("cursor: 42"))
        assertTrue(export.contains("条目: 0"))
    }

    @Test
    fun `export serializes every entry with sequence, kind, partial flag and indented bounded content`() {
        val export = buildProjectionExport(
            state(
                listOf(
                    TimelineEntry("user:1", 10, TimelineKind.USER, "Explain it"),
                    TimelineEntry("assistant:1", 11, TimelineKind.ASSISTANT, "Working", final = false),
                    TimelineEntry(
                        "tool:1",
                        12,
                        TimelineKind.TOOL_DIFF,
                        "apply patch",
                        toolName = "tool-fs/edit",
                        boundedContent = "+added\n-removed",
                        truncated = true,
                    ),
                ),
            ),
        )

        assertTrue(export.contains("[seq 10] USER Explain it"))
        assertTrue(export.contains("[seq 11] ASSISTANT (部分) Working"))
        assertTrue(export.contains("[seq 12] TOOL_DIFF apply patch"))
        assertTrue(export.contains("    +added"))
        assertTrue(export.contains("    -removed"))
        assertTrue(export.contains("    …（Host 截断）"))
    }

    @Test
    fun `export contains no approval or draft content that is not part of the projection`() {
        val export = buildProjectionExport(
            Gate0CState(
                sessionId = "session-1",
                localDraft = "unsent draft body",
                timeline = listOf(TimelineEntry("user:1", 10, TimelineKind.USER, "Hi")),
            ),
        )

        assertFalse(export.contains("unsent draft body"))
    }
}
