package com.liutiaotiao

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 识别记录页。
 *
 * 展示服务最近几次开屏界面里**实际看到的控件**（文字、描述、viewId、坐标、
 * 是否可点击/可见）以及"为什么没点"。某个 App 跳不过去时，把这里的内容复制出来
 * 就能判断是：
 *   - 控件树里压根没有跳过按钮（SurfaceView/视频渲染，无障碍读不到）；
 *   - 有按钮但文字/ID 没匹配上（需要补规则）；
 *   - 匹配上了但被几何策略拒了（记录里会写明拒绝原因）。
 *
 * 数据只在内存里，不落盘、不联网。
 */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var dump: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)

        dump = findViewById(R.id.dump)

        findViewById<Button>(R.id.refresh).setOnClickListener { render() }
        findViewById<Button>(R.id.copyAll).setOnClickListener { copyAll() }
        findViewById<Button>(R.id.clearAll).setOnClickListener {
            Diagnostics.clear()
            ServiceLog.clear()
            render()
            Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
        }

        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        // v1.9：识别记录 + 持久化运行日志一起展示。
        // 运行日志跨重启保留（服务连接/断开/点击/修复动作），"重启后不跳了"
        // 这类问题靠它定位现场。
        dump.text = Diagnostics.dumpText() + "\n\n======== 运行日志（跨重启保留）========\n" + ServiceLog.dump()
    }

    private fun copyAll() {
        val text = Diagnostics.dumpText()
        val ok = runCatching {
            val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("刘跳跳识别记录", text))
            cm != null
        }.getOrDefault(false)
        Toast.makeText(
            this,
            if (ok) "已复制到剪贴板" else "复制失败，请长按文本手动选择复制",
            Toast.LENGTH_SHORT
        ).show()
    }
}
