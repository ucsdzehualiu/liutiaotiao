package com.liutiaotiao

import android.app.Application
import android.os.Handler
import android.os.Looper

/**
 * v1.15：应用级自检。
 *
 * ## 为什么需要它（「一开始好使、后面就不好使」的根因修复）
 *
 * 真机实锤（2026-11-11 晚）：荣耀省电策略把整个进程杀掉 → 前台服务与
 * 30 秒心跳一起死 → **没有任何代码还活着去发现开关被摘/服务被解绑** →
 * 用户下次开 App 看到广告不跳。用户点「重置状态」有效，正是因为重置
 * 触发摘除重绑、系统重新拉起进程——但这件事不该等用户手动做。
 *
 * 结构性修复：**进程每次被系统拉起，无论从哪条路**（无障碍绑定、
 * 前台服务重启、开机广播、用户打开 App），`Application.onCreate`
 * 都必然先执行。在这里挂一个**独立于前台服务的心跳**：
 *
 *  - 前台服务活着时：心跳与它并行（重复检查无害，有 FixGate 节流）；
 *  - 前台服务被杀、进程还在：应用级心跳继续兜底；
 *  - 进程整个被杀：系统因无障碍绑定重新拉起进程 → onCreate → 心跳复活。
 *
 * 心跳做的事（[AccessibilitySwitch.ensureBound]）：
 *  - 开关被摘 → 写回；
 *  - 开关在、服务没连上 → 摘除→等1秒→写回，强迫系统重绑。
 */
class App : Application() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        ServiceLog.initialize(this)
        ServiceLog.i("进程启动(App.onCreate)")

        if (!Prefs.isEnabled(this)) return

        // 首检立即执行一次（进程刚被拉起，最可能处于坏状态），
        // 之后每 [HEARTBEAT_INTERVAL_MS] 一次，永不停。
        handler.post(selfCheck)
    }

    private val selfCheck = object : Runnable {
        override fun run() {
            runCatching {
                AccessibilitySwitch.ensureBound(this@App, "应用心跳")
            }.onFailure { ServiceLog.w("应用心跳检查失败", it) }
            handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    companion object {
        /**
         * 应用级心跳间隔：比前台服务心跳（30s）更疏，作为**兜底层**。
         * 即使前台服务被杀，这条心跳也保证 ≤2 分钟内发现并修复坏状态。
         */
        private const val HEARTBEAT_INTERVAL_MS = 60_000L
    }
}
