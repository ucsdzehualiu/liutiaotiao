package com.liutiaotiao

/**
 * 点击重试策略（**纯逻辑，可 JVM 单测**）。
 *
 * 背景：广告 SDK 的跳过按钮有两种"假成功"——
 *   1. `performAction(ACTION_CLICK)` 返回 true，但它自己处理 `onTouchEvent`，
 *      实际什么都没发生；
 *   2. `dispatchGesture()` 只表示"手势已派发"，并不代表"已经点中并生效"。
 *
 * 旧实现把这两种"返回值 true"直接当成点成功了，于是不再重试。表现出来就是
 * 「明明找到了却点不动 / 第一次能点后面点不动」。现在**不再相信返回值**，
 * 而是：点了之后重新扫描，如果同一个目标还在，就换另一种点击手段再试，
 * 直到达到上限为止。本对象负责"第几次该用哪种手段""还能不能试"这部分纯计算。
 */
object RetryPolicy {

    /** 同一个目标最多尝试几次（手势 / ACTION_CLICK 交替，各 2 次）。 */
    const val MAX_ATTEMPTS_PER_TARGET = 4

    /**
     * 第 [attempt] 次（从 1 开始）尝试时，是否**手势优先**。
     *
     * 奇数次手势优先、偶数次 ACTION_CLICK 优先——两种手段轮流试，避免某一种
     * 恰好在这个 App 上无效就永远点不动。
     */
    fun gestureFirstFor(attempt: Int): Boolean = attempt % 2 == 1

    /** 这个目标还能不能再试一次。 */
    fun canRetry(attemptsMade: Int): Boolean = attemptsMade < MAX_ATTEMPTS_PER_TARGET

    /** 给日志/诊断展示用的方法名。 */
    fun methodLabel(gestureFirst: Boolean): String = if (gestureFirst) "手势" else "ACTION_CLICK"
}
