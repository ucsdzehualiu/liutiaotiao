package com.liutiaotiao

import android.content.Context
import org.json.JSONArray

/**
 * 极简本地设置，只存在手机本地，不联网。
 *
 * 关键词存储从「一个用逗号/空格分隔的长字符串」改成了 **JSON 数组**：
 *   - 旧实现把空格也当分隔符，用户填 `5 跳过` 会被切成 `5` 和 `跳过` 两个词，
 *     结果既匹配不到按钮，又会拿单个数字到处误匹配；
 *   - 现在一个关键词就是一条完整记录，中间有没有空格都不影响。
 *
 * 读取时会自动把旧格式迁移过来（迁移只发生一次），老用户升级不会丢配置。
 */
object Prefs {

    private const val NAME = "liutiaotiao_prefs"
    private const val KEY_ENABLED = "enabled"

    /** 新格式：JSON 字符串数组。 */
    private const val KEY_EXTRA = "extra_keywords_v2"

    /** 旧格式：分隔符拼接的字符串，仅用于一次性迁移。 */
    private const val KEY_EXTRA_LEGACY = "extra_keywords"

    /** 只在屏幕上方区域找跳过按钮（默认开，是防误点的主要手段之一）。 */
    private const val KEY_TOP_ONLY = "top_only"

    /** v1.14：OCR 兜底开关（SurfaceView/自绘广告的截图识别，默认开）。 */
    private const val KEY_OCR_ENABLED = "ocr_enabled"

    /** v1.14：运行状态版本号——「重置状态」用，与用户设置分开存。 */
    private const val KEY_STATE_VERSION = "state_version"

    /** 自定义关键词数量上限，避免规则集无限膨胀拖慢扫描。 */
    const val MAX_KEYWORDS = 50

    /** 单个关键词长度上限：按钮文字不可能太长。 */
    const val MAX_KEYWORD_LENGTH = 20

    /** 添加关键词的结果，供 UI 给出准确提示。 */
    enum class AddResult { OK, EMPTY, TOO_LONG, DUPLICATE, LIMIT }

