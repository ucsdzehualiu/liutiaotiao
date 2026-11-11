package com.liutiaotiao

/**
 * 开屏「扫描会话」的状态决策（**纯逻辑，可 JVM 单测**，无任何 Android 依赖）。
 *
 * 为什么要把这件事单独拎出来：旧实现里"什么时候开一个新扫描窗、什么时候继续、
 * 什么时候停"是三段散落在事件回调里的 if，彼此对不上号，于是出现典型的
 * 「第一次能跳、后面再进同一个 App 就不跳了」——
 *
 *   - 从桌面回到**同一个** App 时，如果系统只发了 content-changed（没有
 *     state-changed），旧代码判断 `pkg == windowPkg` 就不开新窗，而旧窗早已超时，
 *     结果一次都不扫；
 *   - 离开 App 回桌面时 `windowPkg` 从不清空，"上一次是哪个 App"一直留着，
 *     把上面这条错误判定固化下来。
 *
 * 现在把决策抽成一个明确的状态机：每次事件产出 [Action] 之一，服务照着执行即可。
 * 这样规则可以被单元测试逐条锁死，不会再退化成"时灵时不灵"。
 */
object SplashPolicy {

    /**
     * v1.16：会话开启后 launcher 事件不清会话的宽限期。
     *
     * 从桌面点图标打开 App 的瞬间，桌面的动画/收起事件会陆续到达，
     * 每条都触发 CLEAR 会把刚开的会话反复清空（死循环，见 onWindowState
     * 的注释）。宽限期内视为残留动画忽略。
     */
    const val LAUNCHER_CLEAR_GRACE_MS = 2_500L

    /** 一次事件应引发的动作。 */
    enum class Action {
        /** 开一个新的扫描会话（重置计时与点击预算）。 */
        START,

        /** 当前会话仍然有效，继续扫描（可顺带把时间窗续上）。 */
        CONTINUE,

        /** 与当前会话无关，忽略。 */
        IGNORE,

        /** 已经离开业务界面（回到桌面），清空会话状态。 */
        CLEAR
    }

    /**
     * 收到 `TYPE_WINDOW_STATE_CHANGED` 时的决策。
     *
     * @param pkg 事件包名
     * @param excluded 该包是否属于系统 UI / 输入法 / 设置等要排除的
     * @param isLauncher 该包是否是当前桌面（回到桌面 = 离开 App）
     * @param nonActivityClass `event.className` 是否明显不是业务界面
     * @param currentPkg 当前会话的包名（无会话为 null）
     * @param elapsedMs 距当前会话开始的时间；无会话传 [NO_SESSION] 或任意大值
     * @param splashWindowMs 开屏时间窗
     */
    fun onWindowState(
        pkg: String?,
        excluded: Boolean,
        isLauncher: Boolean,
        nonActivityClass: Boolean,
        currentPkg: String?,
        elapsedMs: Long,
        splashWindowMs: Long
    ): Action {
        if (pkg.isNullOrEmpty()) return Action.IGNORE
        // 回到桌面：明确离开业务界面，清空会话。这样下次再进同一个 App，
        // 哪怕只收到 content-changed 也能被识别成"新开屏"。
        //
        // v1.16 防抖：真机实锤（2026-11-11 持久化日志）——从桌面点图标打开
        // App 的瞬间，桌面自己的**动画/收起事件**会陆续到达（间隔几十毫秒），
        // 每条都触发 CLEAR → clearSession → 会话被反复清空 → App 的 content
        // 事件又把会话挂起 → 扫描时证据确认重建 → 桌面下一条动画事件又 CLEAR
        // ——死循环。每圈 startSession 都重置点击预算/OCR 计数/时间窗，扫描
        // 永远在"刚开会话"状态打转，广告永远点不到。
        // 修复：会话开启后 [LAUNCHER_CLEAR_GRACE_MS] 内的 launcher 事件视为
        // 残留动画，不清会话（真正回桌面时，用户从点开 App 到回桌面远超此窗）。
        if (isLauncher) {
            val inGrace = currentPkg != null && elapsedMs >= 0 &&
                elapsedMs <= LAUNCHER_CLEAR_GRACE_MS
            return if (inGrace) Action.IGNORE else Action.CLEAR
        }
        // 输入法/系统浮层等：可能只是盖在 App 上的一层，不能清会话，
        // 否则一次通知浮层就会打断正在进行中的开屏扫描。
        if (excluded || nonActivityClass) return Action.IGNORE
        // 换了一个 App：全新会话。
        if (currentPkg == null || pkg != currentPkg) return Action.START
        // 同一个 App 切到新 Activity：会话内继续；若已超时（App 内页面跳转），
        // 也按新会话处理——很多二次广告是随 Activity 重新出现的。
        return if (elapsedMs > splashWindowMs) Action.START else Action.CONTINUE
    }

    /**
     * 收到 `TYPE_WINDOW_CONTENT_CHANGED` 时的决策。
     *
     * **v1.7 起此函数不再被服务调用**：content 事件一律走「事件即触发」的
     * 兜底扫描（[ScanThrottlePolicy]），不再由会话状态门控。保留此函数与
     * [onWindowState] 一起构成会话状态机的完整文档，供理解历史设计用；
     * 窗口状态事件仍用它决定会话的开启/清空（那部分与扫描无关，只影响
     * 节奏、预算与诊断分组）。
     */
    @Deprecated(
        "v1.7：content 事件不再由会话门控，扫描一律走事件触发的限频兜底",
        ReplaceWith("ScanThrottlePolicy")
    )
    fun onContentChanged(
        pkg: String?,
        excluded: Boolean,
        currentPkg: String?,
        elapsedMs: Long,
        gapMs: Long,
        splashWindowMs: Long
    ): Action {
        if (pkg.isNullOrEmpty() || excluded) return Action.IGNORE
        if (currentPkg == null || pkg != currentPkg) return Action.START
        if (elapsedMs <= splashWindowMs) return Action.CONTINUE
        return if (gapMs >= RESUME_GAP_MS) Action.START else Action.IGNORE
    }

    /**
     * 「后台回归」判定的最小事件间隔。
     *
     * @deprecated v1.7 起由事件触发的兜底扫描取代，不再依赖事件间隔判定。
     */
    @Deprecated("v1.7：由 ScanThrottlePolicy 的事件触发兜底取代")
    const val RESUME_GAP_MS = 5_000L

    /** 「无会话」时给 elapsed 用的哨兵值。 */
    const val NO_SESSION = Long.MAX_VALUE
}
