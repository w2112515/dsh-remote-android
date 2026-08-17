package dev.dshremote.gate0c.ui.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V2FleetRepairTest {
    @Test
    fun unauthenticatedRegistryShowsRepairRetry() {
        val copy = fleetRepairCopy(FleetRegistryFailure.UNAUTHENTICATED)

        assertEquals("配对记录无法认证", copy.title)
        assertEquals("重试", copy.action)
        assertEquals("重新配对", copy.resetAction)
        assertTrue(copy.detail.contains("电脑上的 Host 记录不会被撤销"))
    }

    @Test
    fun lockedRegistryDoesNotAskForRepair() {
        val copy = fleetRepairCopy(FleetRegistryFailure.LOCKED)

        assertEquals("配对记录被设备锁封存", copy.title)
        assertEquals("重试", copy.action)
        assertEquals(null, copy.resetAction)
        assertFalse(copy.action.contains("修复"))
        assertTrue(copy.detail.contains("无需修复"))
    }

    @Test
    fun resetGateKeepsHostRecordsAndRequiresAcknowledgement() {
        val copy = fleetResetCopy()

        assertEquals("重新配对？", copy.title)
        assertTrue(copy.detail.contains("不会撤销或删除电脑上的 Host 记录"))
        assertEquals("我知道本机离线数据和未完成操作无法恢复。", copy.acknowledgement)
        assertEquals("保留本机配对", copy.dismiss)
        assertEquals("清除本机配对并重新开始", copy.confirm)
    }
}
