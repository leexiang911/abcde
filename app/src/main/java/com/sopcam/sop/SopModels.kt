package com.sopcam.sop

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * 数据存本地 JSON，不上 Room。
 * 模板就十几条文本，用不着数据库；省掉 KSP 注解处理器也就省掉一类构建失败。
 * 结构按 Room 实体的样子设计，以后要迁移直接映射，不用改调用方。
 */

/** 一个拍摄点位。项目名必填，位号可选——对齐检修单上「测试项目」那一列的实际写法。 */
/**
 * 一个拍摄步骤 = 一个测点。
 *
 * 拍摄顺序和报表结构是两件事：管压降拍六次（六个测点），
 * 报表上却只占两行（上桥一行、下桥一行）。所以步骤上挂一个 group，
 * 报表按 group 归行，拍摄按 step 走，两边各自舒服。
 */
data class SopStep(
    val order: Int,
    val name: String,
    val refDes: String = "",
    val shots: Int = 1,
    /** 测点名，例如 "上桥U"。同一检查项下的每个测点各占一个步骤 */
    val point: String = "",
    /** 报表归到哪一行。留空则这一步自成一行 */
    val group: String = "",
    val unit: String = "",
    /** 这个测点自己的值要满足的规则 */
    val rule: Rule? = null,
    /** 什么型号 / 平台 / 故障下才需要做这一项 */
    val applies: Applies = Applies(),
) {
    /** 水印强调行 / 文件名主干，例如 "Q1200-5脚 水泵输出电压" 或 "管压降 上桥U" */
    fun label(): String =
        listOf(refDes, name, point).filter { it.isNotBlank() }.joinToString(" ")

    /** 报表上归哪一行 */
    fun rowName(): String = group.ifBlank { name }

    fun toJson(): JSONObject = JSONObject()
        .put("order", order)
        .put("name", name)
        .put("refDes", refDes)
        .put("shots", shots)
        .put("point", point)
        .put("group", group)
        .put("unit", unit)
        .apply {
            rule?.let { put("rule", it.toJson()) }
            applies.toJson()?.let { put("only", it) }
        }

    companion object {
        fun from(o: JSONObject) = SopStep(
            order = o.optInt("order", 1),
            name = o.optString("name"),
            refDes = o.optString("refDes"),
            shots = o.optInt("shots", 1),
            point = o.optString("point"),
            group = o.optString("group"),
            unit = o.optString("unit"),
            rule = Rule.from(o.optJSONObject("rule")),
            applies = Applies.from(o.optJSONObject("only")),
        )
    }
}

/** 检查项级别的规则，作用在一组测点的值上 */
data class SopGroup(
    val name: String,
    val rule: Rule? = null,
    val unit: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("unit", unit)
        .apply { rule?.let { put("rule", it.toJson()) } }

    companion object {
        fun from(o: JSONObject) = SopGroup(
            name = o.optString("name"),
            rule = Rule.from(o.optJSONObject("rule")),
            unit = o.optString("unit"),
        )
    }
}

data class SopTemplate(
    val id: String,
    val name: String,
    val steps: List<SopStep>,
    val groups: List<SopGroup> = emptyList(),
) {
    fun groupOf(name: String): SopGroup? = groups.firstOrNull { it.name == name }

    /**
     * 按这台机器的型号 / 平台 / 故障筛出真正要做的步骤。
     *
     * 不适用的项直接不出现在步骤梯上，报表里也没这行 ——
     * 「A+ 没有保险这一项」就是这么落地的，不用为它单开一套流程。
     */
    fun forCase(model: String, platform: String, fault: String): List<SopStep> =
        steps.filter { it.applies.matches(model, platform, fault) }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })
        .apply {
            if (groups.isNotEmpty()) {
                put("groups", JSONArray().apply { groups.forEach { put(it.toJson()) } })
            }
        }

    companion object {
        fun from(o: JSONObject): SopTemplate {
            val arr = o.optJSONArray("steps") ?: JSONArray()
            val gs = o.optJSONArray("groups") ?: JSONArray()
            return SopTemplate(
                id = o.optString("id").ifBlank { "t" + System.currentTimeMillis() },
                name = o.optString("name").ifBlank { "未命名流程" },
                steps = (0 until arr.length()).map { SopStep.from(arr.getJSONObject(it)) },
                groups = (0 until gs.length()).map { SopGroup.from(gs.getJSONObject(it)) },
            )
        }
    }
}

