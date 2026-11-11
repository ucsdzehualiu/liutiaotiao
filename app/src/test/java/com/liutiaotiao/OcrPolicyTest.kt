package com.liutiaotiao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OcrPolicy] OCR 兜底决策的回归测试。
 */
class OcrPolicyTest {

    private val kws = listOf("跳过", "跳过广告", "Skip")

    // ---------------- shouldOcr ----------------

    @Test
    fun `ocr only after minimum scans with no candidates`() {
        assertFalse(OcrPolicy.shouldOcr(scanCount = 2, foundAnyCandidate = false, elapsedMs = 1000, clickDispatched = false))
        assertTrue(OcrPolicy.shouldOcr(scanCount = 3, foundAnyCandidate = false, elapsedMs = 1000, clickDispatched = false))
    }

    @Test
    fun `ocr skipped when candidates were found`() {
        // 控件树能解决的绝不动用截图。
        assertFalse(OcrPolicy.shouldOcr(scanCount = 10, foundAnyCandidate = true, elapsedMs = 1000, clickDispatched = false))
    }

    @Test
    fun `ocr skipped after click dispatched`() {
        assertFalse(OcrPolicy.shouldOcr(scanCount = 10, foundAnyCandidate = false, elapsedMs = 1000, clickDispatched = true))
    }

    @Test
    fun `ocr only within splash window`() {
        // v1.16 收紧到 4s：切回后台已久的视频播放器等场景不截屏。
        assertFalse(OcrPolicy.shouldOcr(scanCount = 10, foundAnyCandidate = false, elapsedMs = 4_001, clickDispatched = false))
        assertTrue(OcrPolicy.shouldOcr(scanCount = 10, foundAnyCandidate = false, elapsedMs = 4_000, clickDispatched = false))
    }

    // ---------------- isSkipText ----------------

    @Test
    fun `short skip text in corner is accepted`() {
        // 真机场景：ivy 右下角「跳过」小块。
        assertTrue(
            OcrPolicy.isSkipText(
                normalizedText = "跳过",
                left = 850f, top = 2150f, right = 1000f, bottom = 2230f,
                screenWidth = 1060, screenHeight = 2376,
                keywords = kws
            )
        )
    }

    @Test
    fun `skip with countdown number is accepted`() {
        assertTrue(
            OcrPolicy.isSkipText(
                normalizedText = "跳过3",
                left = 850f, top = 2150f, right = 1010f, bottom = 2230f,
                screenWidth = 1060, screenHeight = 2376,
                keywords = kws
            )
        )
    }

    @Test
    fun `long body text containing skip is rejected`() {
        // 广告正文说明里的长文字不点。
        assertFalse(
            OcrPolicy.isSkipText(
                normalizedText = "会员可跳过广告详情咨询",
                left = 100f, top = 2150f, right = 960f, bottom = 2230f,
                screenWidth = 1060, screenHeight = 2376,
                keywords = kws
            )
        )
    }

    @Test
    fun `negative token is rejected`() {
        assertFalse(
            OcrPolicy.isSkipText(
                normalizedText = "自动跳过",
                left = 850f, top = 2150f, right = 1000f, bottom = 2230f,
                screenWidth = 1060, screenHeight = 2376,
                keywords = kws
            )
        )
    }

    @Test
    fun `center block is rejected`() {
        // 广告主体（品牌名/商品名）常在中央，即使含"跳过"也不点。
        assertFalse(
            OcrPolicy.isSkipText(
                normalizedText = "跳过",
                left = 480f, top = 1100f, right = 580f, bottom = 1180f,
                screenWidth = 1060, screenHeight = 2376,
                keywords = kws
            )
        )
    }

    @Test
    fun `oversized block is rejected`() {
        // 整行大字（横幅标题）不点。
        assertFalse(
            OcrPolicy.isSkipText(
                normalizedText = "跳过",
                left = 100f, top = 2150f, right = 960f, bottom = 2350f,
                screenWidth = 1060, screenHeight = 2376,
                keywords = kws
            )
        )
    }

    @Test
    fun `english skip is accepted`() {
        assertTrue(
            OcrPolicy.isSkipText(
                normalizedText = "skipad",
                left = 850f, top = 150f, right = 1000f, bottom = 230f,
                screenWidth = 1060, screenHeight = 2376,
                keywords = kws
            )
        )
    }

    @Test
    fun `tap point is block center`() {
        val (x, y) = OcrPolicy.tapPoint(100f, 200f, 300f, 400f)
        assertEquals(200f, x, 0.01f)
        assertEquals(300f, y, 0.01f)
    }
}
