package dev.dshremote.gate0c.ui

import dev.dshremote.discovery.LanDiscoveryPhase
import dev.dshremote.discovery.LanDiscoveryState
import dev.dshremote.discovery.NearbyDshHost
import dev.dshremote.discovery.NearbyHostPairingState
import dev.dshremote.gate0c.transport.AgentPresetProjection
import dev.dshremote.gate0c.transport.ArtifactEntryState
import dev.dshremote.gate0c.transport.AttachmentLimitsProjection
import dev.dshremote.gate0c.transport.CommittedImage
import dev.dshremote.gate0c.transport.ComposerImage
import dev.dshremote.gate0c.transport.ConnectionPhase
import dev.dshremote.gate0c.transport.ApprovalEvidence
import dev.dshremote.gate0c.transport.ApprovalInteractionState
import dev.dshremote.gate0c.transport.ApprovalRisk
import dev.dshremote.gate0c.transport.ApprovalRuleState
import dev.dshremote.gate0c.transport.SessionBudgetState
import dev.dshremote.gate0c.transport.ControlLeaseStatus
import dev.dshremote.gate0c.transport.Gate0CState
import dev.dshremote.gate0c.transport.ImageAttachmentProjection
import dev.dshremote.gate0c.transport.PendingCommandOperation
import dev.dshremote.gate0c.transport.PendingCommandProgress
import dev.dshremote.gate0c.transport.PendingCommandStatus
import dev.dshremote.gate0c.transport.SessionDirectoryEntry
import dev.dshremote.gate0c.transport.SessionStatsProjection
import dev.dshremote.gate0c.transport.SessionUsageProjection
import dev.dshremote.gate0c.transport.ContextPressureProjection
import dev.dshremote.gate0c.transport.MessageSourceProjection
import dev.dshremote.gate0c.transport.SubagentProjection
import dev.dshremote.gate0c.transport.TimelineEntry
import dev.dshremote.gate0c.transport.TimelineKind
import dev.dshremote.gate0c.transport.TokenUsageProjection

internal object RendererFixture {
    private val directoryNow = System.currentTimeMillis()

    private val fixtureUsage = SessionUsageProjection(
        tokens = TokenUsageProjection(
            uncachedInputTokens = 18_420,
            outputTokens = 3_912,
            cacheReadTokens = 96_300,
            cacheWriteTokens = 1_204,
        ),
        pressure = ContextPressureProjection(
            pressureTokens = 116_000,
            projectedTokens = 119_836,
            contextWindow = 128_000,
        ),
        stats = SessionStatsProjection(turns = 12, steps = 34, llmMs = 486_000, toolMs = 92_000),
    )

    private val fixtureSubagent = SubagentProjection(
        mode = "continuable",
        label = "explore auth refresh chain",
        settledMs = 41_200,
        activeSinceMs = null,
        activeThroughMs = null,
    )

    // S-mode-select fixture roster: the four shipped DSH presets verbatim
    // (name/description from apps/cli/config/agent-presets/*/preset.yml), plus
    // one broken locally authored row to exercise the disabled state.
    val fixtureAgentPresets: List<AgentPresetProjection> = listOf(
        AgentPresetProjection(
            id = "standard",
            userTrust = false,
            isDefault = true,
            name = "标准模式",
            description = "功能完整的编码 Agent，支持文件编辑、Shell、文件与网页检索、Skills、计划、目标、子代理和工作流。",
        ),
        AgentPresetProjection(
            id = "code",
            userTrust = false,
            isDefault = false,
            name = "PTC 模式",
            description = "具备标准模式的全部能力，并通过 Code Mode SDK 呈现工具，让模型用一个 TypeScript 程序组合多步操作。",
        ),
        AgentPresetProjection(
            id = "minimal",
            userTrust = false,
            isDefault = false,
            name = "极简模式",
            description = "仅提供持久 bash 与 str_replace_editor 的双工具编码 Agent。",
        ),
        AgentPresetProjection(
            id = "cordis",
            userTrust = false,
            isDefault = false,
            name = "创造模式",
            description = "用于创建自定义 Agent preset：具备标准模式的全部能力，并提供运行时检查、插件实验和 preset 创作指导。",
        ),
        AgentPresetProjection(
            id = "my-experiment",
            userTrust = true,
            isDefault = false,
            name = "我的实验 preset",
            broken = "composition text is not valid YAML",
        ),
    )

