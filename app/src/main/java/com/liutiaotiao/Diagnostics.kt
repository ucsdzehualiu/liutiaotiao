package com.liutiaotiao

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 识别记录（诊断）。
 *
 * 用途：当某个 App 的开屏广告"点不到"时，靠猜是修不好的——必须知道那一刻
 * 控件树里到底有什么。本对象把最近几次开屏界面里看到的控件（文字、描述、
 * viewId、坐标、是否可点击/可见）连同"为什么没点"一起记下来，在 App 内的
 * 「识别记录」页可以查看和一键复制。
 *
 * 数据只存在内存里，进程结束即消失，不落盘、不联网。
 */
object Diagnostics {

    /** 一个被记录下来的控件。 */
    data class NodeInfo(
        val text: String,
        val desc: String,
        val viewId: String,
        val bounds: String,
        val clickable: Boolean,
        val visible: Boolean,
        /** 匹配结论，例如 `命中「跳过」67分` 或 `拒绝：不在屏幕上方区域`。 */
        val note: String
    )

    /** 一次开屏界面的完整记录。 */
    data class Record(
        val time: Long,
        val pkg: String,
        val activity: String,
        val screen: String,
        val topOnly: Boolean,
        val nodes: List<NodeInfo>,
        val outcome: String
    )

    /** 最多保留几次界面记录。只有真正的 App 开屏才会记录，不会被系统界面刷掉。 */
    private const val MAX_RECORDS = 12

    /** 单次界面最多记录多少个控件，避免信息流页面把记录撑爆。 */
    private const val MAX_NODES = 40

    /** 文字/描述在记录里的截断长度。 */
    private const val MAX_TEXT = 24

    private val records = ArrayList<Record>()
    private var pending: Pending? = null

    private class Pending(
        val time: Long,
        val pkg: String,
        val activity: String,
        val screen: String,
        val topOnly: Boolean
    ) {
        val nodes = LinkedHashMap<String, NodeInfo>()
        var outcome: String = "未发现可点击的跳过按钮"
    }

    /** 新的开屏界面开始：先把上一次的记录归档。 */
    @Synchronized
    fun begin(pkg: String, activity: String, screenWidth: Int, screenHeight: Int, topOnly: Boolean) {
        flush()
        pending = Pending(
            time = System.currentTimeMillis(),
            pkg = pkg,
            activity = activity,
            screen = "${screenWidth}x${screenHeight}",
            topOnly = topOnly
        )
    }

    /**
     * 记录一个控件。按 `bounds|viewId|text` 去重，同一个控件被多次扫到只留一条，
     * 但结论（note）会被更新成最新的一次。
     */
    @Synchronized
    fun addNode(
        text: String,
        desc: String,
        viewId: String,
        bounds: String,
        clickable: Boolean,
        visible: Boolean,
        note: String
    ) {
        val p = pending ?: return
        if (p.nodes.size >= MAX_NODES && note.isEmpty()) return

        val t = truncate(text)
        val d = truncate(desc)
        val key = "$bounds|${viewId}|$t"
        val existing = p.nodes[key]
        // 已经有明确结论的不要被空结论覆盖。
        val finalNote = when {
            note.isNotEmpty() -> note
            existing != null -> existing.note
            else -> ""
        }
        p.nodes[key] = NodeInfo(t, d, viewId, bounds, clickable, visible, finalNote)
    }

    /** 设置本次界面的结论，例如"已点击跳过：xxx"。 */
    @Synchronized
    fun setOutcome(outcome: String) {
        pending?.outcome = outcome
    }

    /** 归档当前记录（服务断开、界面销毁时调用）。 */
    @Synchronized
    fun flush() {
        val p = pending ?: return
        pending = null
        records.add(
            0,
            Record(p.time, p.pkg, p.activity, p.screen, p.topOnly, p.nodes.values.toList(), p.outcome)
        )
        while (records.size > MAX_RECORDS) {
            records.removeAt(records.size - 1)
        }
    }

    /**
     * 取快照。**不会**打断当前正在采集的界面：把未归档的 pending 也当成一条
     * 记录返回，这样用户打开「识别记录」页时，服务仍能继续往里写。
     */
    @Synchronized
    fun snapshot(): List<Record> {
        val out = ArrayList<Record>(records.size + 1)
        pending?.let { p ->
            out.add(
                Record(
                    p.time, p.pkg, p.activity, p.screen, p.topOnly,
                    p.nodes.values.toList(), p.outcome
                )
            )
        }
        out.addAll(records)
        return out
    }

    @Synchronized
    fun clear() {
        pending = null
        records.clear()
    }

    /** 生成可复制的纯文本，方便贴出来排查。 */
    fun dumpText(): String {
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        val list = snapshot()
        val sb = StringBuilder()
        sb.append("刘跳跳 识别记录（仅本地内存，不落盘）\n")
        if (list.isEmpty()) {
            sb.append("\n（暂无记录。请先打开一个有开屏广告的 App，再回到这里查看）\n")
            return sb.toString()
        }
        for (r in list) {
            sb.append('\n')
            sb.append("== ${timeFmt.format(Date(r.time))} ==\n")
            sb.append("应用: ${r.pkg}\n")
            sb.append("界面: ${r.activity}\n")
            sb.append("屏幕: ${r.screen}  仅上方区域: ${if (r.topOnly) "开" else "关"}\n")
            sb.append("结论: ${r.outcome}\n")
            if (r.nodes.isEmpty()) {
                sb.append("（这一屏没有采集到任何带文字或可点击的控件——")
                sb.append("如果广告确实显示了，说明它是 SurfaceView/视频渲染，无障碍读不到）\n")
            } else {
                sb.append("控件(${r.nodes.size}):\n")
                for (n in r.nodes) {
                    sb.append("  - ")
                    if (n.text.isNotEmpty()) sb.append("text=\"${n.text}\" ")
                    if (n.desc.isNotEmpty()) sb.append("desc=\"${n.desc}\" ")
                    if (n.viewId.isNotEmpty()) sb.append("id=${n.viewId} ")
                    sb.append("[${n.bounds}]")
                    sb.append(if (n.clickable) " 可点" else " 不可点")
                    if (!n.visible) sb.append(" 不可见")
                    if (n.note.isNotEmpty()) sb.append(" → ${n.note}")
                    sb.append('\n')
                }
            }
        }
        return sb.toString()
    }

    private fun truncate(s: String): String {
        val t = s.replace('\n', ' ').trim()
        return if (t.length <= MAX_TEXT) t else t.substring(0, MAX_TEXT) + "…"
    }
}