    private fun sp(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 总开关：关闭后服务即使在线也不会去点击任何东西。 */
    fun isEnabled(c: Context): Boolean = sp(c).getBoolean(KEY_ENABLED, true)

    fun setEnabled(c: Context, value: Boolean) {
        sp(c).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    /**
     * 是否只在屏幕上方区域识别跳过按钮。
     *
     * 默认开启：开屏广告的跳过按钮几乎都在上半部分，限制区域能挡掉信息流里
     * 出现「跳过」字样、设置页里的「自动跳过片头片尾」等绝大多数误点。
     * 极少数把按钮放在底部的 App 才需要关掉它。
     */
    fun isTopOnly(c: Context): Boolean = sp(c).getBoolean(KEY_TOP_ONLY, true)

    fun setTopOnly(c: Context, value: Boolean) {
        sp(c).edit().putBoolean(KEY_TOP_ONLY, value).apply()
    }

    /**
     * v1.14：OCR 兜底是否启用（默认开）。
     *
     * OCR 只在控件树一无所获时截图识别（渲染盲区广告），每次会话最多 2 次，
     * 耗电可控；但用户若担心隐私/耗电可关闭——关闭后渲染盲区广告回到
     * "跳不了"状态，控件树广告不受影响。
     */
    fun isOcrEnabled(c: Context): Boolean = sp(c).getBoolean(KEY_OCR_ENABLED, true)

    fun setOcrEnabled(c: Context, value: Boolean) {
        sp(c).edit().putBoolean(KEY_OCR_ENABLED, value).apply()
    }

    /**
     * v1.14：「重置运行状态」——把服务侧全部运行时状态清空，恢复到
     * **刚安装**的样子（相当于刚装好第一次连上服务），但**保留用户设置**
     * （总开关/关键词/仅上方区域/OCR 开关都不动）。
     *
     * 使用场景：服务状态被某种未知原因弄脏（会话卡死/预算异常/开关状态
     * 错乱）时的"一键重启"。实现：
     *  1. 清空内存会话状态（通过重连服务触发）；
     *  2. 清空识别记录与运行日志；
     *  3. 摘除再写回无障碍开关（触发系统重新绑定服务进程）。
     */
    fun resetRuntimeState(c: Context) {
        Diagnostics.clear()
        ServiceLog.clear()
        ServiceLog.i("运行状态已重置（用户设置保留）")
        sp(c).edit().putInt(KEY_STATE_VERSION, currentVersion(c) + 1).apply()
    }

    /** 运行状态版本号（每次重置 +1，服务侧可感知）。 */
    fun currentVersion(c: Context): Int = sp(c).getInt(KEY_STATE_VERSION, 0)

    // ------------------------------------------------------------------
    // 自定义关键词
    // ------------------------------------------------------------------

    /**
     * 内存缓存：无障碍服务每次扫描都会读关键词，不能每次都解析一遍 JSON。
     * 只在写入时失效，单进程使用，安全。
     */
    @Volatile
    private var extraCache: List<String>? = null

    /** 用户自定义追加的关键词，按添加顺序返回。 */
    fun extraKeywords(c: Context): List<String> {
        extraCache?.let { return it }

        val prefs = sp(c)
        prefs.getString(KEY_EXTRA, null)?.let {
            val list = decode(it)
            extraCache = list
            return list
        }

        // 首次读取且没有新格式：把旧格式迁移过来。
        val legacy = prefs.getString(KEY_EXTRA_LEGACY, "").orEmpty()
        val migrated = sanitize(splitLegacy(legacy))
        prefs.edit()
            .putString(KEY_EXTRA, encode(migrated))
            .remove(KEY_EXTRA_LEGACY)
            .apply()
        extraCache = migrated
        return migrated
    }

    /** 整体覆盖保存。 */
    fun setExtraKeywords(c: Context, list: List<String>) {
        val cleaned = sanitize(list)
        sp(c).edit().putString(KEY_EXTRA, encode(cleaned)).apply()
        extraCache = cleaned
    }

    /**
     * 追加一个关键词。返回 [AddResult] 让 UI 能告诉用户到底发生了什么，
     * 而不是静默失败。
     */
    fun addExtraKeyword(c: Context, raw: String): AddResult {
        val kw = raw.trim()
        if (kw.isEmpty()) return AddResult.EMPTY
        if (kw.length > MAX_KEYWORD_LENGTH) return AddResult.TOO_LONG

        val current = extraKeywords(c)
        if (current.size >= MAX_KEYWORDS) return AddResult.LIMIT

        val kwNorm = RuleMatcher.normalize(kw)
        if (kwNorm.isEmpty()) return AddResult.EMPTY
        if (current.any { RuleMatcher.normalize(it) == kwNorm }) return AddResult.DUPLICATE

        val next = ArrayList<String>(current.size + 1)
        next.addAll(current)
        next.add(kw)
        setExtraKeywords(c, next)
        return AddResult.OK
    }

    /** 删除一个关键词（按归一化形式比对，容忍用户手输的空格差异）。 */
    fun removeExtraKeyword(c: Context, keyword: String) {
        val target = RuleMatcher.normalize(keyword)
        val next = extraKeywords(c).filter { RuleMatcher.normalize(it) != target }
        setExtraKeywords(c, next)
    }

    // ------------------------------------------------------------------
    // 序列化 / 清洗
    // ------------------------------------------------------------------

    private fun encode(list: List<String>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        return arr.toString()
    }

    private fun decode(json: String): List<String> {
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "").trim()
                if (s.isNotEmpty()) out.add(s)
            }
            sanitize(out)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 旧格式拆分。**注意这里不再按空格拆分**：旧版把 `5 跳过` 切成两段是 bug，
     * 迁移时按原样保留整段，才能还原用户真正想填的关键词。
     */
    private fun splitLegacy(raw: String): List<String> =
        raw.split(',', '，', ';', '；', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** 去空、去超长、按归一化形式去重、截断到上限。 */
    private fun sanitize(list: List<String>): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>(list.size)
        for (raw in list) {
            val kw = raw.trim()
            if (kw.isEmpty() || kw.length > MAX_KEYWORD_LENGTH) continue
            val norm = RuleMatcher.normalize(kw)
            if (norm.isEmpty() || !seen.add(norm)) continue
            out.add(kw)
            if (out.size >= MAX_KEYWORDS) break
        }
        return out
    }
}
