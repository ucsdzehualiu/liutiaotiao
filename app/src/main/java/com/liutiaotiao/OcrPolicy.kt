package com.liutiaotiao

import android.graphics.RectF

/**
 * OCR 兜底的**纯决策逻辑**（**可 JVM 单测**，无 Android/ML Kit 依赖）。
 *
 * ## 为什么需要 OCR 兜底（v1.14）
 *
 * 真机实锤（2026-11-11，ivy 冷启动实测）：SurfaceView/自绘开屏广告整屏
 * 不在控件树里——遍历 611 个节点全是主界面控件，广告画面连同"跳过"按钮
 * 无障碍物理读不到。这类广告只能靠**截图 + 本地 OCR** 找到按钮文字，再按
 * 坐标手势点击。
 *
 * ## 决策规则（全部纯逻辑，可逐条锁死）
 *
 *  1. **触发条件**：本会话已扫描 ≥ [MIN_SCANS] 轮且**从未找到过任何候选**
 *     （`foundAnyCandidate == false`）——控件树能解决的绝不动用截图；
 *  2. **会话时间窗**：只在开屏会话窗内（elapsed ≤ [MAX_ELAPSED_MS]），
 *     主界面/信息流不截图，控制耗电；
 *  3. **文字准入**：OCR 文本块归一化后命中跳过关键词（复用 [RuleMatcher]
 *     的关键词与负向词），且文本块长度 ≤ [MAX_TEXT_LEN]（正文说明里出现
 *     "跳过"字样的整段文字不点）；
 *  4. **几何准入**：文本块必须是小块（面积 ≤ 屏幕的 [MAX_AREA_RATIO]），
 *     且**宽高都小于屏幕一半**——开屏广告的"跳过"永远是个小角标；
 *     复用"仅上方区域"约束太苛刻（B站按钮在底部），改为**排除正中央**：
 *     广告主体（品牌 logo/商品图）常在中央，按钮不会在正中央。
 */
object OcrPolicy {

    /** 至少扫过这么多轮都没找到候选，才值得动用截图 OCR。 */
    const val MIN_SCANS = 3

    /**
     * 会话开始多久内允许 OCR。
     *
     * v1.16 收紧：原 6s 对齐整个开屏会话窗，但真机实锤用户从别的 App 切回
     * 视频播放器（如腾讯会议投屏）时同样会建立会话——播放界面绝不该截屏
     * （隐私+无意义）。开屏广告只出现在**冷启动后的前几秒**，收紧到 4s：
     * 既覆盖广告渲染期（实测冷启动 3s 内事件就到），又把"切回后台已久的
     * 播放器"这类假开屏排除在外。
     */
    const val MAX_ELAPSED_MS = 4_000L

    /** OCR 文本块的最大长度（归一化后）。正文里出现"跳过"的长文字不点。 */
    const val MAX_TEXT_LEN = 12

    /** 文本块面积占屏幕比例上限。 */
    const val MAX_AREA_RATIO = 0.02f

    /** 文本块宽/高占屏幕比例上限。 */
    const val MAX_SIDE_RATIO = 0.35f

    /** 是否应该做一次 OCR。 */
    fun shouldOcr(
        scanCount: Int,
        foundAnyCandidate: Boolean,
        elapsedMs: Long,
        clickDispatched: Boolean,
        minScans: Int = MIN_SCANS,
        maxElapsedMs: Long = MAX_ELAPSED_MS
    ): Boolean {
        // 已经点过（含 OCR 点过）就不再截：复扫确认交给控件树链路。
        if (clickDispatched) return false
        if (foundAnyCandidate) return false
        if (scanCount < minScans) return false
        if (elapsedMs > maxElapsedMs) return false
        return true
    }

    /**
     * 判定一个 OCR 文本块是否是"跳过按钮"。
     *
     * @param normalizedText 归一化后的文本块内容（调用方用 [RuleMatcher.normalize]）
     * @param bounds 文本块在屏幕上的包围盒
     * @param screenWidth/screenHeight 屏幕尺寸
     * @param keywords 生效的跳过关键词（归一化前原样传入，内部归一化）
     * @return true = 该文本块应该被点击
     */
    @JvmStatic
    fun isSkipText(
        normalizedText: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        screenWidth: Int,
        screenHeight: Int,
        keywords: List<String>
    ): Boolean {
        if (normalizedText.isEmpty() || normalizedText.length > MAX_TEXT_LEN) return false
        // 负向词一票否决（复用控件树链路的黑名单）。
        if (RuleMatcher.isRejectedText(normalizedText)) return false

        // 关键词命中：归一化后包含（与控件树链路同一套规则）。
        val hit = keywords.any { kw ->
            val kwNorm = RuleMatcher.normalize(kw)
            kwNorm.isNotEmpty() && normalizedText.contains(kwNorm)
        }
        if (!hit) return false

        // 几何准入：小块、不超宽高超、不在正中央。
        if (screenWidth <= 0 || screenHeight <= 0) return true // 拿不到尺寸时只信文字
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return false

        val screenArea = screenWidth.toFloat() * screenHeight.toFloat()
        if (w * h > screenArea * MAX_AREA_RATIO) return false
        if (w > screenWidth * MAX_SIDE_RATIO) return false
        if (h > screenHeight * MAX_SIDE_RATIO) return false

        // 排除正中央（广告主体区域）：文本块中心落在屏幕中央 40% 区域内则不点。
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val centerLeft = screenWidth * 0.30f
        val centerRight = screenWidth * 0.70f
        val centerTop = screenHeight * 0.30f
        val centerBottom = screenHeight * 0.70f
        if (cx in centerLeft..centerRight && cy in centerTop..centerBottom) return false

        return true
    }

    /** 从文本块包围盒取点击坐标（块中心）。 */
    fun tapPoint(left: Float, top: Float, right: Float, bottom: Float): Pair<Float, Float> =
        ((left + right) / 2f) to ((top + bottom) / 2f)
}
