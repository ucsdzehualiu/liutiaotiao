package com.liutiaotiao

/**
 * 候选节点的**几何准入策略**（纯逻辑，可 JVM 单测）。
 *
 * 为什么要有这一层：只靠文字匹配会乱点。刷信息流时帖子里出现「跳过」两个字、
 * 逛设置页时看到「自动跳过片头片尾」，都会被当成按钮点下去，甚至因为点击落到
 * 父容器上而直接跳到别的页面。
 *
 * 开屏广告的跳过按钮有非常稳定的几何特征：**在屏幕上方、面积很小、基本完整可见**。
 * 用这几条做硬性准入，能砍掉绝大多数误点，而正常跳过按钮一个都不会漏。
 */
object CandidatePolicy {

    /**
     * 默认只在屏幕上方 40% 的区域内接受候选。
     *
     * 实测绝大多数 App 的跳过按钮位于右上角（少数在左上角），40% 已经留了很大
     * 余量；再往下就是内容区，误点风险急剧上升。
     */
    const val DEFAULT_TOP_RATIO = 0.40f

    /** 候选面积超过屏幕这个比例，说明它是容器而不是按钮，直接排除。 */
    const val MAX_AREA_RATIO = 0.35f

    /**
     * 候选宽度上限（占屏宽比例）。
     *
     * 跳过按钮都是小块头；信息流里的帖子标题、设置页的说明文字往往横跨整屏，
     * 即使文字里带「跳过」也不该被点。这一条能挡掉大部分正文误命中。
     */
    const val MAX_WIDTH_RATIO = 0.60f

    /** 候选高度上限（占屏高比例）。 */
    const val MAX_HEIGHT_RATIO = 0.12f

    /** 允许超出屏幕的比例。displayMetrics 不含系统栏，贴边控件会略微"越界"。 */
    private const val OVERFLOW_TOLERANCE = 0.2f

    /** 拒绝原因，同时用于诊断页展示"为什么没点"。 */
    enum class Reject(val label: String) {
        INVALID("宽高非法"),
        OUT_OF_SCREEN("大部分在屏幕外"),
        TOO_LARGE("面积过大，疑似容器"),
        TOO_WIDE("过宽，疑似正文/标题"),
        TOO_TALL("过高，疑似容器"),
        BELOW_TOP("不在屏幕上方区域")
    }

    /**
     * 检查一个候选矩形是否可以被点击。
     *
     * @param topOnly 为 true 时启用「仅上方区域」限制（默认开启，可在设置里关）。
     * @return null 表示通过，否则返回拒绝原因。
     */
    @JvmOverloads
    fun inspect(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        screenWidth: Int,
        screenHeight: Int,
        topOnly: Boolean,
        topRatio: Float = DEFAULT_TOP_RATIO,
        maxAreaRatio: Float = MAX_AREA_RATIO,
        maxWidthRatio: Float = MAX_WIDTH_RATIO,
        maxHeightRatio: Float = MAX_HEIGHT_RATIO
    ): Reject? {
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return Reject.INVALID

        // 拿不到屏幕尺寸时不做几何限制，退化为纯文字匹配。
        if (screenWidth <= 0 || screenHeight <= 0) return null

        val overflowX = maxOf(0, right - screenWidth) + maxOf(0, -left)
        val overflowY = maxOf(0, bottom - screenHeight) + maxOf(0, -top)
        if (overflowX > w * OVERFLOW_TOLERANCE || overflowY > h * OVERFLOW_TOLERANCE) {
            return Reject.OUT_OF_SCREEN
        }

        val screenArea = screenWidth.toLong() * screenHeight.toLong()
        val areaRatio = (w.toLong() * h.toLong()).toFloat() / screenArea.toFloat()
        if (areaRatio > maxAreaRatio) return Reject.TOO_LARGE

        if (w > screenWidth * maxWidthRatio) return Reject.TOO_WIDE
        if (h > screenHeight * maxHeightRatio) return Reject.TOO_TALL

        if (topOnly && top > screenHeight * topRatio) return Reject.BELOW_TOP

        return null
    }
}
