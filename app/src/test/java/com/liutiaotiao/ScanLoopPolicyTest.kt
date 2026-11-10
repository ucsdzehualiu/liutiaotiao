package com.liutiaotiao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ScanLoopPolicy] 的回归测试。
 *
 * 锁死 v1.5 的核心修复：点掉主广告后扫描**不能提前收工**——
 * 二段式开屏的第二段广告在第一段跳过后才渲染，而它的出现通常只伴随
 * TYPE_WINDOW_CONTENT_CHANGED（窗口列表未变，没有 WINDOWS_CHANGED），
 * 扫描循环必须活到会话窗口结束才有机会兜住它。
 */
class ScanLoopPolicyTest {

    private fun next(active: Boolean, satisfied: Boolean, elapsed: Long) =
        ScanLoopPolicy.nextInterval(
            sessionActive = active,
            satisfied = satisfied,
            elapsedMs = elapsed
        )

    @Test
    fun `loop stops once the session window is over`() {
        assertNull(next(active = false, satisfied = false, elapsed = 0))
        assertNull(next(active = false, satisfied = true, elapsed = 999_999))
    }

    @Test
    fun `early phase scans fast for late-rendering buttons`() {
        assertEquals(ScanLoopPolicy.EARLY_SCAN_INTERVAL_MS, next(true, false, 0))
        assertEquals(ScanLoopPolicy.EARLY_SCAN_INTERVAL_MS, next(true, false, 1499))
    }

    @Test
    fun `late phase slows down but keeps scanning`() {
        assertEquals(ScanLoopPolicy.LATE_SCAN_INTERVAL_MS, next(true, false, 1500))
        assertEquals(ScanLoopPolicy.LATE_SCAN_INTERVAL_MS, next(true, false, 5999))
    }

    @Test
    fun `satisfied session keeps slow-watching instead of stopping`() {
        // v1.4 回归：点掉 2.5s 宽限一到就彻底收工 → 二段广告永远扫不到。
        assertEquals(ScanLoopPolicy.SATISFIED_SCAN_INTERVAL_MS, next(true, true, 2600))
    }

    @Test
    fun `satisfied session keeps watching until the very end of the window`() {
        assertEquals(ScanLoopPolicy.SATISFIED_SCAN_INTERVAL_MS, next(true, true, 5900))
    }

    @Test
    fun `satisfied takes precedence over phase cadence`() {
        // 已点掉时即使仍处前段，也切换到观察节奏。
        assertEquals(ScanLoopPolicy.SATISFIED_SCAN_INTERVAL_MS, next(true, true, 100))
    }
}