    // S-session-admin fixture catalog: a DeepSeek-shaped provider group (names
    // resolve like the real roster; ids stay raw), plus one failed provider row
    // to exercise the honest unavailable state.
    val fixtureModelCatalog: List<dev.dshremote.gate0c.transport.ModelProviderGroupProjection> = listOf(
        dev.dshremote.gate0c.transport.ModelProviderGroupProjection(
            id = "deepseek",
            name = "DeepSeek",
            models = listOf(
                dev.dshremote.gate0c.transport.ModelEntryProjection(
                    id = "deepseek-chat",
                    name = "V4 Flash",
                    reasoningEfforts = listOf("low", "high"),
                    defaultReasoningEffort = "high",
                ),
                dev.dshremote.gate0c.transport.ModelEntryProjection(
                    id = "deepseek-reasoner",
                    name = "V4 Pro",
                    reasoningEfforts = listOf("low", "high", "max"),
                    defaultReasoningEffort = "high",
                ),
            ),
        ),
    )
    val fixtureModelCatalogFailures: List<dev.dshremote.gate0c.transport.ModelCatalogFailureProjection> = listOf(
        dev.dshremote.gate0c.transport.ModelCatalogFailureProjection(
            providerId = "anthropic",
            detail = "provider adapter not configured",
        ),
    )
    private val fixtureModel = dev.dshremote.gate0c.transport.ModelSelectionProjection(
        provider = "deepseek",
        model = "deepseek-chat",
        reasoningEffort = "high",
    )

    val discovery: LanDiscoveryState = LanDiscoveryState(
        phase = LanDiscoveryPhase.COMPLETE,
        hosts = listOf(
            NearbyDshHost(
                serviceName = "Studio workstation",
                displayName = "Studio workstation",
                hostId = "8A4C".repeat(16),
                platform = "win32",
                pairingState = NearbyHostPairingState.NOT_PAIRED,
            ),
            NearbyDshHost(
                serviceName = "Travel laptop",
                displayName = "Travel laptop",
                hostId = "31D7".repeat(16),
                platform = "linux",
                pairingState = NearbyHostPairingState.PAIRED,
            ),
        ),
        explanation = "Nearby search ended. Use the Host-local QR invitation if your machine is missing.",
    )

    // S-artifacts fixture roster: three journal-fact rows exercising the new-file
    // marker, the bounded hunk content (rendered as the diff viewer), the
    // truncation marker and the outside-workspace minimization. The fourth row
    // pairs bounded hunks WITH truncation so the viewer's blob full-fetch
    // affordance (S-blob) is auditable in the fixture.
    val fixtureArtifacts: List<ArtifactEntryState> = listOf(
        ArtifactEntryState(
            artifactId = "renderer-active:42:0",
            sessionId = "renderer-active",
            path = "app/src/auth/session-refresh.ts",
            outsideWorkspace = false,
            isNewFile = false,
            content = """[{"path":"app/src/auth/session-refresh.ts","oldText":"export function refresh(session) {\n  return renew(session.token)\n}","newText":"export function refresh(session) {\n  if (session.token == null) return session\n  return renew(session.token)\n}"}]""",
            truncated = false,
            registeredAtMs = directoryNow - 4 * 60_000,
        ),
        ArtifactEntryState(
            artifactId = "renderer-review:17:0",
            sessionId = "renderer-review",
            path = "docs/carrier-review.md",
            outsideWorkspace = false,
            isNewFile = true,
            content = """[{"path":"docs/carrier-review.md","oldText":null,"newText":"# Carrier review\n\n- Noise IK authenticated\n- Capability mask rechecked per frame\n"}]""",
            truncated = false,
            registeredAtMs = directoryNow - 32 * 60_000,
        ),
        ArtifactEntryState(
            artifactId = "renderer-protocol:9:0",
            sessionId = "renderer-protocol",
            path = "cursor-semantics.ts",
            outsideWorkspace = true,
            isNewFile = false,
            content = null,
            truncated = true,
            registeredAtMs = directoryNow - 5 * 60 * 60_000,
        ),
        ArtifactEntryState(
            artifactId = "renderer-active:51:0",
            sessionId = "renderer-active",
            path = "app/src/theme/dark-palette.ts",
            outsideWorkspace = false,
            isNewFile = false,
            content = """[{"path":"app/src/theme/dark-palette.ts","oldText":"export const palette = light","newText":"export const palette = dark"}]""",
            truncated = true,
            registeredAtMs = directoryNow - 90_000,
        ),
    )

