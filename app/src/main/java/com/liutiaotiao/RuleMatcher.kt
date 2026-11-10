package com.liutiaotiao

/**
 * 规则匹配的「纯逻辑」层。
 *
 * 这里刻意做到 **不依赖任何 Android 类**，因此可以放进 `src/test` 用 JVM 单元测试
 * 直接验证匹配/打分逻辑，不需要真机或模拟器。这是本项目的核心可测试点。
 */

/** 某个包名最终生效的规则集合。 */
data class RuleSet(
    val keywords: List<String>,
    val regexes: List<String> = emptyList(),
    val weights: Map<String, Int> = emptyMap(),
    val defaultWeight: Int = 20,
    /**
     * 控件 ID 规则，匹配 `viewIdResourceName`。
     *
     * 很多广告 SDK（穿山甲、优量汇等）的跳过按钮是**纯图片、没有任何文字**，
     * 只能靠 ID 命中。条目可以是完整 ID，也可以是片段（如 `splashskip`）。
     */
    val ids: List<String> = emptyList()
)

/** 一次匹配结果。 */
data class Match(val keyword: String, val score: Int)

/**
 * 完整规则目录（从 assets/rules.json 解析而来）。
 * [resolve] 负责把「默认规则 + 包名专属规则 + 用户自定义关键词」合成生效规则。
 */
data class RuleCatalog(
    val defaultKeywords: List<String>,
    val packageKeywords: Map<String, List<String>> = emptyMap(),
    val packageRegexes: Map<String, List<String>> = emptyMap(),
    val weights: Map<String, Int> = emptyMap(),
    val defaultWeight: Int = 20,
    val defaultIds: List<String> = emptyList(),
    val packageIds: Map<String, List<String>> = emptyMap()
) {
    fun resolve(pkg: String?, userExtra: List<String> = emptyList()): RuleSet {
        val kws = LinkedHashSet<String>()
        kws.addAll(defaultKeywords)
        if (pkg != null) packageKeywords[pkg]?.let { kws.addAll(it) }
        if (userExtra.isNotEmpty()) kws.addAll(userExtra)

        val rx = if (pkg != null) packageRegexes[pkg].orEmpty() else emptyList()

        val ids = LinkedHashSet<String>()
        ids.addAll(defaultIds)
        if (pkg != null) packageIds[pkg]?.let { ids.addAll(it) }

        return RuleSet(kws.toList(), rx, weights, defaultWeight, ids.toList())
    }
}

/**
 * 匹配方式。
 *
 * - [NONE]：不命中；
 * - [CONTAINS]：归一化后按钮文字里包含关键词（主路径，得分不打折）；
 * - [TOKENS]：关键词由「中文 + 数字/字母」多段组成，且各段都出现在按钮文字里，
 *   但顺序不同（例如关键词 `5跳过`，按钮文字是 `跳过5`）。这种命中会扣分，
 *   优先级低于 [CONTAINS]，避免误点。
 */
private enum class MatchMode { NONE, CONTAINS, TOKENS }

object RuleMatcher {

    /** 太长的文本不可能是按钮文字，直接跳过，降低误匹配。 */
    private const val MAX_TEXT_LENGTH = 30

    /**
     * 归一化后按钮文字的长度上限。
     *
     * 真正的跳过按钮都很短（`跳过广告 5`、`Skip Ad`、`点击跳过`）。把上限压到 12
     * 可以直接排掉「自动跳过片头片尾可在设置中关闭」这类正文说明——它们包含
     * 「跳过」，但绝不该被点。
     */
    private const val MAX_BUTTON_TEXT_LENGTH = 12

    /** 乱序（TOKENS）匹配只在短文本上启用，文本越长越可能是正文而非按钮。 */
    private const val MAX_TOKEN_MATCH_LENGTH = 12

    /** 乱序匹配的扣分，保证同权重下「正常顺序包含」永远优先。 */
    private const val TOKEN_MATCH_PENALTY = 6

    /** 控件 ID 命中的基础分。ID 是写死在 SDK 里的，比文字可靠，给高分。 */
    const val ID_MATCH_SCORE = 90

    /**
     * 归一化时会剔除的字符：空白 + 常见分隔/包裹符号。
     *
     * 注意：**故意不剔除** `× x X` 这类本身就是关闭按钮文字的字符，
     * 也不剔除数字与字母，否则会破坏 `5跳过` 这种带数字的关键词。
     */
    private val STRIPPED_CHARS = setOf(
        '\u00A0', '\u200B', '\u2007', '\u202F', // 各种不换行空格
        '(', ')', '（', '）', '[', ']', '【', '】', '{', '}',
        '<', '>', '《', '》', '〈', '〉',
        '|', '｜', '/', '\\', '·', '、',
        ',', '，', '.', '。', '!', '！', '?', '？',
        ':', '：', ';', '；', '-', '—', '–', '_', '+', '*',
        '~', '～', '\'', '"', '“', '”', '‘', '’'
    )

