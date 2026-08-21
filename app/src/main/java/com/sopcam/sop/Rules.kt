package com.sopcam.sop

import org.json.JSONArray
import org.json.JSONObject

/**
 * 判定规则。
 *
 * 分两层是有原因的：
 *  · 单值规则（range）管「这个数本身合不合格」—— 保险阻值、母线电压
 *  · 组规则（spread）管「这一组数彼此齐不齐」—— 管压降
 *
 * 管压降必须两条同时成立：每个都在 0.288~0.5，而且六个要抱团。
 * 只看范围会漏掉「一个 0.30 一个 0.42」这种都在范围内但明显掉队的情况；
 * 只看抱团会漏掉「六个都是 0.6」这种整体偏了的情况。
 */
sealed interface Rule {

    /** 绝对范围：值必须落在区间里 */
    data class Range(val min: Double?, val max: Double?) : Rule

    /**
     * 组内一致：极差不能超过基准的某个比例。
     *
     * base = "min" 时用最小值作分母 —— 这是刻意的。
     * 拿 0.30/0.31/0.42 举例：除以最大值是 28.6%（判合格），
     * 除以最小值是 40%（判超标）。这组明显有一个掉队，应该判异常，
     * 所以分母用最小值。
     */
    data class Spread(val maxRatio: Double, val base: String = "min") : Rule

    fun toJson(): JSONObject = when (this) {
        is Range -> JSONObject().put("type", "range")
            .apply { min?.let { put("min", it) }; max?.let { put("max", it) } }
        is Spread -> JSONObject().put("type", "spread")
            .put("maxRatio", maxRatio).put("base", base)
    }

    companion object {
        fun from(o: JSONObject?): Rule? {
            if (o == null) return null
            return when (o.optString("type")) {
                "range" -> Range(
                    min = if (o.has("min")) o.optDouble("min") else null,
                    max = if (o.has("max")) o.optDouble("max") else null,
                )
                "spread" -> Spread(
                    maxRatio = o.optDouble("maxRatio", 0.3),
                    base = o.optString("base", "min"),
                )
                else -> null
            }
        }
    }
}

/**
 * 适用条件。
 *
 * 「保险这项只有 B+ 有」写成条件，而不是拆成两套流程 ——
 * 拆流程的话以后加个 C+ 就要复制第三套，改一个公共项要改三个地方。
 * 空条件表示所有情况都适用。
 */
data class Applies(
    val models: List<String> = emptyList(),
    val platforms: List<String> = emptyList(),
    val faults: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = models.isEmpty() && platforms.isEmpty() && faults.isEmpty()

    fun matches(model: String, platform: String, fault: String): Boolean {
        if (models.isNotEmpty() && model !in models) return false
        if (platforms.isNotEmpty() && platform !in platforms) return false
        if (faults.isNotEmpty() && fault !in faults) return false
        return true
    }

    fun toJson(): JSONObject? {
        if (isEmpty) return null
        return JSONObject().apply {
            if (models.isNotEmpty()) put("model", JSONArray(models))
            if (platforms.isNotEmpty()) put("platform", JSONArray(platforms))
            if (faults.isNotEmpty()) put("fault", JSONArray(faults))
        }
    }

    companion object {
        private fun strings(o: JSONObject, key: String): List<String> {
            // 一个值和一组值都收，配置里写 "B+" 或 ["B+","C+"] 都行
            o.optJSONArray(key)?.let { arr ->
                return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
            }
            val one = o.optString(key)
            return if (one.isBlank()) emptyList() else listOf(one)
        }

        fun from(o: JSONObject?): Applies {
            if (o == null) return Applies()
            return Applies(strings(o, "model"), strings(o, "platform"), strings(o, "fault"))
        }
    }
}

/** 一次判定的结果 */
data class Verdict(val pass: Boolean, val reason: String) {
    companion object {
        val OK = Verdict(true, "")
    }
}

object Judge {

    /** 单个值 */
    fun value(rule: Rule?, v: Double?): Verdict? {
        if (rule !is Rule.Range || v == null) return null
        rule.min?.let { if (v < it) return Verdict(false, "低于下限 $it") }
        rule.max?.let { if (v > it) return Verdict(false, "高于上限 $it") }
        return Verdict.OK
    }

    /**
     * 一组值。返回判定和"最可疑的那一个"的下标 —— 报表要指出是哪个管子。
     * 用中位数当参照，因为坏的通常只有一个，中位数不会被它带偏。
     */
    fun group(rule: Rule?, values: List<Double?>): Pair<Verdict, Int?>? {
        if (rule !is Rule.Spread) return null
        val real = values.filterNotNull()
        if (real.size < 2) return null

        val lo = real.min()
        val hi = real.max()
        val base = if (rule.base == "max") hi else lo
        if (base == 0.0) return null

        val ratio = (hi - lo) / base
        if (ratio <= rule.maxRatio) return Verdict.OK to null

        val mid = real.sorted()[real.size / 2]
        val worst = values.indices.maxByOrNull { i ->
            values[i]?.let { kotlin.math.abs(it - mid) } ?: -1.0
        }
        val pct = (ratio * 100).toInt()
        return Verdict(false, "组内偏差 $pct%，超过 ${(rule.maxRatio * 100).toInt()}%") to worst
    }
}
