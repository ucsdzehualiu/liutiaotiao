package com.liutiaotiao

import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * v1.18：1px 透明保活 overlay（GKD `KeepAliveOverlayCoordinator` 同款核心机制）。
 *
 * ## 为什么它能防杀（这是 GKD 在国产 ROM 上保活的看家本领）
 *
 * `TYPE_ACCESSIBILITY_OVERLAY` 是**无障碍服务专属**的窗口类型，不需要
 * 悬浮窗权限。把一个 1px、全透明、不可触摸的 View 挂上去后：
 *
 *  - 系统的省电策略（荣耀 PowerGenie / 各家 ROM 的后台查杀）对"正在
 *    渲染窗口"的进程有豁免——杀掉它会导致窗口泄漏，系统倾向绕开；
 *  - 与前台服务通知（可被用户划掉、ROM 可忽略）不同，overlay 是
 *    **窗口系统层面的存在**，查杀器必须特殊处理才能杀。
 *
 * GKD 源码（KeepAliveOverlayCoordinator）：服务连上挂 overlay，断开撤掉；
 * BadTokenException（系统还没就绪）时按 250/750/1500ms 重试。
 *
 * 风险与克制：1px 全透明 FLAG_NOT_TOUCHABLE，用户完全无感知；
 * 服务断开时必须撤掉，否则窗口残留。
 */
object KeepAliveOverlay {

    /** 挂载。返回 true=成功。 */
    fun attach(service: AdSkipService): Boolean {
        // 已挂过（幂等）。
        if (attachedView != null) return true
        return runCatching {
            val wm = service.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                alpha = 0f
                format = PixelFormat.TRANSPARENT
                flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                gravity = Gravity.START or Gravity.TOP
                width = 1
                height = 1
                packageName = service.packageName
            }
            val view = View(service)
            wm.addView(view, params)
            attachedView = view
            attachedWm = wm
            ServiceLog.i("保活overlay已挂载")
            true
        }.getOrElse {
            ServiceLog.w("保活overlay挂载失败(稍后重试)", it)
            false
        }
    }

    /** 撤掉（服务断开/销毁时必须调用，防窗口残留）。 */
    fun detach() {
        val view = attachedView ?: return
        runCatching {
            attachedWm?.removeView(view)
        }.onFailure { ServiceLog.w("保活overlay撤除失败", it) }
        attachedView = null
        attachedWm = null
    }

    fun isAttached(): Boolean = attachedView != null

    @Volatile
    private var attachedView: View? = null
    private var attachedWm: WindowManager? = null
}
