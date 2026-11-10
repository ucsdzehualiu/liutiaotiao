package com.liutiaotiao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 防误点相关的测试：几何准入策略 + 负向词黑名单 + 控件 ID 匹配。
 *
 * 这一组是「乱点乱跳」的回归防线，改动匹配或准入逻辑时必须全绿。
 */
class AntiMisclickTest {

    /** 与 RuleRepository.FALLBACK.defaultIds 保持一致（这里不引用它，避免拉进 Android 依赖）。 */
    private val fallbackIds = listOf(
        "splashskip", "skipad", "adskip", "skipbtn", "btnskip",
        "skipview", "skipbutton", "buttonskip", "splashjump",
        "splashclose", "jumpbtn", "mjumpbtn", "closead", "adclose"
    )

    private fun rules(
        keywords: List<String> = listOf("跳过广告", "跳过", "关闭广告", "Skip Ad"),
        ids: List<String> = emptyList()
    ): RuleSet = RuleCatalog(
        defaultKeywords = keywords,
        weights = mapOf("跳过广告" to 70, "关闭广告" to 70, "跳过" to 55, "Skip Ad" to 55),
        defaultIds = ids
    ).resolve(null)

    // ------------------------------------------------------------------
    // 负向词黑名单
    // ------------------------------------------------------------------

    @Test
    fun `settings toggle text is never clicked`() {
        val r = rules()
        // 视频/音乐 App 设置页的典型开关，含「跳过」但绝不能点。
        assertNull(RuleMatcher.bestMatch("自动跳过片头片尾", r, true, true))
        assertNull(RuleMatcher.bestMatch("跳过片头", r, true, true))
        assertNull(RuleMatcher.bestMatch("默认跳过片尾", r, true, true))
    }

    @Test
    fun `permission dialog buttons are never clicked`() {
        val r = rules()
        assertNull(RuleMatcher.bestMatch("允许发送通知", r, true, true))
        assertNull(RuleMatcher.bestMatch("同意并继续", r, true, true))
    }

    @Test
    fun `ad landing page CTA is never clicked`() {
        val r = rules()
        assertNull(RuleMatcher.bestMatch("立即下载", r, true, true))
        assertNull(RuleMatcher.bestMatch("立即体验", r, true, true))
    }

    @Test
    fun `explanatory sentence containing keyword is rejected`() {
        val r = rules()
        assertNull(RuleMatcher.bestMatch("跳过广告可在设置中关闭", r, true, true))
    }

    @Test
    fun `real skip button is still matched after blacklist`() {
        val r = rules()
        assertNotNull(RuleMatcher.bestMatch("跳过广告", r, true, true))
        assertNotNull(RuleMatcher.bestMatch("跳过 5", r, true, true))
        assertNotNull(RuleMatcher.bestMatch("关闭广告", r, true, true))
    }

    // ------------------------------------------------------------------
    // 长度与单字符关键词
    // ------------------------------------------------------------------

    @Test
    fun `long body text is rejected by length cap`() {
        val r = rules()
        // 归一化后 15 字，超过按钮文字上限 12。
        assertNull(RuleMatcher.bestMatch("本视频将在广告结束后自动跳过", r, true, true))
    }

    @Test
    fun `single char keyword requires exact text`() {
        val r = rules(keywords = listOf("×"))
        assertNotNull(RuleMatcher.bestMatch("×", r, true, true))
        // 含 × 的更长文字不该命中，否则搜索框的清除按钮会被点。
        assertNull(RuleMatcher.bestMatch("×广告", r, true, true))
    }

    @Test
    fun `single latin char keyword does not match words containing it`() {
        val r = rules(keywords = listOf("x"))
        assertNull(RuleMatcher.bestMatch("Xbox", r, true, true))
        assertNull(RuleMatcher.bestMatch("MAX", r, true, true))
        assertNotNull(RuleMatcher.bestMatch("x", r, true, true))
    }

    // ------------------------------------------------------------------
    // 控件 ID 匹配
    // ------------------------------------------------------------------

