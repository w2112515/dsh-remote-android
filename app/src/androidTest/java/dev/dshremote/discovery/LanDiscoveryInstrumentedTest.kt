package dev.dshremote.discovery

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanDiscoveryInstrumentedTest {
    @Test
    fun permissionDenialPreservesManualRecoveryWithoutClaimingNoHosts() {
        val client = LanDiscoveryClient(ApplicationProvider.getApplicationContext())
        try {
            client.permissionDenied()

            val state = client.state.value
            assertEquals(LanDiscoveryPhase.MANUAL_RECOVERY, state.phase)
            assertTrue(state.hosts.isEmpty())
            assertTrue(state.explanation.orEmpty().contains("No conclusion"))
            assertTrue(state.explanation.orEmpty().contains("QR invitation"))
        } finally {
            client.close()
        }
    }
}
