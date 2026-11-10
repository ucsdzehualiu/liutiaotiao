package com.liutiaotiao

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 持久化运行日志（**落盘，跨重启保留**）。
 *
 * v1.8 之前所有诊断只在内存里，进程一死（系统杀、重启、崩溃）就全没了——
 * "重启后不跳了"这类问题永远查不到现场。现在把关键生命周期事件写进
 * `filesDir/logs/service.log`，识别记录页可以一键复制。
 *
 * 设计约束：
 *  - **只在主进程写**（前台服务与无障碍服务同进程）；
 *  - **追加写 + 环形截断**：超过 [MAX_BYTES] 就砍掉前半段，防止无限膨胀；
 *  - **绝不抛异常**：日志系统自己出问题不能影响主业务；
 *  - 无网络权限，数据只在本机。
 */
object ServiceLog {

    private const val TAG = "LiuTiaoTiao"

    /** 单文件上限（约 128KB，足够存几天的事件）。 */
    private const val MAX_BYTES = 128 * 1024

    /** 截断时保留的后半段大小。 */
    private const val KEEP_BYTES = 64 * 1024

    private const val MAX_LINE = 500

    @Volatile
    private var file: File? = null
    private val lock = Any()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun initialize(context: Context) {
        synchronized(lock) {
            if (file != null) return
            runCatching {
                val dir = File(context.filesDir, "logs")
                if (!dir.exists()) dir.mkdirs()
                file = File(dir, "service.log")
            }
        }
    }

    /** 记录一条事件。同时打到 logcat，方便 adb 在线排查。 */
    fun i(message: String) {
        Log.d(message)
        append("I", message)
    }

    fun w(message: String, tr: Throwable? = null) {
        val text = if (tr != null) "$message :: ${tr.javaClass.simpleName}: ${tr.message}" else message
        Log.w(text)
        append("W", text)
    }

    /** 崩溃专用：进程即将死亡，必须同步 flush 完成后再返回。 */
    fun crash(message: String, tr: Throwable) {
        val stack = tr.stackTraceToString()
        Log.w("$message\n$stack")
        append("E", "$message\n$stack")
    }

    private fun append(level: String, message: String) {
        val f = file ?: return
        runCatching {
            synchronized(lock) {
                val line = "${fmt.format(Date())} $level ${message.replace('\n', ' ')}\n"
                if (f.length() > MAX_BYTES) {
                    // 环形截断：保留较新的后半段。
                    val keep = f.readBytes().let { it.copyOfRange((it.size - KEEP_BYTES).coerceAtLeast(0), it.size) }
                    f.writeBytes(keep)
                }
                f.appendText(line)
            }
        }
    }

    /** 读取全部日志（识别记录页展示用）。 */
    fun dump(): String =
        runCatching {
            val f = file ?: return@runCatching ""
            val lines = f.readLines()
            if (lines.size <= MAX_LINE) lines.joinToString("\n")
            else lines.takeLast(MAX_LINE).joinToString("\n")
        }.getOrDefault("（日志读取失败）")

    fun clear() {
        runCatching {
            file?.delete()
            file?.createNewFile()
        }
    }

    private object Log {
        fun d(msg: String) = android.util.Log.i(TAG, msg)
        fun w(msg: String) = android.util.Log.w(TAG, msg)
    }
}
