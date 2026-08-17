package dev.dshremote.gate0c

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.dshremote.gate0c.transport.PendingApprovalDecision
import dev.dshremote.gate0c.ui.DshRemoteTheme
import dev.dshremote.gate0c.ui.RendererFixture
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApprovalAccessibilityInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun destructiveApprovalRemainsOperableAtTwoHundredPercentFontScale() {
        val density = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density
        var decision: Pair<String, PendingApprovalDecision>? = null

        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                DshRemoteTheme {
                    SessionScreen(
                        state = RendererFixture.approvalDestructiveSession,
                        onBack = {},
                        onConnect = {},
                        onProbe = {},
                        onAcquireControl = {},
                        onSend = {},
                        onStop = {},
                        onApprovalDecision = { approvalId, outcome -> decision = approvalId to outcome },
                        onReconcile = {},
                        onClearLocalCopy = {},
                        onStartNewPairing = {},
                        onDraftChanged = {},
                        onReadingPositionChanged = { _, _, _ -> },
                    )
                }
            }
        }

        compose.onNodeWithTag("approval-attention").assertIsDisplayed()
        compose.onNodeWithTag("approval-allow-once").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Confirm destructive approval").assertIsDisplayed()
        compose.onNodeWithTag("approval-confirm-allow-once").assertIsNotEnabled()
        compose.onNodeWithTag("approval-destructive-acknowledgement")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.onNodeWithTag("approval-confirm-allow-once").assertIsEnabled().performClick()

        compose.runOnIdle {
            assertEquals(
                "approval-destructive" to PendingApprovalDecision.ALLOW_ONCE,
                decision,
            )
        }
    }
}
