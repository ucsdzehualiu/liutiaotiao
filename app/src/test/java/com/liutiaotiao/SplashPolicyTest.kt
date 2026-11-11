package com.liutiaotiao

import com.liutiaotiao.SplashPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SplashPolicy] 的回归测试。
 *
 * 这组测试专门锁死「第一次能跳、后面再进同一个 App 就不跳」的那几条路径：
 *   - 回到桌面必须清会话，否则再次进入同一 App 时会被误判成"还是同一个窗"；
 *   - 从桌面/其他 App 回来，只要包名变了（哪怕只是 content-changed）就必须重开会话；
 *   - 系统浮层（systemui / 输入法）不能清掉进行中的会话。
 */
class SplashPolicyTest {

    private val app = "com.tencent.qqmusic"
    private val other = "com.baidu.tieba"
    private val win = 6000L

    private fun ws(
        pkg: String?,
        excluded: Boolean = false,
        isLauncher: Boolean = false,
        nonActivityClass: Boolean = false,
        currentPkg: String? = app,
        elapsed: Long = 0L
    ) = SplashPolicy.onWindowState(
        pkg = pkg,
        excluded = excluded,
        isLauncher = isLauncher,
        nonActivityClass = nonActivityClass,
        currentPkg = currentPkg,
        elapsedMs = elapsed,
        splashWindowMs = win
    )

    // ---------------- window-state ----------------

    @Test
    fun `going to launcher clears the session`() {
        // 无会话或会话已开很久：真正回桌面 → 清。
        assertEquals(Action.CLEAR, ws("com.miui.home", isLauncher = true, currentPkg = null))
        assertEquals(
            Action.CLEAR,
            ws("com.miui.home", isLauncher = true, currentPkg = app, elapsed = 3_000L)
        )
    }

    @Test
    fun `launcher event within grace period is ignored`() {
        // v1.16 防抖：会话刚开（宽限期内）的 launcher 事件是桌面残留动画，
        // 不清会话——否则从桌面点图标打开 App 的瞬间，桌面的动画事件会
        // 把刚开的会话反复清空（真机实锤的死循环）。
        assertEquals(
            Action.IGNORE,
            ws("com.hihonor.android.launcher", isLauncher = true, currentPkg = app, elapsed = 0L)
        )
        assertEquals(
            Action.IGNORE,
            ws(
                "com.hihonor.android.launcher", isLauncher = true,
                currentPkg = app, elapsed = SplashPolicy.LAUNCHER_CLEAR_GRACE_MS
            )
        )
        // 宽限期一过：正常清（真正回桌面）。
        assertEquals(
            Action.CLEAR,
            ws(
                "com.hihonor.android.launcher", isLauncher = true,
                currentPkg = app, elapsed = SplashPolicy.LAUNCHER_CLEAR_GRACE_MS + 1
            )
        )
    }

    @Test
    fun `system overlay does not clear or start a session`() {
        // 通知栏/输入法的 state-changed 不能打断进行中的开屏扫描。
        assertEquals(Action.IGNORE, ws("com.android.systemui", excluded = true))
        assertEquals(Action.IGNORE, ws("com.baidu.input", excluded = true))
    }

    @Test
    fun `null package is ignored`() {
        assertEquals(Action.IGNORE, ws(null))
        assertEquals(Action.IGNORE, ws(""))
    }

    @Test
    fun `non activity class is ignored`() {
        assertEquals(Action.IGNORE, ws(other, nonActivityClass = true))
    }

    @Test
    fun `first app after no session starts`() {
        assertEquals(Action.START, ws(app, currentPkg = null))
    }

    @Test
    fun `switching to another app starts a new session`() {
        assertEquals(Action.START, ws(other, currentPkg = app))
    }

    @Test
    fun `same app within window continues`() {
        assertEquals(Action.CONTINUE, ws(app, currentPkg = app, elapsed = 500L))
    }

    @Test
    fun `same app after window starts fresh`() {
        // App 内页面跳转后可能再弹一次广告，超时即按新会话处理。
        assertEquals(Action.START, ws(app, currentPkg = app, elapsed = win + 1))
    }

    // ---------------- content-changed ----------------
    // v1.7 起 content 事件不再由会话门控（扫描一律走事件触发的限频兜底，
    // 见 ScanThrottlePolicyTest），onContentChanged 已 @Deprecated，不再测试。
}
