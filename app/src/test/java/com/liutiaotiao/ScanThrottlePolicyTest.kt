package com.liutiaotiao

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ScanThrottlePolicy] 的回归测试。
 *
 * v1.7 架构原则（李跳跳式「事件即触发」）：**扫描永远不被会话状态门控**，
 * 任何非排除包的窗口/内容事件都要扫一次。会话只影响**节奏**：
 *  - 窗内（开屏时间窗内）：密——80ms 限频，按钮一渲染就打到；
 *  - 窗外（主界面/信息流）：疏——500ms 限频，兜住"没有 state 事件、
 *    只有 content 事件"的广告，同时不至于在信息流刷屏时打满主线程。
 */
class ScanThrottlePolicyTest {

    @Test
    fun `in-window scans are throttled tightly`() {
        assertEquals(ScanThrottlePolicy.IN_WINDOW_MIN_INTERVAL_MS, ScanThrottlePolicy.minInterval(inWindow = true))
    }

    @Test
    fun `out-of-window scans are throttled loosely but still allowed`() {
        // 关键：窗外返回的是"更长的间隔"，而不是"不许扫"。
        // 时间窗只调节奏，永远不能让扫描失明。
        assertEquals(ScanThrottlePolicy.OUT_OF_WINDOW_MIN_INTERVAL_MS, ScanThrottlePolicy.minInterval(inWindow = false))
        assert(ScanThrottlePolicy.OUT_OF_WINDOW_MIN_INTERVAL_MS > ScanThrottlePolicy.IN_WINDOW_MIN_INTERVAL_MS)
    }
}
