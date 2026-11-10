package com.liutiaotiao

/**
 * 会话内扫描循环的节奏决策（**纯逻辑，可 JVM 单测**）。
 *
 * v1.4 的循环里有一条「点掉后 2.5s 宽限一到就收工」的退出分支，和它自己
 * 「放慢但不停，兜住两段式广告的第二段」的设计目标直接矛盾——第二段广告
 * 恰恰在第一段跳过后才渲染，收工即失明。现在节奏决策抽到这里锁死：
 *
 *  - 会话窗口结束 → 停（null）；
 *  - 已点掉（satisfied）→ 慢速观察，**直到窗口结束都不提前收工**；
 *  - 未点掉 → 前段密集（按钮常延迟渲染）、后段稀疏。
 */
object ScanLoopPolicy {

    /** 会话前段的扫描间隔：按钮常在此阶段延迟渲染，扫密一点。 */
    const val EARLY_SCAN_INTERVAL_MS = 130L

    /** 会话后段的扫描间隔：降低频率省电，同时仍能兜住晚到的广告。 */
    const val LATE_SCAN_INTERVAL_MS = 380L

    /** 已点掉主广告后的观察节奏：慢，但绝不停。 */
    const val SATISFIED_SCAN_INTERVAL_MS = 700L

    /** 前段/后段的分界时间。 */
    const val EARLY_PHASE_MS = 1500L

    /**
     * 计算下一轮扫描的间隔。
     *
     * @param sessionActive 会话是否仍在时间窗内
     * @param satisfied 主广告是否已点掉（点过且最近一轮复扫未再发现候选）
     * @param elapsedMs 距会话开始的毫秒数
     * @return 下一次扫描的间隔；null 表示应停止循环
     */
    fun nextInterval(sessionActive: Boolean, satisfied: Boolean, elapsedMs: Long): Long? {
        if (!sessionActive) return null
        return when {
            satisfied -> SATISFIED_SCAN_INTERVAL_MS
            elapsedMs < EARLY_PHASE_MS -> EARLY_SCAN_INTERVAL_MS
            else -> LATE_SCAN_INTERVAL_MS
        }
    }
}
