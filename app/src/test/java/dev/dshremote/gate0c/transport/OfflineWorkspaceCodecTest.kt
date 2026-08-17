package dev.dshremote.gate0c.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineWorkspaceCodecTest {
    @Test
    fun roundTripsAuthorityBoundProjectionDraftAndReadingAnchor() {
        val workspace = workspace()
        val decoded = OfflineWorkspaceCodec.decode(OfflineWorkspaceCodec.encode(workspace))

        assertArrayEquals(workspace.hostBinding, decoded.hostBinding)
        assertEquals("host-instance", decoded.hostInstanceId)
        assertEquals("Studio workstation", decoded.hostDisplayName)
        assertEquals("stream-1", decoded.projections.single().streamId)
        assertEquals("session-1", decoded.selectedSessionId)
        assertEquals("Draft kept locally", decoded.drafts["session-1"])
        assertEquals("assistant:stable", decoded.readingPositions["session-1"]?.anchorEntryId)
        assertEquals(18, decoded.readingPositions["session-1"]?.offsetPx)
        assertFalse(decoded.readingPositions["session-1"]?.followTail ?: true)
        assertEquals(2, decoded.sessions.single().pendingApprovalCount)
        assertEquals(1, decoded.sessions.single().pendingInputCount)
        assertEquals(workspace.projections.single(), decoded.projections.single())
    }

    @Test
    fun boundsLocalEvictionSeparatelyFromHostHistoryTruncation() {
        val timeline = List(OfflineWorkspaceCache.MAX_TIMELINE_ENTRIES + 7) { index ->
            TimelineEntry(
                id = "row-$index",
                sourceSequence = index.toLong(),
                kind = TimelineKind.ASSISTANT,
                text = if (index == 0) "x".repeat(OfflineWorkspaceCache.MAX_STRING_CHARS + 1) else "row $index",
            )
        }
        val bounded = workspace(
            projection = workspace().projections.single().copy(
                timeline = timeline,
                historyTruncated = false,
            ),
        ).boundedForStorage().projections.single()

        assertEquals(OfflineWorkspaceCache.MAX_TIMELINE_ENTRIES, bounded.timeline.size)
        assertEquals("row-7", bounded.timeline.first().id)
        assertTrue(bounded.cacheTruncated)
        assertFalse(bounded.historyTruncated)
    }

    @Test
    fun rejectsTrailingBytesAndAuthorityBindingChanges() {
        val encoded = OfflineWorkspaceCodec.encode(workspace())
        assertThrows(IllegalArgumentException::class.java) {
            OfflineWorkspaceCodec.decode(encoded + byteArrayOf(1))
        }
        val obsoleteVersion = encoded.copyOf().also { it[3] = 1 }
        assertThrows(IllegalArgumentException::class.java) {
            OfflineWorkspaceCodec.decode(obsoleteVersion)
        }

        val host = ByteArray(32) { 1 }
        val device = ByteArray(32) { 2 }
        val baseline = OfflineProjectionStore.hostBinding(host, device, 3)
        assertNotEquals(
            baseline.toList(),
            OfflineProjectionStore.hostBinding(ByteArray(32) { 3 }, device, 3).toList(),
        )
        assertNotEquals(
            baseline.toList(),
            OfflineProjectionStore.hostBinding(host, ByteArray(32) { 4 }, 3).toList(),
        )
        assertNotEquals(
            baseline.toList(),
            OfflineProjectionStore.hostBinding(host, device, 7).toList(),
        )
    }

    @Test
    fun rejectsOversizedIdentifiersInsteadOfChangingTheirIdentity() {
        val oversizedId = "i".repeat(OfflineWorkspaceCache.MAX_STRING_CHARS + 1)
        val base = workspace()
        val bounded = base.copy(
            sessions = base.sessions + SessionDirectoryEntry(oversizedId, "Oversized", false, 1_000, null),
            projections = listOf(
                base.projections.single().copy(
                    timeline = base.projections.single().timeline + TimelineEntry(
                        id = oversizedId,
                        sourceSequence = 11,
                        kind = TimelineKind.ASSISTANT,
                        text = "Must not acquire a truncated identity",
                    ),
                ),
            ),
        ).boundedForStorage()

        assertEquals(listOf("session-1"), bounded.sessions.map(SessionDirectoryEntry::sessionId))
        assertEquals(listOf("assistant:stable"), bounded.projections.single().timeline.map(TimelineEntry::id))
        assertTrue(bounded.projections.single().cacheTruncated)
    }

    @Test
    fun roundTripsUsageViewsWithPerUnitAndPerFieldAbsence() {
        val base = workspace()
        val usage = SessionUsageProjection(
            tokens = TokenUsageProjection(
                uncachedInputTokens = 96,
                outputTokens = 6,
                cacheReadTokens = 640,
                cacheWriteTokens = 4,
            ),
            pressure = ContextPressureProjection(
                pressureTokens = null,
                projectedTokens = 740,
                contextWindow = 128_000,
            ),
            stats = SessionStatsProjection(turns = 2, steps = 5, llmMs = 9_000, toolMs = 300),
        )
        val workspace = base.copy(
            sessions = listOf(base.sessions.single().copy(usage = usage)),
            projections = listOf(base.projections.single().copy(usage = usage)),
        )
        val decoded = OfflineWorkspaceCodec.decode(OfflineWorkspaceCodec.encode(workspace))

        assertEquals(usage, decoded.sessions.single().usage)
        assertEquals(usage, decoded.projections.single().usage)
        assertNull(decoded.sessions.single().usage?.pressure?.pressureTokens)
    }

    @Test
    fun roundTripsAbsentUsageAsAbsent() {
        val decoded = OfflineWorkspaceCodec.decode(OfflineWorkspaceCodec.encode(workspace()))

        assertNull(decoded.sessions.single().usage)
        assertNull(decoded.projections.single().usage)
    }

    @Test
    fun roundTripsAgentPresetRosterAndPerSessionPresetWithPerHalfAbsence() {
        // S-mode-select: the connect-time roster and the log-resolved per-session
        // preset survive the encrypted cache with their absence semantics intact.
        val roster = listOf(
            AgentPresetProjection(
                id = "standard",
                userTrust = false,
                isDefault = true,
                name = "标准模式",
                description = "功能完整的编码 Agent",
            ),
            AgentPresetProjection(id = "code", userTrust = false, isDefault = false, name = "PTC 模式"),
            AgentPresetProjection(
                id = "my-experiment",
                userTrust = true,
                isDefault = false,
                broken = "composition text is not valid YAML",
            ),
        )
        val base = workspace()
        val workspace = base.copy(
            agentPresets = roster,
            sessions = listOf(base.sessions.single().copy(agentPreset = "code")),
            projections = listOf(base.projections.single().copy(agentPreset = "code")),
        )
        val decoded = OfflineWorkspaceCodec.decode(OfflineWorkspaceCodec.encode(workspace))

        assertEquals(roster, decoded.agentPresets)
        assertEquals("code", decoded.sessions.single().agentPreset)
        assertEquals("code", decoded.projections.single().agentPreset)
        // Optional halves stay absent, never defaulted.
        assertNull(decoded.agentPresets[1].description)
        assertNull(decoded.agentPresets[1].broken)
        assertNull(decoded.agentPresets[2].name)

        val absent = OfflineWorkspaceCodec.decode(OfflineWorkspaceCodec.encode(workspace()))
        assertEquals(emptyList<AgentPresetProjection>(), absent.agentPresets)
        assertNull(absent.sessions.single().agentPreset)
        assertNull(absent.projections.single().agentPreset)
    }

    @Test
    fun roundTripsModelCatalogAndPerSessionModelWithPerFieldAbsence() {
        // S-session-admin: the connect-time catalog, its failure rows, and the
        // log-resolved per-session triple survive the encrypted cache with
        // their absence semantics intact (absence never becomes a default).
        val catalog = listOf(
            ModelProviderGroupProjection(
                id = "deepseek",
                name = "DeepSeek",
                models = listOf(
                    ModelEntryProjection(
                        id = "deepseek-chat",
                        name = "V4 Flash",
                        reasoningEfforts = listOf("low", "high"),
                        defaultReasoningEffort = "high",
                    ),
                    ModelEntryProjection(id = "deepseek-reasoner"),
                ),
            ),
        )
        val failures = listOf(
            ModelCatalogFailureProjection(providerId = "anthropic", detail = "adapter not configured"),
        )
        val model = ModelSelectionProjection("deepseek", "deepseek-chat", "high")
        val base = workspace()
        val workspace = base.copy(
            modelCatalog = catalog,
            modelCatalogFailures = failures,
            sessions = listOf(base.sessions.single().copy(model = model)),
            projections = listOf(base.projections.single().copy(model = model)),
        )
        val decoded = OfflineWorkspaceCodec.decode(OfflineWorkspaceCodec.encode(workspace))

        assertEquals(catalog, decoded.modelCatalog)
        assertEquals(failures, decoded.modelCatalogFailures)
        assertEquals(model, decoded.sessions.single().model)
        assertEquals(model, decoded.projections.single().model)
        // Optional halves stay absent, never defaulted.
        assertNull(decoded.modelCatalog[0].models[1].name)
        assertNull(decoded.modelCatalog[0].models[1].defaultReasoningEffort)
        assertEquals(emptyList<String>(), decoded.modelCatalog[0].models[1].reasoningEfforts)

        val absent = OfflineWorkspaceCodec.decode(OfflineWorkspaceCodec.encode(workspace()))
        assertEquals(emptyList<ModelProviderGroupProjection>(), absent.modelCatalog)
        assertEquals(emptyList<ModelCatalogFailureProjection>(), absent.modelCatalogFailures)
        assertNull(absent.sessions.single().model)
        assertNull(absent.projections.single().model)
    }

    @Test
    fun roundTripsLineageSubagentAndInjectSourceWithPerHalfAbsence() {
        val subagent = SubagentProjection(
            mode = "continuable",
            label = "explore auth refresh chain",
            settledMs = 41_200,
            activeSinceMs = null,
            activeThroughMs = null,
        )
        val source = MessageSourceProjection(kind = "plugin", plugin = "hooks-codex", form = "snapshot")
        val base = workspace()
        val workspace = base.copy(
            sessions = listOf(
                base.sessions.single().copy(
                    parentSessionId = "parent-session",
                    origin = "subagent",
                    subagent = subagent,
                ),
            ),
            projections = listOf(
                base.projections.single().copy(
                    subagent = subagent,
                    timeline = base.projections.single().timeline + TimelineEntry(
                        id = "user:injected",
                        sourceSequence = 11,
                        kind = TimelineKind.INJECT,
                        text = "AGENTS.md + git status",
                        source = source,
                    ),
                ),
            ),
        )
        val decoded = OfflineWorkspaceCodec.decode(OfflineWorkspaceCodec.encode(workspace))

        val session = decoded.sessions.single()
        assertEquals("parent-session", session.parentSessionId)
        assertEquals("subagent", session.origin)
        assertEquals(subagent, session.subagent)
        assertNull(session.subagent?.activeSinceMs)

        val projection = decoded.projections.single()
        assertEquals(subagent, projection.subagent)
        val injected = projection.timeline.single { it.kind == TimelineKind.INJECT }
        assertEquals(source, injected.source)
        // The non-injected row keeps provenance absent, not defaulted.
        assertNull(projection.timeline.single { it.id == "assistant:stable" }.source)
    }

    @Test
    fun roundTripsTheRegistryProjectLabelWithAbsence() {
        // S-project: the operator label survives the encrypted cache; an
        // unlabeled row stays unlabeled offline (never restated from the
        // basename).
        val base = workspace()
        val workspace = base.copy(
            sessions = listOf(base.sessions.single().copy(projectLabel = "DSH Remote")),
        )
        val decoded = OfflineWorkspaceCodec.decode(OfflineWorkspaceCodec.encode(workspace))

        assertEquals("DSH Remote", decoded.sessions.single().projectLabel)

        val absent = OfflineWorkspaceCodec.decode(OfflineWorkspaceCodec.encode(workspace()))
        assertNull(absent.sessions.single().projectLabel)
    }

    @Test
    fun roundTripsArtifactRosterVerbatim() {
        val artifacts = listOf(
            ArtifactEntryState(
                artifactId = "session-1:42:0",
                sessionId = "session-1",
                path = "src/a.ts",
                outsideWorkspace = false,
                isNewFile = false,
                content = """[{"path":"src/a.ts","oldText":"old","newText":"new"}]""",
                truncated = false,
                registeredAtMs = 1_700_000_000_000,
            ),
            ArtifactEntryState(
                artifactId = "session-1:43:1",
                sessionId = "session-1",
                path = "big.log",
                outsideWorkspace = true,
                isNewFile = true,
                content = null,
                truncated = true,
                registeredAtMs = 1_700_000_100_000,
            ),
        )
        val decoded = OfflineWorkspaceCodec.decode(
            OfflineWorkspaceCodec.encode(workspace().copy(artifacts = artifacts)),
        )

        assertEquals(artifacts, decoded.artifacts)
        // Absence stays absence: a workspace without a roster decodes empty.
        assertTrue(
            OfflineWorkspaceCodec.decode(OfflineWorkspaceCodec.encode(workspace())).artifacts.isEmpty(),
        )
    }

    @Test
    fun boundsArtifactRosterAndFiltersUnstorableRows() {
        val oversized = "i".repeat(OfflineWorkspaceCache.MAX_STRING_CHARS + 1)
        val rows = List(OfflineWorkspaceCache.MAX_ARTIFACTS + 5) { index ->
            ArtifactEntryState(
                artifactId = "session-1:$index:0",
                sessionId = "session-1",
                path = "file-$index.ts",
                outsideWorkspace = false,
                isNewFile = false,
                content = if (index == 0) oversized else null,
                truncated = index == 0,
                registeredAtMs = 1_000L + index,
            )
        } + ArtifactEntryState(
            artifactId = oversized,
            sessionId = "session-1",
            path = "unstorable.ts",
            outsideWorkspace = false,
            isNewFile = false,
            content = null,
            truncated = false,
            registeredAtMs = 9_999,
        )
        val bounded = workspace().copy(artifacts = rows).boundedForStorage().artifacts

        assertEquals(OfflineWorkspaceCache.MAX_ARTIFACTS, bounded.size)
        assertTrue(bounded.none { it.artifactId == oversized })
        // Oversized hunk content is bounded by characters, never dropped to absent.
        assertEquals(OfflineWorkspaceCache.MAX_STRING_CHARS, bounded.first().content?.length)
        val decoded = OfflineWorkspaceCodec.decode(
            OfflineWorkspaceCodec.encode(workspace().copy(artifacts = rows)),
        )
        assertEquals(bounded, decoded.artifacts)
    }

    @Test
    fun roundTripsTimelineAttachmentReferencesWithPerFieldAbsence() {
        // S-blob: image references survive the offline cache byte-exact;
        // absent optional dimensions stay absent (never cached as zero).
        val attachments = listOf(
            ImageAttachmentProjection(
                attachmentId = "sha256:${"ab".repeat(32)}",
                mediaType = "image/png",
                bytes = 4_096,
                width = 640,
                height = 480,
                name = "photo.png",
            ),
            ImageAttachmentProjection(
                attachmentId = "sha256:${"cd".repeat(32)}",
                mediaType = "image/jpeg",
                bytes = 8_192,
            ),
        )
        val base = workspace()
        val withImages = base.copy(
            projections = listOf(
                base.projections.single().copy(
                    timeline = base.projections.single().timeline + TimelineEntry(
                        id = "user:with-image",
                        sourceSequence = 11,
                        kind = TimelineKind.USER,
                        text = "see this",
                        attachments = attachments,
                    ),
                ),
            ),
        )
        val decoded = OfflineWorkspaceCodec.decode(OfflineWorkspaceCodec.encode(withImages))

        val row = decoded.projections.single().timeline.single { it.id == "user:with-image" }
        assertEquals(attachments, row.attachments)
        assertNull(row.attachments[1].width)
        assertNull(row.attachments[1].height)
        assertNull(row.attachments[1].name)
    }

    @Test
    fun roundTripsPolicyRulesAndBudgetWithAbsenceKeptAbsent() {
        // S-policy: the fold survives the cache byte-exact — rules keep their
        // exact identity, an absent budget stays absent, and an absent
        // class_mode stays absent (a tool-level rule never grows a mode).
        val rules = listOf(
            ApprovalRuleState(
                ruleId = "9c1e04d548f7bda3",
                classKind = "escalate",
                toolName = "shell",
                classMode = "workspace-write",
                grantedBy = "user",
                grantedAtMs = 900,
            ),
            ApprovalRuleState(
                ruleId = "aaaa04d548f7bda3",
                classKind = "tool",
                toolName = "apply_patch",
                classMode = null,
                grantedBy = "operator",
                grantedAtMs = 901,
            ),
        )
        val base = workspace()
        val withPolicy = base.copy(
            projections = listOf(
                base.projections.single().copy(
                    approvalRules = rules,
                    sessionBudget = SessionBudgetState(maxTotalTokens = 200_000, exhausted = true),
                ),
            ),
        )
        val decoded = OfflineWorkspaceCodec.decode(OfflineWorkspaceCodec.encode(withPolicy))

        val projection = decoded.projections.single()
        assertEquals(rules, projection.approvalRules)
        assertNull(projection.approvalRules[1].classMode)
        assertEquals(SessionBudgetState(maxTotalTokens = 200_000, exhausted = true), projection.sessionBudget)

        // Absence round-trips as absence: no rules is an exact empty list and
        // no budget stays null — never a zero ceiling.
        val bare = OfflineWorkspaceCodec.decode(OfflineWorkspaceCodec.encode(base))
        assertEquals(emptyList<ApprovalRuleState>(), bare.projections.single().approvalRules)
        assertNull(bare.projections.single().sessionBudget)
    }

    private fun workspace(
        projection: CachedSessionProjection = CachedSessionProjection(
            sessionId = "session-1",
            title = "Cached session",
            running = false,
            streamId = "stream-1",
            projectionVersion = 1,
            cursor = 42,
            timeline = listOf(
                TimelineEntry(
                    id = "assistant:stable",
                    sourceSequence = 10,
                    kind = TimelineKind.ASSISTANT,
                    text = "Authenticated projection",
                ),
            ),
            historyTruncated = false,
            cacheTruncated = false,
            savedAtMs = 1_000,
        ),
    ): OfflineWorkspaceCache = OfflineWorkspaceCache(
        hostBinding = ByteArray(32) { it.toByte() },
        hostInstanceId = "host-instance",
        hostDisplayName = "Studio workstation",
        savedAtMs = 1_000,
        sessions = listOf(
            SessionDirectoryEntry(
                "session-1", "Cached session", false, 900, "workspace",
                pendingApprovalCount = 2,
                pendingInputCount = 1,
            ),
        ),
        selectedSessionId = "session-1",
        projections = listOf(projection),
        drafts = mapOf("session-1" to "Draft kept locally"),
        readingPositions = mapOf(
            "session-1" to CachedReadingPosition("assistant:stable", 18, followTail = false),
        ),
    )
}