    val directory: Gate0CState = Gate0CState(
        phase = ConnectionPhase.READY,
        endpoint = "renderer fixture",
        hostInstanceId = "studio-host",
        grantedCapabilities = 95uL,
        agentPresets = fixtureAgentPresets,
        modelCatalog = fixtureModelCatalog,
        modelCatalogFailures = fixtureModelCatalogFailures,
        artifacts = fixtureArtifacts,
        sessions = listOf(
            SessionDirectoryEntry(
                sessionId = "renderer-active",
                title = "Build the DSH Remote Android experience",
                running = true,
                updatedAtMs = directoryNow - 2 * 60_000,
                workspaceLabel = "dsh remote",
                pendingInputCount = 1,
                usage = fixtureUsage,
                agentPreset = "code",
                model = fixtureModel,
                projectLabel = "DSH Remote",
            ),
            SessionDirectoryEntry(
                sessionId = "renderer-review",
                title = "Review the source-backed Host carrier",
                running = true,
                updatedAtMs = directoryNow - 12 * 60_000,
                workspaceLabel = "deepseek-harness",
                pendingApprovalCount = 2,
                usage = fixtureUsage,
                agentPreset = "standard",
                projectLabel = "DeepSeek Harness",
            ),
            SessionDirectoryEntry(
                sessionId = "renderer-protocol",
                title = "Define cursor recovery semantics",
                running = false,
                updatedAtMs = directoryNow - 3 * 60 * 60_000,
                workspaceLabel = "dsh remote",
                agentPreset = "minimal",
                projectLabel = "DSH Remote",
            ),
            SessionDirectoryEntry(
                sessionId = "renderer-audit",
                title = "Audit approval authority boundaries",
                running = false,
                updatedAtMs = directoryNow - 24 * 60 * 60_000,
                workspaceLabel = "deepseek-harness",
                projectLabel = "DeepSeek Harness",
            ),
            SessionDirectoryEntry(
                sessionId = "renderer-subagent",
                title = "explore auth refresh chain",
                running = false,
                updatedAtMs = directoryNow - 26 * 60 * 60_000,
                workspaceLabel = "dsh remote",
                parentSessionId = "renderer-active",
                origin = "subagent",
                subagent = fixtureSubagent,
                projectLabel = "DSH Remote",
            ),
        ),
        events = listOf("Renderer fixture: source-shaped Session directory."),
    )

