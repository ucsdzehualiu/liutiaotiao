package com.liutiaotiao

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FixGate] 修复节流的回归测试。
 */
class FixGateTest {

    @Test
    fun `从未修复过时允许修复`() {
        assertTrue(FixGate.canAttempt(now = 1000, lastAt = 0))
    }

    @Test
    fun `间隔未满时拒绝修复`() {
        assertFalse(FixGate.canAttempt(now = 1000, lastAt = 500))
        assertFalse(FixGate.canAttempt(now = 10_499, lastAt = 500))
    }

    @Test
    fun `间隔满后允许再次修复`() {
        assertTrue(FixGate.canAttempt(now = 10_500, lastAt = 500))
    }

    @Test
    fun `自定义间隔生效`() {
        // 间隔恰好未满（差 1ms）→ 拒绝；间隔满 → 允许。
        assertFalse(FixGate.canAttempt(now = 299, lastAt = 100, minIntervalMs = 200))
        assertTrue(FixGate.canAttempt(now = 300, lastAt = 100, minIntervalMs = 200))
    }
}
