package com.liutiaotiao

/**
 * 扫描限频策略（**纯逻辑，可 JVM 单测**）。
 *
 * v1.7 的架构原则（对齐李跳跳 / GKD 的触发模型）：
 * **扫描永远不被会话状态门控**——任何非排除包的窗口/内容事件都值得扫一次，
 * 因为"哪个事件能开窗"这件事在 ROM 之间差异极大（有的恢复前台只发
 * content-changed、有的连事件都不发、有的乱序），把扫描绑在"会话必须正确
 * 开启"上，任何一条开窗路径失效 = 整个服务失明。v1.4~v1.6 三轮修复都在给
 * 这扇门修门闩，v1.7 直接把门拆了：
 *
 *  - 会话/时间窗只影响**节奏**（限频间隔），不影响"扫不扫"；
 *  - 防误点靠规则匹配 + 负向词 + 几何准入 + 每目标重试上限，这些闸门与
 *    时间无关，事件驱动扫描不会因此乱点；
 *  - 窗内密扫（按钮一渲染就打到），窗外疏扫（兜住"没有 state 事件"的
 *    广告，同时不至于在信息流刷屏时打满主线程）。
 */
object ScanThrottlePolicy {

    /** 会话时间窗内的最小扫描间隔：密，按钮一渲染就打到。 */
    const val IN_WINDOW_MIN_INTERVAL_MS = 80L

    /**
     * 时间窗外的最小扫描间隔：疏，但**不是零**。
     *
     * 信息流 content 事件风暴下每 500ms 最多扫一次；BFS 有节点预算上限，
     * 一次扫描毫秒级，不会卡主线程。这一层是"会话门控"的替代品——
     * 用频率换覆盖，而不是用开关换省电。
     */
    const val OUT_OF_WINDOW_MIN_INTERVAL_MS = 500L

    /** 按是否在会话时间窗内取限频间隔。 */
    fun minInterval(inWindow: Boolean): Long =
        if (inWindow) IN_WINDOW_MIN_INTERVAL_MS else OUT_OF_WINDOW_MIN_INTERVAL_MS
}
