package com.liutiaotiao

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils

/**
 * 无障碍开关的检测与**绑定修复**（GKD `fixRestartAutomatorService` 同款思路，纯本地）。
 *
 * ## v1.9 的关键认知（对照 GKD 源码后确认）
 *
 * 「不跳了」时开关的真实状态有**三种**，旧代码只处理了其中一种：
 *
 *  1. **开关被摘**：`ENABLED_ACCESSIBILITY_SERVICES` 里没有本服务。
 *     → 旧代码能修：直接写回。**这是最不常见的一种。**
 *
 *  2. **开关在、绑定没建立**（重启/重授权后失效的主因）：列表里**有**本服务，
 *     系统设置显示"已开启"，但系统并没有真正 bind 服务进程——事件不会送达，
 *     表现为"授权是开的、就是不跳"。旧代码的 `isRegistered` 一看"在列表里"
 *     就返回"一切正常"，**永远不做任何事**，所以重新打开授权也没用。
 *     → 修法（GKD 原文注释："无障碍出现故障，先移除再添加以触发系统重启服务。
 *       必须等待一段时间，否则概率不会触发系统重启无障碍"）：
 *       **先从列表摘除 → 等 1 秒 → 再写回**，强迫系统重新走绑定流程。
 *
 *  3. **开关在、服务进程被杀**：`AdSkipService.instance == null`。
 *     → 摘除写回同样能触发系统重新拉起进程并绑定。
 *
 * 修复需要 adb 一次性授权（`WRITE_SECURE_SETTINGS`）：
 * ```
 * adb shell pm grant com.liutiaotiao android.permission.WRITE_SECURE_SETTINGS
 * ```
 * 没授权时自动退化为"只检测、提示用户手动关开一次"。
 */
object AccessibilitySwitch {

    /** 摘除到写回之间的等待（GKD 用 1000ms，等系统处理完 unbind 再 bind）。 */
    const val REBIND_DELAY_MS = 1_000L

    /** 修复节流：两次修复之间的最小间隔（[FixGate]）。 */
    const val FIX_MIN_INTERVAL_MS = FixGate.MIN_INTERVAL_MS

    private val handler = Handler(Looper.getMainLooper())

    /** 最近一次发起修复的时间（uptime 时钟）。 */
    @Volatile
    private var lastFixAt = 0L

    /** 本服务的组件名（写回开关时用）。 */
    fun componentName(context: Context): ComponentName =
        ComponentName(context, AdSkipService::class.java)

    /** 系统设置里本服务的开关是否打开（列表里有没有本服务）。 */
    fun isRegistered(context: Context): Boolean {
        val expected = componentName(context).flattenToString()
        val enabled = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        }.getOrNull() ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    /** 是否拥有 adb 一次性授权的 WRITE_SECURE_SETTINGS（决定能否自修复）。 */
    fun canSelfRepair(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * 把本服务追加回无障碍开关列表（需要 [canSelfRepair]）。
     * @return true = 写回成功（或本来就在列表里）
     */
    fun reRegister(context: Context): Boolean {
        if (isRegistered(context)) return true
        if (!canSelfRepair(context)) return false
        return runCatching {
            val expected = componentName(context).flattenToString()
            val current = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            val next = if (current.isBlank()) expected else "$current:$expected"
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                next
            ) && Settings.Secure.putInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                1
            )
        }.getOrDefault(false)
    }

    /** 检测 + 修复一步到位（只处理"开关被摘"这一种坏状态）。 */
    fun ensureRegistered(context: Context): Boolean {
        if (isRegistered(context)) return true
        val repaired = reRegister(context)
        return if (repaired) true else isRegistered(context)
    }

    // ------------------------------------------------------------------
    // v1.9：绑定修复（处理"开关在、服务没连上"）
    // ------------------------------------------------------------------

    /**
     * **完整的健康检查 + 修复**。调用方：MainActivity 每秒刷新、前台服务心跳、
     * 开机/解锁广播、应用心跳。
     *
     * 三种坏状态都能处理：
     *  - 开关被摘 → [reRegister] 写回；
     *  - 开关在、`AdSkipService.instance == null`（没绑定/进程被杀）→
     *    [scheduleRebind] 摘除→等1秒→写回，强迫系统重新绑定；
     *  - 一切正常 → 什么都不做。
     *
     * @param why 调用来源（写日志定位用）
     * @param urgent v1.17：紧急场景（解锁自检）强制立即修复，绕过节流
     * @return true = 当前健康（开关在 + 服务连着），或已发起修复
     */
    fun ensureBound(context: Context, why: String, urgent: Boolean = false): Boolean {
        val registered = isRegistered(context)
        val connected = AdSkipService.isConnected()

        if (registered && connected) return true

        ServiceLog.i("健康检查($why): registered=$registered connected=$connected")

        if (!registered) {
            val ok = reRegister(context)
            ServiceLog.i("开关被摘，写回: $ok")
            return ok
        }

        // 开关在、服务没连上：需要摘除重绑。
        scheduleRebind(context, why, force = urgent)
        return true // 修复已发起
    }

    /**
     * 摘除 → 等 [REBIND_DELAY_MS] → 写回（GKD 同款）。
     *
     * 节流：两次修复至少间隔 [FIX_MIN_INTERVAL_MS]（[FixGate]），
     * 防止"绑定途中又被摘一次"打断本来能成功的绑定。
     *
     * v1.17：[force] 参数——解锁自检（用户马上要开广告 App）必须立刻修，
     * 不能被节流挡住：用户息屏→亮屏间隔可能很短，但每次亮屏都可能是
     * "荣耀刚杀完进程摘完开关"之后的第一次，慢一拍广告就放完了。
     */
    fun scheduleRebind(context: Context, why: String, force: Boolean = false) {
        if (!canSelfRepair(context)) {
            ServiceLog.w("需要重绑但无 WRITE_SECURE_SETTINGS 授权，请手动关开一次开关")
            return
        }
        val now = SystemClock.uptimeMillis()
        if (!force && !FixGate.canAttempt(now, lastFixAt)) {
            ServiceLog.i("修复节流中($why)，稍后再试")
            return
        }
        lastFixAt = now

        val appContext = context.applicationContext
        val expected = componentName(appContext).flattenToString()

        // 1) 摘除
        val removed = runCatching {
            val current = Settings.Secure.getString(
                appContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            val kept = current.split(':')
                .filter { it.isNotBlank() && !it.equals(expected, ignoreCase = true) }
                .joinToString(":")
            Settings.Secure.putString(
                appContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                kept
            )
        }.getOrDefault(false)
        ServiceLog.i("重绑第1步($why): 摘除=$removed")

        // 2) 等 1 秒再写回，让系统先走完 unbind
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            runCatching {
                val ok = reRegister(appContext)
                ServiceLog.i("重绑第2步($why): 写回=$ok")
            }.onFailure { ServiceLog.w("重绑写回失败($why)", it) }
        }, REBIND_DELAY_MS)
    }

    /** 系统的无障碍总开关（AccessibilityManager 的全局状态）。 */
    fun isSystemAccessibilityEnabled(context: Context): Boolean =
        runCatching {
            Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            ) != 0
        }.getOrDefault(false)

    /** 给 UI 展示用的 adb 授权命令（用户复制到电脑上执行一次即可）。 */
    fun adbGrantCommand(context: Context): String =
        "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
}
