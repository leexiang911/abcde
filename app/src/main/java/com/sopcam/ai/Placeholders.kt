package com.sopcam.ai

import com.sopcam.archive.Archive
import com.sopcam.sop.SopTemplate
import org.json.JSONObject

/**
 * 提示词和组装值里的 `${测试项ID.ai}` 占位符解析。
 *
 * 三种取值：
 *  · `${步骤ID.ai}`     —— 那张照片的 AI 读数
 *  · `${步骤ID.scan}`   —— 那张照片扫出来的码值
 *  · `${分组ID.result}` —— 那个分组已经跑出来的结论
 *
 * 取值不受分组边界限制，想引用哪一项都行 —— 这是配置文档里明说的。
 *
 * 取不到一律填「—」而不是留空：留空的话「U 相 V 相 W 相」会挤成一团，
 * 看不出到底是哪一相没值。
 */
object Placeholders {

    const val MISSING = "—"

    // 跟后台 _validate.js 里那条保持一模一样：后台校验能拦下的，
    // 这边就一定解析得了；两边规则不同的话，后台放行的写法到手机上会静默失效
    private val REF = Regex("""\$\{([A-Za-z0-9._\-]+?)\.(ai|scan|result)\}""")

    /**
     * 一个项目里，每个测试项 ID 对应的那张照片的随行 json。
     *
     * 同一项拍了多张就取最后一张 —— 重拍过的话，后拍的才是你要的。
     *
     * 老照片没有 stepId 字段（那时候还没存），退回按序号 + 名字在流程里反查。
     * 反查不到就认了：占位符填「—」，总比整条炸掉强。
     */
    fun indexOf(serialNo: String, template: SopTemplate?): Map<String, JSONObject> {
        val out = LinkedHashMap<String, JSONObject>()
        Archive.shots(serialNo).forEach { raw ->
            val side = Archive.sidecar(raw) ?: return@forEach
            val id = side.optString("stepId").ifBlank { fallbackId(side, template) }
            if (id.isNotBlank()) out[id] = side
        }
        return out
    }

    private fun fallbackId(side: JSONObject, template: SopTemplate?): String {
        val tpl = template ?: return ""
        val order = side.optInt("stepOrder", 0)
        val name = side.optString("stepName")
        val point = side.optString("stepPoint")
        return tpl.steps.firstOrNull {
            it.order == order && it.name == name && it.point == point
        }?.id
            ?: tpl.steps.firstOrNull { it.order == order }?.id
            ?: ""
    }

    /**
     * 把一段模板里的占位符换成实际值。
     *
     * groupResults 是已经跑出来的分组结论，给 `${分组ID.result}` 用。
     * 返回（替换后的文本，有没有缺值）—— 缺值的信息调用方要用：
     * 组级 AI 提示词缺成员读数时不该硬跑，问出来的结论是错的。
     */
    fun expand(
        template: String,
        shots: Map<String, JSONObject>,
        groupResults: Map<String, String>,
    ): Pair<String, Boolean> {
        var missing = false
        val out = REF.replace(template) { m ->
            val id = m.groupValues[1]
            val kind = m.groupValues[2]
            val v = when (kind) {
                "ai" -> shots[id]?.optString("aiText").orEmpty()
                "scan" -> shots[id]?.optString("codeValue").orEmpty()
                else -> groupResults[id].orEmpty()
            }
            if (v.isBlank()) {
                missing = true
                MISSING
            } else v
        }
        return out to missing
    }

    /** 这段模板引用了哪些测试项 —— 校验和排依赖顺序都要用 */
    fun refsIn(template: String): List<Pair<String, String>> =
        REF.findAll(template).map { it.groupValues[1] to it.groupValues[2] }.toList()

    /** 方便直接拿一个项目的组装值，不必自己先建索引 */
    fun assemble(
        serialNo: String,
        template: SopTemplate?,
        format: String,
        groupResults: Map<String, String> = emptyMap(),
    ): String {
        if (format.isBlank()) return ""
        return expand(format, indexOf(serialNo, template), groupResults).first
    }
}
