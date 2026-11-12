package com.liutiaotiao

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * 系统关键时刻的自检（GKD `BootReceiver` 思路的扩展）。
 *
 * ## v1.17 为什么监听亮屏/解锁（用户反馈「过一会再开广告 App 就不跳」的根治）
 *
 * 荣耀省电策略在息屏闲置后：杀进程 + 摘无障碍开关。此时：
 *  - 应用心跳随进程死掉（v1.15 的盲区）；
 *  - 开关被摘后系统不再因无障碍绑定拉起进程——**没有任何代码活着**；
 *  - 用户下次开广告 App 时，服务是死的，广告放完才可能被（用户打开
 *    刘跳跳 App 触发的）修复救回来——太晚了。
 *
 * **用户每次拿起手机开 App，必经「亮屏→解锁」**。在这个时点自检修复，
 * 广告出现前服务已就绪。这是不依赖进程存活的、最可靠的唤醒钩子：
 * manifest 注册的广播接收器由系统进程持有，**即使 App 进程被杀也能收到**。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val DELAY_MS = 800L
        private val handler = Handler(Looper.getMainLooper())
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val isBoot = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        val isUnlock = action == Intent.ACTION_USER_PRESENT
        val isPackageReplaced = action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!isBoot && !isUnlock && !isPackageReplaced) return

        ServiceLog.initialize(context)
        ServiceLog.i("收到广播: $action")
        if (!Prefs.isEnabled(context)) return

        // 亮屏/解锁后延迟一点再检查：解锁动画期间写 secure settings 偶发失败。
        // 解锁是紧急场景（用户马上要开广告 App）：强制立即修复，绕过节流。
        val appContext = context.applicationContext
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            runCatching {
                val ok = AccessibilitySwitch.ensureBound(
                    appContext,
                    if (isUnlock) "解锁自检" else "开机自检",
                    urgent = isUnlock
                )
                ServiceLog.i("广播自检完成: ensureBound=$ok")
            }.onFailure { ServiceLog.w("广播自检失败", it) }
        }, DELAY_MS)
    }
}
