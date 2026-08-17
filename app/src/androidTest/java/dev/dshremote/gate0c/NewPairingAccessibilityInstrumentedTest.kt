package dev.dshremote.gate0c

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dshremote.gate0c.ui.DshRemoteTheme
import dev.dshremote.gate0c.ui.RendererFixture
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NewPairingAccessibilityInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun newCeremonyRequiresExplicitAcknowledgementBeforeLocalIdentityRemoval() {
        var resets = 0
        compose.setContent {
            DshRemoteTheme {
                SessionScreen(
                    state = RendererFixture.offlineSession.copy(
                        newPairingRequired = true,
                        timeline = emptyList(),
                        readingAnchorId = null,
                        readingOffsetPx = 0,
                        followTail = false,
                    ),
                    onBack = {},
                    onConnect = {},
                    onProbe = {},
                    onAcquireControl = {},
                    onSend = {},
                    onStop = {},
                    onApprovalDecision = { _, _ -> },
                    onReconcile = {},
                    onClearLocalCopy = {},
                    onStartNewPairing = { resets++ },
                    onDraftChanged = {},
                    onReadingPositionChanged = { _, _, _ -> },
                )
            }
        }

        compose.onNodeWithText("AUTHORIZATION ENDED · NEW PAIRING REQUIRED")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Pair again").performClick()
        compose.onNodeWithText("Create a new device identity?").assertIsDisplayed()
        compose.onNodeWithTag("confirm-new-pairing").assertIsNotEnabled()
        compose.onNodeWithTag("new-pairing-acknowledgement").performClick()
        compose.onNodeWithTag("confirm-new-pairing").assertIsEnabled().performClick()

        compose.runOnIdle { assertEquals(1, resets) }
    }
}