/** 当前这一单的现场状态。退出重进要能接着拍，所以也落盘。 */
data class Session(
    val serialNo: String = "",
    val modelId: String = "",
    val platformId: String = "",
    val faultId: String = "",
    val templateId: String = "",
    val stepIndex: Int = 0,
    /** key 是步骤 order，value 是已拍张数 */
    val shotCounts: Map<Int, Int> = emptyMap(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("serialNo", serialNo)
        .put("modelId", modelId)
        .put("platformId", platformId)
        .put("faultId", faultId)
        .put("templateId", templateId)
        .put("stepIndex", stepIndex)
        .put("shotCounts", JSONObject().apply {
            shotCounts.forEach { (k, v) -> put(k.toString(), v) }
        })

    companion object {
        fun from(o: JSONObject): Session {
            val counts = o.optJSONObject("shotCounts") ?: JSONObject()
            val map = mutableMapOf<Int, Int>()
            counts.keys().forEach { k -> map[k.toInt()] = counts.optInt(k) }
            return Session(
                serialNo = o.optString("serialNo"),
                modelId = o.optString("modelId"),
                platformId = o.optString("platformId"),
                faultId = o.optString("faultId"),
                templateId = o.optString("templateId"),
                stepIndex = o.optInt("stepIndex", 0),
                shotCounts = map,
            )
        }
    }
}

object SopStore {

    private fun file(ctx: Context, name: String) = File(ctx.filesDir, name)

    fun loadTemplates(ctx: Context): List<SopTemplate> {
        val f = file(ctx, "templates.json")
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { SopTemplate.from(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun saveTemplates(ctx: Context, list: List<SopTemplate>) {
        runCatching {
            val arr = JSONArray().apply { list.forEach { put(it.toJson()) } }
            file(ctx, "templates.json").writeText(arr.toString())
        }
    }

    fun loadSession(ctx: Context): Session {
        val f = file(ctx, "session.json")
        if (!f.exists()) return Session()
        return runCatching { Session.from(JSONObject(f.readText())) }.getOrDefault(Session())
    }

    fun saveSession(ctx: Context, s: Session) {
        runCatching { file(ctx, "session.json").writeText(s.toJson().toString()) }
    }
}

/* ------------------------------------------------------------------
 * 从粘贴的文本批量建步骤
 * ------------------------------------------------------------------ */

object SopParser {

    // 行首的编号：1. / 1、/ 01 / 1) / 1：
    private val leadingIndex = Regex("""^\d{1,3}\s*[.、)\]．:：]?\s*""")

    /**
     * 整份流程。粘 JSON 就按 JSON 读，粘纯文本就一行一条。
     *
     * 复杂流程（测点、判定规则、适用条件）在手机上一项项点会疯掉，
     * 所以让人在电脑上用记事本写 JSON，粘进来一次成型。
     * 以后有了后台，后台生成的也是同一份 JSON，手机端不用改。
     */
    fun parseTemplate(raw: String, fallbackName: String): SopTemplate? {
        val t = raw.trim()
        if (!t.startsWith("{")) return null
        return runCatching { SopTemplate.from(JSONObject(t)) }
            .getOrNull()
            ?.takeIf { it.steps.isNotEmpty() }
            ?.let { if (it.name.isBlank()) it.copy(name = fallbackName) else it }
    }

    /**
     * 一行一条。同时兼容从表格直接复制过来的情况——
     * 那种一行是「1<TAB>控制器编号<TAB>正常」，取中间那列。
     */
    fun parse(raw: String): List<SopStep> {
        val names = raw.lines()
            .map { pickName(it) }
            .filter { it.isNotBlank() }
        return names.mapIndexed { i, n -> SopStep(order = i + 1, name = n) }
    }

    private fun pickName(line: String): String {
        val cells = line.split('\t', '|')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (cells.isEmpty()) return ""
        // 丢掉纯数字的序号列，剩下第一个就是项目名
        val meaningful = cells.filterNot { it.all { c -> c.isDigit() } }
        val first = meaningful.firstOrNull() ?: return ""
        return first.replace(leadingIndex, "").trim()
    }
}

/* ------------------------------------------------------------------
 * 命名与归档路径
 * ------------------------------------------------------------------ */

object FileNaming {

    private val timeFmt = SimpleDateFormat("HHmmss", Locale.US)
    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val illegal = Regex("""[\\/:*?"<>|\r\n\t]""")

    /** 中文保留（NTFS / exFAT / ext4 都支持），只清掉跨平台非法字符 */
    fun sanitize(raw: String, maxLen: Int = 40): String {
        var s = raw.replace(illegal, "").trim()
        s = s.replace(Regex("""\s+"""), "_")
        s = s.trimEnd('.', '_')
        if (s.length > maxLen) s = s.take(maxLen).trimEnd('_')
        return s.ifBlank { "未命名" }
    }

    /**
     * 成片文件名。
     *   03_低压发波_143052
     *   03_Q1200-5脚_水泵输出电压_143052
     *   07_上桥管压降_2_143052        ← 同一步骤第 2 张
     *   FREE_143052                   ← 未选模板时的自由拍摄
     *
     * 两位序号前缀是刻意的：电脑上按文件名排序 = 按 SOP 顺序，不用再看时间戳。
     */
    fun build(step: SopStep?, shotIndex: Int = 1, at: Long = System.currentTimeMillis()): String {
        val head = step?.let {
            val idx = it.order.toString().padStart(2, '0')
            val body = if (it.refDes.isBlank()) sanitize(it.name, 44)
            else sanitize(it.refDes, 16) + "_" + sanitize(it.name, 32)
            "${idx}_$body"
        } ?: "FREE"
        val dup = if (shotIndex > 1) "_$shotIndex" else ""
        return "$head$dup" + "_" + timeFmt.format(Date(at))
    }

    /**
     * 归档目录。电脑端整个 SopCam 拖过去，层级就是天然分类：
     *   DCIM/SopCam/20260809/0104215HZN92952565/
     * 序列号是唯一的，所以单独用它做目录名就够，不会撞。
     * 序列号为空时退到按日期存，不至于丢照片。
     */
    fun relativePath(serialNo: String, at: Long = System.currentTimeMillis()): String {
        val folder = if (serialNo.isBlank()) "" else sanitize(serialNo, 32)
        val day = dayFmt.format(Date(at))
        return if (folder.isBlank()) "DCIM/SopCam/$day" else "DCIM/SopCam/$day/$folder"
    }
}
