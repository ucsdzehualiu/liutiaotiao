package com.liutiaotiao

/**
 * 扫描范围过滤（纯逻辑，可 JVM 单测）。
 *
 * 为什么需要这一层：`TYPE_WINDOW_STATE_CHANGED` / `TYPE_WINDOWS_CHANGED` 不只
 * 在 App 开屏时触发——状态栏展开、输入法弹出、桌面滑动、权限弹窗都会触发。
 * 不加过滤的后果有两个，都已经在真机上抓到实据：
 *
 *  1. 每次输入法弹出都被当成"开屏"，开一个 6 秒扫描窗，服务在无关界面上乱找；
 *  2. 识别记录被 systemui / launcher / 输入法刷屏，真正想看的那个 App 的记录
 *     被挤出缓冲区。
 *
 * 更严重的是：`getWindows()` 会枚举**屏幕上所有窗口**，其中包含刘跳跳自己的
 * Activity。旧实现的自检只看事件所属包名（例如 `com.android.systemui`），
 * 放行之后遍历所有窗口，结果把首页那个「跳过 ×」关键词标签当成跳过按钮点了，
 * 直接删掉了用户的自定义关键词。所以**自检必须落到每个窗口的根节点包名上**。
 */
object ScanFilter {

    /** 永远不扫的系统包。 */
    val SYSTEM_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.android.launcher",
        "com.android.launcher3"
    )

    /** 包名前缀命中即视为系统/桌面/输入法宿主，不扫。 */
    val SYSTEM_PACKAGE_PREFIXES = listOf(
        "com.android.systemui",
        "com.android.launcher",
        "com.android.inputmethods",
        "com.hihonor.android.launcher", // 荣耀桌面真身（UniHomeLauncher，真机 resolve 验证）
        "com.huawei.android.launcher",
        "com.miui.home",
        "com.coloros.launcher",
        "com.vivo.launcher",
        "com.sec.android.app.launcher",
        "com.android.incallui",
        "com.android.server.telecom"
    )

    /**
     * 这个包是否应该被排除在扫描之外。
     *
     * @param selfPackage 本应用包名，**必须排除**——否则会点自己的 UI。
     * @param launcherPackage 当前默认桌面，排除（桌面图标文字里出现"跳过"会乱点）。
     * @param imePackages 已启用的输入法包名，排除（输入法弹出是最频繁的假开屏）。
     */
    fun isExcluded(
        pkg: String?,
        selfPackage: String?,
        launcherPackage: String?,
        imePackages: Set<String> = emptySet()
    ): Boolean {
        if (pkg.isNullOrEmpty()) return true // 包名不明，宁可不扫
        if (pkg == selfPackage) return true
        if (pkg in SYSTEM_PACKAGES) return true
        if (launcherPackage != null && launcherPackage.isNotEmpty() && pkg == launcherPackage) return true
        if (pkg in imePackages) return true
        return SYSTEM_PACKAGE_PREFIXES.any { pkg.startsWith(it) }
    }

    /**
     * `event.className` 是否明显不是 App 的业务界面。
     *
     * 输入法窗口、系统 ListView、SystemUI 面板都会以 STATE_CHANGED 的形式上报，
     * 它们的 className 有很稳定的特征。className 为空时不拦，交给包名过滤。
     */
    fun isNonActivityClass(className: String?): Boolean {
        val c = className?.trim().orEmpty()
        if (c.isEmpty()) return false
        if (c.contains("InputMethod")) return true
        if (c.contains("SoftInputWindow")) return true
        if (c.startsWith("android.inputmethodservice.")) return true
        if (c.startsWith("android.widget.")) return true
        if (c.startsWith("android.view.")) return true
        if (c.startsWith("com.android.systemui")) return true
        if (c.startsWith("android.app.Dialog") && c.contains("systemui")) return true
        return false
    }
}
