package com.liutiaotiao

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager

/**
 * 核心无障碍服务：监听当前界面，找到"跳过 / 关闭广告"按钮并点击。
 *
 * 分层：
 *   - [RuleRepository] / [RuleMatcher]：规则加载与纯匹配逻辑（可单元测试）；
 *   - [CandidatePolicy]：几何准入策略（纯逻辑，可单元测试）；
 *   - [SplashPolicy]：**扫描会话**状态机（何时开窗 / 继续 / 清空，纯逻辑，可单测）；
 *   - [ScanLoopPolicy]：会话内**轮询节奏**（前段密集 / 后段稀疏 / 点掉后慢速观察，
 *     纯逻辑，可单测）；
 *   - [RetryPolicy]：点击重试策略（用哪种手段、还能不能试，纯逻辑，可单测）；
 *   - 本类：无障碍事件 → 多窗口 BFS 遍历 → 匹配 → 准入 → 执行点击 → 复扫验证；
 *   - [Diagnostics]：识别记录，用于排查"为什么没点"；
 *   - [Prefs]：本地设置。
 *
 * ## 本版重点根治「时灵时不灵 / 第一次能跳、后面就不跳」
 *
 * 旧实现把"能不能扫"绑在一个时间窗和 `windowPkg` 上，但这两者的生命周期没对齐：
 *   - 离开 App 回桌面时 `windowPkg` 不清空，再次进入**同一个 App**若只收到
 *     content-changed，会被判定为"还是同一个窗"，而旧窗早已超时 → 一次都不扫；
 *   - 扫描只靠开窗后固定几个时间点（60/180/…/5200ms），按钮稍晚渲染或第一下
 *     点不中，就没有下文了；
 *   - 点击把 `performAction`/`dispatchGesture` 的"已派发"当成"已成功"，不再验证、
 *     不再换招，广告没消失也认为完事。
 *
 * 现在改为「**会话 + 轮询 + 验证重试**」模型（与李跳跳 / GKD 一类实现的做法一致）：
 *  1. **明确的状态机**（[SplashPolicy]）：到桌面就清会话；换 App 就开会话；同一 App
 *     新 Activity 在窗内继续、窗外重开。回到同一 App 的每条路径都能重新开窗。
 *  2. **会话内轮询扫描**：开窗后按 130ms（前 1.5s）→ 380ms 的节奏反复扫，直到
 *     会话窗口结束。跳过按钮无论早渲染晚渲染都会被打到，不再依赖某几个固定时刻。
 *     点掉主广告后**只放慢到 700ms、不收工**——二段式开屏的第二段广告在第一段
 *     跳过后才渲染，且往往只伴随 content-changed（没有 WINDOWS_CHANGED），提前
 *     收工就等于对它失明（v1.4「第一条跳得掉、后面的就不跳」的根因）。
 *  3. **点击后复扫验证**：点了不当作成功；下一轮如果同一个目标还在，就**换另一种
 *     点击手段**再试（手势 ⇄ ACTION_CLICK 交替，最多 4 次）。复扫确认目标消失后
 *     归还该目标的尝试预算——第二段广告若再弹出同一个按钮，仍有点击额度可用。
 *  4. **所有定时任务都可取消**：开新会话前先清掉旧会话遗留的扫描，避免上个 App 的
 *     任务在下一个界面上乱扫（这也是"偶发乱跳/偶发失效"的来源之一）。
 *
 * ## 治「乱点乱跳」的几道闸门（保留并加强）
 *  1. 会话时间窗：只在 [SPLASH_WINDOW_MS] 内扫描；
 *  2. 文字约束：按钮文字归一化后 ≤ 12 字，命中负向词一票否决；
 *  3. 几何准入：候选须在屏幕上方、面积小、不过宽过高、基本完整可见；
 *  4. 点击约束：祖先最多上溯 [MAX_ANCESTOR_STEPS] 层且面积不能暴涨，否则改为
 *     手势点击目标自身中心，绝不把点击落到大容器上。
 *
 * ## 健壮性
 *   - 全链路异常兜底：无障碍服务抛未捕获异常会被系统直接停用（表现为"无障碍
 *     偶尔自动关"），所以事件回调、窗口枚举、节点访问、手势全部包 try/catch；
 *   - 事件去抖 + 扫描频率上限 + 节点数上限 + 每目标尝试上限，避免耗电、卡顿与 ANR；
 *   - 全程本地运行，无网络、无热更新、无外部代码加载。
 */
class AdSkipService : AccessibilityService() {

    companion object {
        private const val TAG = "LiuTiaoTiao"

        /** 同一界面的最小扫描间隔已移至 [ScanThrottlePolicy]（窗内 80ms / 窗外 500ms）。 */

        /** 点击刚派发后的静默期：先让页面响应，别在同一帧连点。 */
        private const val POST_CLICK_QUIET_MS = 220L

        /**
         * 同一个目标两次尝试之间的最小间隔。
         *
         * 点了之后要留一点时间给广告响应；过了这个间隔如果目标还在，就换招再试。
         */
        private const val RETRY_SAME_TARGET_MS = 650L

        /**
         * 过期会话重开前要求的"点击静默期"。
         *
         * 同包会话过期后，只有"距上次点击超过这个时间"才允许重开新会话（拿回
         * 全新的点击预算）。信息流里持续误命中时点击会不断刷新 lastClickAt，
         * 永远凑不满静默期 → 不会反复重开预算乱点；而"放一会儿再打开"距上次
         * 点击必然隔了很久 → 顺利重开。
         */
        private const val SESSION_RESET_QUIET_MS = 3_000L

        /** BFS 深度上限：跳过按钮都在浅层，30 层足够。 */
        private const val MAX_DEPTH = 30

        /** 单个窗口的节点预算。BFS 顺序下浅层优先，够用且不至于卡主线程。 */
        private const val MAX_NODES_PER_WINDOW = 3000

        /** 最多检查几个窗口（下拉栏、输入法、浮层都会算进来）。 */
        private const val MAX_WINDOWS = 4

        /**
         * 高分候选提前收工线。命中达到这个分数基本可以确定就是它，
         * 不必再遍历剩下的节点和窗口。
         *
         * v1.13：80 → 70。旧值 80 只有「跳过广告」(70+12=82) 这类长关键词能
         * 达标；最常见的纯「跳过」(58+12=70) 恰好差 1 分收不了工，BFS 要
         * 遍历完整棵控件树（贴吧 359 节点 ≈ 300ms）才回去点击——真机实测
         * 命中「跳过 03」后拖到倒计时走完才点，用户看到的就是"等 01 才点"。
         * 70 让「跳过」类按钮命中即收工；防误点不受影响（负向词/几何准入/
         * 重试上限全在，提前收工只是不再看后面的节点）。
         */
        private const val FAST_ACCEPT_SCORE = 70

        /**
         * 开屏时间窗。开屏广告一般 3~5 秒，留 6 秒余量。
         * 会话内切到新 Activity（仍在窗内）时会把时间窗续上，见 [SplashPolicy]。
         */
        private const val SPLASH_WINDOW_MS = 6000L

        /** 单次会话内所有目标加起来最多尝试几次，硬性防连点/防乱跳。 */
        private const val MAX_TOTAL_ATTEMPTS = 6

        /** 找可点击祖先时最多往上几层。层数越多越容易点到整行/整页容器。 */
        private const val MAX_ANCESTOR_STEPS = 2

        /** 祖先面积相对目标的最大放大倍数，超过就认为爬到了容器上。 */
        private const val MAX_ANCESTOR_AREA_GROWTH = 4.0f

        @Volatile
        var instance: AdSkipService? = null
            private set

        /** 已知国产桌面包名（动态解析失败时的兜底清单，与 [ScanFilter.SYSTEM_PACKAGE_PREFIXES] 保持一致）。 */
        private val KNOWN_LAUNCHER_PACKAGES = listOf(
            "com.hihonor.android.launcher", // 荣耀桌面真身（真机 resolve 验证）
            "com.huawei.android.launcher",
            "com.miui.home",
            "com.coloros.launcher",
            "com.vivo.launcher",
            "com.sec.android.app.launcher",
            "com.android.launcher3"
        )

        /**
         * v1.12：跳过按钮固定在屏幕**底部**的 App 白名单。
         *
         * "仅上方区域"准入（默认开）是为防误点设计的，但少数 App 的开屏跳过
         * 按钮天生在右下角（真机实测：B站 id/count_down，top=89% 屏高），
         * 一刀切拒绝导致广告永远跳不掉。这里按包名豁免——豁免后其余闸门
         * （面积/宽高/可见性/负向词/重试上限）全部照常生效，误点风险可控。
         */
        private val BOTTOM_BUTTON_PACKAGES = setOf(
            "tv.danmaku.bili", // 哔哩哔哩：跳过按钮固定右下角
            "tv.danmaku.bilibilihd"
        )

        /** v1.14：每会话最多 OCR 截图次数（识别一次几百毫秒+耗电，克制使用）。 */
        private const val MAX_OCR_PER_SESSION = 2

        /**
         * v1.15：两次 takeScreenshot 的最小间隔（AOSP 系统限频 333ms + 余量）。
         * 间隔内的请求系统直接拒（code=3 INTERVAL_TIME_SHORT），不发起反而省事。
         */
        private const val SCREENSHOT_MIN_GAP_MS = 500L

        /**
         * 服务是否真正连着系统。
         *
         * 系统设置里"开关是开的"并不代表服务在跑：应用被覆盖安装、进程被
         * 国产 ROM 的省电策略杀掉后，开关可能还亮着但服务已经断了。
         */
        fun isConnected(): Boolean = instance != null
    }

