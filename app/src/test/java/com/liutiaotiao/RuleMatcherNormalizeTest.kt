package com.liutiaotiao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 针对「带数字/带空格的关键词」的匹配测试。
 *
 * 背景：旧实现用严格子串匹配，且 Prefs 把空格也当分隔符，导致用户填
 * `5 跳过` 这类关键词完全失效。这里锁住修复后的行为。
 */
class RuleMatcherNormalizeTest {

    private fun setOf(vararg keywords: String, weight: Int = 55): RuleSet =
        RuleCatalog(
            defaultKeywords = keywords.toList(),
            weights = keywords.associateWith { weight }
        ).resolve(null)

    // ------------------------------------------------------------------
    // normalize
    // ------------------------------------------------------------------

    @Test
    fun `normalize strips spaces brackets and punctuation`() {
        assertEquals("5跳过", RuleMatcher.normalize("5 跳过"))
        assertEquals("5跳过", RuleMatcher.normalize(" 5　跳过 "))
        assertEquals("跳过5s", RuleMatcher.normalize("跳过 (5s)"))
        assertEquals("跳过5s", RuleMatcher.normalize("跳过【5s】"))
        assertEquals("skipad", RuleMatcher.normalize("Skip Ad"))
        assertEquals("skipad", RuleMatcher.normalize("SKIP-AD"))
    }

    @Test
    fun `normalize keeps close button glyphs`() {
        // `× x X` 本身就是关闭按钮的文字，不能被当标点剔掉。
        assertEquals("×", RuleMatcher.normalize("×"))
        assertEquals("关闭×", RuleMatcher.normalize("关闭 ×"))
    }

    @Test
    fun `keyword made only of punctuation never matches`() {
        val rules = setOf("()", "　")
        assertNull(RuleMatcher.bestMatch("()", rules, true, true))
        assertNull(RuleMatcher.bestMatch("跳过", rules, true, true))
    }

    // ------------------------------------------------------------------
    // 带数字的关键词
    // ------------------------------------------------------------------

    @Test
    fun `keyword with number and space matches button text`() {
        val rules = setOf("5 跳过")
        assertNotNull(RuleMatcher.bestMatch("5跳过", rules, true, true))
        assertNotNull(RuleMatcher.bestMatch("5 跳过", rules, true, true))
        assertNotNull(RuleMatcher.bestMatch("5　跳过", rules, false, true))
        assertNotNull(RuleMatcher.bestMatch("5跳过广告", rules, true, true))
    }

    @Test
    fun `keyword with seconds suffix matches common countdown formats`() {
        val rules = setOf("跳过5s")
        assertNotNull(RuleMatcher.bestMatch("跳过 5s", rules, true, true))
        assertNotNull(RuleMatcher.bestMatch("跳过(5s)", rules, true, true))
        assertNotNull(RuleMatcher.bestMatch("跳过5S", rules, true, true))
    }

    @Test
    fun `number first and number last both match`() {
        // 用户说的「数字放前面也不好使」：顺序颠倒也要能命中。
        val rules = setOf("5跳过")
        assertNotNull(RuleMatcher.bestMatch("5 跳过", rules, true, true))
        assertNotNull(RuleMatcher.bestMatch("跳过5", rules, true, true))
        assertNotNull(RuleMatcher.bestMatch("跳过 5", rules, true, true))
    }

    @Test
    fun `out of order match scores lower than in order match`() {
        val rules = setOf("5跳过")
        val inOrder = RuleMatcher.bestMatch("5跳过", rules, true, true)!!
        val outOfOrder = RuleMatcher.bestMatch("跳过5", rules, true, true)!!
        assertTrue(
            "顺序一致(${inOrder.score}) 应高于顺序颠倒(${outOfOrder.score})",
            inOrder.score > outOfOrder.score
        )
    }

    @Test
    fun `number keyword does not match unrelated text`() {
        val rules = setOf("5跳过")
        assertNull(RuleMatcher.bestMatch("5G网络套餐", rules, true, true))
        assertNull(RuleMatcher.bestMatch("第5集", rules, true, true))
        assertNull(RuleMatcher.bestMatch("设置", rules, true, true))
    }

    @Test
    fun `pure chinese keyword is not matched out of order`() {
        // 纯中文关键词若做乱序匹配，正文「广告已关闭」也会被点，误伤太大。
        val rules = setOf("关闭广告")
        assertNotNull(RuleMatcher.bestMatch("关闭广告", rules, true, true))
        assertNull(RuleMatcher.bestMatch("广告已关闭", rules, true, true))
    }

    @Test
    fun `out of order match is disabled on long text`() {
        val rules = setOf("5跳过")
        // 归一化后超过 12 字，几乎不可能是按钮，乱序兜底不启用。
        assertNull(RuleMatcher.bestMatch("跳过这一段很长的推广文案5", rules, true, true))
    }

    // ------------------------------------------------------------------
    // 不能破坏既有行为
    // ------------------------------------------------------------------

    @Test
    fun `exact contains keeps full weight score`() {
        val rules = setOf("跳过", weight = 55)
        val m = RuleMatcher.bestMatch("跳过", rules, clickable = true, visible = true)!!
        assertEquals(55 + 8 + 4, m.score)
        assertEquals("跳过", m.keyword)
    }

    @Test
    fun `too long text is still ignored`() {
        val rules = setOf("5跳过")
        val long = "5跳过".repeat(12)
        assertNull(RuleMatcher.bestMatch(long, rules, true, true))
    }
}
