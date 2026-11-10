package com.liutiaotiao

import android.content.Context
import org.json.JSONObject

/**
 * 从 `assets/rules.json` 加载规则目录（数据驱动，规则与代码分离）。
 *
 * - 解析失败或文件缺失时回退到内置的 [FALLBACK] 规则，保证服务不会因规则问题崩溃。
 * - 进程内单例缓存，避免每次扫描都读文件。
 */
class RuleRepository private constructor(val catalog: RuleCatalog) {

    fun resolve(pkg: String?, userExtra: List<String>): RuleSet = catalog.resolve(pkg, userExtra)

    companion object {

        /**
         * 兜底规则：即使 rules.json 出问题也能工作。
         *
         * 关于 `defaultIds`：穿山甲、优量汇这类广告 SDK 的跳过按钮经常是**纯图片、
         * 没有文字**，只能靠 `viewIdResourceName` 命中。这里配的是「ID 片段」，
         * 匹配时会取 `pkg:id/xxx` 里 `/` 之后的部分做归一化包含判断，
         * 所以 `tt_splash_skip_btn` 能被 `splashskip` 命中。
         *
         * 刻意**不放**裸的 `skip`：QQ音乐这类播放器的「下一首」控件常叫
         * `skip_next`，用裸 skip 会误点切歌。
         */
        val FALLBACK = RuleCatalog(
            defaultKeywords = listOf(
                "跳过广告", "跳过此广告", "点击跳过", "关闭广告", "广告跳过",
                "跳过", "Skip Ad", "Skip", "×", "✕", "✖"
            ),
            weights = mapOf(
                "跳过广告" to 70, "跳过此广告" to 70, "关闭广告" to 70, "广告跳过" to 70,
                "点击跳过" to 65, "跳过" to 58,
                "Skip Ad" to 55, "Skip" to 35,
                "关闭" to 30, "×" to 15, "✕" to 15, "✖" to 15
            ),
            defaultIds = listOf(
                "splashskip", "skipad", "adskip", "skipbtn", "btnskip",
                "skipview", "skipbutton", "buttonskip", "splashjump",
                "splashclose", "jumpbtn", "mjumpbtn", "closead", "adclose"
            )
        )

        @Volatile
        private var cached: RuleRepository? = null

        fun get(context: Context): RuleRepository {
            cached?.let { return it }
            synchronized(this) {
                cached?.let { return it }
                val repo = RuleRepository(load(context.applicationContext))
                cached = repo
                return repo
            }
        }

        /** 规则变化后（例如未来支持导入规则文件）可强制重新加载。 */
        @Synchronized
        fun invalidate() {
            cached = null
        }

        private fun load(context: Context): RuleCatalog {
            return try {
                val json = context.assets.open("rules.json")
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
                parse(json)
            } catch (e: Exception) {
                FALLBACK
            }
        }

        /** 解析 JSON；任何异常都回退到 [FALLBACK]。包内可见，便于测试。 */
        fun parse(json: String?): RuleCatalog {
            if (json.isNullOrBlank()) return FALLBACK
            return try {
                val root = JSONObject(json)

                val weights = HashMap<String, Int>()
                root.optJSONObject("keywordWeights")?.let { w ->
                    for (key in w.keys()) weights[key] = w.optInt(key, 20)
                }

                val defaults = readStringArray(root.optJSONArray("defaultKeywords"))
                    ?: FALLBACK.defaultKeywords
                val defaultIds = readStringArray(root.optJSONArray("defaultIds"))
                    ?: FALLBACK.defaultIds

                val pkgKeywords = HashMap<String, List<String>>()
                val pkgRegexes = HashMap<String, List<String>>()
                val pkgIds = HashMap<String, List<String>>()
                root.optJSONObject("packages")?.let { packages ->
                    for (pkg in packages.keys()) {
                        val po = packages.optJSONObject(pkg) ?: continue
                        readStringArray(po.optJSONArray("keywords"))?.let { pkgKeywords[pkg] = it }
                        readStringArray(po.optJSONArray("regexes"))?.let { pkgRegexes[pkg] = it }
                        readStringArray(po.optJSONArray("ids"))?.let { pkgIds[pkg] = it }
                    }
                }

                RuleCatalog(
                    defaultKeywords = defaults,
                    packageKeywords = pkgKeywords,
                    packageRegexes = pkgRegexes,
                    weights = weights.ifEmpty { FALLBACK.weights },
                    defaultWeight = root.optInt("defaultWeight", 20),
                    defaultIds = defaultIds,
                    packageIds = pkgIds
                )
            } catch (e: Exception) {
                FALLBACK
            }
        }

        private fun readStringArray(arr: org.json.JSONArray?): List<String>? {
            if (arr == null) return null
            val list = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "")
                if (s.isNotBlank()) list.add(s)
            }
            return list.ifEmpty { null }
        }
    }
}
