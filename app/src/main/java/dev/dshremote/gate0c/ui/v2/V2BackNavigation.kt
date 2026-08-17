package dev.dshremote.gate0c.ui.v2

/**
 * System-back destinations on the paired v2 surface.
 *
 * Sheets (`V2Sheet` / `ModalBottomSheet`) consume back themselves and are not
 * listed. Root tabs return [FINISH] so the single Activity can close.
 */
internal enum class V2SystemBackAction {
    CLOSE_ARTIFACT,
    CLOSE_TOOL_DETAIL,
    CLOSE_REPLAY,
    LEAVE_CHAT,
    FINISH,
}

internal fun v2SystemBackAction(
    artifactOpen: Boolean,
    toolDetailOpen: Boolean,
    replayOpen: Boolean,
    chatOpen: Boolean,
): V2SystemBackAction = when {
    artifactOpen -> V2SystemBackAction.CLOSE_ARTIFACT
    toolDetailOpen -> V2SystemBackAction.CLOSE_TOOL_DETAIL
    replayOpen -> V2SystemBackAction.CLOSE_REPLAY
    chatOpen -> V2SystemBackAction.LEAVE_CHAT
    else -> V2SystemBackAction.FINISH
}