    @Test
    fun `pangle splash skip button is matched by id fragment`() {
        val r = rules(ids = fallbackIds)
        val m = RuleMatcher.bestMatch(
            text = "",
            rules = r,
            clickable = true,
            visible = true,
            viewId = "com.byted.pangle:id/tt_splash_skip_btn"
        )
        assertNotNull(m)
        assertEquals(RuleMatcher.ID_MATCH_SCORE + 8 + 4, m!!.score)
    }

    @Test
    fun `image only skip button with no text is matched by id`() {
        val r = rules(ids = fallbackIds)
        assertNotNull(
            RuleMatcher.bestMatch("", r, true, true, "com.foo.bar:id/splash_ad_skip")
        )
        assertNotNull(
            RuleMatcher.bestMatch("", r, true, true, "com.foo.bar:id/btn_skip")
        )
        assertNotNull(
            RuleMatcher.bestMatch("", r, true, true, "com.jd.app.reader:id/mJumpBtn")
        )
    }

    @Test
    fun `media player next button is not matched by id`() {
        // 关键回归：QQ音乐播放器的「下一首」常叫 skip_next，不能被裸 skip 命中。
        val r = rules(ids = fallbackIds)
        assertNull(RuleMatcher.bestMatch("", r, true, true, "com.tencent.qqmusic:id/skip_next"))
        assertNull(RuleMatcher.bestMatch("", r, true, true, "com.tencent.qqmusic:id/skip_prev"))
    }

    @Test
    fun `id match loses to nothing but outranks text keywords`() {
        val r = rules(ids = fallbackIds)
        // 同一个控件既有文字又有 ID 时，取分数更高的一方。
        val m = RuleMatcher.bestMatch("跳过", r, true, true, "com.foo:id/skip_ad_btn")
        assertNotNull(m)
        assertTrue(m!!.score >= RuleMatcher.ID_MATCH_SCORE)
    }

    @Test
    fun `no id rules means no id matching`() {
        val r = rules(ids = emptyList())
        assertNull(RuleMatcher.bestMatch("", r, true, true, "com.foo:id/skip_ad_btn"))
    }

    // ------------------------------------------------------------------
    // 几何准入策略
    // ------------------------------------------------------------------

    private val W = 1080
    private val H = 2400

    @Test
    fun `top right small button is accepted`() {
        assertNull(
            CandidatePolicy.inspect(900, 150, 1050, 250, W, H, topOnly = true)
        )
    }

    @Test
    fun `button below top region is rejected when topOnly`() {
        assertEquals(
            CandidatePolicy.Reject.BELOW_TOP,
            CandidatePolicy.inspect(900, 1800, 1050, 1900, W, H, topOnly = true)
        )
        // 关掉限制后就放行。
        assertNull(
            CandidatePolicy.inspect(900, 1800, 1050, 1900, W, H, topOnly = false)
        )
    }

    @Test
    fun `full screen container is rejected`() {
        assertEquals(
            CandidatePolicy.Reject.TOO_LARGE,
            CandidatePolicy.inspect(0, 0, W, H, W, H, topOnly = true)
        )
    }

    @Test
    fun `wide feed title is rejected even inside top region`() {
        // 信息流标题横跨整屏，即使文字里有「跳过」也不该点。
        assertEquals(
            CandidatePolicy.Reject.TOO_WIDE,
            CandidatePolicy.inspect(40, 200, 1040, 280, W, H, topOnly = true)
        )
    }

    @Test
    fun `mostly off screen node is rejected`() {
        assertEquals(
            CandidatePolicy.Reject.OUT_OF_SCREEN,
            CandidatePolicy.inspect(-500, 100, -10, 200, W, H, topOnly = true)
        )
    }

    @Test
    fun `slight edge overflow is tolerated`() {
        // displayMetrics 不含系统栏，贴边控件略微越界是正常的。
        assertNull(
            CandidatePolicy.inspect(980, 150, 1100, 250, W, H, topOnly = true)
        )
    }

    @Test
    fun `zero size node is rejected`() {
        assertEquals(
            CandidatePolicy.Reject.INVALID,
            CandidatePolicy.inspect(100, 100, 100, 200, W, H, topOnly = true)
        )
    }

    @Test
    fun `unknown screen size degrades to text only matching`() {
        assertNull(
            CandidatePolicy.inspect(900, 1800, 1050, 1900, 0, 0, topOnly = true)
        )
    }
}