    val longSession: Gate0CState = Gate0CState(
        phase = ConnectionPhase.READY,
        endpoint = "renderer fixture",
        hostInstanceId = "studio-host",
        grantedCapabilities = 79uL,
        agentPresets = fixtureAgentPresets,
        modelCatalog = fixtureModelCatalog,
        modelCatalogFailures = fixtureModelCatalogFailures,
        sessionId = "fixture-long-session",
        sessionTitle = "Build the DSH Remote Android experience",
        sessionRunning = true,
        sessionUsage = fixtureUsage,
        sessionAgentPreset = "code",
        sessionModel = fixtureModel,
        activityRevision = 180,
        streamId = "fixture-stream",
        projectionVersion = 8,
        cursor = 723,
        controlLease = ControlLeaseStatus(
            sessionId = "fixture-long-session",
            epoch = "12",
            expiresAtMs = 4_000_000_000_000,
        ),
        localDraft = "Run the focused Android recovery checks and summarize any remaining risk.",
        timeline = buildList {
            repeat(180) { index ->
                val sequence = index * 4L
                add(
                    TimelineEntry(
                        id = "user-$index",
                        sourceSequence = sequence + 1,
                        kind = TimelineKind.USER,
                        text = "Review renderer slice ${index + 1} and preserve the current reading position.",
                    ),
                )
                add(
                    TimelineEntry(
                        id = "assistant-$index",
                        sourceSequence = sequence + 2,
                        kind = TimelineKind.ASSISTANT,
                        text = "I inspected the source-backed projection and kept the timeline append-stable. " +
                            "This response uses a stable message identity, so streamed text updates in place.",
                    ),
                )
                add(
                    TimelineEntry(
                        id = "tool-$index",
                        sourceSequence = sequence + 3,
                        kind = if (index % 2 == 0) TimelineKind.TOOL_DIFF else TimelineKind.TOOL_TERMINAL,
                        text = if (index % 2 == 0) "2 files changed · +48 −17" else "Android verification · exit 0",
                        callId = "call-$index",
                        toolName = if (index % 2 == 0) "apply_patch" else "gradlew",
                        boundedContent = if (index % 2 == 0) {
                            "+ stable timeline identity\n− one row per streamed token"
                        } else {
                            ":app:testDebugUnitTest\nBUILD SUCCESSFUL"
                        },
                        truncated = index % 17 == 0,
                    ),
                )
                add(
                    TimelineEntry(
                        id = "session-$index",
                        sourceSequence = sequence + 4,
                        kind = TimelineKind.SESSION,
                        text = if (index == 179) "Running" else "Checkpoint ${index + 1}",
                    ),
                )
            }
            // S-vocab-ext fixture rows: injected context, sub-agent delegation card,
            // and a durable per-turn terminal fact. Sequences follow the 720-loop.
            add(
                TimelineEntry(
                    id = "inject-fixture",
                    sourceSequence = 721,
                    kind = TimelineKind.INJECT,
                    text = "AGENTS.md + git status",
                    source = MessageSourceProjection(kind = "plugin", plugin = "hooks-codex", form = "snapshot"),
                ),
            )
            add(
                TimelineEntry(
                    id = "tool-subagent-fixture",
                    sourceSequence = 722,
                    kind = TimelineKind.SUBAGENT,
                    text = "梳理 refreshToken 调用链",
                    callId = "call-subagent",
                    toolName = "subagent",
                    boundedContent = "dispatch: explore\nscope: src/auth/**",
                ),
            )
            add(
                TimelineEntry(
                    id = "turn-end-fixture",
                    sourceSequence = 723,
                    kind = TimelineKind.SESSION,
                    text = "Idle · completed",
                ),
            )
        },
        events = listOf("Renderer fixture: 723 typed timeline rows."),
    )

    val offlineSession: Gate0CState = Gate0CState(
        phase = ConnectionPhase.OFFLINE,
        endpoint = "127.0.0.1:50051",
        hostInstanceId = "studio-host",
        sessions = directory.sessions,
        agentPresets = fixtureAgentPresets,
        modelCatalog = fixtureModelCatalog,
        modelCatalogFailures = fixtureModelCatalogFailures,
        sessionId = "renderer-active",
        sessionTitle = "Build the DSH Remote Android experience",
        sessionRunning = true,
        sessionUsage = fixtureUsage,
        sessionAgentPreset = "code",
        sessionModel = fixtureModel,
        projectionVersion = 8,
        cursor = 42,
        timeline = listOf(
            TimelineEntry(
                id = "offline-user",
                sourceSequence = 40,
                kind = TimelineKind.USER,
                text = "Keep enough context available when the Host cannot be reached.",
            ),
            TimelineEntry(
                id = "offline-assistant",
                sourceSequence = 41,
                kind = TimelineKind.ASSISTANT,
                text = "This encrypted local copy is explicitly stale. Reconnect replaces it with a fresh Host snapshot.",
            ),
            TimelineEntry(
                id = "offline-tool",
                sourceSequence = 42,
                kind = TimelineKind.TOOL_UNSUPPORTED,
                text = "unregistered-tool",
                callId = "offline-call",
                toolName = "unregistered-tool",
            ),
        ),
        failure = "Host is unavailable.",
        offlineSnapshot = true,
        offlineCacheSavedAtMs = directoryNow - 8 * 60_000,
        offlineCacheTruncated = true,
        localDraft = "Ask DSH to verify the reconnect result before continuing.",
        readingAnchorId = "offline-assistant",
        readingOffsetPx = 0,
        followTail = false,
        readingAnchorUnavailable = true,
        events = listOf("Renderer fixture: encrypted stale workspace."),
    )

