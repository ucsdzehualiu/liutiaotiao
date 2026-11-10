package com.liutiaotiao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RetryPolicy] 的回归测试。
 *
 * 锁死两点：
 *   - 手势 / ACTION_CLICK 必须交替（不能因为某一种在本 App 上无效就永远点不动）；
 *   - 同一目标的尝试次数有上限（防止对着一个点不动的按钮无限连点）。
 */
class RetryPolicyTest {

    @Test
    fun `gesture goes first on odd attempts and click first on even`() {
        assertTrue(RetryPolicy.gestureFirstFor(1))
        assertFalse(RetryPolicy.gestureFirstFor(2))
        assertTrue(RetryPolicy.gestureFirstFor(3))
        assertFalse(RetryPolicy.gestureFirstFor(4))
    }

    @Test
    fun `retry allowed until the per-target cap`() {
        assertTrue(RetryPolicy.canRetry(0))
        assertTrue(RetryPolicy.canRetry(1))
        assertTrue(RetryPolicy.canRetry(2))
        assertTrue(RetryPolicy.canRetry(3))
        assertFalse(RetryPolicy.canRetry(RetryPolicy.MAX_ATTEMPTS_PER_TARGET))
        assertFalse(RetryPolicy.canRetry(RetryPolicy.MAX_ATTEMPTS_PER_TARGET + 1))
    }

    @Test
    fun `method label matches the chosen strategy`() {
        assertEquals("手势", RetryPolicy.methodLabel(true))
        assertEquals("ACTION_CLICK", RetryPolicy.methodLabel(false))
    }
}
