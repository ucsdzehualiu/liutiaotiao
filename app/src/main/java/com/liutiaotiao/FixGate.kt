package com.liutiaotiao

/**
 * 绑定修复的节奏闸门（**纯逻辑，可 JVM 单测**）。
 *
 * 「先摘除再写回」的修复不能高频重试：
 *  - 摘除到写回之间有 1 秒空窗，此时系统设置显示"未开启"；
 *  - 服务正在绑定途中（bind 已发起、onServiceConnected 还没回调）时如果
 *    又发起一轮摘除，会把一次本来能成功的绑定打断。
 * 所以两次修复之间必须留足间隔。
 */
object FixGate {

    /** 两次修复尝试的最小间隔。 */
    const val MIN_INTERVAL_MS = 10_000L

    /** 现在是否允许发起一次新的修复。 */
    fun canAttempt(now: Long, lastAt: Long, minIntervalMs: Long = MIN_INTERVAL_MS): Boolean =
        lastAt <= 0L || now - lastAt >= minIntervalMs
}
