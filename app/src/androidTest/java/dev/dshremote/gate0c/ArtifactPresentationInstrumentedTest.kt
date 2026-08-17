package dev.dshremote.gate0c

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dshremote.gate0c.ui.v2.artifactKindOf
import dev.dshremote.gate0c.ui.v2.artifactSizeLabel
import dev.dshremote.gate0c.ui.v2.V2ArtifactKind
import dev.dshremote.gate0c.ui.v2.parseArtifactHunks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The artifact presentation helpers parse the Host's bounded hunk JSON with
 * org.json, which only exists on a real runtime — these pin the honest
 * absence/malformed handling where the parser actually runs.
 */
@RunWith(AndroidJUnit4::class)
class ArtifactPresentationInstrumentedTest {
    @Test
    fun parsesWholeHunkTriplesVerbatim() {
        val hunks = parseArtifactHunks(
            """[{"path":"a.ts","oldText":null,"newText":"one\ntwo\n"},""" +
                """{"path":"a.ts","oldText":"old","newText":"new"}]""",
        )

        assertEquals(2, hunks?.size)
        assertEquals(null, hunks?.first()?.oldText)
        assertEquals("one\ntwo\n", hunks?.first()?.newText)
        assertEquals("old", hunks?.last()?.oldText)
    }

    @Test
    fun treatsAbsentEmptyAndMalformedContentAsAbsent() {
        assertNull(parseArtifactHunks(null))
        assertNull(parseArtifactHunks("[]"))
        assertNull(parseArtifactHunks("not json"))
        assertNull(parseArtifactHunks("""[{"path":1}]"""))
    }

    @Test
    fun derivesSizeFromHunksWithoutPhantomTrailingLines() {
        val hunks = parseArtifactHunks(
            """[{"path":"a.ts","oldText":"x\ny","newText":"x\nz\nw\n"}]""",
        )

        // "x\nz\nw\n" is three real lines; the final newline is punctuation.
        assertEquals("+3/−2", artifactSizeLabel(hunks))
        assertNull(artifactSizeLabel(null))
    }

    @Test
    fun derivesIconFamilyFromExtensionOnly() {
        assertEquals(V2ArtifactKind.DIFF, artifactKindOf("fix-auth-race.diff"))
        assertEquals(V2ArtifactKind.DIFF, artifactKindOf("patches/0001.patch"))
        assertEquals(V2ArtifactKind.MD, artifactKindOf("docs/REVIEW.md"))
        assertEquals(V2ArtifactKind.LOG, artifactKindOf("build/output.log"))
        assertEquals(V2ArtifactKind.CODE, artifactKindOf("src/session-refresh.ts"))
        assertEquals(V2ArtifactKind.CODE, artifactKindOf("no-extension"))
    }
}
