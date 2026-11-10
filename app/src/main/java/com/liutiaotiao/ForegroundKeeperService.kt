package com.liutiaotiao

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * 前台常驻服务：只挂一条"刘跳跳运行中"的状态通知，**不执行任何业务逻辑**。
 *
 * 为什么需要它：国产 ROM 的省电策略会把"没有前台通知"的进程划入待杀名单。
 * 无障碍服务进程被杀后，系统设置里的开关仍然亮着，但事件再也不会送来——
 * 表现正是用户反馈的「装好第一次能跳，App 最小化/关闭放一会儿再打开，
 * 广告弹出来却不跳了，时好时坏」。挂上前台通知后：
 *  - 进程被杀概率大幅下降（用户能看见它在跑，系统也把它当活跃进程对待）；
 *  - 就算被杀，通知一消失用户立刻能发现，而不是纳闷"怎么又不跳了"。
 *
 * 生命周期由 [AdSkipService] 控制：无障碍服务连上时启动它，断开/销毁时停掉它，
 * 保证通知与真实服务状态一致。通知上的"停止服务"按钮 = 关掉总开关并停前台。
 *
 * 无网络权限、无任何数据采集，通知内容固定，可逐行审计。
 */
class ForegroundKeeperService : Service() {

    companion object {
        private const val CHANNEL_ID = "liutiaotiao_status"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.liutiaotiao.action.STOP_SERVICE"

        /** 自愈心跳间隔：足够快（半分钟内恢复），又足够疏（不耗电）。 */
        private const val SELF_HEAL_INTERVAL_MS = 30_000L

        /** 启动前台常驻通知（幂等：重复调用只更新通知，不会叠加）。 */
        fun start(context: Context) {
            val intent = Intent(context, ForegroundKeeperService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 停掉前台常驻通知。 */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ForegroundKeeperService::class.java)) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ServiceLog.initialize(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService 的契约：**必须**先 startForeground 再考虑停止，
        // 否则系统会抛 "did not then call Service.startForeground" 崩溃。
        // 所以这里无条件先挂通知，需要停的场景再立刻撤掉。
        //
        // v1.8 关键教训：startForeground 本身也可能抛 SecurityException（v1.7 就是
        // 因为缺 FOREGROUND_SERVICE_SPECIAL_USE 权限，在 targetSdk 34 的真机上
        // 一挂通知就崩，把整个无障碍服务进程带崩，系统随即将无障碍开关标记为
        // crashed 并禁用——表现为「装上第一次都跳不了」）。这里必须兜住一切
        // 异常并立刻 stopSelf：服务停了，系统就不会再追究"没挂上通知"，
        // 无障碍服务进程得以存活。前台通知只是锦上添花，绝不允许它弄死主业。
        val notification = buildNotification()
        val fgOk = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.isSuccess
        if (!fgOk) {
            ServiceLog.w("前台通知挂载失败(已放弃，不影响无障碍服务)")
            stopSelf()
            return START_NOT_STICKY
        }

        val isStopAction = intent?.action == ACTION_STOP

        // 两种情况要停：用户点了通知上的"停止服务"；或服务被系统重启（START_STICKY）
        // 但总开关已被关掉。停之前先把刚挂上的通知撤干净。
        if (isStopAction || !Prefs.isEnabled(this)) {
            if (isStopAction) {
                runCatching { Prefs.setEnabled(this, false) }
                AdSkipService.instance?.let { runCatching { it.disableSelf() } }
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // v1.8：常驻自愈心跳。国产 ROM 会在应用不在前台时悄悄把无障碍开关从
        // 系统设置里摘掉（开关灭了服务就断，用户毫无感知，只觉得"又不跳了"）。
        // 只要本前台服务还活着（START_STICKY + 电池白名单），每 30 秒检查一次
        // 开关状态，被摘掉且已 adb 授权时立即写回。这是「后面一直保持」的关键。
        // v1.9：心跳从 ensureRegistered 升级为 ensureBound——除了"开关被摘"，
        // 还能检测并修复"开关在、服务没连上"（重启/重授权后失效的主因，
        // 通过摘除→等1秒→写回强迫系统重新绑定）。
        handler.removeCallbacks(selfHealRunnable)
        handler.postDelayed(selfHealRunnable, SELF_HEAL_INTERVAL_MS)

        return START_STICKY
    }

    private val handler = Handler(Looper.getMainLooper())

    private val selfHealRunnable = object : Runnable {
        override fun run() {
            if (!Prefs.isEnabled(this@ForegroundKeeperService)) return
            runCatching {
                AccessibilitySwitch.ensureBound(this@ForegroundKeeperService, "心跳")
            }.onFailure { ServiceLog.w("心跳健康检查失败", it) }
            handler.postDelayed(this, SELF_HEAL_INTERVAL_MS)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_MIN // 不响铃不弹横幅，只挂在通知栏
            ).apply {
                setShowBadge(false)
                description = getString(R.string.notification_text)
            }
            nm.createNotificationChannel(channel)
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ForegroundKeeperService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_MIN)
            .addAction(0, getString(R.string.notification_stop), stopIntent)

        // API 29+ 才有此方法；低版本调用会 NoSuchMethodError。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }
}
