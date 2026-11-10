package com.liutiaotiao

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * 开机/更新后自检（GKD `BootReceiver` 同款思路）。
 *
 * 为什么需要它：重启后国产 ROM 有两种坏状态——
 *  1. 开关列表里还有本服务，但系统**没有**真正绑定（表现：设置显示已开启，
 *     实际不工作）；
 *  2. 开关被 ROM 直接摘掉。
 * 两种都靠 [AccessibilitySwitch.ensureBound] 检测并修复。开机广播是修复的
 * 最早时机（比用户下次打开 App 早得多）。
 *
 * 注意：开机后系统可能还没就绪，立即写 secure settings 偶发失败，
 * 所以延迟 [DELAY_MS] 再执行。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val DELAY_MS = 5_000L
        private val handler = Handler(Looper.getMainLooper())
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        ServiceLog.initialize(context)
        ServiceLog.i("收到广播: $action")
        if (!Prefs.isEnabled(context)) return

        val appContext = context.applicationContext
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            runCatching {
                val ok = AccessibilitySwitch.ensureBound(appContext, "开机自检")
                ServiceLog.i("开机自检完成: ensureBound=$ok")
            }.onFailure { ServiceLog.w("开机自检失败", it) }
        }, DELAY_MS)
    }
}