    private val handler = Handler(Looper.getMainLooper())

    /**
     * 规则仓库。刻意不用 `lateinit`：服务进程被系统重建时，如果
     * [onServiceConnected] 还没回调就来了事件，`lateinit` 会抛
     * UninitializedPropertyAccessException 把服务搞挂。
     */
    private var repo: RuleRepository? = null

    // ---------------- 会话状态 ----------------
    /** 当前扫描会话的包名；null 表示没有进行中的会话。 */
    private var sessionPkg: String? = null
    private var sessionStartAt = 0L
    private var sessionActivity: String = ""

    private var lastScanAt = 0L
    private var lastClickAt = 0L

    /**
     * v1.12：**全局**点击静默时间戳（uptime 时钟）。
     *
     * v1.10 及之前 `lastClickAt` 是会话状态，`startSession` 会把它归零——
     * 广告 SDK 包名与宿主包名摇摆导致会话反复重建时，静默期跟着反复失效，
     * 出现 25ms 连点 3 次的事故（真机日志实锤）。静默期是**防连点**的，
     * 属于全局安全属性，绝不能随会话重置。`startSession`/`clearSession`
     * 都不再碰它。
     */
    private var globalLastClickAt = 0L

    /**
     * v1.11：会话切换两步确认闸门（[SessionGate]）。
     *
     * state 事件只挂起候选，不立即切换会话；扫描时用真实窗口根节点包名做
     * 证据确认/否决。这是 GKD `ForegroundTracker` 的通用机制，替代 v1.10
     * 硬编码桌面包名的修法——任何 ROM、任何"会捣乱的系统包"都走同一路径。
     */
    private var gate = SessionGate.initial()

    /** 本会话内每个「目标签名」尝试了几次、最近一次尝试时间。签名用「包名|关键词」。 */
    private val attemptsByTarget = HashMap<String, Int>()
    private val lastAttemptAtByTarget = HashMap<String, Long>()
    private var totalAttempts = 0

    /** 本会话是否已经派发过点击（用于复扫确认广告消失后停表省电）。 */
    private var clickDispatched = false

    /** 最近一次尝试点击的关键词，用于"目标确认消失"时定位要重置预算的签名。 */
    private var lastAttemptedKeyword = ""

    /** 最近一次尝试点击时目标节点的包名（v1.10：会话劫持纠正用）。 */
    private var lastAttemptedNodePkg: String? = null

    /** v1.12：被拒候选的落盘节流——同一「包名|拒绝原因」30 秒内只记一次，防倒计时刷屏。 */
    private var lastRejectLogKey = ""
    private var lastRejectLogAt = 0L

    // ---------------- v1.14：OCR 兜底状态 ----------------

    /** 本会话内控件树是否找到过任何候选（找到过就不再动用 OCR）。 */
    private var foundAnyCandidate = false

    /** 本会话扫描轮数（OCR 触发条件：≥[OcrPolicy.MIN_SCANS] 轮无候选）。 */
    private var scanCount = 0

    /** OCR 进行中标志（识别是异步的，防止重复截图）。 */
    @Volatile
    private var ocrInProgress = false

    /** 本会话 OCR 已尝试次数（每次会话最多截 2 次，控制耗电）。 */
    private var ocrAttempts = 0

    /**
     * v1.17：本会话是否是「从别的包切换过来」建立的。
     *
     * OCR 只对"疑似开屏"的场景截屏（隐私+耗电）。判据：
     *  - 会话由**换包**建立（从桌面/别的 App 打开）→ 疑似开屏，允许 OCR；
     *  - 会话由**同包恢复**建立（后台切回，如视频播放器）→ 不是开屏，
     *    禁止 OCR——真机实锤：用户从别的 App 切回腾讯会议投屏，OCR 截了
     *    播放画面，既无意义又涉隐私。
     * 冷启动（进程被杀后重开）时 sessionPkg 从 null 切来，也算换包。
     */
    private var sessionFromSwitch = false

    /**
     * v1.15：上次 takeScreenshot 的时刻。
     *
     * AOSP 规定两次无障碍截图之间至少 333ms
     * （ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS），间隔内请求
     * 直接报 code=3（INTERVAL_TIME_SHORT）。真机上 OCR 连续两轮触发时第二
     * 次必然撞上这个限频。这里记录上次截图时刻，间隔不足 [SCREENSHOT_MIN_GAP_MS]
     * 就不发起（等下一轮扫描自然到点），而不是白白吃一个失败。
     */
    private var lastScreenshotAt = 0L

    /** 最近一次扫描是否找到了候选，用于判断"广告是否已经消失"。 */
    private var lastScanFoundCandidate = false

    /** 最近一次扫描遍历了多少节点，写进识别记录，用于区分"没按钮"和"没扫到"。 */
    private var lastTraversed = 0

    private var screenWidth = 0
    private var screenHeight = 0

    /** 最近一次窗口状态事件的时间戳（uptime 时钟），用于丢弃"迟到"的 content 事件。 */
    private var lastStateEventTime = 0L

