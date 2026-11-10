package com.liutiaotiao

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫描范围过滤的回归测试。
 *
 * 每一条都对应真机上抓到的一次事故：
 *   - 服务把刘跳跳自己首页的「跳过 ×」关键词标签当成跳过按钮点掉，
 *     删掉了用户的自定义关键词（事件包名是 systemui，自检形同虚设）；
 *   - 输入法弹出、状态栏变化被当成开屏，8 条识别记录全被系统界面占满，
 *     真正想看的 QQ音乐/贴吧 记录被挤出缓冲区。
 */
class ScanFilterTest {

    private val self = "com.liutiaotiao"
    private val launcher = "com.hihonor.android.launcher"
    private val imes = setOf("com.bytedance.android.doubaoime", "com.baidu.input")

    private fun excluded(pkg: String?) =
        ScanFilter.isExcluded(pkg, self, launcher, imes)

    // ------------------------------------------------------------------
    // 必须排除
    // ------------------------------------------------------------------

    @Test
    fun `own package is always excluded`() {
        // 真机事故：点掉了自己首页的关键词标签。
        assertTrue(excluded(self))
    }

    @Test
    fun `system ui is excluded`() {
        assertTrue(excluded("com.android.systemui"))
        assertTrue(excluded("com.android.systemui.volume.VolumeDialogImpl"))
    }

    @Test
    fun `launcher is excluded`() {
        assertTrue(excluded(launcher))
        assertTrue(excluded("com.miui.home"))
        assertTrue(excluded("com.huawei.android.launcher"))
        assertTrue(excluded("com.android.launcher3"))
    }

    @Test
    fun `input methods are excluded`() {
        assertTrue(excluded("com.bytedance.android.doubaoime"))
        assertTrue(excluded("com.baidu.input"))
    }

    @Test
    fun `settings and permission dialogs are excluded`() {
        assertTrue(excluded("com.android.settings"))
        assertTrue(excluded("com.android.permissioncontroller"))
        assertTrue(excluded("android"))
    }

    @Test
    fun `unknown package is excluded`() {
        // 包名不明时宁可不扫，也不能乱点。
        assertTrue(excluded(null))
        assertTrue(excluded(""))
    }

    // ------------------------------------------------------------------
    // 必须放行
    // ------------------------------------------------------------------

    @Test
    fun `real apps are not excluded`() {
        assertFalse(excluded("com.tencent.qqmusic"))
        assertFalse(excluded("com.baidu.tieba"))
        assertFalse(excluded("com.ss.android.ugc.aweme"))
        assertFalse(excluded("com.tencent.mm"))
        assertFalse(excluded("com.taobao.taobao"))
    }

    @Test
    fun `empty launcher does not block anything`() {
        // 桌面解析失败时返回空串，不能把所有包都当成桌面排除掉。
        assertFalse(ScanFilter.isExcluded("com.tencent.qqmusic", self, "", imes))
    }

    // ------------------------------------------------------------------
    // className 过滤
    // ------------------------------------------------------------------

    @Test
    fun `input method window class is rejected`() {
        // 真机记录里输入法上报的就是这个 className。
        assertTrue(ScanFilter.isNonActivityClass("android.inputmethodservice.SoftInputWindow"))
        assertTrue(ScanFilter.isNonActivityClass("com.google.android.inputmethod.latin.InputMethodImpl"))
    }

    @Test
    fun `system widget and systemui classes are rejected`() {
        // 真机记录里 launcher 上报的 className 就是 android.widget.ListView。
        assertTrue(ScanFilter.isNonActivityClass("android.widget.ListView"))
        assertTrue(ScanFilter.isNonActivityClass("android.view.VolumePanel"))
        assertTrue(ScanFilter.isNonActivityClass("com.android.systemui qs.frame"))
    }

    @Test
    fun `real splash activities are accepted`() {
        assertFalse(ScanFilter.isNonActivityClass("com.tencent.qqmusic.activity.SplashActivity"))
        assertFalse(ScanFilter.isNonActivityClass("com.baidu.tieba.LogoActivity"))
        assertFalse(ScanFilter.isNonActivityClass("com.ss.android.ugc.aweme.splash.SplashActivity"))
        // className 为空时不拦，交给包名过滤。
        assertFalse(ScanFilter.isNonActivityClass(""))
        assertFalse(ScanFilter.isNonActivityClass(null))
    }
}
