package dev.dshremote.gate0c.transport

import dev.dshremote.protocol.v1alpha.ArtifactSummary as ProtoArtifactSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactMappingTest {
    @Test
    fun mapsEveryWireFieldVerbatim() {
        val state = artifactEntryStateOf(
            ProtoArtifactSummary.newBuilder()
                .setArtifactId("s1:42:0")
                .setSessionId("s1")
                .setPath("src/a.ts")
                .setOutsideWorkspace(false)
                .setIsNewFile(true)
                .setContent("""[{"path":"src/a.ts","oldText":null,"newText":"x"}]""")
                .setTruncated(false)
                .setRegisteredAtMs(1_700_000_000_000)
                .build(),
        )

        assertEquals("s1:42:0", state.artifactId)
        assertEquals("s1", state.sessionId)
        assertEquals("src/a.ts", state.path)
        assertEquals(false, state.outsideWorkspace)
        assertEquals(true, state.isNewFile)
        assertEquals("""[{"path":"src/a.ts","oldText":null,"newText":"x"}]""", state.content)
        assertEquals(false, state.truncated)
        assertEquals(1_700_000_000_000, state.registeredAtMs)
    }

    @Test
    fun keepsAbsentContentAbsentNeverEmpty() {
        val state = artifactEntryStateOf(
            ProtoArtifactSummary.newBuilder()
                .setArtifactId("s1:7:1")
                .setSessionId("s1")
                .setPath("b.ts")
                .setRegisteredAtMs(5)
                .build(),
        )

        assertNull(state.content)
        assertEquals(false, state.truncated)
    }

    @Test
    fun liveRegistrationPrependsAndDedupesByArtifactId() {
        val first = ArtifactEntryState("s1:2:0", "s1", "a.ts", false, true, null, false, 100)
        val second = ArtifactEntryState("s1:4:0", "s1", "b.ts", false, false, "[]", true, 200)

        val roster = emptyList<ArtifactEntryState>()
            .withArtifactRegistered(first)
            .withArtifactRegistered(second)
        assertEquals(listOf("s1:4:0", "s1:2:0"), roster.map { it.artifactId })

        // A repeated id is the same immutable journal fact: no move, no update.
        val replayed = roster.withArtifactRegistered(first.copy(content = "mutated"))
        assertEquals(roster, replayed)
        assertTrue(replayed.first().truncated)
    }
}
