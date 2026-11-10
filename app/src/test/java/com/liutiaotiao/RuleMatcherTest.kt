package com.liutiaotiao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RuleMatcher 的 JVM 单元测试（无需真机）。
 * 运行：./gradlew test  或 Android Studio 右键 Run。
 */
class RuleMatcherTest {

    private val rules = RuleCatalog(
        defaultKeywords = listOf("跳过广告", "跳过", "关闭广告", "Skip Ad"),
        weights = mapOf(
            "跳过广告" to 70,
            "关闭广告" to 70,
            "跳过" to 55,
            "Skip Ad" to 55
        ),
        defaultWeight = 20
    ).resolve(null)

    @Test
    fun `matches exact skip keyword`() {
        val m = RuleMatcher.bestMatch("跳过", rules, clickable = true, visible = true)
        assertNotNull(m)
        assertEquals("跳过", m!!.keyword)
        assertEquals(55 + 8 + 4, m.score)
    }

    @Test
    fun `higher specificity keyword wins`() {
        val m = RuleMatcher.bestMatch("跳过广告", rules, clickable = false, visible = true)
        assertNotNull(m)
        assertEquals("跳过广告", m!!.keyword)
    }

    @Test
    fun `matches keyword embedded in countdown text`() {
        // 典型开屏广告： "跳过 5"
        val m = RuleMatcher.bestMatch("跳过 5", rules, clickable = false, visible = true)
        assertNotNull(m)
        assertEquals("跳过", m!!.keyword)
    }

    @Test
    fun `english keyword is case insensitive`() {
        val m = RuleMatcher.bestMatch("SKIP AD", rules, clickable = false, visible = false)
        assertNotNull(m)
    }

    @Test
    fun `clickable and visible increase score`() {
        val plain = RuleMatcher.bestMatch("跳过", rules, clickable = false, visible = false)!!
        val rich = RuleMatcher.bestMatch("跳过", rules, clickable = true, visible = true)!!
        assertTrue(rich.score > plain.score)
    }

    @Test
    fun `does not match unrelated text`() {
        assertNull(RuleMatcher.bestMatch("设置", rules, clickable = true, visible = true))
        assertNull(RuleMatcher.bestMatch("", rules, clickable = true, visible = true))
    }

    @Test
    fun `too long text is ignored`() {
        val long = "跳过".repeat(20) // 长度超过上限
        assertNull(RuleMatcher.bestMatch(long, rules, clickable = true, visible = true))
    }

    @Test
    fun `package specific keyword only applies to that package`() {
        val catalog = RuleCatalog(
            defaultKeywords = listOf("跳过"),
            packageKeywords = mapOf("com.foo" to listOf("关闭")),
            weights = mapOf("跳过" to 55, "关闭" to 30)
        )
        val forFoo = catalog.resolve("com.foo")
        val forBar = catalog.resolve("com.bar")

        assertNotNull(RuleMatcher.bestMatch("关闭", forFoo, true, true))
        assertNull(RuleMatcher.bestMatch("关闭", forBar, true, true))
    }

    @Test
    fun `user extra keywords extend matching`() {
        val set = rules.let { RuleCatalog(it.keywords, weights = rules.weights).resolve(null, listOf("我知道了")) }
        assertNotNull(RuleMatcher.bestMatch("我知道了", set, true, true))
    }

    @Test
    fun `skip outranks close so ad skip is preferred over generic close`() {
        // 预置规则里内容/电商类同时有“跳过”和“关闭”，必须让“跳过”优先。
        val set = RuleCatalog(
            defaultKeywords = listOf("跳过", "关闭"),
            weights = mapOf("跳过" to 55, "关闭" to 28)
        ).resolve(null)

        val skip = RuleMatcher.bestMatch("跳过", set, clickable = true, visible = true)!!
        val close = RuleMatcher.bestMatch("关闭", set, clickable = true, visible = true)!!
        assertTrue("跳过(${skip.score}) 应高于 关闭(${close.score})", skip.score > close.score)
    }
}
