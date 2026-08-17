package dev.dshremote.gate0c.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteWorkspaceNameTest {
    @Test
    fun acceptsAPlainFolderNameAndTrimsIt() {
        assertEquals("notes", RemoteWorkspaceName.sanitize("  notes  "))
        assertEquals("项目", RemoteWorkspaceName.sanitize("项目"))
    }

    @Test
    fun rejectsPathsTraversalReservedAndEmptyNames() {
        assertNull(RemoteWorkspaceName.sanitize(""))
        assertNull(RemoteWorkspaceName.sanitize(".."))
        assertNull(RemoteWorkspaceName.sanitize("a/b"))
        assertNull(RemoteWorkspaceName.sanitize("a\\b"))
        assertNull(RemoteWorkspaceName.sanitize("foo:bar"))
        assertNull(RemoteWorkspaceName.sanitize("con"))
        assertNull(RemoteWorkspaceName.sanitize("x".repeat(65)))
    }

    @Test
    fun helloKeepsABlankCreateIdWhenTheDirectoryHidesIt() {
        assertEquals(
            "android-new",
            helloSelectedSessionId("android-new", listOf("other"), pendingCreateSessionId = "android-new"),
        )
        assertEquals(
            "android-new",
            helloSelectedSessionId("android-new", listOf("other"), pendingCreateSessionId = null),
        )
        assertEquals(
            "listed",
            helloSelectedSessionId(null, listOf("listed"), pendingCreateSessionId = null),
        )
        assertEquals(
            "listed",
            helloSelectedSessionId("gone", listOf("listed"), pendingCreateSessionId = null),
        )
    }
}