    /** 会话内轮询扫描任务。 */
    private var scanLoopRunnable: Runnable? = null
    private var windowsScanRunnable: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        ServiceLog.i("无障碍服务已连接(onServiceConnected)")
        ensureReady("onServiceConnected")
    }

    /**
     * 解绑后重绑（某些 ROM 会先 unbind 再 bind，且**不会**再次回调
     * [onServiceConnected]）。若不在这里恢复状态，`instance` 会一直为 null，
     * 首页状态页显示"服务没连上"，且事件里用到的会话状态可能过期——表现为
     * "用着用着就不跳了，关开一次又好了"。参考 GKD / 轻护的实现，这里做一次完整恢复。
     *
     * **注意 [onUnbind] 必须返回 true**，系统才会在重新绑定走这条路径；
     * 返回 false 时 onRebind 永远不会被回调（v1.4 的恢复逻辑因此是死代码）。
     */
    override fun onUnbind(intent: Intent?): Boolean {
        clearSession()
        Diagnostics.flush()
        ServiceLog.w("无障碍服务被解绑(onUnbind)，等待系统重绑")
        if (instance === this) instance = null
        // 服务断开时撤掉常驻通知，保证通知与真实状态一致。
        runCatching { ForegroundKeeperService.stop(this) }
        return true
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        ServiceLog.i("无障碍服务重新绑定(onRebind)")
        ensureReady("onRebind")
    }

    private fun ensureReady(tag: String) {
        instance = this
        refreshScreenSize()
        clearSession()
        repository() // 预热，失败也不影响后续
        // 挂前台常驻通知：降低进程被 ROM 省电策略杀掉的概率（"放一会儿就不跳"的
        // 主因之一），被杀时通知消失、用户能立刻发现。
        // v1.8：包 runCatching——前台服务一旦抛异常（v1.7 的 SecurityException 教训），
        // 绝不能把无障碍服务进程带崩，否则系统会把无障碍开关标记为 crashed 并禁用。
        if (Prefs.isEnabled(this)) {
            runCatching { ForegroundKeeperService.start(this) }
                .onFailure { Log.w(TAG, "启动前台通知失败(已忽略): ${it.message}") }
        }
        Log.i(TAG, "无障碍服务就绪($tag)，屏幕 ${screenWidth}x${screenHeight}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 任何异常都不能往外抛，否则系统会停用本服务。
        try {
            handleEvent(event)
        } catch (e: Throwable) {
            Log.w(TAG, "处理无障碍事件时出错(已忽略): ${e.message}")
        }
    }

    private fun handleEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!Prefs.isEnabled(this)) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = runCatching { event.packageName?.toString() }.getOrNull()
                val activity = runCatching { event.className?.toString() }.getOrNull().orEmpty()
                // 丢弃迟到的旧窗口事件（乱序/延迟投递），避免把已切走的界面又当成新会话。
                val evTime = event.eventTime
                if (evTime > 0L && lastStateEventTime > 0L && evTime < lastStateEventTime) return
                if (evTime > 0L) lastStateEventTime = evTime

                // v1.11：会话切换走两步确认（SessionGate）。
                //  - 回桌面（isLauncher）仍然立即清会话——这是明确的用户意图；
                //  - 排除包/非 Activity 类事件仍然 IGNORE——不挂起，不给劫持留门；
                //  - 其它包的 state 事件只**挂起**为候选，等扫描时用真实窗口
                //    根节点包名做证据再确认切换。迟到的桌面残留事件会被证据
                //    自然否决（真机实锤：hiboard 事件曾劫持正在验证点击的会话）。
                val action = SplashPolicy.onWindowState(
                    pkg = pkg,
                    excluded = isExcludedPackage(pkg),
                    isLauncher = pkg != null && pkg == launcherPackage(),
                    nonActivityClass = ScanFilter.isNonActivityClass(activity),
                    currentPkg = sessionPkg,
                    elapsedMs = elapsedSinceSession(),
                    splashWindowMs = SPLASH_WINDOW_MS
                )
                when (action) {
                    SplashPolicy.Action.CLEAR -> if (sessionPkg != null) clearSession()
                    SplashPolicy.Action.START -> {
                        if (pkg != null && pkg != sessionPkg) {
                            // 挂起候选，不立即切换；证据在 scanOnce 里确认。
                            gate = SessionGate.onWindowState(gate, pkg, SystemClock.uptimeMillis())
                            Log.i(TAG, "会话切换挂起待证据: $sessionPkg → $pkg")
                        }
                    }
                    // 同一个 App 的 Activity 切换：续上时间窗，保证晚渲染的广告也能被打到。
                    SplashPolicy.Action.CONTINUE -> extendSession()
                    SplashPolicy.Action.IGNORE -> Unit
                }
                // v1.7：事件即触发。会话状态只影响节奏，不影响"扫不扫"——
                // 即使上面判定 IGNORE（比如超时后同包名），也要兜底扫一次。
                scheduleEventScan(pkg)
            }

            // 窗口层级变化：浮层/弹窗广告出现或消失。
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (!sessionActive()) return
                windowsScanRunnable?.let { handler.removeCallbacks(it) }
                val r = Runnable {
                    windowsScanRunnable = null
                    safeScan()
                }
                windowsScanRunnable = r
                handler.postDelayed(r, 100L)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val pkg = runCatching { event.packageName?.toString() }.getOrNull()
                // 迟到事件：某个 content 事件比最近一次窗口切换还早，说明它是上一个界面
                // 残留的（例如切 App 后才送到），丢弃，避免误扫已切走的界面。
                val evTime = event.eventTime
                if (evTime > 0L && lastStateEventTime > 0L && evTime < lastStateEventTime) return
                // v1.7：事件即触发。无论会话状态如何（在窗内/超时/无会话），
                // 只要包名不是排除对象，就限频兜底扫一次。这彻底消灭
                // "某条开窗路径失效 → 服务失明"这一整类 bug。
                scheduleEventScan(pkg)
            }
        }
    }

    /**
     * 事件触发的兜底扫描（v1.7 核心）。
     *
     * - 排除包（自己/系统/桌面/输入法）不扫；
     * - 键盘弹出时不扫（检查在 [scanOnce] 的限频之后做，避免事件风暴下
     *   每事件一次跨进程调用）；
     * - 限频：窗内 80ms / 窗外 500ms（[ScanThrottlePolicy]），事件风暴打不满主线程；
     * - 会话若没开（比如 ROM 恢复前台没发 state 事件），顺手把会话开上，
     *   让重试预算/诊断记录/节奏回到正确轨道。
     *
     * 同包会话已过期时**有条件重开**：只有"预算已被消耗过 + 距上次点击超过
     * [SESSION_RESET_QUIET_MS]"才重开。这样「最小化放一会儿再打开」能拿到
     * 全新的点击预算和密扫窗口；而信息流里的持续误命中因为点击不断刷新
     * lastClickAt，不会反复重开预算，天然止血。
     */
    private fun scheduleEventScan(pkg: String?) {
        if (pkg == null || isExcludedPackage(pkg)) return

        val now = SystemClock.uptimeMillis()
        when {
            // v1.16：分支顺序修正——null 检查必须在「!=」之前，否则
            // sessionPkg=null 时走第一分支（挂起），下面的 null 分支永远死代码。
            sessionPkg == null -> startSession(pkg, "")
            sessionPkg != pkg -> {
                gate = SessionGate.onWindowState(gate, pkg, now)
            }
            !sessionActive() && totalAttempts > 0 &&
                now - lastClickAt > SESSION_RESET_QUIET_MS -> startSession(pkg, "")
        }

        val minInterval = ScanThrottlePolicy.minInterval(inWindow = sessionActive())
        if (now - lastScanAt < minInterval) return
        safeScan()
        // 键盘检查刻意放在 safeScan 里的限频之后（见 scanOnce）：
        // `windows` 是跨进程调用，content 事件风暴下每个事件都查会打满主线程。
    }

    /**
     * 是否有输入法窗口正盖在屏幕上。
     *
     * 键盘弹出时聊天/搜索框的 content 事件会连续不断，且打字界面不可能有
     * 开屏广告；不拦的话既浪费扫描，又有把输入内容误当按钮点掉的风险。
     */
    private fun isKeyboardShowing(): Boolean =
        runCatching {
            windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        }.getOrDefault(false)

    // ------------------------------------------------------------------
    // 扫描范围过滤
    // ------------------------------------------------------------------

    /**
     * 这个包是否不该被扫描：自己、系统 UI、桌面、输入法、设置/权限弹窗。
     *
     * **自检必须落到窗口/根节点这一层**：`getWindows()` 返回屏幕上所有窗口，其中
     * 包含刘跳跳自己的 Activity。只按事件包名自检是无效的——真机上就出现过事件
     * 包名是 `com.android.systemui`、却把首页「跳过 ×」标签当成跳过按钮点掉的情况。
     */
    private fun isExcludedPackage(pkg: String?): Boolean =
        ScanFilter.isExcluded(pkg, packageName, launcherPackage(), imePackages())

    private var launcherPkg: String? = null
    private var imePkgs: Set<String> = emptySet()

    /** 当前默认桌面包名，解析失败返回空串（空串不会匹配任何包）。 */
    private fun launcherPackage(): String {
        launcherPkg?.let { return it }
        val p = runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
        }.getOrNull()
        // 解析到 "android" 说明命中的是系统选择器，不是真正的桌面。
        var resolved = if (p == null || p == "android") "" else p
        // v1.16：resolve 失败时按已知国产桌面包名兜底（让 isLauncher 判定生效，
        // "回桌面清会话"路径正确）。注意：v1.10 曾把 com.hihonor.hiboard 当桌面
        // ——真机 resolve 验证桌面真身是 com.hihonor.android.launcher
        // （UniHomeLauncher），hiboard 是智慧助手/负一屏组件，已从清单移除。
        if (resolved.isEmpty()) {
            resolved = KNOWN_LAUNCHER_PACKAGES.firstOrNull { pkg ->
                runCatching { packageManager.getPackageInfo(pkg, 0) }.isSuccess
            } ?: ""
        }
        launcherPkg = resolved
        return resolved
    }

    /** 已启用的输入法包名。输入法弹出是最频繁的"假开屏"，必须整包排除。 */
    private fun imePackages(): Set<String> {
        if (imePkgs.isNotEmpty()) return imePkgs
        imePkgs = runCatching {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.enabledInputMethodList?.map { it.packageName }?.toSet()
        }.getOrNull() ?: emptySet()
        return imePkgs
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        super.onDestroy()
        clearSession()
        Diagnostics.flush()
        ServiceLog.w("无障碍服务销毁(onDestroy)")
        if (instance === this) instance = null
        runCatching { ForegroundKeeperService.stop(this) }
    }

    // ------------------------------------------------------------------
    // 扫描会话
    // ------------------------------------------------------------------

    private fun elapsedSinceSession(): Long =
        if (sessionStartAt == 0L) SplashPolicy.NO_SESSION
        else SystemClock.uptimeMillis() - sessionStartAt

    private fun sessionActive(): Boolean =
        sessionPkg != null &&
            sessionStartAt > 0L &&
            SystemClock.uptimeMillis() - sessionStartAt <= SPLASH_WINDOW_MS

    /** 本次会话已经达成目的（点过，且再扫已经找不到目标）→ 可以停表省电。 */
    private fun sessionSatisfied(): Boolean = clickDispatched && !lastScanFoundCandidate

    /** 开一个新会话：清空旧状态与旧定时任务，重置计时与点击预算，开始轮询扫描。 */
    private fun startSession(pkg: String, activity: String) {
        stopScanLoop()
        cancelAuxScans()

        // v1.17：记录会话来源——换包（从别的 App/桌面/null 切来）= 疑似开屏，
        // 允许 OCR；同包重建（超时重开/预算重置）= 不是新开屏，禁止 OCR。
        sessionFromSwitch = pkg != sessionPkg
        sessionPkg = pkg
        sessionActivity = activity
        sessionStartAt = SystemClock.uptimeMillis()
        gate = SessionGate.initial(pkg)
        lastScanAt = 0L
        // v1.12：lastClickAt 不再随会话重置（globalLastClickAt 才是防连点闸门）。
        attemptsByTarget.clear()
        lastAttemptAtByTarget.clear()
        totalAttempts = 0
        clickDispatched = false
        lastAttemptedKeyword = ""
        lastAttemptedNodePkg = null
        lastScanFoundCandidate = false
        lastTraversed = 0
        // v1.14：OCR 兜底状态随会话重置。
        foundAnyCandidate = false
        scanCount = 0
        ocrAttempts = 0

        refreshScreenSize()
        Diagnostics.begin(pkg, activity, screenWidth, screenHeight, Prefs.isTopOnly(this))
        Log.i(TAG, "开始扫描会话 pkg=$pkg activity=$activity 屏幕 ${screenWidth}x$screenHeight")
        armScanLoop()
    }

    /**
     * 同一 App 切到新 Activity：会话内轮询已经在跑，这里只需确保任务没停即可。
     * 刻意**不重置**时间窗——轮询本来就是连续的，按钮晚渲染也能打到；无限续窗
     * 反而会把扫描拖到主界面上、增加误点与耗电。
     */
    private fun extendSession() {
        if (sessionPkg == null) return
        if (scanLoopRunnable == null) armScanLoop()
    }

    /** 清空会话与全部定时任务（离开 App / 服务断开时）。 */
    private fun clearSession() {
        stopScanLoop()
        cancelAuxScans()
        sessionPkg = null
        sessionStartAt = 0L
        sessionActivity = ""
        gate = SessionGate.initial()
        lastScanAt = 0L
        // v1.12：lastClickAt 不再随会话清零（globalLastClickAt 才是防连点闸门）。
        attemptsByTarget.clear()
        lastAttemptAtByTarget.clear()
        totalAttempts = 0
        clickDispatched = false
        lastAttemptedKeyword = ""
        lastAttemptedNodePkg = null
        lastScanFoundCandidate = false
        lastTraversed = 0
        lastStateEventTime = 0L
    }

    /**
     * 启动会话内轮询扫描：前段密集、后段稀疏，直到会话窗口结束。
     *
     * 节奏决策全部交给 [ScanLoopPolicy.nextInterval]（纯逻辑，可单测）。
     * **已点掉主广告后只放慢、不收工**：二段式开屏的第二段广告在第一段
     * 跳过后才渲染，且它的出现往往只伴随 content-changed（窗口列表未变），
     * 若在这里提前退出，第二段就永远没人扫——这正是 v1.4「第一条跳得掉、
     * 后面的就不跳了」的根因之一。
     */
    private fun armScanLoop() {
        stopScanLoop()
        val loop = object : Runnable {
            override fun run() {
                scanLoopRunnable = null
                if (!Prefs.isEnabled(this@AdSkipService)) return
                if (!sessionActive()) return

                safeScan()

                val interval = ScanLoopPolicy.nextInterval(
                    sessionActive = sessionActive(),
                    satisfied = sessionSatisfied(),
                    elapsedMs = SystemClock.uptimeMillis() - sessionStartAt
                ) ?: return
                scanLoopRunnable = this
                handler.postDelayed(this, interval)
            }
        }
        scanLoopRunnable = loop
        handler.post(loop) // 立即扫一次，再按节奏轮询
    }

    private fun stopScanLoop() {
        scanLoopRunnable?.let { handler.removeCallbacks(it) }
        scanLoopRunnable = null
    }

    private fun cancelAuxScans() {
        windowsScanRunnable?.let { handler.removeCallbacks(it) }
        windowsScanRunnable = null
    }

    /**
     * 取屏幕真实尺寸。
     *
     * 优先用 `maximumWindowMetrics`：`resources.displayMetrics` 不含状态栏和
     * 导航栏，真机上量到 1060x2205，而控件坐标 `getBoundsInScreen` 是含系统栏的
     * 绝对坐标（实际到 2376）。两者口径不一致会让"上方 40%"这条线算低，
     * 白白拒掉一部分合法的跳过按钮。
     */
    private fun refreshScreenSize() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val ok = runCatching {
                val wm = getSystemService(WINDOW_SERVICE) as? WindowManager
                val b = wm?.maximumWindowMetrics?.bounds
                if (b != null && b.width() > 0 && b.height() > 0) {
                    screenWidth = b.width()
                    screenHeight = b.height()
                    true
                } else {
                    false
                }
            }.getOrDefault(false)
            if (ok) return
        }
        runCatching {
            val dm = resources.displayMetrics
            if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                screenWidth = dm.widthPixels
                screenHeight = dm.heightPixels
            }
        }
    }

    // ------------------------------------------------------------------
    // 扫描
    // ------------------------------------------------------------------

    private fun repository(): RuleRepository? {
        repo?.let { return it }
        val r = runCatching { RuleRepository.get(this) }.getOrNull()
        repo = r
        return r
    }

    private fun safeScan() {
        try {
            scanOnce()
        } catch (e: Throwable) {
            Log.w(TAG, "扫描时出错(已忽略): ${e.message}")
        }
    }

    /**
     * v1.11：用真实窗口根节点包名做证据，确认/否决挂起的会话切换（[SessionGate]）。
     *
     * - 活动窗口的根节点包名 == 挂起候选 → 确认切换（startSession）；
     * - == 当前会话包 → 否决挂起（迟到的桌面/浮层劫持事件被自然丢弃）；
     * - 是别的非排除包 → 证据优先，直接切换（窗口先于事件出现的 ROM）；
     * - 挂起超时（窗口一直读不到根节点）→ 自动确认，会话不会卡死。
     */
    private fun reconcileSessionWithWindows() {
        val now = SystemClock.uptimeMillis()

        // 证据：活动窗口（或 rootInActiveWindow）的根节点包名。
        val evidence = runCatching {
            rootInActiveWindow?.packageName?.toString()
        }.getOrNull()

        var confirmedPkg: String? = null
        if (!evidence.isNullOrEmpty() && !isExcludedPackage(evidence)) {
            val (s, confirmed) = SessionGate.onWindowEvidence(gate, evidence, now)
            gate = s
            confirmedPkg = confirmed
        }

        // 超时兜底确认。
        if (confirmedPkg == null) {
            val (s, timeoutConfirmed) = SessionGate.onTimeout(gate, now)
            gate = s
            confirmedPkg = timeoutConfirmed
        }

        if (confirmedPkg != null && confirmedPkg != sessionPkg) {
            ServiceLog.i("会话切换确认(证据=$evidence): $sessionPkg → $confirmedPkg")
            startSession(confirmedPkg, "")
        }
    }

    private fun scanOnce() {
        if (!Prefs.isEnabled(this)) return
        if (!isScreenInteractive()) return

        // v1.11：两步确认——扫描本身就是"证据时刻"。真实窗口根节点的包名
        // 比任何事件都可信：确认挂起的切换 / 否决迟到的劫持事件。
        // 放在限频之前：切换确认不能被限频拖延（否则挂起超时误确认）。
        reconcileSessionWithWindows()

        val realPkg = sessionPkg ?: return
        // 包名属于系统/自己/桌面/输入法，一律不扫。
        if (isExcludedPackage(realPkg)) return
        // 键盘弹出时不扫：打字界面不可能有开屏广告，还能防误点聊天文本。
        // 放在限频之后，避免 content 事件风暴下每事件一次跨进程调用。
        if (isKeyboardShowing()) return

        val now = SystemClock.uptimeMillis()
        // v1.7：限频按窗内/窗外取（[ScanThrottlePolicy]），**不再因"窗口过期"直接
        // 返回**。时间窗只调节奏——窗外疏扫兜住"没有 state 事件"的广告，
        // 防误点仍由规则+几何+重试上限负责，与时间无关。
        val minInterval = ScanThrottlePolicy.minInterval(inWindow = sessionActive())
        if (now - lastScanAt < minInterval) return
        lastScanAt = now

        val rules = repository()?.resolve(realPkg, Prefs.extraKeywords(this)) ?: return

        val candidate = findBestCandidate(rules)
        if (candidate == null) {
            val becameSatisfied = clickDispatched && !lastScanFoundCandidate
            lastScanFoundCandidate = false
            // 关键支撑修复：上一轮点过、这一轮确认目标已消失 → 广告真的被跳掉了。
            // 此时清掉该目标的尝试计数，二段式广告若再弹出**同一个按钮**，
            // 预算重新计算，不会因沿用旧计数而被 RetryPolicy 直接拒掉。
            if (becameSatisfied) {
                val sig = "$realPkg|${lastAttemptedKeyword}"
                val consumed = attemptsByTarget.remove(sig)
                if (consumed != null) {
                    totalAttempts = (totalAttempts - consumed).coerceAtLeast(0)
                    Log.i(TAG, "目标确认消失，归还重试预算 ${consumed} 次: $sig")
                }
            }
            // 把"扫了多少节点"记下来：0 说明控件树里啥都没有（视频/SurfaceView 渲染），
            // 几千个节点却没命中说明是规则问题。这两者的修法完全不同。
            Diagnostics.setOutcome(
                if (clickDispatched) "已跳过（复扫未再发现候选，本次遍历 $lastTraversed 个节点）"
                else "未找到候选（本次遍历 $lastTraversed 个节点）"
            )
            Log.d(TAG, "未找到候选 pkg=$realPkg 遍历节点=$lastTraversed")

            // v1.14：OCR 兜底——控件树一无所获时，截图用本地 OCR 找"跳过"文字。
            // 真机实锤：ivy 等 App 的开屏广告整屏 SurfaceView 渲染，控件树里
            // 只有主界面控件，无障碍物理读不到按钮。决策全部在 [OcrPolicy]
            // （纯逻辑可单测）：≥3 轮无候选、会话窗内、未点过，才截一次。
            maybeOcrSkip(realPkg, rules)
            return
        }
        lastScanFoundCandidate = true
        // 控件树找到过候选 → 本会话不再动用 OCR（OcrPolicy 的触发条件之一）。
        foundAnyCandidate = true

        // 复核：候选节点必须仍然属于当前会话的 App。切页/多窗口时可能拿到别的窗口
        // 的残留节点，若直接点会把点击落到错误界面（GKD 也有一层 window 匹配校验）。
        // v1.10 例外：候选正是**上一轮刚点过的目标**（包名+关键词都对得上）时，
        // 说明会话被系统浮层/桌面的迟到事件劫持了（真机抓到：荣耀桌面 hiboard 的
        // 事件在点击后 3ms 到达，把 QQ音乐 会话抢走）。此时把会话纠正回候选的包名，
        // 让"复扫验证→换招重试"链路继续走完，而不是把重试废掉。
        val nodePkg = runCatching { candidate.node.packageName?.toString() }.getOrNull()
        if (nodePkg != null && nodePkg != realPkg) {
            val isLastTarget = nodePkg == lastAttemptedNodePkg &&
                candidate.keyword == lastAttemptedKeyword
            if (isLastTarget) {
                ServiceLog.w("会话被劫持($realPkg→$nodePkg)，纠正回刚点过的目标继续验证")
                sessionPkg = nodePkg
            } else {
                Log.w(TAG, "候选包名不符已丢弃: node=$nodePkg session=$realPkg")
                return
            }
        }

        // 目标签名：同一 App 同一关键词视为同一个目标，不受按钮轻微位移影响，
        // 才能稳定地在"没点掉"时换招重试。
        val targetSig = "$realPkg|${candidate.keyword}"
        val attempts = attemptsByTarget[targetSig] ?: 0

        if (!RetryPolicy.canRetry(attempts)) {
            Diagnostics.setOutcome(
                "目标已尝试 $attempts 次仍未消失：「${candidate.keyword}」" +
                    "（该广告可能对点击不响应，可在识别记录里查看控件）"
            )
            Log.w(TAG, "目标已用尽尝试次数: $targetSig")
            return
        }
        if (totalAttempts >= MAX_TOTAL_ATTEMPTS) return

        // 同一个目标刚点完，先等一会儿看效果，别在同一帧连点。
        val lastAt = lastAttemptAtByTarget[targetSig] ?: 0L
        if (attempts > 0 && now - lastAt < RETRY_SAME_TARGET_MS) return
        // v1.12：静默期用全局时间戳，会话重建不再能绕过（修 25ms 连点事故）。
        if (now - globalLastClickAt < POST_CLICK_QUIET_MS) return

        val attemptNo = attempts + 1
        val gestureFirst = RetryPolicy.gestureFirstFor(attemptNo)
        val method = RetryPolicy.methodLabel(gestureFirst)
        lastAttemptedKeyword = candidate.keyword
        lastAttemptedNodePkg = runCatching { candidate.node.packageName?.toString() }.getOrNull()

        val dispatched = clickNode(candidate.node, gestureFirst)

        // 记录本次尝试（无论是否派发成功都计数），保证对同一目标最多 4 次。
        attemptsByTarget[targetSig] = attemptNo
        lastAttemptAtByTarget[targetSig] = now
        totalAttempts++

        if (dispatched) {
            clickDispatched = true
            lastClickAt = now
            globalLastClickAt = now
            ServiceLog.i(
                "已点击(第${attemptNo}次/$method): $targetSig bounds=${candidate.bounds.toShortString()}"
            )
            Diagnostics.setOutcome(
                "已尝试点击（第 $attemptNo 次 · $method 优先）：命中「${candidate.keyword}」" +
                    "${candidate.score}分 [${candidate.bounds.toShortString()}]"
            )
            Log.i(TAG, "已尝试点击(第${attemptNo}次/$method): $targetSig")
        } else {
            Diagnostics.setOutcome("找到候选但点击动作未派发成功：「${candidate.keyword}」")
            Log.w(TAG, "点击未派发: $targetSig")
        }
    }

    private fun isScreenInteractive(): Boolean =
        runCatching {
            val pm = getSystemService(POWER_SERVICE) as? PowerManager
            pm?.isInteractive ?: true
        }.getOrDefault(true)

    // ------------------------------------------------------------------
    // v1.14：OCR 兜底（SurfaceView/自绘广告）
    // ------------------------------------------------------------------

    /**
     * 控件树一无所获时的 OCR 兜底。
     *
     * 流程：[OcrPolicy.shouldOcr] 判定 → [takeScreenshot]（API 30+，无障碍
     * 自带能力）→ ML Kit 中文识别（**bundled 本地模型**，无网络）→
     * [OcrPolicy.isSkipText] 判定文本块 → 手势点击块中心。
     *
     * 约束：
     *  - 每会话最多 [MAX_OCR_PER_SESSION] 次截图（识别一次几百毫秒+耗电）；
     *  - 识别异步进行，期间 [ocrInProgress] 置位防重入；
     *  - 任何异常静默吞掉——OCR 是锦上添花，绝不能带崩主链路。
     */
    private fun maybeOcrSkip(pkg: String, rules: RuleSet) {
        // v1.14：OCR 开关（用户可在首页关闭，隐私/耗电顾虑）。
        if (!Prefs.isOcrEnabled(this)) return
        // v1.17：只有「换包建立的会话」（疑似开屏）才允许截屏。
        // 同包恢复（后台切回视频播放器等）绝不截。
        if (!sessionFromSwitch) return
        scanCount++
        if (ocrInProgress) return
        if (ocrAttempts >= MAX_OCR_PER_SESSION) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return // takeScreenshot 需要 API 30
        // v1.15：AOSP 限频——两次截图至少隔 SCREENSHOT_MIN_GAP_MS（系统 333ms 限制
        // 加余量），间隔内不发起，避免白白吃 code=3 失败。
        if (SystemClock.uptimeMillis() - lastScreenshotAt < SCREENSHOT_MIN_GAP_MS) return
        if (!OcrPolicy.shouldOcr(
                scanCount = scanCount,
                foundAnyCandidate = foundAnyCandidate,
                elapsedMs = SystemClock.uptimeMillis() - sessionStartAt,
                clickDispatched = clickDispatched
            )
        ) return

        ocrInProgress = true
        ocrAttempts++
        lastScreenshotAt = SystemClock.uptimeMillis()
        ServiceLog.i("OCR兜底触发: pkg=$pkg 第${scanCount}轮无候选，截图识别")

        runCatching {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        runCatching { handleOcrScreenshot(screenshot, pkg, rules) }
                        ocrInProgress = false
                    }

                    override fun onFailure(errorCode: Int) {
                        ServiceLog.w("OCR兜底截图失败: code=$errorCode")
                        ocrInProgress = false
                    }
                }
            )
        }.onFailure {
            ServiceLog.w("OCR兜底截图异常", it)
            ocrInProgress = false
        }
    }

    /** 截图到手：跑 ML Kit 本地识别，命中"跳过"小块就手势点击。 */
    private fun handleOcrScreenshot(screenshot: ScreenshotResult, pkg: String, rules: RuleSet) {
        val hardwareBuffer = screenshot.hardwareBuffer
        val bitmap = runCatching {
            android.graphics.Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
        }.getOrNull()
        if (bitmap == null) {
            ServiceLog.w("OCR兜底: bitmap 包装失败")
            hardwareBuffer.close()
            return
        }

        // ML Kit 需要软件位图：硬件位图转 ARGB_8888。
        val softwareBitmap = runCatching {
            bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
        }.getOrNull()
        bitmap.recycle()
        if (softwareBitmap == null) {
            ServiceLog.w("OCR兜底: 硬件位图转换失败")
            hardwareBuffer.close()
            return
        }

        // 中文识别器（bundled 本地模型）。正确 API：
        // TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
            com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions.Builder().build()
        )
        val image = com.google.mlkit.vision.common.InputImage.fromBitmap(softwareBitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener { result ->
                runCatching {
                    val sw = softwareBitmap.width
                    val sh = softwareBitmap.height
                    var hitAny = false
                    for (block in result.textBlocks) {
                        val text = RuleMatcher.normalize(block.text.trim())
                        val box: Rect? = block.boundingBox
                        if (box == null) continue
                        val ok = OcrPolicy.isSkipText(
                            normalizedText = text,
                            left = box.left.toFloat(),
                            top = box.top.toFloat(),
                            right = box.right.toFloat(),
                            bottom = box.bottom.toFloat(),
                            screenWidth = sw,
                            screenHeight = sh,
                            keywords = rules.keywords
                        )
                        if (ok) {
                            hitAny = true
                            val (x, y) = OcrPolicy.tapPoint(
                                box.left.toFloat(), box.top.toFloat(),
                                box.right.toFloat(), box.bottom.toFloat()
                            )
                            ServiceLog.i(
                                "OCR命中「$text」@$x,$y (${box.toShortString()}) → 手势点击"
                            )
                            Diagnostics.setOutcome(
                                "OCR 兜底命中「$text」[${box.toShortString()}]，已手势点击" +
                                    "（该广告为渲染盲区，控件树读不到）"
                            )
                            if (tap(x, y)) {
                                clickDispatched = true
                                lastClickAt = SystemClock.uptimeMillis()
                                globalLastClickAt = lastClickAt
                                lastAttemptedKeyword = "OCR:$text"
                            }
                            break
                        }
                    }
                    // v1.16：识别完成但没命中也记一条（带识别出的文本块数），
                    // 否则"触发了但没下文"没法排查——是截图失败、识别失败、
                    // 还是识别了但全被准入拒了，三种情况在日志里要能区分。
                    if (!hitAny) {
                        // v1.16b：把每个文本块的内容+位置也记下来（截断防刷屏）。
                        // 真机实锤：ivy 广告右下角「跳过 5」清晰可见，OCR 识别出
                        // 26 个块却"未命中"——必须看到 OCR 到底把按钮读成了什么、
                        // 是文字没对上还是几何准入拒了，才能修。
                        val details = result.textBlocks.joinToString(" | ") { b ->
                            val box = b.boundingBox
                            "\"${b.text.trim().take(12)}\"[${box?.toShortString() ?: "?"}]"
                        }.take(400)
                        ServiceLog.i("OCR识别完成未命中(${result.textBlocks.size}块): $details")
                    }
                }
                softwareBitmap.recycle()
                recognizer.close()
            }
            .addOnFailureListener { e ->
                ServiceLog.w("OCR兜底识别失败", e)
                softwareBitmap.recycle()
                recognizer.close()
            }
        hardwareBuffer.close()
    }

    private data class Candidate(
        val node: AccessibilityNodeInfo,
        val keyword: String,
        val score: Int,
        val bounds: Rect
    )

    // ------------------------------------------------------------------
    // 多窗口 + BFS 遍历
    // ------------------------------------------------------------------

    /**
     * 依次检查所有窗口的根节点，返回全局最优候选。
     *
     * 以 `getWindows()` 为主：它不会像 `rootInActiveWindow` 那样在切换过程中
     * 返回 null，节点信息也更全（浮层、弹窗、下拉栏都是独立 window）。活动/聚焦
     * 窗口排在前面，`rootInActiveWindow` 作为部分 ROM 上的补充。
     */
    private fun findBestCandidate(rules: RuleSet): Candidate? {
        var best: Candidate? = null
        var totalVisited = 0

        for (root in windowRoots()) {
            val out = ArrayList<Candidate>()
            val budget = intArrayOf(0)
            traverseBfs(root, rules, out, budget, 0)
            totalVisited += budget[0]

            val local = out.maxByOrNull { it.score }
            if (local != null && (best == null || local.score > best.score)) best = local
            // 已经拿到高分候选，不必再翻剩下的窗口。
            if (best != null && best.score >= FAST_ACCEPT_SCORE) break
        }

        lastTraversed = totalVisited
        return best
    }

    /** 收集所有窗口的根节点，活动/聚焦窗口优先；**逐个窗口按包名过滤**。 */
    private fun windowRoots(): List<AccessibilityNodeInfo> {
        val wins = runCatching { windows }.getOrNull().orEmpty()
        val roots = ArrayList<AccessibilityNodeInfo>(wins.size + 1)

        if (wins.isNotEmpty()) {
            val sorted = wins.sortedByDescending { w ->
                runCatching { w.isActive || w.isFocused }.getOrDefault(false)
            }
            for (w in sorted) {
                if (roots.size >= MAX_WINDOWS) break
                val r = runCatching { w.root }.getOrNull() ?: continue
                // 关键：过滤落在**每个窗口的根节点包名**上，而不是事件包名。
                // 否则 systemui 触发的事件会让自己的 Activity 窗口混进候选，
                // 真机上就这么把首页的关键词标签点掉了。
                val pkg = runCatching { r.packageName?.toString() }.getOrNull()
                if (isExcludedPackage(pkg)) continue
                if (roots.none { it == r }) roots.add(r)
            }
        }

        // 部分 ROM 的 getWindows() 不含当前活动内容，用 rootInActiveWindow 补。
        val active = runCatching { rootInActiveWindow }.getOrNull()
        if (active != null) {
            val activePkg = runCatching { active.packageName?.toString() }.getOrNull()
            if (!isExcludedPackage(activePkg) && roots.none { it == active }) roots.add(active)
        }

        return roots
    }

    /**
     * 层序遍历（BFS）。
     *
     * 为什么不用 DFS：跳过按钮在浅层，而它后面常常挂着整棵 App 主界面。DFS 会
     * 先一头扎进第一个子树把节点预算耗光，等轮到按钮时已经超限——表现出来就是
     * "有时候找得到、有时候找不到"。BFS 按层推进，浅层按钮优先。
     */
    private fun traverseBfs(
        root: AccessibilityNodeInfo,
        rules: RuleSet,
        out: MutableList<Candidate>,
        budget: IntArray,
        startDepth: Int
    ) {
        var level = ArrayList<AccessibilityNodeInfo>(1)
        level.add(root)
        var depth = startDepth

        while (level.isNotEmpty() && depth <= MAX_DEPTH && budget[0] < MAX_NODES_PER_WINDOW) {
            val next = ArrayList<AccessibilityNodeInfo>()
            for (node in level) {
                if (budget[0] >= MAX_NODES_PER_WINDOW) break
                budget[0]++

                if (evaluate(node, rules, out)) return // 高分命中，提前收工

                val childCount = runCatching { node.childCount }.getOrDefault(0)
                for (i in 0 until childCount) {
                    val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                    next.add(child)
                }
            }
            level = next
            depth++
        }
    }

    /**
     * 评估单个节点：匹配 → 几何准入 → 加入候选 / 写识别记录。
     * @return true 表示已拿到高分候选，可以停止遍历。
     */
    private fun evaluate(
        node: AccessibilityNodeInfo,
        rules: RuleSet,
        out: MutableList<Candidate>
    ): Boolean {
        val text = rawText(node)
        val desc = rawDesc(node)
        val viewId = runCatching { node.viewIdResourceName }.getOrNull().orEmpty()
        val clickable = runCatching { node.isClickable }.getOrDefault(false)
        val visible = runCatching { node.isVisibleToUser }.getOrDefault(false)
        val bounds = boundsOf(node)

        // 识别记录：把带文字的和可点击的控件都记下来，排查"点不到"全靠它。
        if (bounds != null && (text.isNotEmpty() || desc.isNotEmpty() || clickable)) {
            Diagnostics.addNode(
                text = text,
                desc = desc,
                viewId = viewId,
                bounds = bounds.toShortString(),
                clickable = clickable,
                visible = visible,
                note = ""
            )
        }

        if (text.isEmpty() && desc.isEmpty() && viewId.isEmpty()) return false

        val match = RuleMatcher.bestMatchFields(text, desc, rules, clickable, visible, viewId)
            ?: return false
        if (bounds == null) return false

        val reject = CandidatePolicy.inspect(
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            // v1.12：底部按钮白名单。B站等 App 的开屏跳过按钮固定在屏幕右下角
            // （真机实测 tv.danmaku.bili:id/count_down，top=89% 屏高），被"仅上方
            // 区域"一刀切拒绝 → 广告全程倒计时自然结束，表现为"点不到"。
            // 这些包名下放宽 topOnly；其余 App 维持原判，防误点不松懈。
            topOnly = Prefs.isTopOnly(this) && sessionPkg !in BOTTOM_BUTTON_PACKAGES
        )

        val note = if (reject == null) {
            out.add(Candidate(node, match.keyword, match.score, bounds))
            "命中「${match.keyword}」${match.score}分"
        } else {
            // 记录被拒原因：用户反馈"某个 App 点不到"时，这里能直接看出
            // 是按钮在屏幕下方、还是被判定成了容器。
            // v1.12：被拒也落持久化日志——用户看不到 logcat，识别记录页的
            // 运行日志必须能回答"为什么没点"（B站按钮在底部被 topOnly 拒绝
            // 这类问题，之前只有连电脑才能查）。同一原因 30 秒记一次防刷屏。
            val rejectKey = "$sessionPkg|${reject.label}"
            val nowMs = SystemClock.uptimeMillis()
            if (rejectKey != lastRejectLogKey || nowMs - lastRejectLogAt > 30_000L) {
                lastRejectLogKey = rejectKey
                lastRejectLogAt = nowMs
                ServiceLog.i(
                    "候选被拒[${reject.label}]: $viewId text=\"$text\" " +
                        "bounds=${bounds.toShortString()}"
                )
            }
            "文字/ID 命中但被拒：${reject.label}"
        }
        // 同时打到 logcat：连上电脑用 `adb logcat -s LiuTiaoTiao` 就能看到
        // 到底是没匹配上、还是匹配上了被几何策略拒了。
        // v1.9：附带归一化后的文字/关键词——规则「跳过 >」归一化后是「跳过」，
        // 只显示原始写法会让人误以为是"关键词包含文字"的反向匹配。
        Log.i(
            TAG,
            "候选[$note] text=\"$text\" desc=\"$desc\" id=$viewId " +
                "textNorm=\"${RuleMatcher.normalize(text)}\" " +
                "bounds=${bounds.toShortString()} clickable=$clickable visible=$visible"
        )
        Diagnostics.addNode(
            text = text,
            desc = desc,
            viewId = viewId,
            bounds = bounds.toShortString(),
            clickable = clickable,
            visible = visible,
            note = note
        )

        return reject == null && match.score >= FAST_ACCEPT_SCORE
    }

    private fun boundsOf(node: AccessibilityNodeInfo): Rect? =
        runCatching {
            val r = Rect()
            node.getBoundsInScreen(r)
            r
        }.getOrNull()

    private fun rawText(node: AccessibilityNodeInfo): String =
        runCatching { node.text?.toString()?.trim() }.getOrNull().orEmpty()

    private fun rawDesc(node: AccessibilityNodeInfo): String =
        runCatching { node.contentDescription?.toString()?.trim() }.getOrNull().orEmpty()

    // ------------------------------------------------------------------
    // 点击
    // ------------------------------------------------------------------

    /**
     * 点击目标。返回"动作是否成功派发"（注意：派发 ≠ 已生效，效果要靠复扫验证）。
     *
     * **[useGestureFirst] = true：手势优先。**
     * 广告 SDK 的跳过按钮常常自己处理 onTouchEvent，`performAction(ACTION_CLICK)`
     * 会返回 true 但实际什么都没发生。`dispatchGesture` 注入的是真实触摸，穿透力
     * 强得多；而且点的是目标**自身中心**，天然不会把点击落到父容器上。
     *
     * **[useGestureFirst] = false：反过来，先 ACTION_CLICK 再手势。**
     * 同一个目标手势点不动时，下一轮换这条路再试一次，两种手段都覆盖。
     *
     * ACTION_CLICK 链路的约束：往上找可点击祖先**最多 [MAX_ANCESTOR_STEPS] 层**，
     * 且祖先面积不能超过目标的 [MAX_ANCESTOR_AREA_GROWTH] 倍，否则视为爬到了容器上。
     */
    private fun clickNode(target: AccessibilityNodeInfo, useGestureFirst: Boolean): Boolean {
        return try {
            runCatching { target.refresh() }

            val bounds = boundsOf(target)
            val gesturePoint = bounds?.let { tapPointOf(it) }

            if (useGestureFirst) {
                if (gesturePoint != null && tap(gesturePoint.x, gesturePoint.y)) return true
                return actionClickChain(target, bounds)
            }

            if (actionClickChain(target, bounds)) return true
            gesturePoint != null && tap(gesturePoint.x, gesturePoint.y)
        } catch (e: Throwable) {
            Log.w(TAG, "点击失败(已忽略): ${e.message}")
            false
        }
    }

    /** 自身 → 最多 [MAX_ANCESTOR_STEPS] 层祖先的 ACTION_CLICK 尝试。 */
    private fun actionClickChain(target: AccessibilityNodeInfo, targetBounds: Rect?): Boolean {
        val targetArea = areaOf(targetBounds)
        var node: AccessibilityNodeInfo? = target
        var steps = 0
        while (node != null && steps <= MAX_ANCESTOR_STEPS) {
            val current = node
            val clickable = runCatching { current.isClickable }.getOrDefault(false)
            if (clickable) {
                val ancestorArea = areaOf(boundsOf(current))
                val areaOk = steps == 0 ||
                    (targetArea > 0 && ancestorArea <= targetArea * MAX_ANCESTOR_AREA_GROWTH)
                if (areaOk &&
                    runCatching { current.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                        .getOrDefault(false)
                ) {
                    return true
                }
                // 要么点击失败，要么爬到了容器上：都不再继续往上。
                break
            }
            node = runCatching { current.parent }.getOrNull()
            steps++
        }
        return false
    }

    /**
     * 取手势点击坐标：控件中心，并夹到屏幕范围内。
     * 返回 null 表示这个控件没法用手势点（尺寸为 0 或完全在屏幕外）。
     */
    private fun tapPointOf(r: Rect): PointF? {
        if (r.width() <= 0 || r.height() <= 0) return null
        var x = r.exactCenterX()
        var y = r.exactCenterY()
        if (screenWidth > 0) x = x.coerceIn(1f, (screenWidth - 1).toFloat())
        if (screenHeight > 0) y = y.coerceIn(1f, (screenHeight - 1).toFloat())
        return PointF(x, y)
    }

    private fun areaOf(r: Rect?): Long =
        if (r == null) 0L else r.width().toLong() * r.height().toLong()

    private fun tap(x: Float, y: Float): Boolean {
        return try {
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                .build()
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    Log.i(TAG, "手势点击完成")
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    Log.w(TAG, "手势点击被取消")
                }
            }
            dispatchGesture(gesture, callback, null)
        } catch (e: Throwable) {
            Log.w(TAG, "手势点击失败: ${e.message}")
            false
        }
    }
}
