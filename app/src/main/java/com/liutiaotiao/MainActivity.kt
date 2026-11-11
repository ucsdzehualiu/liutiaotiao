package com.liutiaotiao

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 主界面。三件事：
 *  1. 展示无障碍服务的**真实**状态（系统开关是否打开 + 服务进程是否连着）；
 *  2. 标签式管理自定义关键词；
 *  3. 引导用户把应用加入电池优化白名单，减少服务被系统杀掉/关闭的概率。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var openSettings: Button
    private lateinit var enableSwitch: SwitchCompat
    private lateinit var topOnlySwitch: SwitchCompat
    private lateinit var ocrSwitch: SwitchCompat
    private lateinit var resetStateButton: Button
    private lateinit var keywordInput: EditText
    private lateinit var chipContainer: FlowLayout
    private lateinit var keywordCount: TextView
    private lateinit var batteryTip: TextView
    private lateinit var batteryButton: Button
    private lateinit var selfRepairTip: TextView
    private lateinit var copyAdbButton: Button

    private val handler = Handler(Looper.getMainLooper())

    private companion object {
        /** 通知权限的请求码。 */
        const val REQUEST_NOTIFICATIONS = 100
    }

    /**
     * 状态每秒刷新一次。
     *
     * 服务被系统悄悄关掉时，用户如果一直停留在本页，必须能立刻看到，
     * 而不是等下次打开 App 才发现"怎么又不跳广告了"。
     */
    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateStatus()
            updateBatterySection()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServiceLog.initialize(this)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        openSettings = findViewById(R.id.openSettings)
        enableSwitch = findViewById(R.id.enableSwitch)
        topOnlySwitch = findViewById(R.id.topOnlySwitch)
        ocrSwitch = findViewById(R.id.ocrSwitch)
        resetStateButton = findViewById(R.id.resetStateButton)
        keywordInput = findViewById(R.id.keywordInput)
        chipContainer = findViewById(R.id.keywordChips)
        keywordCount = findViewById(R.id.keywordCount)
        batteryTip = findViewById(R.id.batteryTip)
        batteryButton = findViewById(R.id.batteryButton)
        selfRepairTip = findViewById(R.id.selfRepairTip)
        copyAdbButton = findViewById(R.id.copyAdbCommand)

        copyAdbButton.setOnClickListener {
            val cmd = AccessibilitySwitch.adbGrantCommand(this)
            val cm = runCatching {
                getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            }.getOrNull()
            runCatching {
                cm?.setPrimaryClip(
                    android.content.ClipData.newPlainText("adb", cmd)
                )
            }
            toast("已复制。在电脑上连着手机执行一次即可，永久生效（重装/恢复出厂后需重新授权）")
        }

        enableSwitch.isChecked = Prefs.isEnabled(this)
        enableSwitch.setOnCheckedChangeListener { _, value ->
            Prefs.setEnabled(this, value)
            // 手动打开总开关时，若服务开关被系统关掉了，顺手自修复。
            if (value) AccessibilitySwitch.ensureRegistered(this)
        }

        topOnlySwitch.isChecked = Prefs.isTopOnly(this)
        topOnlySwitch.setOnCheckedChangeListener { _, value -> Prefs.setTopOnly(this, value) }

        // v1.14：OCR 兜底开关（渲染盲区广告的截图识别）。
        ocrSwitch.isChecked = Prefs.isOcrEnabled(this)
        ocrSwitch.setOnCheckedChangeListener { _, value -> Prefs.setOcrEnabled(this, value) }

        // v1.14：重置运行状态——清空识别记录/运行日志/会话状态并重绑服务，
        // 恢复到"刚安装"的样子；用户设置（开关/关键词）全部保留。
        resetStateButton.setOnClickListener {
            Prefs.resetRuntimeState(this)
            // 摘除再写回无障碍开关，强迫系统重新绑定服务进程（清空全部内存状态）。
            runCatching { AccessibilitySwitch.scheduleRebind(this, "用户重置状态") }
            toast("已重置运行状态，服务正在重新连接…")
            updateStatus()
        }

        findViewById<Button>(R.id.openDiagnostics).setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }

        findViewById<Button>(R.id.addKeyword).setOnClickListener { addKeyword() }

        // 输入法上的"完成"等同于点"添加"，少一次抬手。
        keywordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addKeyword()
                true
            } else {
                false
            }
        }

        openSettings.setOnClickListener { openAccessibilitySettings() }
        batteryButton.setOnClickListener { requestIgnoreBatteryOptimizations() }

        // 标题带上版本号：覆盖安装很容易搞混"现在跑的是哪个包"。
        findViewById<TextView>(R.id.appTitle).text =
            getString(R.string.app_name) + "  " + versionLabel()

        renderChips()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshRunnable)
        renderChips()
        // Android 13+ 通知权限：没它前台常驻通知显示不出来，进程保护形同虚设。
        requestNotificationPermissionIfNeeded()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    // ------------------------------------------------------------------
    // 服务状态
    // ------------------------------------------------------------------

    private fun updateStatus() {
        // 先检测+自修复（v1.9：ensureBound 能同时处理"开关被摘"和
        // "开关在、服务没连上"两种坏状态，后者通过摘除→写回强迫系统重绑）。
        val registered = AccessibilitySwitch.ensureBound(this, "首页刷新")
        val connected = AdSkipService.isConnected()

        val text: String
        val color: Int
        when {
            registered && connected -> {
                text = "✅ 服务运行中，正在为你跳过广告"
                color = Color.parseColor("#2E9E5B")
            }

            // 开关是开的、服务没连上：v1.9 已自动发起"摘除→写回"重绑修复，
            // 通常几秒内恢复。这里如实告诉用户正在修复，而不是让他反复关开开关。
            registered && !connected -> {
                text = if (AccessibilitySwitch.canSelfRepair(this)) {
                    "⚠️ 开关已开但服务未连接，正在自动修复（摘除重绑）…\n" +
                        "若 1 分钟后仍是此状态，请点下方按钮手动关开一次。"
                } else {
                    "⚠️ 系统开关是开的，但服务没连上\n" +
                        "请点下方按钮，把「刘跳跳」的开关关掉，再重新打开一次。"
                }
                color = Color.parseColor("#D98324")
            }

            else -> {
                text = if (AccessibilitySwitch.canSelfRepair(this)) {
                    "❌ 无障碍服务未开启（已尝试自动恢复失败，请手动开启一次）"
                } else {
                    "❌ 无障碍服务未开启\n请点下方按钮去系统设置里开启"
                }
                color = Color.parseColor("#D24B4B")
            }
        }
        status.text = text
        status.setTextColor(color)

        openSettings.text = when {
            registered && !connected -> "去系统设置：关掉开关再重新打开"
            registered -> "打开系统无障碍设置"
            else -> "① 去开启无障碍服务"
        }
    }

    /** 系统设置里是否登记了本服务（开关是否打开）。 */
    private fun isAccessibilityRegistered(): Boolean =
        AccessibilitySwitch.isRegistered(this)

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {
            // 某些定制系统没有该入口，退回应用详情页或总设置页。
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                } catch (_: Exception) {
                    toast("请手动到系统设置 → 无障碍 中开启")
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 通知权限（前台常驻通知依赖它，Android 13+ 需运行时请求）
    // ------------------------------------------------------------------

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            // 权限到手，立刻把前台通知挂上（服务若已连着）。
            AdSkipService.instance?.let { runCatching { ForegroundKeeperService.start(it) } }
        }
    }

    // ------------------------------------------------------------------
    // 电池优化白名单
    // ------------------------------------------------------------------

    private fun updateBatterySection() {
        val whitelisted = isIgnoringBatteryOptimizations()
        if (whitelisted) {
            batteryTip.text = "✅ 已加入电池优化白名单，系统不会为了省电杀掉服务。"
            batteryTip.setTextColor(Color.parseColor("#2E9E5B"))
            batteryButton.visibility = View.GONE
        } else {
            batteryTip.text = "⚠️ 还没加入电池优化白名单。\n" +
                "息屏一段时间后，系统可能杀掉后台，导致无障碍服务掉线甚至被自动关闭。\n" +
                "国产系统（MIUI / EMUI / ColorOS / OriginOS）还建议手动允许「自启动」，" +
                "并在最近任务里把本应用「锁定」。"
            batteryTip.setTextColor(Color.parseColor("#D98324"))
            batteryButton.visibility = View.VISIBLE
        }

        // 自修复授权状态：授权过 → 已就绪；没授权 → 展示指引。
        if (AccessibilitySwitch.canSelfRepair(this)) {
            selfRepairTip.text = "✅ 已授权自动恢复：无障碍开关被系统悄悄关掉时，" +
                "刘跳跳会立即自动把它重新打开，无需你手动处理。"
            selfRepairTip.setTextColor(Color.parseColor("#2E9E5B"))
            copyAdbButton.visibility = View.GONE
        } else {
            selfRepairTip.text = "⚠️ 未授权：部分国产系统会悄悄把无障碍开关关掉，" +
                "表现为「用着用着就不跳了」。在电脑上用 adb 执行一次下面的授权命令，" +
                "之后刘跳跳就能在被关掉时自动恢复。不授权也能用，只是需要手动重开。"
            selfRepairTip.setTextColor(Color.parseColor("#D98324"))
            copyAdbButton.visibility = View.VISIBLE
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean =
        runCatching {
            val pm = getSystemService(POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(packageName) ?: true
        }.getOrDefault(true)

    private fun requestIgnoreBatteryOptimizations() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
            return
        } catch (_: Exception) {
            // 落到通用列表页
        }
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
            toast("请手动到 系统设置 → 电池 中把本应用设为「不优化」")
        }
    }

    // ------------------------------------------------------------------
    // 关键词标签
    // ------------------------------------------------------------------

    private fun addKeyword() {
        val raw = keywordInput.text.toString()
        when (Prefs.addExtraKeyword(this, raw)) {
            Prefs.AddResult.OK -> {
                keywordInput.setText("")
                renderChips()
                toast("已添加")
                hideKeyboard()
            }

            Prefs.AddResult.EMPTY -> toast("请先输入关键词")
            Prefs.AddResult.TOO_LONG ->
                toast("太长了，单个关键词最多 ${Prefs.MAX_KEYWORD_LENGTH} 个字")

            Prefs.AddResult.DUPLICATE -> toast("这个关键词已经添加过了")
            Prefs.AddResult.LIMIT -> toast("最多只能添加 ${Prefs.MAX_KEYWORDS} 个关键词")
        }
    }

    private fun renderChips() {
        chipContainer.removeAllViews()
        val list = Prefs.extraKeywords(this)
        if (list.isEmpty()) {
            keywordCount.text = "还没有自定义关键词，内置规则已能覆盖大多数开屏广告。"
            return
        }
        for (kw in list) {
            chipContainer.addView(buildChip(kw))
        }
        keywordCount.text = "已添加 ${list.size} / ${Prefs.MAX_KEYWORDS} 个"
    }

    private fun buildChip(keyword: String): TextView {
        val accent = resolveAccentColor()
        val chip = TextView(this).apply {
            text = "$keyword   ×"
            textSize = 14f
            setTextColor(accent)
            setPadding(dp(12f), dp(7f), dp(12f), dp(7f))
            background = GradientDrawable().apply {
                cornerRadius = dp(15f).toFloat()
                setColor(withAlpha(accent, 0.14f))
                setStroke(dp(1f), withAlpha(accent, 0.45f))
            }
            isClickable = true
            isFocusable = true
            contentDescription = "删除关键词 $keyword"
            setOnClickListener {
                Prefs.removeExtraKeyword(this@MainActivity, keyword)
                renderChips()
                toast("已删除「$keyword」")
            }
        }
        return chip
    }

    private fun resolveAccentColor(): Int {
        val fallback = Color.parseColor("#4A7DFF")
        return runCatching {
            val tv = TypedValue()
            val ok = theme.resolveAttribute(android.R.attr.colorAccent, tv, true)
            if (ok && tv.data != 0) tv.data else fallback
        }.getOrDefault(fallback)
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb(
            (alpha * 255).toInt().coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(keywordInput.windowToken, 0)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    @Suppress("DEPRECATION")
    private fun versionLabel(): String =
        runCatching {
            "v" + (packageManager.getPackageInfo(packageName, 0).versionName ?: "?")
        }.getOrDefault("")

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
