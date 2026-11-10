package com.liutiaotiao

/**
 * 会话切换的**两步确认**闸门（**纯逻辑，可 JVM 单测**）。
 *
 * ## 为什么需要它（v1.11，对照 GKD `ForegroundTracker` 后引入）
 *
 * v1.10 之前，收到一个非排除包的 `TYPE_WINDOW_STATE_CHANGED` 就立刻切换扫描
 * 会话。但 state 事件只是"线索"，不是"事实"：
 *
 *  - 桌面/系统浮层的**迟到残留事件**会在你点开 App 的瞬间到达（真机实锤：
 *    荣耀桌面 hiboard 的事件在点击 QQ音乐跳过按钮 3ms 后到达，把正在验证
 *    点击效果的会话劫持成"桌面"，复扫发现按钮还在却因包名不符被丢弃，
 *    换招重试链路被废——偶发不跳的主因）；
 *  - v1.10 用硬编码 `com.hihonor.hiboard` 修了这一台荣耀，但换个 ROM/换台
 *    手机（或下一代 MagicOS 改个包名）同样的事故还会发生。
 *
 * GKD 的通用解法（`ForegroundTracker`）：**新窗口只挂起为 pending，等
 * 真实窗口内容（节点证据）确认后才切换 confirmed**。本类把同样的思想
 * 落到刘跳跳的会话模型上：
 *
 *  - `onWindowState(pkg)`：非排除包的 state 事件 → 挂起为候选会话，
 *    **不立即切换**；与当前会话同包则直接续窗（同包没有劫持问题）；
 *  - `onWindowEvidence(pkg)`：扫描时真实窗口根节点的包名 → 证据。
 *    证据与挂起候选同包 → **确认切换**；证据与当前会话同包 → **否决挂起**
 *    （劫持事件被自然否决，正在进行的验证重试链路不受影响）；
 *  - `onTimeout(now)`：挂起超过 [PENDING_TIMEOUT_MS] 仍无证据 → 自动确认。
 *    兜住"state 事件来了但窗口读不到根节点"的 ROM（否则会话永远切不过去）。
 *
 * 这样任何 ROM、任何桌面包名、任何乱序事件都走同一条通用路径，
 * 不再需要枚举每一种"会捣乱的系统包"。
 */
object SessionGate {

    /** 挂起候选等待证据的超时：超时自动确认（窗口读不到根节点的 ROM 兜底）。 */
    const val PENDING_TIMEOUT_MS = 1_200L

    /** 挂起的候选会话包名；null 表示没有待确认的切换。 */
    data class State(
        val currentPkg: String?,
        val pendingPkg: String?,
        val pendingSince: Long
    )

    fun initial(currentPkg: String? = null): State = State(currentPkg, null, 0L)

    /**
     * 收到非排除包的 state 事件。
     *
     * @return 更新后的状态（可能挂起新候选；同包则原样——续窗由调用方处理）
     */
    fun onWindowState(state: State, pkg: String?, now: Long): State {
        if (pkg.isNullOrEmpty()) return state
        if (pkg == state.currentPkg) return state // 同包：无切换可言
        if (pkg == state.pendingPkg) return state // 已在等这个包的证据
        return state.copy(pendingPkg = pkg, pendingSince = now)
    }

    /**
     * 收到真实窗口根节点包名（证据）。
     *
     * @return Pair(更新后的状态, 确认切换到的包名或 null)
     *   - 证据 == 挂起候选 → 确认切换（清挂起）；
     *   - 证据 == 当前会话包 → 否决挂起（迟到的劫持事件被丢弃）；
     *   - 证据是第三个包 → 证据优先（真实窗口永远比事件可信），直接切换；
     *   - 无挂起且证据 != 当前包 → 也切换（窗口先于事件出现的场景）。
     */
    fun onWindowEvidence(state: State, evidencePkg: String?, now: Long): Pair<State, String?> {
        if (evidencePkg.isNullOrEmpty()) return state to null
        if (evidencePkg == state.currentPkg) {
            // 证据支持当前会话：否决挂起（如果有）。
            return if (state.pendingPkg != null) {
                state.copy(pendingPkg = null, pendingSince = 0L) to null
            } else {
                state to null
            }
        }
        // 证据指向别的包：无论有没有挂起，真实窗口说了算。
        return state.copy(currentPkg = evidencePkg, pendingPkg = null, pendingSince = 0L) to evidencePkg
    }

    /**
     * 挂起超时检查（每次扫描时顺带调用）。
     *
     * @return Pair(更新后的状态, 超时确认的包名或 null)
     */
    fun onTimeout(state: State, now: Long): Pair<State, String?> {
        val pending = state.pendingPkg ?: return state to null
        if (now - state.pendingSince < PENDING_TIMEOUT_MS) return state to null
        return state.copy(currentPkg = pending, pendingPkg = null, pendingSince = 0L) to pending
    }
}