    val incompatibleSession: Gate0CState = offlineSession.copy(
        phase = ConnectionPhase.INCOMPATIBLE,
        failure = "Update DSH Remote and the Host integration before reconnecting.",
        events = listOf("Renderer fixture: incompatible Host projection protocol."),
    )

    val commandUnknownSession: Gate0CState = longSession.copy(
        pendingCommand = PendingCommandStatus(
            commandId = "android-command-recovery",
            sessionId = "fixture-long-session",
            operation = PendingCommandOperation.SEND_INPUT,
            expectedActivityRevision = null,
            progress = PendingCommandProgress.UNKNOWN,
            createdAtMs = directoryNow - 45_000,
        ),
        commandWarning = "The final Host receipt was lost. Reconcile checks the same command id without resending a new effect.",
    )

    val stopRequestedSession: Gate0CState = longSession.copy(
        pendingCommand = PendingCommandStatus(
            commandId = "android-stop-recovery",
            sessionId = "fixture-long-session",
            operation = PendingCommandOperation.STOP,
            expectedActivityRevision = 180,
            progress = PendingCommandProgress.REQUESTED,
            createdAtMs = directoryNow - 2_000,
        ),
        commandWarning = null,
    )

    private val sensitiveApproval = ApprovalInteractionState(
        approvalId = "approval-sensitive",
        revision = "8f7bda39c1e04d54",
        sessionId = "fixture-long-session",
        toolName = "apply_patch",
        callId = "call-sensitive",
        reason = "Update the Android pairing documentation",
        workspaceLabel = "dsh remote",
        allowOnce = true,
        deny = true,
        // S-policy: the Host offered ALLOW_SAME_KIND for this ask, so the
        // fixture exercises the real third decision (P7 capture target).
        allowSameKind = true,
        evidence = ApprovalEvidence(
            available = true,
            summary = "Edit Android recovery documentation",
            risk = ApprovalRisk.SENSITIVE,
            resources = listOf("android-app/README.md", "docs/engineering/ANDROID_BASELINE.md"),
            consequence = "Two tracked documentation files will be modified.",
            source = "apply_patch presenter",
            unavailableReason = null,
        ),
    )

    val approvalSensitiveSession: Gate0CState = longSession.copy(
        grantedCapabilities = 95uL,
        approvals = listOf(sensitiveApproval),
    )

    val approvalDestructiveSession: Gate0CState = longSession.copy(
        grantedCapabilities = 95uL,
        approvals = listOf(
            sensitiveApproval.copy(
                approvalId = "approval-destructive",
                revision = "bd80f13d44684389",
                toolName = "delete_workspace",
                evidence = ApprovalEvidence(
                    available = true,
                    summary = "Delete generated release artifacts",
                    risk = ApprovalRisk.DESTRUCTIVE,
                    resources = listOf("artifacts/release/", "android-app/build/outputs/"),
                    consequence = "Selected local artifacts will be permanently removed from this Host.",
                    source = "delete_workspace policy owner",
                    unavailableReason = null,
                ),
            ),
        ),
        // S-policy fixture: an already-minted rule plus a budget, so the
        // usage-strip segment, the policy sheet, and revocation are capturable.
        approvalRules = listOf(
            ApprovalRuleState(
                ruleId = "9c1e04d548f7bda39c1e04d548f7bda3",
                classKind = "tool",
                toolName = "apply_patch",
                classMode = null,
                grantedBy = "user",
                grantedAtMs = directoryNow - 540_000,
            ),
        ),
        sessionBudget = SessionBudgetState(maxTotalTokens = 200_000, exhausted = false),
    )

    /**
     * S-mode-select fixture: a freshly created blank Session (no turn yet), the
     * only state in which the mode chip is an active picker.
     */
    val blankSession: Gate0CState = Gate0CState(
        phase = ConnectionPhase.READY,
        endpoint = "renderer fixture",
        hostInstanceId = "studio-host",
        grantedCapabilities = 95uL,
        sessions = directory.sessions,
        agentPresets = fixtureAgentPresets,
        modelCatalog = fixtureModelCatalog,
        modelCatalogFailures = fixtureModelCatalogFailures,
        sessionId = "renderer-blank-session",
        sessionTitle = null,
        sessionRunning = false,
        streamId = "fixture-stream-blank",
        projectionVersion = 8,
        cursor = 0,
        timeline = emptyList(),
        events = listOf("Renderer fixture: blank Session awaiting its first turn."),
    )