    /**
     * 负向词：只要按钮文字里出现其中任意一个，就**一票否决**，不管它是否同时
     * 包含「跳过」。
     *
     * 这是治「乱点乱跳」的关键。典型翻车场景：
     *   - 视频/音乐 App 设置页的「自动跳过片头片尾」开关；
     *   - 开屏后的系统弹窗「允许发送通知吗」，里面的按钮被当成跳过点掉；
     *   - 广告落地页的「立即下载」「立即体验」——点进去比不点更糟。
     */
    private val NEGATIVE_TOKENS = listOf(
        "片头", "片尾", "自动跳过", "默认跳过", "跳过片",
        "设置", "权限", "通知", "允许", "拒绝", "协议", "隐私",
        "评论", "分享", "收藏", "举报", "关注",
        "下载", "安装", "更新", "立即体验", "查看详情", "详情",
        "已开启", "已关闭", "开启后", "不再提示",
        "同意并", "不同意", "退出应用", "领取", "抽奖", "红包"
    ).map { normalize(it) }

    /**
     * 归一化：去掉所有空白与包裹符号，英文转小写。
     *
     * 这样 `5 跳过`、`5跳过`、`跳过 (5)` 之类的写法差异不再影响命中，
     * 用户填关键词时不用纠结空格和括号。控件 ID 也走同一套归一化，
     * 于是 `tt_splash_skip_btn` 与 `splashskip` 能对上。
     */
    fun normalize(raw: String): String {
        if (raw.isEmpty()) return raw
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            if (ch.isWhitespace()) continue
            if (ch in STRIPPED_CHARS) continue
            sb.append(ch.lowercaseChar())
        }
        return sb.toString()
    }

    /** 负向词一票否决。 */
    fun isRejectedText(normalizedText: String): Boolean =
        NEGATIVE_TOKENS.any { normalizedText.contains(it) }

    /**
     * 文本 + 控件 ID 综合匹配，结合控件是否可点击/可见算出候选分数；
     * 不命中返回 null。
     *
     * [viewId] 传 `viewIdResourceName`，可以为 null。文字为空（纯图片按钮）时
     * 仍然可能靠 ID 命中。
     */
    @JvmOverloads
    fun bestMatch(
        text: String,
        rules: RuleSet,
        clickable: Boolean,
        visible: Boolean,
        viewId: String? = null
    ): Match? = bestMatchFields(text, "", rules, clickable, visible, viewId)

    /**
     * 分别对 `text` 与 `contentDescription` 做匹配，取分数更高的一方。
     *
     * **为什么不把两者拼起来再匹配**（旧实现就是这么干的，是"偶现找不到"的
     * 主要来源之一）：
     *   - 拼接后长度很容易超过按钮上限。例如 text=`跳过`、desc=`点击跳过广告，
     *     进入应用`，归一化后 13 字，被 12 字的长度闸门直接拒掉；
     *   - desc 里常带"设置/通知/进入"等词，会触发负向词一票否决，把一个完全
     *     正常的跳过按钮否掉。
     *
     * 拆开各自匹配、取最优，两个问题都不存在。
     */
    @JvmOverloads
    fun bestMatchFields(
        text: String,
        desc: String,
        rules: RuleSet,
        clickable: Boolean,
        visible: Boolean,
        viewId: String? = null
    ): Match? {
        var bestKeyword: String? = null
        var bestScore = 0

        // ---- 1. 文字 / 内容描述，各自独立匹配 ----
        val byText = matchText(text, rules)
        if (byText != null && byText.score > bestScore) {
            bestScore = byText.score
            bestKeyword = byText.keyword
        }
        val byDesc = matchText(desc, rules)
        if (byDesc != null && byDesc.score > bestScore) {
            bestScore = byDesc.score
            bestKeyword = byDesc.keyword
        }

        // ---- 2. 控件 ID 匹配（纯图片按钮的唯一出路）----
        if (!viewId.isNullOrEmpty() && rules.ids.isNotEmpty()) {
            val hit = matchId(viewId, rules.ids)
            if (hit != null && ID_MATCH_SCORE > bestScore) {
                bestScore = ID_MATCH_SCORE
                bestKeyword = hit
            }
        }

        val keyword = bestKeyword ?: return null

        var score = bestScore
        if (clickable) score += 8
        if (visible) score += 4
        return Match(keyword, score)
    }

    /**
     * 单个文本字段的匹配，返回**基础权重分**（不含可点击/可见加成）。
     * 长度闸门、负向词黑名单、乱序兜底、正则补充都在这里。
     */
    private fun matchText(raw: String, rules: RuleSet): Match? {
        val t = raw.trim()
        if (t.isEmpty() || t.length > MAX_TEXT_LENGTH) return null

        val tNorm = normalize(t)
        if (tNorm.isEmpty() || tNorm.length > MAX_BUTTON_TEXT_LENGTH) return null
        if (isRejectedText(tNorm)) return null

        var bestKeyword: String? = null
        var bestScore = 0
        for (kw in rules.keywords) {
            if (kw.isEmpty()) continue
            val kwNorm = normalize(kw)
            if (kwNorm.isEmpty()) continue

            when (matchMode(tNorm, kwNorm)) {
                MatchMode.NONE -> Unit
                MatchMode.CONTAINS -> {
                    val w = rules.weights[kw] ?: rules.defaultWeight
                    if (w > bestScore) {
                        bestScore = w
                        bestKeyword = kw
                    }
                }

                MatchMode.TOKENS -> {
                    val w = (rules.weights[kw] ?: rules.defaultWeight) - TOKEN_MATCH_PENALTY
                    if (w > bestScore) {
                        bestScore = w
                        bestKeyword = kw
                    }
                }
            }
        }

        // 正则作为补充（例如某些 App 的按钮文字只显示纯倒计时数字）。
        if (bestKeyword == null) {
            for (rx in rules.regexes) {
                val ok = runCatching { Regex(rx).matches(t) }.getOrDefault(false)
                if (ok) return Match(t, 60)
            }
        }

        return if (bestKeyword == null) null else Match(bestKeyword, bestScore)
    }

    /**
     * 控件 ID 命中判断。
     *
     * 只看 `pkg:id/xxx` 里 `/` 之后的那一段，并做归一化（下划线会被去掉），
     * 所以 `tt_splash_skip_btn` 能被片段 `splashskip` 命中。
     *
     * 刻意**不使用**裸的 `skip` 作为片段：QQ音乐这类播放器的「下一首」控件
     * 常常叫 `skip_next`，用裸 skip 会误点切歌。
     */
    private fun matchId(viewId: String, ids: List<String>): String? {
        val segment = normalize(viewId.substringAfterLast('/', viewId))
        if (segment.isEmpty()) return null
        val full = normalize(viewId)

        for (entry in ids) {
            val e = normalize(entry)
            if (e.isEmpty()) continue
            if (segment == e || segment.contains(e) || full == e) return entry
        }
        return null
    }

    /** 判断归一化后的按钮文字 [tNorm] 与归一化后的关键词 [kwNorm] 的命中方式。 */
    private fun matchMode(tNorm: String, kwNorm: String): MatchMode {
        // 单字符关键词（如 `×`）要求整段文字完全相等。
        // 否则 `x` 会命中 `Xbox`、`5` 会命中 `5G网络`，误点灾难。
        if (kwNorm.length == 1) {
            return if (tNorm == kwNorm) MatchMode.CONTAINS else MatchMode.NONE
        }

        if (tNorm.contains(kwNorm)) return MatchMode.CONTAINS

        // 乱序兜底：只对「含数字/字母的多段关键词」开放。
        // 纯中文关键词（如 `关闭广告`）不做乱序匹配，否则 `广告已关闭`
        // 这类正文也会被点，误伤太大。
        if (kwNorm.length > 2 && tNorm.length <= MAX_TOKEN_MATCH_LENGTH) {
            val kwTokens = tokens(kwNorm)
            if (kwTokens.size >= 2) {
                val tTokens = tokens(tNorm).toHashSet()
                if (tTokens.containsAll(kwTokens)) return MatchMode.TOKENS
            }
        }
        return MatchMode.NONE
    }

    /**
     * 把归一化文本按字符类别切段：数字一段、英文字母一段、其它（中文等）一段。
     * 例：`跳过5s` -> [`跳过`, `5`, `s`]，`5s跳过` -> [`5`, `s`, `跳过`]。
     */
    private fun tokens(s: String): List<String> {
        val out = ArrayList<String>(3)
        val cur = StringBuilder()
        var lastCat = -1
        for (ch in s) {
            val cat = when {
                ch.isDigit() -> 0
                ch in 'a'..'z' -> 1
                else -> 2
            }
            if (cat != lastCat && cur.isNotEmpty()) {
                out.add(cur.toString())
                cur.setLength(0)
            }
            cur.append(ch)
            lastCat = cat
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }
}
