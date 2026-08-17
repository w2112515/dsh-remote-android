package dev.dshremote.gate0c.transport

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

internal data class CachedReadingPosition(
    val anchorEntryId: String?,
    val offsetPx: Int,
    val followTail: Boolean,
)

internal data class CachedSessionProjection(
    val sessionId: String,
    val title: String?,
    val running: Boolean,
    val streamId: String,
    val projectionVersion: Int,
    val cursor: Long,
    val timeline: List<TimelineEntry>,
    val historyTruncated: Boolean,
    val cacheTruncated: Boolean,
    val savedAtMs: Long,
    val usage: SessionUsageProjection? = null,
    val subagent: SubagentProjection? = null,
    val agentPreset: String? = null,
    val model: ModelSelectionProjection? = null,
    // S-policy: durable policy facts ride the cache so a STALE view shows the
    // same rules and ceiling the Host logged (marked offline), never a lie.
    val approvalRules: List<ApprovalRuleState> = emptyList(),
    val sessionBudget: SessionBudgetState? = null,
)

internal data class OfflineWorkspaceCache(
    val hostBinding: ByteArray,
    val hostInstanceId: String?,
    /** Operator-facing Host name from the last hello; keeps STALE cards titled. */
    val hostDisplayName: String? = null,
    val savedAtMs: Long,
    val sessions: List<SessionDirectoryEntry>,
    val selectedSessionId: String?,
    val projections: List<CachedSessionProjection>,
    val drafts: Map<String, String>,
    val readingPositions: Map<String, CachedReadingPosition>,
    val agentPresets: List<AgentPresetProjection> = emptyList(),
    val modelCatalog: List<ModelProviderGroupProjection> = emptyList(),
    val modelCatalogFailures: List<ModelCatalogFailureProjection> = emptyList(),
    // S-artifacts: the connect-time roster rides the cache so a STALE view
    // shows the same journal facts (marked offline), never an empty lie.
    val artifacts: List<ArtifactEntryState> = emptyList(),
) {
    init {
        require(hostBinding.size == HOST_BINDING_BYTES)
        require(savedAtMs >= 0)
    }

    fun projection(sessionId: String?): CachedSessionProjection? =
        sessionId?.let { selected -> projections.find { it.sessionId == selected } }

    fun boundedForStorage(): OfflineWorkspaceCache {
        val boundedSessions = sessions
            .filter { it.sessionId.isNotBlank() && fitsString(it.sessionId) }
            .distinctBy(SessionDirectoryEntry::sessionId)
            .take(MAX_SESSIONS)
            .map { session ->
                session.copy(
                    title = session.title?.let(::bounded),
                    workspaceLabel = session.workspaceLabel?.let(::bounded),
                    agentPreset = session.agentPreset?.let(::bounded),
                    model = session.model?.let(::boundedModelSelection),
                    projectLabel = session.projectLabel?.let(::bounded),
                )
            }
        val allowedSessionIds = boundedSessions.mapTo(mutableSetOf(), SessionDirectoryEntry::sessionId)
        val boundedProjections = projections
            .filter {
                it.sessionId in allowedSessionIds && it.streamId.isNotBlank() && fitsString(it.streamId)
            }
            .distinctBy(CachedSessionProjection::sessionId)
            .sortedByDescending(CachedSessionProjection::savedAtMs)
            .take(MAX_PROJECTIONS)
            .map(::boundedProjection)
        val retainedIds = boundedProjections.mapTo(mutableSetOf(), CachedSessionProjection::sessionId)
        return copy(
            hostInstanceId = hostInstanceId?.let(::bounded),
            hostDisplayName = hostDisplayName?.let(::bounded),
            sessions = boundedSessions,
            selectedSessionId = selectedSessionId?.takeIf { it in allowedSessionIds },
            projections = boundedProjections,
            drafts = drafts
                .filterKeys { it in allowedSessionIds }
                .mapValues { (_, draft) -> draft.take(MAX_DRAFT_CHARS) }
                .filterValues(String::isNotEmpty),
            readingPositions = readingPositions
                .filterKeys { it in retainedIds }
                .mapValues { (_, position) ->
                    position.copy(
                        anchorEntryId = position.anchorEntryId?.let(::bounded),
                        offsetPx = position.offsetPx.coerceIn(0, MAX_READING_OFFSET_PX),
                    )
                },
            agentPresets = agentPresets
                .filter { it.id.isNotBlank() && fitsString(it.id) }
                .distinctBy(AgentPresetProjection::id)
                .take(MAX_AGENT_PRESETS)
                .map { preset ->
                    preset.copy(
                        name = preset.name?.let(::bounded),
                        description = preset.description?.let(::bounded),
                        broken = preset.broken?.let(::bounded),
                    )
                },
            modelCatalog = modelCatalog
                .filter { it.id.isNotBlank() && fitsString(it.id) }
                .distinctBy(ModelProviderGroupProjection::id)
                .take(MAX_MODEL_PROVIDER_GROUPS)
                .map { group ->
                    group.copy(
                        name = group.name?.let(::bounded),
                        models = group.models
                            .filter { it.id.isNotBlank() && fitsString(it.id) }
                            .distinctBy(ModelEntryProjection::id)
                            .take(MAX_MODELS_PER_PROVIDER)
                            .map { entry ->
                                entry.copy(
                                    name = entry.name?.let(::bounded),
                                    reasoningEfforts = entry.reasoningEfforts
                                        .filter { effort -> effort.isNotBlank() && fitsString(effort) }
                                        .distinct()
                                        .take(MAX_REASONING_EFFORTS_PER_MODEL),
                                    defaultReasoningEffort = entry.defaultReasoningEffort?.let(::bounded),
                                )
                            },
                    )
                },
            modelCatalogFailures = modelCatalogFailures
                .filter { it.providerId.isNotBlank() && fitsString(it.providerId) }
                .distinctBy(ModelCatalogFailureProjection::providerId)
                .take(MAX_MODEL_PROVIDER_GROUPS)
                .map { failure -> failure.copy(detail = failure.detail?.let(::bounded)) },
            artifacts = artifacts
                .filter { it.artifactId.isNotBlank() && fitsString(it.artifactId) && fitsString(it.path) }
                .distinctBy(ArtifactEntryState::artifactId)
                .take(MAX_ARTIFACTS)
                .map { artifact ->
                    artifact.copy(
                        path = bounded(artifact.path),
                        content = artifact.content?.let(::bounded),
                    )
                },
        )
    }

    private fun boundedModelSelection(selection: ModelSelectionProjection): ModelSelectionProjection =
        selection.copy(
            provider = bounded(selection.provider),
            model = bounded(selection.model),
            reasoningEffort = selection.reasoningEffort?.let(::bounded),
        )

    private fun boundedProjection(projection: CachedSessionProjection): CachedSessionProjection {
        val validTimeline = projection.timeline.filter { it.id.isNotBlank() && fitsString(it.id) }
        val retained = validTimeline.takeLast(MAX_TIMELINE_ENTRIES)
        var truncated = projection.cacheTruncated || retained.size != projection.timeline.size
        val timeline = retained.map { entry ->
            val text = bounded(entry.text)
            val content = entry.boundedContent?.let(::bounded)
            if (text != entry.text || content != entry.boundedContent) truncated = true
            entry.copy(
                text = text,
                callId = entry.callId?.takeIf(::fitsString),
                toolName = entry.toolName?.let(::bounded),
                boundedContent = content,
                // S-blob: attachment references bound like every other cached
                // string; an unboundable reference is dropped, never truncated
                // into a different content address.
                attachments = entry.attachments.take(MAX_CACHED_ENTRY_ATTACHMENTS).mapNotNull { attachment ->
                    if (!fitsString(attachment.attachmentId) || !fitsString(attachment.mediaType)) {
                        null
                    } else {
                        attachment.copy(name = attachment.name?.let(::bounded))
                    }
                },
            )
        }
        return projection.copy(
            title = projection.title?.let(::bounded),
            agentPreset = projection.agentPreset?.let(::bounded),
            model = projection.model?.let(::boundedModelSelection),
            timeline = timeline,
            cacheTruncated = truncated,
            // S-policy: rules are Host-bounded already; the cache re-fences
            // shape (an unboundable rule is dropped, never truncated into a
            // different rule) and keeps the list exact per rule id.
            approvalRules = projection.approvalRules
                .filter { rule ->
                    rule.ruleId.isNotBlank() && fitsString(rule.ruleId) &&
                        fitsString(rule.classKind) && fitsString(rule.toolName) &&
                        (rule.classMode?.let(::fitsString) ?: true) && fitsString(rule.grantedBy)
                }
                .distinctBy(ApprovalRuleState::ruleId)
                .take(MAX_POLICY_RULES),
        )
    }

    companion object {
        const val HOST_BINDING_BYTES = 32
        const val MAX_SESSIONS = 32
        const val MAX_PROJECTIONS = 8
        const val MAX_TIMELINE_ENTRIES = 300
        const val MAX_CACHED_ENTRY_ATTACHMENTS = 16
        const val MAX_AGENT_PRESETS = 64
        const val MAX_ARTIFACTS = 100
        /** Mirrors the Host engine's per-session active-rule cap (S-policy). */
        const val MAX_POLICY_RULES = 100
        const val MAX_DRAFT_CHARS = 12_000
        const val MAX_STRING_CHARS = 15_000
        const val MAX_STRING_BYTES = MAX_STRING_CHARS * 4
        const val MAX_READING_OFFSET_PX = 100_000

        private fun bounded(value: String): String = value.take(MAX_STRING_CHARS)

        private fun fitsString(value: String): Boolean =
            value.length <= MAX_STRING_CHARS && value.encodeToByteArray().size <= MAX_STRING_BYTES
    }
}

