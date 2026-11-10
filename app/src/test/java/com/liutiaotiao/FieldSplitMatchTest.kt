package com.liutiaotiao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * text 与 contentDescription **分开匹配**的回归测试。
 *
 * 旧实现把两者拼成一个字符串再匹配，导致两类"偶现找不到"：
 *   1. 拼接后长度超过按钮上限（12 字），正常的跳过按钮被长度闸门拒掉；
 *   2. desc 里带"通知/设置"等词，触发负向词一票否决，把 text 里明确的
 *      「跳过」一起否掉。
 *
 * 这两条都必须锁死，谁再改回拼接就会红。
 */
class FieldSplitMatchTest {

    private val rules = RuleCatalog(
        defaultKeywords = listOf("跳过广告", "点击跳过", "跳过", "关闭广告"),
        weights = mapOf(
            "跳过广告" to 70,
            "点击跳过" to 65,
            "跳过" to 55,
            "关闭广告" to 70
        ),
        defaultIds = listOf("splashskip", "skipad")
    ).resolve(null)

    @Test
    fun `long desc does not break a short text match`() {
        // 拼接后归一化长度 17 > 12，旧实现会被长度闸门拒掉；拆开则 text 直接命中。
        val m = RuleMatcher.bestMatchFields(
            text = "跳过广告",
            desc = "跳过广告，进入QQ音乐首页",
            rules = rules,
            clickable = true,
            visible = true
        )
        assertNotNull(m)
        assertEquals("跳过广告", m!!.keyword)
        assertEquals(70 + 8 + 4, m.score)
    }

    @Test
    fun `negative word in desc does not veto a good text match`() {
        // 拼接后含「通知」，旧实现会一票否决整个控件。
        val m = RuleMatcher.bestMatchFields(
            text = "跳过",
            desc = "关闭通知提醒",
            rules = rules,
            clickable = true,
            visible = true
        )
        assertNotNull(m)
        assertEquals("跳过", m!!.keyword)
    }

    @Test
    fun `negative word in text does not leak into desc match`() {
        // 反过来也成立：text 被否决，desc 干净就按 desc 命中。
        val m = RuleMatcher.bestMatchFields(
            text = "自动跳过片头",
            desc = "跳过广告",
            rules = rules,
            clickable = true,
            visible = true
        )
        assertNotNull(m)
        assertEquals("跳过广告", m!!.keyword)
    }

    @Test
    fun `both fields negative means no match`() {
        assertNull(
            RuleMatcher.bestMatchFields(
                text = "自动跳过片头片尾",
                desc = "在设置中关闭",
                rules = rules,
                clickable = true,
                visible = true
            )
        )
    }

    @Test
    fun `image button matched by desc only`() {
        val m = RuleMatcher.bestMatchFields(
            text = "",
            desc = "跳过广告",
            rules = rules,
            clickable = true,
            visible = true
        )
        assertNotNull(m)
        assertEquals(70 + 8 + 4, m!!.score)
    }

    @Test
    fun `id wins over both text fields`() {
        val m = RuleMatcher.bestMatchFields(
            text = "跳过",
            desc = "跳过",
            rules = rules,
            clickable = true,
            visible = true,
            viewId = "com.foo:id/splash_skip_view"
        )
        assertNotNull(m)
        assertEquals(RuleMatcher.ID_MATCH_SCORE + 8 + 4, m!!.score)
    }

    @Test
    fun `single arg bestMatch still behaves as before`() {
        // 兼容旧调用点与既有测试。
        assertEquals(
            RuleMatcher.bestMatchFields("跳过广告", "", rules, true, true),
            RuleMatcher.bestMatch("跳过广告", rules, true, true)
        )
        assertNull(RuleMatcher.bestMatch("自动跳过片头片尾", rules, true, true))
    }
}
