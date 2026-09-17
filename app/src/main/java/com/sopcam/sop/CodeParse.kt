package com.sopcam.sop

import org.json.JSONArray
import org.json.JSONObject

/**
 * 从扫出来的整串码值里，切出这一项真正要的那段。
 *
 * 控制板的点阵码解出来是一整行：
 *
 *     (HJ)BHY(A)-DJKZQKZB T4.00.0 A4 HU 231220 2357
 *      ①厂  ②系列 ③平台 ④板号   ⑤版本 ⑥配置 ⑦产线 ⑧日期 ⑨流水
 *
 * 而检修单上要填的是两个不同的东西：
 *
 *     编码：(HJ)BHY(A) 231220 2357
 *     型号：BHY-DJKZQKZB-T4.00.0
 *
 * 两者都在这一串里，不用 OCR 也不用大模型 —— 纯字符串处理，
 * 确定性 100%，不会像认字那样偶尔读错一位。
 *
 * 规则是**一个数组，按顺序试，第一条匹配上就用它**。旧版本的码格式不一样，
 * 就多加一条规则，不用改代码。
 *
 * ⚠ 别按「第几个空格」去切。⑥配置那段长度不定（A4 / A1X1），
 * 哪天冒出个带空格的写法，按顺序数就全错位了。日期和流水永远在末尾，
 * 从后面锚才稳 —— 所以下面示例里的正则都是锚末尾的。
 */
data class ParseRule(
    /** 匹配整串码值的正则。匹配不上就轮到下一条 */
    val match: String,
    /** 输出模板，用 $1 $2 引用捕获组，跟 JS 的 String.replace 一样 */
    val out: String,
) {
    fun toJson(): JSONObject = JSONObject().put("match", match).put("out", out)

    companion object {
        fun from(o: JSONObject) = ParseRule(
            match = o.optString("match"),
            out = o.optString("out"),
        )

        fun listFrom(arr: JSONArray?): List<ParseRule> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull {
                val o = arr.optJSONObject(it) ?: return@mapNotNull null
                val r = from(o)
                if (r.match.isBlank()) null else r
            }
        }

        fun listToJson(rules: List<ParseRule>): JSONArray =
            JSONArray().apply { rules.forEach { put(it.toJson()) } }
    }
}

object CodeParse {

    private val GROUP_REF = Regex("""\$(\d)""")

    /**
     * 按规则切一次。
     *
     * 一条都没配就原样返回 —— 大多数扫码项要的就是整串，不需要切。
     * 配了规则却一条都没匹配上，返回 null：那说明这个码不是预期的格式，
     * 与其塞一个错值进去，不如让调用方知道「没解析出来」。
     */
    fun apply(raw: String, rules: List<ParseRule>): String? {
        if (raw.isBlank()) return null
        if (rules.isEmpty()) return raw
        for (r in rules) {
            val re = runCatching { Regex(r.match) }.getOrNull() ?: continue
            val m = re.find(raw) ?: continue
            return GROUP_REF.replace(r.out) { ref ->
                val i = ref.groupValues[1].toInt()
                m.groupValues.getOrNull(i).orEmpty()
            }
        }
        return null
    }
}