internal object OfflineWorkspaceCodec {
    // v10: timeline entries carry S-blob image attachment references.
    // v11: projections carry the S-policy fold (approval rules + budget).
    // v12: the workspace carries the Host's operator-facing display name.
    private const val VERSION = 12
    private const val MAX_ENTRY_ATTACHMENTS = 16

    fun encode(workspace: OfflineWorkspaceCache): ByteArray {
        val bounded = workspace.boundedForStorage()
        return ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(VERSION)
                data.write(bounded.hostBinding)
                data.writeNullableString(bounded.hostInstanceId)
                data.writeNullableString(bounded.hostDisplayName)
                data.writeLong(bounded.savedAtMs)
                data.writeInt(bounded.sessions.size)
                bounded.sessions.forEach { session ->
                    data.writeString(session.sessionId)
                    data.writeNullableString(session.title)
                    data.writeBoolean(session.running)
                    data.writeLong(session.updatedAtMs)
                    data.writeNullableString(session.workspaceLabel)
                    data.writeInt(session.pendingApprovalCount)
                    data.writeInt(session.pendingInputCount)
                    data.writeUsage(session.usage)
                    data.writeNullableString(session.parentSessionId)
                    data.writeNullableString(session.origin)
                    data.writeSubagent(session.subagent)
                    data.writeNullableString(session.agentPreset)
                    data.writeModelSelection(session.model)
                    // S-project: the registry label survives the cache like the
                    // workspace basename — absence stays absence offline.
                    data.writeNullableString(session.projectLabel)
                }
                data.writeNullableString(bounded.selectedSessionId)
                data.writeInt(bounded.projections.size)
                bounded.projections.forEach { projection -> data.writeProjection(projection) }
                data.writeInt(bounded.drafts.size)
                bounded.drafts.forEach { (sessionId, draft) ->
                    data.writeString(sessionId)
                    data.writeString(draft)
                }
                data.writeInt(bounded.readingPositions.size)
                bounded.readingPositions.forEach { (sessionId, position) ->
                    data.writeString(sessionId)
                    data.writeNullableString(position.anchorEntryId)
                    data.writeInt(position.offsetPx)
                    data.writeBoolean(position.followTail)
                }
                // S-mode-select: the connect-time preset roster rides the cache so
                // an offline view keeps the same honest labels (never re-offered
                // for selection while STALE).
                data.writeInt(bounded.agentPresets.size)
                bounded.agentPresets.forEach { preset ->
                    data.writeString(preset.id)
                    data.writeBoolean(preset.userTrust)
                    data.writeBoolean(preset.isDefault)
                    data.writeNullableString(preset.name)
                    data.writeNullableString(preset.description)
                    data.writeNullableString(preset.broken)
                }
                // S-session-admin: the connect-time catalog snapshot rides the
                // cache like the preset roster — an offline view keeps the same
                // honest labels and never offers selection while STALE.
                data.writeInt(bounded.modelCatalog.size)
                bounded.modelCatalog.forEach { group ->
                    data.writeString(group.id)
                    data.writeNullableString(group.name)
                    data.writeInt(group.models.size)
                    group.models.forEach { entry ->
                        data.writeString(entry.id)
                        data.writeNullableString(entry.name)
                        data.writeInt(entry.reasoningEfforts.size)
                        entry.reasoningEfforts.forEach { effort -> data.writeString(effort) }
                        data.writeNullableString(entry.defaultReasoningEffort)
                    }
                }
                data.writeInt(bounded.modelCatalogFailures.size)
                bounded.modelCatalogFailures.forEach { failure ->
                    data.writeString(failure.providerId)
                    data.writeNullableString(failure.detail)
                }
                // S-artifacts: absence stays absence offline; a cached roster is
                // always rendered with its STALE marker.
                data.writeInt(bounded.artifacts.size)
                bounded.artifacts.forEach { artifact ->
                    data.writeString(artifact.artifactId)
                    data.writeString(artifact.sessionId)
                    data.writeString(artifact.path)
                    data.writeBoolean(artifact.outsideWorkspace)
                    data.writeBoolean(artifact.isNewFile)
                    data.writeNullableString(artifact.content)
                    data.writeBoolean(artifact.truncated)
                    data.writeLong(artifact.registeredAtMs)
                }
            }
            output.toByteArray()
        }
    }

    fun decode(bytes: ByteArray): OfflineWorkspaceCache =
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            require(data.readInt() == VERSION) { "Unsupported offline cache version" }
            val hostBinding = ByteArray(OfflineWorkspaceCache.HOST_BINDING_BYTES).also(data::readFully)
            val hostInstanceId = data.readNullableString()
            val hostDisplayName = data.readNullableString()
            val savedAtMs = data.readLong().also { require(it >= 0) }
            val sessions = List(data.readCount(OfflineWorkspaceCache.MAX_SESSIONS)) {
                SessionDirectoryEntry(
                    sessionId = data.readString(),
                    title = data.readNullableString(),
                    running = data.readBoolean(),
                    updatedAtMs = data.readLong(),
                    workspaceLabel = data.readNullableString(),
                    pendingApprovalCount = data.readInt().also { require(it >= 0) },
                    pendingInputCount = data.readInt().also { require(it >= 0) },
                    usage = data.readUsage(),
                    parentSessionId = data.readNullableString(),
                    origin = data.readNullableString(),
                    subagent = data.readSubagent(),
                    agentPreset = data.readNullableString(),
                    model = data.readModelSelection(),
                    projectLabel = data.readNullableString(),
                ).also { require(it.sessionId.isNotBlank()) }
            }
            val selectedSessionId = data.readNullableString()
            val projections = List(data.readCount(OfflineWorkspaceCache.MAX_PROJECTIONS)) {
                data.readProjection()
            }
            val drafts = buildMap {
                repeat(data.readCount(OfflineWorkspaceCache.MAX_SESSIONS)) {
                    val sessionId = data.readString()
                    val draft = data.readString(OfflineWorkspaceCache.MAX_DRAFT_CHARS)
                    require(sessionId.isNotBlank() && sessionId !in this)
                    put(sessionId, draft)
                }
            }
            val positions = buildMap {
                repeat(data.readCount(OfflineWorkspaceCache.MAX_PROJECTIONS)) {
                    val sessionId = data.readString()
                    val position = CachedReadingPosition(
                        anchorEntryId = data.readNullableString(),
                        offsetPx = data.readInt().also {
                            require(it in 0..OfflineWorkspaceCache.MAX_READING_OFFSET_PX)
                        },
                        followTail = data.readBoolean(),
                    )
                    require(sessionId.isNotBlank() && sessionId !in this)
                    put(sessionId, position)
                }
            }
            val agentPresets = List(data.readCount(OfflineWorkspaceCache.MAX_AGENT_PRESETS)) {
                AgentPresetProjection(
                    id = data.readString().also { require(it.isNotBlank()) },
                    userTrust = data.readBoolean(),
                    isDefault = data.readBoolean(),
                    name = data.readNullableString(),
                    description = data.readNullableString(),
                    broken = data.readNullableString(),
                )
            }
            require(agentPresets.distinctBy(AgentPresetProjection::id).size == agentPresets.size)
            val modelCatalog = List(data.readCount(MAX_MODEL_PROVIDER_GROUPS)) {
                ModelProviderGroupProjection(
                    id = data.readString().also { require(it.isNotBlank()) },
                    name = data.readNullableString(),
                    models = List(data.readCount(MAX_MODELS_PER_PROVIDER)) {
                        ModelEntryProjection(
                            id = data.readString().also { require(it.isNotBlank()) },
                            name = data.readNullableString(),
                            reasoningEfforts = List(data.readCount(MAX_REASONING_EFFORTS_PER_MODEL)) {
                                data.readString().also { effort -> require(effort.isNotBlank()) }
                            },
                            defaultReasoningEffort = data.readNullableString(),
                        )
                    },
                )
            }
            require(modelCatalog.distinctBy(ModelProviderGroupProjection::id).size == modelCatalog.size)
            val modelCatalogFailures = List(data.readCount(MAX_MODEL_PROVIDER_GROUPS)) {
                ModelCatalogFailureProjection(
                    providerId = data.readString().also { require(it.isNotBlank()) },
                    detail = data.readNullableString(),
                )
            }
            val artifacts = List(data.readCount(OfflineWorkspaceCache.MAX_ARTIFACTS)) {
                ArtifactEntryState(
                    artifactId = data.readString().also { require(it.isNotBlank()) },
                    sessionId = data.readString().also { require(it.isNotBlank()) },
                    path = data.readString().also { require(it.isNotBlank()) },
                    outsideWorkspace = data.readBoolean(),
                    isNewFile = data.readBoolean(),
                    content = data.readNullableString(),
                    truncated = data.readBoolean(),
                    registeredAtMs = data.readLong().also { require(it >= 0) },
                )
            }
            require(artifacts.distinctBy(ArtifactEntryState::artifactId).size == artifacts.size)
            require(data.available() == 0) { "Trailing offline cache data" }
            OfflineWorkspaceCache(
                hostBinding = hostBinding,
                hostInstanceId = hostInstanceId,
                hostDisplayName = hostDisplayName,
                savedAtMs = savedAtMs,
                sessions = sessions,
                selectedSessionId = selectedSessionId,
                projections = projections,
                drafts = drafts,
                readingPositions = positions,
                agentPresets = agentPresets,
                modelCatalog = modelCatalog,
                modelCatalogFailures = modelCatalogFailures,
                artifacts = artifacts,
            ).also { workspace ->
                require(workspace.sessions.distinctBy(SessionDirectoryEntry::sessionId).size == sessions.size)
                require(workspace.projections.distinctBy(CachedSessionProjection::sessionId).size == projections.size)
                require(selectedSessionId == null || sessions.any { it.sessionId == selectedSessionId })
            }
        }

    private fun DataOutputStream.writeProjection(projection: CachedSessionProjection) {
        writeString(projection.sessionId)
        writeNullableString(projection.title)
        writeBoolean(projection.running)
        writeString(projection.streamId)
        writeInt(projection.projectionVersion)
        writeLong(projection.cursor)
        writeBoolean(projection.historyTruncated)
        writeBoolean(projection.cacheTruncated)
        writeLong(projection.savedAtMs)
        writeUsage(projection.usage)
        writeSubagent(projection.subagent)
        writeNullableString(projection.agentPreset)
        writeModelSelection(projection.model)
        // S-policy: the durable fold rides the projection (exact rule list +
        // optional ceiling with the owner-asserted gate state).
        writeInt(projection.approvalRules.size)
        projection.approvalRules.forEach { rule ->
            writeString(rule.ruleId)
            writeString(rule.classKind)
            writeString(rule.toolName)
            writeNullableString(rule.classMode)
            writeString(rule.grantedBy)
            writeLong(rule.grantedAtMs)
        }
        writeBoolean(projection.sessionBudget != null)
        projection.sessionBudget?.let { budget ->
            writeLong(budget.maxTotalTokens)
            writeBoolean(budget.exhausted)
        }
        writeInt(projection.timeline.size)
        projection.timeline.forEach { entry ->
            writeString(entry.id)
            writeLong(entry.sourceSequence)
            writeString(entry.kind.name)
            writeString(entry.text)
            writeBoolean(entry.final)
            writeNullableString(entry.callId)
            writeNullableString(entry.toolName)
            writeNullableString(entry.boundedContent)
            writeBoolean(entry.truncated)
            writeSource(entry.source)
            writeInt(entry.attachments.size)
            entry.attachments.forEach { attachment ->
                writeString(attachment.attachmentId)
                writeString(attachment.mediaType)
                writeLong(attachment.bytes)
                writeBoolean(attachment.width != null)
                attachment.width?.let(::writeInt)
                writeBoolean(attachment.height != null)
                attachment.height?.let(::writeInt)
                writeNullableString(attachment.name)
            }
        }
    }

    private fun DataInputStream.readProjection(): CachedSessionProjection {
        val sessionId = readString().also { require(it.isNotBlank()) }
        val title = readNullableString()
        val running = readBoolean()
        val streamId = readString().also { require(it.isNotBlank()) }
        val projectionVersion = readInt().also { require(it > 0) }
        val cursor = readLong().also { require(it >= 0) }
        val historyTruncated = readBoolean()
        val cacheTruncated = readBoolean()
        val savedAtMs = readLong().also { require(it >= 0) }
        val usage = readUsage()
        val subagent = readSubagent()
        val agentPreset = readNullableString()
        val model = readModelSelection()
        val approvalRules = List(readCount(OfflineWorkspaceCache.MAX_POLICY_RULES)) {
            ApprovalRuleState(
                ruleId = readString().also { require(it.isNotBlank()) },
                classKind = readString().also { require(it.isNotBlank()) },
                toolName = readString(),
                classMode = readNullableString(),
                grantedBy = readString(),
                grantedAtMs = readLong().also { require(it >= 0) },
            )
        }
        require(approvalRules.distinctBy(ApprovalRuleState::ruleId).size == approvalRules.size)
        val sessionBudget = if (readBoolean()) {
            SessionBudgetState(
                maxTotalTokens = readLong().also { require(it > 0) },
                exhausted = readBoolean(),
            )
        } else {
            null
        }
        val timeline = List(readCount(OfflineWorkspaceCache.MAX_TIMELINE_ENTRIES)) {
            TimelineEntry(
                id = readString().also { require(it.isNotBlank()) },
                sourceSequence = readLong(),
                kind = TimelineKind.valueOf(readString()),
                text = readString(),
                final = readBoolean(),
                callId = readNullableString(),
                toolName = readNullableString(),
                boundedContent = readNullableString(),
                truncated = readBoolean(),
                source = readSource(),
                attachments = List(readCount(MAX_ENTRY_ATTACHMENTS)) {
                    ImageAttachmentProjection(
                        attachmentId = readString().also { require(it.isNotBlank()) },
                        mediaType = readString().also { require(it.isNotBlank()) },
                        bytes = readLong().also { require(it >= 0) },
                        width = if (readBoolean()) readInt().also { require(it > 0) } else null,
                        height = if (readBoolean()) readInt().also { require(it > 0) } else null,
                        name = readNullableString(),
                    )
                },
            )
        }
        require(timeline.distinctBy(TimelineEntry::id).size == timeline.size)
        return CachedSessionProjection(
            sessionId = sessionId,
            title = title,
            running = running,
            streamId = streamId,
            projectionVersion = projectionVersion,
            cursor = cursor,
            timeline = timeline,
            historyTruncated = historyTruncated,
            cacheTruncated = cacheTruncated,
            savedAtMs = savedAtMs,
            usage = usage,
            subagent = subagent,
            agentPreset = agentPreset,
            model = model,
            approvalRules = approvalRules,
            sessionBudget = sessionBudget,
        )
    }

    private fun DataOutputStream.writeModelSelection(selection: ModelSelectionProjection?) {
        writeBoolean(selection != null)
        if (selection == null) return
        writeString(selection.provider)
        writeString(selection.model)
        writeNullableString(selection.reasoningEffort)
    }

    private fun DataInputStream.readModelSelection(): ModelSelectionProjection? {
        if (!readBoolean()) return null
        return ModelSelectionProjection(
            provider = readString().also { require(it.isNotBlank()) },
            model = readString().also { require(it.isNotBlank()) },
            reasoningEffort = readNullableString(),
        )
    }

    private fun DataOutputStream.writeUsage(usage: SessionUsageProjection?) {
        writeBoolean(usage != null)
        if (usage == null) return
        writeBoolean(usage.tokens != null)
        usage.tokens?.let { tokens ->
            writeNonNegativeCount(tokens.uncachedInputTokens)
            writeNonNegativeCount(tokens.outputTokens)
            writeNonNegativeCount(tokens.cacheReadTokens)
            writeNonNegativeCount(tokens.cacheWriteTokens)
        }
        writeBoolean(usage.pressure != null)
        usage.pressure?.let { pressure ->
            writeBoolean(pressure.pressureTokens != null)
            pressure.pressureTokens?.let { writeNonNegativeCount(it) }
            writeBoolean(pressure.projectedTokens != null)
            pressure.projectedTokens?.let { writeNonNegativeCount(it) }
            writeBoolean(pressure.contextWindow != null)
            pressure.contextWindow?.let { writeNonNegativeCount(it) }
        }
        writeBoolean(usage.stats != null)
        usage.stats?.let { stats ->
            writeNonNegativeCount(stats.turns)
            writeNonNegativeCount(stats.steps)
            writeNonNegativeCount(stats.llmMs)
            writeNonNegativeCount(stats.toolMs)
        }
    }

    private fun DataInputStream.readUsage(): SessionUsageProjection? {
        if (!readBoolean()) return null
        val tokens = if (readBoolean()) {
            TokenUsageProjection(
                uncachedInputTokens = readNonNegativeCount(),
                outputTokens = readNonNegativeCount(),
                cacheReadTokens = readNonNegativeCount(),
                cacheWriteTokens = readNonNegativeCount(),
            )
        } else {
            null
        }
        val pressure = if (readBoolean()) {
            ContextPressureProjection(
                pressureTokens = if (readBoolean()) readNonNegativeCount() else null,
                projectedTokens = if (readBoolean()) readNonNegativeCount() else null,
                contextWindow = if (readBoolean()) readNonNegativeCount() else null,
            )
        } else {
            null
        }
        val stats = if (readBoolean()) {
            SessionStatsProjection(
                turns = readNonNegativeCount(),
                steps = readNonNegativeCount(),
                llmMs = readNonNegativeCount(),
                toolMs = readNonNegativeCount(),
            )
        } else {
            null
        }
        return SessionUsageProjection(tokens = tokens, pressure = pressure, stats = stats)
    }

    private fun DataOutputStream.writeNonNegativeCount(value: Long) {
        require(value >= 0)
        writeLong(value)
    }

    private fun DataOutputStream.writeSubagent(subagent: SubagentProjection?) {
        writeBoolean(subagent != null)
        if (subagent == null) return
        writeNullableString(subagent.mode)
        writeNullableString(subagent.label)
        writeBoolean(subagent.settledMs != null)
        subagent.settledMs?.let { writeNonNegativeCount(it) }
        writeBoolean(subagent.activeSinceMs != null)
        subagent.activeSinceMs?.let { writeNonNegativeCount(it) }
        writeBoolean(subagent.activeThroughMs != null)
        subagent.activeThroughMs?.let { writeNonNegativeCount(it) }
    }

    private fun DataInputStream.readSubagent(): SubagentProjection? {
        if (!readBoolean()) return null
        return SubagentProjection(
            mode = readNullableString(),
            label = readNullableString(),
            settledMs = if (readBoolean()) readNonNegativeCount() else null,
            activeSinceMs = if (readBoolean()) readNonNegativeCount() else null,
            activeThroughMs = if (readBoolean()) readNonNegativeCount() else null,
        )
    }

    private fun DataOutputStream.writeSource(source: MessageSourceProjection?) {
        writeBoolean(source != null)
        if (source == null) return
        writeString(source.kind)
        writeNullableString(source.plugin)
        writeNullableString(source.form)
    }

    private fun DataInputStream.readSource(): MessageSourceProjection? {
        if (!readBoolean()) return null
        return MessageSourceProjection(
            kind = readString().also { require(it.isNotBlank()) },
            plugin = readNullableString(),
            form = readNullableString(),
        )
    }

    private fun DataInputStream.readNonNegativeCount(): Long = readLong().also { require(it >= 0) }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeString(value)
    }

    private fun DataInputStream.readNullableString(): String? = if (readBoolean()) readString() else null

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.encodeToByteArray()
        require(bytes.size <= OfflineWorkspaceCache.MAX_STRING_BYTES)
        writeInt(bytes.size)
        write(bytes)
        bytes.fill(0)
    }

    private fun DataInputStream.readString(maxChars: Int = OfflineWorkspaceCache.MAX_STRING_CHARS): String {
        val length = readInt()
        require(length in 0..OfflineWorkspaceCache.MAX_STRING_BYTES && length <= available())
        val bytes = ByteArray(length).also(::readFully)
        return try {
            bytes.decodeToString(throwOnInvalidSequence = true).also { require(it.length <= maxChars) }
        } finally {
            bytes.fill(0)
        }
    }

    private fun DataInputStream.readCount(max: Int): Int = readInt().also { require(it in 0..max) }
}