    /**
     * S-blob fixture: a short session whose user rows carry image attachments
     * (rendered after verified fetch), plus one composer-staged image with a
     * decodable preview and one Host-committed reference (no local preview —
     * honest reference-only row). [previewUri] and [fetchedBytes] come from the
     * deterministic PNG MainActivity generates into the fixture cache, so the
     * declared size/dimensions match the bytes the fetch returns.
     */
    fun blobSession(previewUri: String, fetchedBytes: Long): Gate0CState = Gate0CState(
        phase = ConnectionPhase.READY,
        endpoint = "renderer fixture",
        hostInstanceId = "studio-host",
        grantedCapabilities = 95uL,
        sessions = directory.sessions,
        agentPresets = fixtureAgentPresets,
        modelCatalog = fixtureModelCatalog,
        modelCatalogFailures = fixtureModelCatalogFailures,
        artifacts = fixtureArtifacts,
        attachmentLimits = AttachmentLimitsProjection(
            maxImageBytes = 10L * 1024 * 1024,
            maxImagesPerMessage = 4,
            mediaTypes = listOf("image/png", "image/jpeg"),
        ),
        composerImages = listOf(
            ComposerImage(
                key = "fixture-composer-1",
                previewUri = previewUri,
                displayName = "dark-theme-draft.png",
                mediaType = "image/png",
            ),
        ),
        committedAttachments = listOf(
            CommittedImage(
                attachmentId = "fixture-committed-1",
                displayName = "earlier-reference.png",
            ),
        ),
        sessionId = "fixture-blob-session",
        sessionTitle = "设计稿深色主题改造",
        sessionRunning = false,
        sessionUsage = fixtureUsage,
        sessionAgentPreset = "standard",
        sessionModel = fixtureModel,
        activityRevision = 6,
        streamId = "fixture-stream-blob",
        projectionVersion = 8,
        cursor = 6,
        controlLease = ControlLeaseStatus(
            sessionId = "fixture-blob-session",
            epoch = "7",
            expiresAtMs = 4_000_000_000_000,
        ),
        localDraft = "按钮保持品牌蓝，",
        timeline = listOf(
            TimelineEntry(
                id = "blob-user-1",
                sourceSequence = 1,
                kind = TimelineKind.USER,
                text = "把这张设计稿改成深色主题，按钮保持品牌蓝。",
                attachments = listOf(
                    ImageAttachmentProjection(
                        attachmentId = "fixture-image-1",
                        mediaType = "image/png",
                        bytes = fetchedBytes,
                        width = 480,
                        height = 320,
                        name = "design-mock.png",
                    ),
                ),
            ),
            TimelineEntry(
                id = "blob-assistant-1",
                sourceSequence = 2,
                kind = TimelineKind.ASSISTANT,
                text = "收到。我先确认设计稿的主色与层级，再给深色化方案。",
            ),
            TimelineEntry(
                id = "blob-user-2",
                sourceSequence = 3,
                kind = TimelineKind.USER,
                text = "再参考这张现状截图。",
                attachments = listOf(
                    ImageAttachmentProjection(
                        attachmentId = "fixture-image-2",
                        mediaType = "image/png",
                        bytes = fetchedBytes,
                        width = 480,
                        height = 320,
                        name = "current-state.png",
                    ),
                ),
            ),
            TimelineEntry(
                id = "blob-assistant-2",
                sourceSequence = 4,
                kind = TimelineKind.ASSISTANT,
                text = "两张都看到了：现状是浅色卡片式布局。我会保持栅格不变，只替换色板与对比度。",
            ),
            TimelineEntry(
                id = "blob-session-1",
                sourceSequence = 5,
                kind = TimelineKind.SESSION,
                text = "Idle · completed",
            ),
        ),
        events = listOf("Renderer fixture: S-blob image session."),
    )
}
