package dev.dshremote.gate0c.transport

import dev.dshremote.protocol.v1alpha.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionDirectoryMappingTest {
    @Test
    fun runningSessionsSortBeforeIdleThenByHostActivity() {
        val entries = sessionDirectoryEntries(
            listOf(
                summary("idle-new", running = false, updatedAtMs = 30),
                summary("running-old", running = true, updatedAtMs = 10),
                summary("running-new", running = true, updatedAtMs = 20),
            ),
        )

        assertEquals(listOf("running-new", "running-old", "idle-new"), entries.map { it.sessionId })
    }

    @Test
    fun inputWaitingSortsBeforeApprovalWaitingThenRunning() {
        val entries = sessionDirectoryEntries(
            listOf(
                summary("running", running = true, updatedAtMs = 40),
                summary("approval", running = false, updatedAtMs = 10, pendingApprovalCount = 1),
                summary("input", running = false, updatedAtMs = 5, pendingInputCount = 1),
            ),
        )

        assertEquals(listOf("input", "approval", "running"), entries.map { it.sessionId })
    }

    @Test
    fun mapsOnlyRemoteDirectoryFields() {
        val entry = sessionDirectoryEntries(
            listOf(
                summary(
                    id = "session-1",
                    running = true,
                    updatedAtMs = 42,
                    title = "Source-backed work",
                    workspaceLabel = "deepseek-harness",
                ),
            ),
        ).single()

        assertEquals("Source-backed work", entry.title)
        assertEquals("deepseek-harness", entry.workspaceLabel)
        assertEquals(42L, entry.updatedAtMs)
    }

    @Test
    fun mapsTheLogResolvedModelTripleAndKeepsItsAbsence() {
        // S-session-admin: a summary with a request-header model maps the exact
        // triple; a headerless summary states nothing (never the deployment
        // default).
        val withModel = sessionDirectoryEntries(
            listOf(
                SessionSummary.newBuilder()
                    .setSessionId("modeled")
                    .setRunning(false)
                    .setUpdatedAtMs(10)
                    .setModel(
                        dev.dshremote.protocol.v1alpha.ModelSelection.newBuilder()
                            .setProvider("deepseek")
                            .setModel("deepseek-chat")
                            .setReasoningEffort("high"),
                    )
                    .build(),
                summary("headerless", running = false, updatedAtMs = 5),
            ),
        )

        val modeled = withModel.single { it.sessionId == "modeled" }
        assertEquals(ModelSelectionProjection("deepseek", "deepseek-chat", "high"), modeled.model)
        assertEquals(null, withModel.single { it.sessionId == "headerless" }.model)
    }

    @Test
    fun mapsTheRegistryProjectLabelAndKeepsItsAbsence() {
        // S-project: a summary with a registry match carries the operator label;
        // an unmatched summary states nothing — the basename is never restated
        // as a project.
        val entries = sessionDirectoryEntries(
            listOf(
                SessionSummary.newBuilder()
                    .setSessionId("labeled")
                    .setRunning(false)
                    .setUpdatedAtMs(10)
                    .setWorkspaceLabel("android-app")
                    .setProjectLabel("DSH Remote")
                    .build(),
                summary("unlabeled", running = false, updatedAtMs = 5, workspaceLabel = "android-app"),
            ),
        )

        assertEquals("DSH Remote", entries.single { it.sessionId == "labeled" }.projectLabel)
        assertEquals(null, entries.single { it.sessionId == "unlabeled" }.projectLabel)
        assertEquals("android-app", entries.single { it.sessionId == "unlabeled" }.workspaceLabel)
    }

    @Test
    fun updateSessionInsertsAMissingCreatedRow() {
        val listed = listOf(entry("listed", updatedAtMs = 10))
        val next = listed.updateSession(
            sessionId = "android-new",
            title = null,
            running = false,
            updatedAtMs = 20,
        )
        assertEquals(listOf("android-new", "listed"), next.map { it.sessionId })
        assertEquals(null, next.single { it.sessionId == "android-new" }.title)
    }

    @Test
    fun updateSessionStillPatchesAnExistingRow() {
        val listed = listOf(entry("listed", title = "Old", running = false, updatedAtMs = 10))
        val next = listed.updateSession(sessionId = "listed", title = "New", running = true)
        assertEquals(listOf("listed"), next.map { it.sessionId })
        assertEquals("New", next.single().title)
        assertEquals(true, next.single().running)
        assertEquals(10L, next.single().updatedAtMs)
    }

    @Test
    fun upsertCreatedSessionKeepsWorkspaceFacts() {
        val next = emptyList<SessionDirectoryEntry>().upsertCreatedSession(
            sessionId = "android-new",
            workspaceLabel = "phone-ab",
            agentPreset = "build",
            updatedAtMs = 42,
        )
        val row = next.single()
        assertEquals("android-new", row.sessionId)
        assertEquals("phone-ab", row.workspaceLabel)
        assertEquals("build", row.agentPreset)
        assertEquals(42L, row.updatedAtMs)
    }

    @Test
    fun helloMergeKeepsABlankCreateTheHostOmitted() {
        val hello = listOf(entry("listed", updatedAtMs = 10))
        val previous = listOf(entry("android-new", workspaceLabel = "phone-ab", updatedAtMs = 20))
        val merged = mergeKeptSessionIntoDirectory(hello, "android-new", previous)
        assertEquals(listOf("android-new", "listed"), merged.map { it.sessionId })
        assertEquals("phone-ab", merged.single { it.sessionId == "android-new" }.workspaceLabel)
        assertEquals(hello, mergeKeptSessionIntoDirectory(hello, "listed", previous))
    }

    private fun entry(
        id: String,
        title: String? = null,
        running: Boolean = false,
        updatedAtMs: Long = 0,
        workspaceLabel: String? = null,
    ) = SessionDirectoryEntry(
        sessionId = id,
        title = title,
        running = running,
        updatedAtMs = updatedAtMs,
        workspaceLabel = workspaceLabel,
    )

    private fun summary(
        id: String,
        running: Boolean,
        updatedAtMs: Long,
        title: String = "",
        workspaceLabel: String = "",
        pendingApprovalCount: Int = 0,
        pendingInputCount: Int = 0,
    ): SessionSummary = SessionSummary.newBuilder()
        .setSessionId(id)
        .setRunning(running)
        .setUpdatedAtMs(updatedAtMs)
        .setTitle(title)
        .setWorkspaceLabel(workspaceLabel)
        .setPendingApprovalCount(pendingApprovalCount)
        .setPendingInputCount(pendingInputCount)
        .build()
}
