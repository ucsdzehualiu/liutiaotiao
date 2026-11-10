package com.liutiaotiao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [SessionGate] 会话切换两步确认的回归测试。
 *
 * 每条测试对应一个真机场景：
 *  - 荣耀桌面 hiboard 迟到事件劫持 QQ音乐 会话（2026-11-07 日志实锤）；
 *  - 正常从桌面打开新 App；
 *  - 窗口先于事件出现（部分 ROM 不发 state 事件）。
 */
class SessionGateTest {

    private val qq = "com.tencent.qqmusic"
    private val tieba = "com.baidu.tieba"
    private val hiboard = "com.hihonor.hiboard"

    @Test
    fun `same package state event does not create pending`() {
        val s = SessionGate.onWindowState(SessionGate.initial(qq), qq, now = 100)
        assertNull(s.pendingPkg)
    }

    @Test
    fun `different package state event becomes pending not confirmed`() {
        // 桌面的迟到事件：只挂起，不切换。
        val s = SessionGate.onWindowState(SessionGate.initial(qq), hiboard, now = 100)
        assertEquals(hiboard, s.pendingPkg)
        assertEquals(qq, s.currentPkg) // 当前会话不受影响
    }

    @Test
    fun `evidence for current package vetoes pending hijack`() {
        // 核心场景：hiboard 事件挂起后，扫描发现真实窗口还是 QQ音乐 → 否决劫持。
        var s = SessionGate.onWindowState(SessionGate.initial(qq), hiboard, now = 100)
        val (s2, confirmed) = SessionGate.onWindowEvidence(s, qq, now = 150)
        assertNull(confirmed)
        assertNull(s2.pendingPkg)
        assertEquals(qq, s2.currentPkg)
        s = s2
    }

    @Test
    fun `evidence matching pending confirms switch`() {
        // 正常切换：打开贴吧 → state 事件挂起贴吧 → 窗口证据也是贴吧 → 确认。
        var s = SessionGate.onWindowState(SessionGate.initial(qq), tieba, now = 100)
        val (s2, confirmed) = SessionGate.onWindowEvidence(s, tieba, now = 150)
        assertEquals(tieba, confirmed)
        assertEquals(tieba, s2.currentPkg)
        assertNull(s2.pendingPkg)
        s = s2
    }

    @Test
    fun `evidence for third package wins immediately`() {
        // 事件说切到桌面、窗口实际是贴吧（桌面图标点开贴吧的瞬间）→ 证据优先。
        var s = SessionGate.onWindowState(SessionGate.initial(qq), hiboard, now = 100)
        val (s2, confirmed) = SessionGate.onWindowEvidence(s, tieba, now = 120)
        assertEquals(tieba, confirmed)
        assertEquals(tieba, s2.currentPkg)
        s = s2
    }

    @Test
    fun `evidence without pending switches directly`() {
        // 部分 ROM 恢复前台不发 state 事件：窗口证据直接切换。
        val (s, confirmed) = SessionGate.onWindowEvidence(SessionGate.initial(qq), tieba, now = 100)
        assertEquals(tieba, confirmed)
        assertEquals(tieba, s.currentPkg)
    }

    @Test
    fun `pending times out and confirms`() {
        // 窗口读不到根节点的 ROM：挂起超时自动确认，会话永远卡不死。
        var s = SessionGate.onWindowState(SessionGate.initial(qq), tieba, now = 100)
        val (s2, confirmed) = SessionGate.onTimeout(s, now = 100 + SessionGate.PENDING_TIMEOUT_MS)
        assertEquals(tieba, confirmed)
        assertEquals(tieba, s2.currentPkg)
        s = s2
    }

    @Test
    fun `pending does not time out early`() {
        var s = SessionGate.onWindowState(SessionGate.initial(qq), tieba, now = 100)
        val (s2, confirmed) = SessionGate.onTimeout(s, now = 100 + SessionGate.PENDING_TIMEOUT_MS - 1)
        assertNull(confirmed)
        assertEquals(qq, s2.currentPkg)
        s = s2
    }

    @Test
    fun `null or empty package is ignored`() {
        val s = SessionGate.initial(qq)
        assertEquals(s, SessionGate.onWindowState(s, null, 100))
        assertEquals(s, SessionGate.onWindowState(s, "", 100))
        val (s2, c) = SessionGate.onWindowEvidence(s, null, 100)
        assertNull(c)
        assertEquals(s, s2)
    }

    @Test
    fun `repeated state events for same pending do not reset timer`() {
        var s = SessionGate.onWindowState(SessionGate.initial(qq), tieba, now = 100)
        s = SessionGate.onWindowState(s, tieba, now = 500)
        // 第二个同包事件不重置计时（pendingSince 仍是 100）。
        assertEquals(100L, s.pendingSince)
        // 从原始时间起算超时。
        val (s2, confirmed) = SessionGate.onTimeout(s, now = 100 + SessionGate.PENDING_TIMEOUT_MS)
        assertEquals(tieba, confirmed)
        s = s2
    }
}
