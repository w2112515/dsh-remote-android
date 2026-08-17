package dev.dshremote.gate0c.transport

private val WINDOWS_RESERVED = Regex("^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?$", RegexOption.IGNORE_CASE)
private val FORBIDDEN = Regex("[\\\\/<>:\"|?*\\u0000-\\u001f]")

/**
 * Host-owned child folder name: a single segment, never a path.
 * Must stay aligned with Host `sanitizeRemoteWorkspaceName`.
 */
internal object RemoteWorkspaceName {
    fun sanitize(raw: String): String? {
        val name = raw.trim()
        if (name.isEmpty() || name.length > 64) return null
        if (name == "." || name == "..") return null
        if (FORBIDDEN.containsMatchIn(name)) return null
        if (WINDOWS_RESERVED.matches(name)) return null
        return name
    }
}

/**
 * Keep a just-created blank session across HELLO when the directory still
 * hides it. Fall back to the first directory row only when there is nothing
 * to keep.
 */
internal fun helloSelectedSessionId(
    current: String?,
    directoryIds: Collection<String>,
    pendingCreateSessionId: String?,
): String? {
    if (current != null && (
            current in directoryIds ||
                current == pendingCreateSessionId ||
                current.startsWith("android-")
            )
    ) {
        return current
    }
    return directoryIds.firstOrNull()
}
