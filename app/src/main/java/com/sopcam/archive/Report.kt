package com.sopcam.archive

import com.sopcam.ai.Placeholders
import com.sopcam.sop.FileNaming
import com.sopcam.sop.SopTemplate
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * 检修报表。
 *
 * 数据来源是归档区那一堆随行 json，导出时现拼成一份总表。
 * 存储保持「一图一 json」是为了写入安全 —— 改一张的码值只动一个小文件，
 * 不会跟别的写操作打架，也不会写到一半崩了毁掉整张总表。
 * 读取端要的是整体，所以导出时反过来汇总一次。
 *
 * 总表必须内嵌进 HTML：file:// 协议下 Chrome 会拿 CORS 拦掉 fetch，
 * 读同目录的 report.json 也一样被拦。图片不受影响（img src 不走 fetch），
 * 所以图片用相对路径，数据走内嵌。
 */
object Report {

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
    private val shotFmt = SimpleDateFormat("HH:mm", Locale.CHINA)

    /**
     * 汇总成一份总表。
     *
     * value / verdict / remark 现在是空的，报表里显示成可填的框。
     * 以后 AI 读表填的就是它们 —— 位置先留好，到时候数据结构和模板都不用改。
     */
    fun manifest(
        serials: List<String>,
        imageExt: String,
        watermarked: Boolean,
        template: SopTemplate? = null,
    ): JSONObject {
        val projects = JSONArray()
        val index = Archive.list().associateBy { it.serialNo }

        serials.forEach { sn ->
            val meta = index[sn]
            val steps = LinkedHashMap<String, JSONObject>()
            // 分组横跨哪些序号、叫什么名字，先算一遍 —— 行和文件夹都照这个来
            val rows = rowsOf(sn, template)

            Archive.shots(sn).forEach { raw ->
                val side = Archive.sidecar(raw) ?: JSONObject()
                val key = keyOf(side, raw).first
                val (order, name) = rows[key] ?: (side.optInt("stepOrder", 0) to "自由拍摄")
                val point = side.optString("stepPoint")

                val group = steps.getOrPut(key) {
                    JSONObject()
                        .put("order", order)
                        .put("name", name)
                        .put("key", key)
                        .put("firstAt", side.optLong("capturedAt", raw.lastModified()))
                        .put("refDes", side.optString("stepRefDes"))
                        .put("shots", JSONArray())
                        .put("codes", JSONArray())
                        .put("folder", folderOf(order, name))
                        .put("points", JSONArray())
                        .put("value", "")
                        .put("unit", "")
                        .put("verdict", "")
                        .put("remark", "")
                }

                // 报表引用的是包里那份的路径，不是手机上的路径
                val path = if (watermarked) {
                    val stem = side.optString("fileName").ifBlank { raw.nameWithoutExtension }
                    "$sn/水印图/${folderOf(order, name)}/$stem.$imageExt"
                } else {
                    "$sn/原图/${raw.nameWithoutExtension}.jpg"
                }

                group.getJSONArray("shots").put(
                    JSONObject()
                        .put("file", path)
                        .put("name", File(path).name)
                        .put("at", side.optLong("capturedAt", raw.lastModified()))
                        .put("time", shotFmt.format(Date(side.optLong("capturedAt", raw.lastModified()))))
                        .put("code", side.optString("codeValue"))
                        // 合成一行之后，组里这几张分别是哪个测试项，就只剩这个字段说得清了
                        //（温度1 一行下面既有阻值也有电压）
                        .put("step", side.optString("stepName"))
                        // 拍照时打的备注。跟着这张图走，不并到组上 ——
                        // 一组里几张图各说各的事，合在一起就分不清哪句对应哪张
                        .put("note", side.optString("note"))
                        // AI 读出来的值。跟备注一样绑在图上，不并到组上 ——
                        // 一组拍好几张时，几个值合成一格就分不清是哪张读出来的
                        .put("ai", side.optString("aiText"))
                )

                // 每个测点一个格子。同一测点拍多张，格子还是一个
                if (point.isNotBlank()) {
                    val pts = group.getJSONArray("points")
                    val seen = (0 until pts.length()).any { pts.getJSONObject(it).optString("name") == point }
                    if (!seen) pts.put(JSONObject().put("name", point).put("value", ""))
                }

                // 码值汇到组上，报表右栏直接显示，不用在图底下找
                val code = side.optString("codeValue")
                if (code.isNotBlank()) {
                    val arr = group.getJSONArray("codes")
                    val has = (0 until arr.length()).any { arr.optString(it) == code }
                    if (!has) arr.put(code)
                }
            }

            // 自由拍摄（序号 0）排到最后；两组序号相同时按最早那张的时间排，
            // 这样顺序跟实际干活的先后一致
            val ordered = steps.values.sortedWith(
                compareBy(
                    { if (it.optInt("order") == 0) 1 else 0 },
                    { it.optInt("order") },
                    { it.optLong("firstAt") },
                )
            )

            // 把判定规则拌进去，报表页面自己算 —— 数一填完当场出正常/异常
            // 组装值现算，不存盘 —— 你在手机上改过某张的 AI 读数之后，
            // 导出的报表就该按改后的值重拼。存下来的话必然有一份是旧的
            val shotIndex = Placeholders.indexOf(sn, template)
            val groupResults = Archive.groupAi(sn)

            ordered.forEach { g ->
                val key = g.optString("key")
                if (key.startsWith(GROUP_PREFIX)) {
                    // 分组行的规则和单位取组上的。这里必须拿组 id 去查 ——
                    // 显示名已经换成中文了，用它撞 rowName() 撞不上
                    template?.groupOf(key.removePrefix(GROUP_PREFIX))?.let { gr ->
                        gr.rule?.let { g.put("groupRule", it.toJson()) }
                        if (gr.unit.isNotBlank()) g.put("unit", gr.unit)
                        if (gr.format.isNotBlank()) {
                            g.put(
                                "assembled",
                                Placeholders.expand(gr.format, shotIndex, groupResults).first
                            )
                        }
                        groupResults[gr.id]?.takeIf { it.isNotBlank() }
                            ?.let { g.put("groupAi", it) }
                    }
                } else {
                    val row = g.optString("name")
                    template?.steps?.firstOrNull { it.rowName() == row }?.let { st ->
                        st.rule?.let { g.put("valueRule", it.toJson()) }
                        if (st.unit.isNotBlank() && g.optString("unit").isBlank()) {
                            g.put("unit", st.unit)
                        }
                    }
                }
            }

            projects.put(
                JSONObject()
                    .put("serialNo", sn)
                    .put("model", meta?.model ?: "")
                    .put("platform", meta?.platform ?: "")
                    .put("fault", meta?.fault ?: "")
                    .put("status", meta?.status?.name ?: "NONE")
                    .put("note", meta?.note ?: "")
                    .put("updatedAt", meta?.updatedAt ?: 0L)
                    .put("updatedText", meta?.updatedAt?.let { dateFmt.format(Date(it)) } ?: "")
                    .put("shotCount", meta?.shotCount ?: 0)
                    .put("steps", JSONArray(ordered))
            )
        }

        return JSONObject()
            .put("generatedAt", System.currentTimeMillis())
            .put("generatedText", dateFmt.format(Date()))
            .put("projects", projects)
    }

    /** 把总表塞进模板。`</` 要转义，否则 JSON 里出现 </script> 会把脚本块提前截断 */
    fun html(manifest: JSONObject): String {
        val safe = manifest.toString().replace("</", "<\\/")
        return TEMPLATE
            .replace("__TITLE__", titleOf(manifest))
            .replace("__DATA__", safe)
    }

    /** 分组行的键前缀。带这个前缀的，冒号后面是流程配置里的组 id */
    const val GROUP_PREFIX = "g|"

    /**
     * 这张照片归到哪一行：返回（行键，这张自己的序号）。
     *
     * 配了分组的，键里**不带序号** —— 温度1 的阻值在 02、电压在 07，
     * 带上序号就成了两行，报表上一堆重名。分组的意义就是并成一行。
     *
     * 没配分组的还是「序号 + 名字」：同一个控制器上跑过两套流程时，
     * 两套的序号都从 1 开始，只按名字分会把不相干的两项并到一起。
     * 名字三级回退：文件名（用户改过名的话那才是他要的）→ stepName → 自由拍摄。
     */
    private fun keyOf(side: JSONObject, raw: File): Pair<String, Int> {
        val order = side.optInt("stepOrder", 0)
        val group = side.optString("stepGroup")
        if (group.isNotBlank()) return GROUP_PREFIX + group to order
        val fileStem = side.optString("fileName").ifBlank { raw.nameWithoutExtension }
        val name = labelOf(fileStem)
            .ifBlank { side.optString("stepName") }
            .ifBlank { "自由拍摄" }
        return "s|$order|$name" to order
    }

    /**
     * 行键 → 显示名。
     *
     * 元数据里存的 stepGroup 是组 **id**（temp1、net_pins），报表上要显示的是
     * 流程配置里的中文名。查不到（老照片、流程已删）就退回 id，
     * 至少还是个能认出来的字符串。
     */
    private fun labelFor(key: String, template: SopTemplate?): String {
        if (!key.startsWith(GROUP_PREFIX)) return key.substringAfterLast('|')
        val id = key.removePrefix(GROUP_PREFIX)
        return template?.groupOf(id)?.name?.ifBlank { id } ?: id
    }

    /**
     * 一个项目里，每一行归到哪个序号、叫什么名字。
     *
     * 分组可能横跨好几个序号，但报表只有一行、导出只有一个文件夹，
     * 所以统一取组内最小的那个序号。manifest 和 folderMap 都走这里，
     * 两边各算一遍必然对不上 —— 报表上写着 02_温度1，包里却是 07_温度1。
     */
    private fun rowsOf(serialNo: String, template: SopTemplate?): Map<String, Pair<Int, String>> {
        val out = LinkedHashMap<String, Pair<Int, String>>()
        Archive.shots(serialNo).forEach { raw ->
            val side = Archive.sidecar(raw) ?: return@forEach
            val (key, order) = keyOf(side, raw)
            val prev = out[key]
            if (prev == null || order < prev.first) out[key] = order to labelFor(key, template)
        }
        return out
    }

    private val illegalInName = Regex("[\\\\/:*?\"<>|\\r\\n\\t]")

    /**
     * 检查项在包里对应的子文件夹。
     *
     * 分文件夹是为了传图：在文件管理器里进这个文件夹，Ctrl+A 一拖就完事。
     * 网页里做不到多选拖拽 —— 一次拖拽只能带一个元素，那是 HTML 拖放模型的
     * 硬限制，选中再多张也没用。文件管理器没这个问题。
     */
    fun folderOf(order: Int, name: String): String {
        val clean = name.replace(illegalInName, "").trim().take(40).ifBlank { FileNaming.UNNAMED }
        return if (order > 0) "%02d_%s".format(order, clean) else "00_$clean"
    }

    /**
     * 成片文件名（不含扩展名）→ 它该进哪个子文件夹。
     *
     * 必须跟 manifest 走同一套 rowsOf，否则报表里那个「点一下复制文件夹名」
     * 复制出来的名字，在包里根本不存在。
     */
    fun folderMap(serialNo: String, template: SopTemplate? = null): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val rows = rowsOf(serialNo, template)
        Archive.shots(serialNo).forEach { raw ->
            val side = Archive.sidecar(raw) ?: return@forEach
            val key = keyOf(side, raw).first
            val (order, name) = rows[key] ?: return@forEach
            val stem = side.optString("fileName").ifBlank { raw.nameWithoutExtension }
            out[stem] = folderOf(order, name)
        }
        return out
    }

    /**
     * 从文件名里还原出这张照片"是什么"。
     *
     * 文件名的形状是 `序号_[位号_]名称[_重复序号]_时分秒`，
     * 从两头剥掉机器加的部分，剩下的就是人写的那截。
     */
    private fun labelOf(stem: String): String {
        var t = stem
        t = t.replace(Regex("_\\d{4,6}$"), "")   // 尾巴上的时分秒
        t = t.replace(Regex("_\\d{1,2}$"), "")   // 同一分钟内连拍的重复序号
        t = t.replace(Regex("^\\d{1,3}_"), "")   // 开头的步骤号
        return t.replace('_', '·').replace("·", " · ").trim()
    }

    private fun titleOf(m: JSONObject): String {
        val arr = m.optJSONArray("projects") ?: return "检修留档"
        return when (arr.length()) {
            0 -> "检修留档"
            1 -> arr.getJSONObject(0).optString("serialNo", "检修留档")
            else -> "检修留档 · " + arr.length() + " 个项目"
        }
    }

    /**
     * 模板里刻意不出现美元符号 —— Kotlin 的原始字符串会把它当模板占位，
     * 转义写法满屏都是的话可读性直接归零。所以 JS 用字符串拼接，不用模板字面量。
     */
    private val TEMPLATE = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>__TITLE__ · 检修留档</title>
<style>
:root{
  --paper:#FBFAF7; --ink:#16191C; --rule:#DEDBD3; --mute:#6B7076;
  --pass:#1F7A4D; --fail:#B3352C; --unsure:#B8517E; --mark:#FDCE04;
  --mono:ui-monospace,"Cascadia Mono","SF Mono",Consolas,"Courier New",monospace;
  --sans:system-ui,-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;
}
*{box-sizing:border-box}
body{margin:0;background:var(--paper);color:var(--ink);font-family:var(--sans);
  font-size:15px;line-height:1.55;-webkit-font-smoothing:antialiased}
.wrap{max-width:1180px;margin:0 auto;padding:36px 28px 96px}

/* 铭牌：照搬控制器铭牌的排法，序列号是主角 */
header{border-top:3px solid var(--ink);padding-top:18px;margin-bottom:8px}
.eyebrow{font-family:var(--mono);font-size:11px;letter-spacing:.22em;
  text-transform:uppercase;color:var(--mute)}
h1{margin:6px 0 0;font-family:var(--mono);font-size:30px;font-weight:600;
  letter-spacing:.01em;word-break:break-all}
.tags{display:flex;flex-wrap:wrap;gap:8px;margin-top:14px}
.tag{font-size:12.5px;padding:4px 11px;border:1px solid var(--rule);background:#fff}
.tag b{font-weight:600}
.meta{margin-top:12px;color:var(--mute);font-size:12.5px;font-family:var(--mono)}
.note{margin-top:14px;padding:11px 14px;background:#fff;border-left:3px solid var(--mark);
  font-size:13.5px}

.bar{display:flex;gap:10px;flex-wrap:wrap;align-items:center;
  margin:26px 0 8px;padding:12px 0;border-top:1px solid var(--rule);
  border-bottom:1px solid var(--rule)}
button{font:inherit;font-size:13px;padding:8px 15px;background:#fff;
  border:1px solid var(--rule);cursor:pointer;color:var(--ink)}
button:hover{border-color:var(--ink)}
button.go{background:var(--mark);border-color:var(--mark);font-weight:600}
.hint{color:var(--mute);font-size:12px;margin-left:auto}

/* 签名元素：左侧一条测量标尺，步骤号是刻度，结论是刻度上的实心节点。
   一眼从上往下扫就能看出这台机器卡在哪一项 */
.sw{margin:0 0 8px;padding:14px 0 4px;border-bottom:1px solid var(--rule)}
.swhead{font-size:13.5px;font-weight:600;margin-bottom:9px}
.swhead span{font-weight:400;color:var(--mute);font-size:11.5px}
.drop{border:1px dashed var(--rule);background:#fff;padding:16px;
  text-align:center;color:var(--mute);font-size:12.5px;cursor:copy}
.drop.hot{border-color:var(--mark);background:#FFFDF2;color:var(--ink)}
.swrow{margin-top:10px}
.swpics{display:flex;gap:14px;flex-wrap:wrap}
.swout{margin-top:12px;display:flex;align-items:flex-end;gap:14px;flex-wrap:wrap}
.swlist{font-family:var(--mono);font-size:15px;line-height:1.85}
.swline span{color:var(--mute)}
.swline b{font-weight:600;cursor:copy;padding:1px 6px;
  border-bottom:2px solid var(--mark)}
.swline b:hover{background:#FFFDF2}
.swcell{width:200px}
.swcell b{display:block;font-family:var(--mono);font-size:11px;
  letter-spacing:.12em;color:var(--mute);margin-bottom:5px}
.swcell img{width:200px;height:132px;object-fit:contain;background:#fff;
  border:1px solid var(--rule);cursor:zoom-in;display:block}
.swcell .ver{font-family:var(--mono);font-size:12px;color:var(--mute);margin-top:5px}
@media print{ .drop{display:none} }

.step{display:grid;grid-template-columns:64px 1fr 260px;gap:0;
  border-bottom:1px solid var(--rule)}
.rail{position:relative;padding:22px 0 22px 0}
.rail::before{content:"";position:absolute;left:31px;top:0;bottom:0;
  width:1px;background:var(--rule)}
.step:first-of-type .rail::before{top:22px}
.step:last-of-type .rail::before{bottom:22px}
.node{position:relative;width:26px;height:26px;margin-left:19px;
  border:1px solid var(--ink);background:var(--paper);border-radius:50%;
  display:flex;align-items:center;justify-content:center;
  font-family:var(--mono);font-size:11.5px;font-weight:600}
.node.pass{background:var(--pass);border-color:var(--pass);color:#fff}
.node.fail{background:var(--fail);border-color:var(--fail);color:#fff}
.node.unsure{background:var(--unsure);border-color:var(--unsure);color:#fff}

.body{padding:22px 22px 22px 4px;min-width:0}
.title{font-size:16.5px;font-weight:600;cursor:pointer;user-select:none;
  display:flex;align-items:center;gap:8px}
.title:hover{color:#000}
.caret{font-family:var(--mono);font-size:11px;color:var(--mute);
  transition:transform .15s;display:inline-block}
.step.shut .caret{transform:rotate(-90deg)}
/* 折叠要真的塌下去：图片和整个右栏都收起来，行高压到一行。
   只藏图片的话右边那列输入框还撑着高度，滚半天才翻一项 */
.step.shut .shots,.step.shut .refdes,.step.shut .data{display:none}
.step.shut .body{padding:9px 22px 9px 4px}
.step.shut .rail{padding:9px 0}
.step.shut .title{font-size:15px}
/* 折起来时把结论显在标题上，一眼扫完整台机器 */
.step.shut .tag{display:inline-block}
.tag{display:none;font-size:11px;padding:1px 8px;border-radius:9px;
  color:#fff;font-weight:600}
.tag.pass{background:var(--pass)}
.tag.fail{background:var(--fail)}
.tag.unsure{background:var(--unsure)}
.count{font-family:var(--mono);font-size:11px;color:var(--mute);font-weight:400}
.refdes{font-family:var(--mono);font-size:12px;color:var(--mute);margin-top:3px}
.shots{display:flex;flex-wrap:wrap;gap:10px;margin-top:13px}
/* 图片区禁用文字选择：框选时不会把标题和说明一起选进去，
   拖图片才拖得干净。图片本身不受影响，照样能拖能右键 */
.folder{font-family:var(--mono);font-size:11.5px;color:var(--mute);
  margin-top:9px;cursor:copy;display:inline-block;
  padding:4px 9px;background:#fff;border:1px solid var(--rule)}
.folder:hover{border-color:var(--ink);color:var(--ink)}
.folder span{opacity:.65}
.step.shut .folder{display:none}

.shots{user-select:none;-webkit-user-select:none}
figure{margin:0;width:172px}
/* 图和它的备注绑成一格，备注绝对定位就有参照物了 */
.thumb{position:relative;width:172px;line-height:0}
figure img{width:172px;height:129px;object-fit:cover;border:1px solid var(--rule);
  background:#fff;cursor:zoom-in;display:block}
figure img:hover{border-color:var(--ink)}
figcaption{font-family:var(--mono);font-size:10.5px;color:var(--mute);
  margin-top:4px;text-align:center;width:172px;line-height:1.4;
  word-break:break-word}
/* 单张图的备注：压在缩略图下沿，跟图绑死 —— 一屏几十张图，
   备注挨着图放都可能看串行，盖在图上就不会认错是哪张。
   最多三行，长备注在这儿截断；完整内容点开大图看，那边能一键复制。
   pointer-events:none 是为了点到备注上也照样开大图，别在小图上做两种点击 */
/* AI 读数：跟备注一样压在图上，但压在上沿、用蓝色，
   一眼分得清哪句是人写的、哪句是机器读的 */
/* 组装值 / AI 结论：跟在图组下面各占一栏。点一下整条复制 ——
   复制出来是纯值，不带「组装值」这个标签 */
.asm{margin-top:10px;display:flex;align-items:baseline;gap:10px;
  padding:8px 10px;background:#fff;border:1px solid var(--rule);
  border-left:3px solid var(--mark);cursor:copy}
.asm:hover{background:#FFFDF2}
.asm label{font-family:var(--mono);font-size:10.5px;color:var(--mute);
  flex:0 0 auto;text-transform:none}
.asm span{font-size:14px;line-height:1.6;color:var(--ink);
  word-break:break-word;user-select:text;-webkit-user-select:text}
.asm.gai{border-left-color:#7BC6FF}
.shotai{position:absolute;left:1px;right:1px;top:1px;
  font-size:11px;line-height:1.35;color:#0B2434;text-align:left;
  padding:4px 6px;background:rgba(123,198,255,.92);
  white-space:pre-wrap;word-break:break-word;pointer-events:none;
  display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
.shotnote{position:absolute;left:1px;right:1px;bottom:1px;
  font-size:11px;line-height:1.35;color:#fff;text-align:left;
  padding:4px 6px;background:rgba(14,16,18,.82);
  white-space:pre-wrap;word-break:break-word;pointer-events:none;
  display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical;overflow:hidden}
.code{display:inline-block;margin-top:8px;font-family:var(--mono);font-size:12px;
  padding:3px 8px;background:#fff;border:1px solid var(--rule);cursor:copy}
.code:hover{border-color:var(--ink)}

.data{padding:22px 0 22px 20px;border-left:1px solid var(--rule)}
/* 识别值：机器从图里读出来的，不给编辑，点一下复制 */
.read{margin-bottom:14px}
.read .val{display:block;font-family:var(--mono);font-size:13px;
  padding:7px 10px;background:#fff;border:1px solid var(--rule);
  border-left:3px solid var(--pass);cursor:copy;margin-bottom:4px;
  word-break:break-all;line-height:1.4}
.read .val:hover{border-color:var(--ink);border-left-color:var(--pass)}
.read .none{color:var(--mute);font-size:12px}

label{display:block;font-size:11px;letter-spacing:.1em;color:var(--mute);
  text-transform:uppercase;font-family:var(--mono);margin-bottom:5px}
input[type=text]{width:100%;font:inherit;font-family:var(--mono);font-size:15px;
  padding:8px 10px;border:1px solid var(--rule);background:#fff}
input[type=text]:focus{outline:2px solid var(--mark);outline-offset:-1px;border-color:var(--mark)}
.pt{display:flex;align-items:center;gap:8px;margin-bottom:6px}
.ptname{font-family:var(--mono);font-size:11.5px;color:var(--mute);
  min-width:52px;flex-shrink:0}
.pt input{flex:1}
.auto{font-size:12px;line-height:1.5;margin-top:8px;min-height:1px}
.auto.yes{color:var(--pass)}
.auto.no{color:var(--fail)}

.verdicts{display:flex;gap:6px;margin-top:14px}
.verdicts button{flex:1;padding:8px 4px;font-size:12.5px}
.verdicts button.on[data-v=pass]{background:var(--pass);border-color:var(--pass);color:#fff}
.verdicts button.on[data-v=fail]{background:var(--fail);border-color:var(--fail);color:#fff}
.verdicts button.on[data-v=unsure]{background:var(--unsure);border-color:var(--unsure);color:#fff}
.data .rm{margin-top:14px}

.copied{position:fixed;left:50%;bottom:34px;transform:translateX(-50%);
  background:var(--ink);color:#fff;font-size:13px;padding:9px 18px;
  opacity:0;pointer-events:none;transition:opacity .16s}
.copied.on{opacity:1}

#box{position:fixed;inset:0;background:rgba(14,16,18,.94);display:none;
  align-items:center;justify-content:center;cursor:zoom-out;z-index:9}
#box img{max-width:94vw;max-height:88vh;object-fit:contain}
#box .cap{position:absolute;bottom:22px;left:0;right:0;text-align:center;
  color:#C9CDD2;font-family:var(--mono);font-size:12px}
/* 有备注时换个样子：白底药丸，一看就知道能点。cursor 要盖掉 #box 的 zoom-out */
#box .cap.has{max-width:80vw;margin:0 auto;padding:9px 16px;
  background:#fff;color:var(--ink);font-family:var(--sans);font-size:13px;
  line-height:1.6;white-space:pre-wrap;word-break:break-word;text-align:left;
  cursor:copy;display:inline-block;position:absolute;left:50%;
  transform:translateX(-50%);right:auto;
  user-select:text;-webkit-user-select:text}

footer{margin-top:40px;color:var(--mute);font-size:12px;line-height:1.9}
footer b{color:var(--ink)}

@media(max-width:820px){
  .step{grid-template-columns:44px 1fr}
  .rail::before{left:21px}
  .node{margin-left:9px}
  .data{grid-column:1/-1;border-left:0;border-top:1px solid var(--rule);
    padding:16px 0 22px 44px}
}
@media print{
  body{background:#fff}
  /* 打印要的是完整记录，折叠状态不该带到纸上 */
  .step.shut .shots,.step.shut .refdes{display:block!important}
  .caret,.count{display:none}
  .bar,#box,.copied,.verdicts{display:none}
  .step{break-inside:avoid}
  figure img{height:auto}
}
@media(prefers-reduced-motion:reduce){*{transition:none!important}}
</style>
</head>
<body>
<div class="wrap" id="app"></div>
<div class="copied" id="toast">已复制</div>
<div id="box"><img alt=""><div class="cap"></div></div>

<script>
var DATA = __DATA__;

function esc(s){
  return String(s == null ? "" : s)
    .replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;")
    .replace(/"/g,"&quot;");
}

function toast(msg){
  var t = document.getElementById("toast");
  t.textContent = msg || "已复制";
  t.classList.add("on");
  clearTimeout(t._h);
  t._h = setTimeout(function(){ t.classList.remove("on"); }, 1100);
}

function copy(text){
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).then(function(){ toast(); }, fallback);
  } else fallback();
  function fallback(){
    var ta = document.createElement("textarea");
    ta.value = text; ta.style.position = "fixed"; ta.style.opacity = "0";
    document.body.appendChild(ta); ta.select();
    try { document.execCommand("copy"); toast(); } catch(e) { toast("复制失败，请手动选中"); }
    document.body.removeChild(ta);
  }
}

var VERDICTS = [["pass","正常"],["fail","异常"],["unsure","待定"]];

function render(){
  var app = document.getElementById("app");
  var html = "";

  DATA.projects.forEach(function(p, pi){
    var tags = [];
    if (p.model) tags.push(["控制器型号", p.model]);
    if (p.platform) tags.push(["平台", p.platform]);
    if (p.fault) tags.push(["故障类型", p.fault]);

    html += '<header>';
    html += '<div class="eyebrow">controller serial</div>';
    html += '<h1 class="copyable" data-copy="' + esc(p.serialNo) + '">' + esc(p.serialNo) + '</h1>';
    if (tags.length) {
      html += '<div class="tags">';
      tags.forEach(function(t){
        html += '<span class="tag">' + esc(t[0]) + ' <b>' + esc(t[1]) + '</b></span>';
      });
      html += '</div>';
    }
    html += '<div class="meta">' + esc(p.shotCount) + ' 张 · 最后更新 ' + esc(p.updatedText) + '</div>';
    if (p.note) html += '<div class="note">' + esc(p.note) + '</div>';
    html += '</header>';

    html += '<div class="bar">';
    html += '<button class="go" data-tsv="' + pi + '">复制整表</button>';
    html += '<button data-sn="' + esc(p.serialNo) + '">复制序列号</button>';
    html += '<button onclick="window.print()">打印 / 存为 PDF</button>';
    html += '<button data-fold="1">全部折叠</button>';
    html += '<button data-fold="0">全部展开</button>';
    html += '<span class="hint">数值和结论可以直接在页面上填，填完点「复制整表」粘进系统</span>';
    html += '</div>';

    // 软件版本：本地网页没法自己列目录（file:// 下没有目录枚举，fetch 也被 CORS 拦），
    // 但从文件管理器往网页里拖是给真 File 对象的，所以让人一次拖三张进来
    html += '<div class="sw" id="sw' + pi + '">';
    html += '<div class="swhead">软件版本 <span>可选 · 把 mcu / vcu / dc 三张图一起拖进来</span></div>';
    html += '<div class="drop" data-drop="' + pi + '">拖到这里</div>';
    html += '<div class="swrow" data-swrow="' + pi + '"></div>';
    html += '</div>';

    p.steps.forEach(function(s, si){
      var num = s.order > 0 ? ("0" + s.order).slice(-2) : "—";
      html += '<div class="step" data-p="' + pi + '" data-s="' + si + '">';

      html += '<div class="rail"><div class="node" data-node>' + num + '</div></div>';

      html += '<div class="body">';
      html += '<div class="title" data-toggle>';
      html += '<span class="caret">▼</span>';
      html += '<span>' + esc(s.name) + '</span>';
      html += '<span class="count">' + s.shots.length + ' 张</span>';
      html += '<span class="tag" data-tag></span>';
      html += '</div>';
      if (s.folder) {
        // 传图的正路：复制这一项的文件夹名，去文件管理器里找到它，
        // Ctrl+A 一拖就完事。网页里做不到多选拖拽
        html += '<div class="folder" data-folder="' + esc(s.folder) + '">';
        html += '📁 ' + esc(s.folder) + '　<span>点一下复制文件夹名</span>';
        html += '</div>';
      }
      if (s.refDes) html += '<div class="refdes">' + esc(s.refDes) + '</div>';
      html += '<div class="shots">';
      s.shots.forEach(function(sh){
        html += '<figure>';
        html += '<div class="thumb">';
        html += '<img src="' + esc(sh.file) + '" alt="' + esc(s.name) + '" data-full="' + esc(sh.file) + '"' +
                ' data-note="' + esc(sh.note || "") + '"' +
                ' data-ai="' + esc(sh.ai || "") + '">';
        if (sh.ai) html += '<div class="shotai">' + esc(sh.ai) + '</div>';
        if (sh.note) html += '<div class="shotnote">' + esc(sh.note) + '</div>';
        html += '</div>';
        // 合并成一行之后，光看时间分不清哪张是阻值哪张是电压 ——
        // 测试项名跟行名不一样时才标出来，一样就没必要重复
        var cap = (sh.step && sh.step !== s.name) ? sh.step + '　' + sh.time : sh.time;
        html += '<figcaption>' + esc(cap) + '</figcaption>';
        html += '</figure>';
      });
      html += '</div>';

      // 组装值单独一栏：一个项目十几个分组，值挤进那个数值框里看不清，
      // 而且那个框是留给你手填结论的
      if (s.assembled) {
        html += '<div class="asm copyable" data-copy="' + esc(s.assembled) + '">';
        html += '<label>组装值</label>';
        html += '<span>' + esc(s.assembled) + '</span>';
        html += '</div>';
      }
      if (s.groupAi) {
        html += '<div class="asm gai copyable" data-copy="' + esc(s.groupAi) + '">';
        html += '<label>AI 结论</label>';
        html += '<span>' + esc(s.groupAi) + '</span>';
        html += '</div>';
      }
      html += '</div>';

      html += '<div class="data">';

      // 图里读出来的值放最上面 —— 报表的第一用途就是把这些数抄进系统
      html += '<div class="read">';
      html += '<label>识别值</label>';
      var codes = s.codes || [];
      if (codes.length) {
        codes.forEach(function(c){
          html += '<span class="val copyable" data-copy="' + esc(c) + '">' + esc(c) + '</span>';
        });
      } else {
        html += '<span class="none">这组图里没识别到码</span>';
      }
      html += '</div>';

      html += '<label>读数' + (s.unit ? ' (' + esc(s.unit) + ')' : '') + '</label>';
      if (s.points && s.points.length) {
        // 每个测点一格。管压降六个管子各填各的，才判得出是哪个掉队
        s.points.forEach(function(pt, pi){
          html += '<div class="pt">';
          html += '<span class="ptname">' + esc(pt.name) + '</span>';
          html += '<input type="text" data-pt="' + pi + '" value="' + esc(pt.value) + '">';
          html += '</div>';
        });
      } else {
        html += '<input type="text" data-f="value" placeholder="例如 12.4" value="' + esc(s.value) + '">';
      }
      html += '<div class="auto" data-auto></div>';
      html += '<div class="verdicts">';
      VERDICTS.forEach(function(v){
        html += '<button data-v="' + v[0] + '">' + v[1] + '</button>';
      });
      html += '</div>';
      html += '<div class="rm"><label>备注</label>';
      html += '<input type="text" data-f="remark" value="' + esc(s.remark) + '"></div>';
      html += '</div>';

      html += '</div>';
    });
  });

  app.innerHTML = html;
  bind();
  DATA.projects.forEach(function(p, pi){
    p.steps.forEach(function(s, si){ paint(pi, si); judge(pi, si); });
  });
}

function stepEl(pi, si){
  return document.querySelector('.step[data-p="' + pi + '"][data-s="' + si + '"]');
}

function paint(pi, si){
  var el = stepEl(pi, si);
  if (!el) return;
  var v = DATA.projects[pi].steps[si].verdict;
  var node = el.querySelector("[data-node]");
  node.className = "node" + (v ? " " + v : "");
  var tag = el.querySelector("[data-tag]");
  if (tag) {
    var label = { pass:"正常", fail:"异常", unsure:"待定" };
    tag.className = "tag" + (v ? " " + v : "");
    tag.textContent = label[v] || "";
  }
  el.querySelectorAll(".verdicts button").forEach(function(b){
    b.classList.toggle("on", b.getAttribute("data-v") === v);
  });
}

function num(v){
  var n = parseFloat(String(v == null ? "" : v).replace(/[^0-9.\-]/g, ""));
  return isFinite(n) ? n : null;
}

/**
 * 按流程里配的规则当场判定。
 *
 * 两条规则要同时成立：每个值都在绝对范围内，而且这一组彼此靠拢。
 * 只看范围会漏掉「一个 0.30 一个 0.42」这种都在范围内但明显掉队的；
 * 只看抱团会漏掉「六个都是 0.6」这种整体偏了的。
 */
function judge(pi, si){
  var el = stepEl(pi, si);
  if (!el) return;
  var s = DATA.projects[pi].steps[si];
  var slot = el.querySelector("[data-auto]");
  if (!slot) return;

  var vals = [], names = [];
  if (s.points && s.points.length) {
    s.points.forEach(function(p){ vals.push(num(p.value)); names.push(p.name); });
  } else {
    vals.push(num(s.value)); names.push("");
  }
  var real = vals.filter(function(v){ return v !== null; });
  if (!real.length) { slot.textContent = ""; slot.className = "auto"; return; }

  var bad = [];

  var vr = s.valueRule;
  if (vr && vr.type === "range") {
    vals.forEach(function(v, i){
      if (v === null) return;
      if (vr.min != null && v < vr.min) bad.push((names[i] || "值") + " 低于 " + vr.min);
      if (vr.max != null && v > vr.max) bad.push((names[i] || "值") + " 高于 " + vr.max);
    });
  }

  var gr = s.groupRule;
  if (gr && gr.type === "spread" && real.length > 1) {
    var lo = Math.min.apply(null, real), hi = Math.max.apply(null, real);
    var base = gr.base === "max" ? hi : lo;
    if (base) {
      var ratio = (hi - lo) / base;
      if (ratio > gr.maxRatio) {
        var sorted = real.slice().sort(function(a,b){ return a-b; });
        var mid = sorted[Math.floor(sorted.length/2)];
        var worst = 0, far = -1;
        vals.forEach(function(v, i){
          if (v === null) return;
          var d = Math.abs(v - mid);
          if (d > far) { far = d; worst = i; }
        });
        bad.push("组内偏差 " + Math.round(ratio*100) + "%，超过 " +
                 Math.round(gr.maxRatio*100) + "%（" + (names[worst] || "?") + " 最偏）");
      }
    }
  }

  if (!s.valueRule && !s.groupRule) { slot.textContent = ""; slot.className = "auto"; return; }

  if (bad.length) {
    slot.className = "auto no";
    slot.textContent = "✕ " + bad.join("；");
    if (!s.verdict) { s.verdict = "fail"; paint(pi, si); }
  } else {
    slot.className = "auto yes";
    slot.textContent = "✓ 符合标准";
    if (!s.verdict) { s.verdict = "pass"; paint(pi, si); }
  }
}

/* 软件版本：一个框拖三张，按文件名分到 MCU / VCU / DC */
var SW = {};

function swParse(fileName){
  // 去扩展名。不用正则的行尾锚点 —— 美元符号在 Kotlin 原始字符串里是模板占位
  var dot = fileName.lastIndexOf(".");
  var base = dot > 0 ? fileName.slice(0, dot) : fileName;
  var m = base.match(/^\s*(mcu|vcu|dc)\b/i);
  if (!m) return null;
  var kind = m[1].toUpperCase();
  var ver = base.match(/(\d+(?:\.\d+)+)/);
  return { kind: kind, ver: ver ? ver[1] : "" };
}

/* 版本号按 vcu:123663 这种形状排，一行一条，整段能一次复制 */
function swLines(pi){
  var store = SW[pi] || {};
  var out = [];
  ["MCU","VCU","DC"].forEach(function(k){
    var it = store[k];
    if (it && it.plain) out.push(k.toLowerCase() + ":" + it.plain);
  });
  return out;
}

function swRender(pi){
  var row = document.querySelector('[data-swrow="' + pi + '"]');
  if (!row) return;
  var store = SW[pi] || {};

  var pics = "";
  ["MCU","VCU","DC"].forEach(function(k){
    var it = store[k];
    if (!it) return;
    pics += '<div class="swcell">';
    pics += '<b>' + k + '</b>';
    pics += '<img src="' + it.url + '" alt="' + k + '" data-full="' + it.url + '">';
    pics += '<div class="ver">' + esc(it.ver || "没读出版本号") + '</div>';
    pics += '</div>';
  });

  var lines = swLines(pi);
  var block = "";
  if (lines.length) {
    block += '<div class="swout">';
    block += '<div class="swlist">';
    lines.forEach(function(t){
      var num = t.split(":")[1];
      // 单条点一下只复制数字，填进系统时不用再删前缀
      block += '<div class="swline"><span>' + esc(t.split(":")[0]) + ':</span>' +
               '<b class="copyable" data-copy="' + esc(num) + '">' + esc(num) + '</b></div>';
    });
    block += '</div>';
    block += '<button data-swall="' + pi + '">复制软件版本号</button>';
    block += '</div>';
  }

  row.innerHTML = '<div class="swpics">' + pics + '</div>' + block;
  bindCopy(row);
  bindZoom(row);
  row.querySelectorAll("[data-swall]").forEach(function(b){
    b.addEventListener("click", function(){ copy(swLines(pi).join("\n")); });
  });
}

function swTake(pi, files){
  SW[pi] = SW[pi] || {};
  Array.prototype.forEach.call(files, function(f){
    if (!/^image\//.test(f.type)) return;
    var info = swParse(f.name);
    if (!info) return;
    SW[pi][info.kind] = {
      url: URL.createObjectURL(f),
      ver: info.ver,
      // 去掉点号 —— 填进系统时要的是纯数字
      plain: info.ver ? info.ver.replace(/\./g, "") : ""
    };
  });
  swRender(pi);
}

function bindCopy(root){
  root.querySelectorAll(".copyable").forEach(function(el){
    if (el._c) return;
    el._c = 1;
    el.style.cursor = "copy";
    el.addEventListener("click", function(){ copy(el.getAttribute("data-copy")); });
  });
}

function bindZoom(root){
  var box = document.getElementById("box");
  root.querySelectorAll("[data-full]").forEach(function(img){
    if (img._z) return;
    img._z = 1;
    img.addEventListener("click", function(){
      box.querySelector("img").src = img.getAttribute("data-full");
      // 有备注就把备注顶上来，没有才退回那句操作提示
      var a = img.getAttribute("data-ai") || "";
      var nt = img.getAttribute("data-note") || "";
      // 显示带「AI：」前缀好分辨是机器读的，复制出来只要值本身 ——
      // 这个数是要抄进系统的，带上前缀还得手动删
      var shown = [a ? "AI：" + a : "", nt].filter(Boolean).join("\n");
      var copyable = [a, nt].filter(Boolean).join("\n");
      var cap = box.querySelector(".cap");
      cap.textContent = shown || "右键可以复制图片";
      cap.className = shown ? "cap has" : "cap";
      cap.setAttribute("data-copy", copyable);
      box.style.display = "flex";
    });
  });
}

function bind(){
  document.querySelectorAll("[data-drop]").forEach(function(z){
    var pi = +z.getAttribute("data-drop");
    ["dragenter","dragover"].forEach(function(ev){
      z.addEventListener(ev, function(e){ e.preventDefault(); z.classList.add("hot"); });
    });
    ["dragleave","drop"].forEach(function(ev){
      z.addEventListener(ev, function(e){ e.preventDefault(); z.classList.remove("hot"); });
    });
    z.addEventListener("drop", function(e){
      if (e.dataTransfer && e.dataTransfer.files) swTake(pi, e.dataTransfer.files);
    });
    z.addEventListener("click", function(){
      var inp = document.createElement("input");
      inp.type = "file"; inp.multiple = true; inp.accept = "image/*";
      inp.addEventListener("change", function(){ swTake(pi, inp.files); });
      inp.click();
    });
  });

  bindCopy(document);

  document.querySelectorAll("[data-folder]").forEach(function(el){
    el.addEventListener("click", function(){
      copy(el.getAttribute("data-folder"));
    });
  });

  document.querySelectorAll("[data-sn]").forEach(function(b){
    b.addEventListener("click", function(){ copy(b.getAttribute("data-sn")); });
  });

  document.querySelectorAll("[data-tsv]").forEach(function(b){
    b.addEventListener("click", function(){ copy(tsv(+b.getAttribute("data-tsv"))); });
  });

  document.querySelectorAll(".step").forEach(function(el){
    var pi = +el.getAttribute("data-p"), si = +el.getAttribute("data-s");
    var step = DATA.projects[pi].steps[si];

    el.querySelectorAll("input[data-f]").forEach(function(inp){
      inp.addEventListener("input", function(){
        step[inp.getAttribute("data-f")] = inp.value;
        judge(pi, si);
      });
    });

    el.querySelectorAll("input[data-pt]").forEach(function(inp){
      inp.addEventListener("input", function(){
        step.points[+inp.getAttribute("data-pt")].value = inp.value;
        judge(pi, si);
      });
    });

    el.querySelectorAll(".verdicts button").forEach(function(b){
      b.addEventListener("click", function(){
        var v = b.getAttribute("data-v");
        step.verdict = (step.verdict === v) ? "" : v;
        paint(pi, si);
      });
    });
  });

  document.querySelectorAll("[data-toggle]").forEach(function(t){
    t.addEventListener("click", function(){
      t.closest(".step").classList.toggle("shut");
    });
  });

  document.querySelectorAll("[data-fold]").forEach(function(b){
    b.addEventListener("click", function(){
      var shut = b.getAttribute("data-fold") === "1";
      document.querySelectorAll(".step").forEach(function(el){
        el.classList.toggle("shut", shut);
      });
    });
  });

  bindZoom(document);
  var box = document.getElementById("box");
  // 点备注是复制，不是关灯箱 —— 得先把事件截住，否则冒泡上去图就关了
  box.querySelector(".cap").addEventListener("click", function(e){
    var t = this.getAttribute("data-copy");
    if (!t) return;
    e.stopPropagation();
    copy(t);
  });
  box.addEventListener("click", function(){ box.style.display = "none"; });
  document.addEventListener("keydown", function(e){
    if (e.key === "Escape") box.style.display = "none";
  });
}

/* 制表符分隔，粘进 Excel 或系统表格就是整齐的几列 */
function tsv(pi){
  var p = DATA.projects[pi];
  var label = { pass:"正常", fail:"异常", unsure:"待定", "":"" };
  var rows = [];
  swLines(pi).forEach(function(t){ rows.push(t); });
  if (rows.length) rows.push("");
  rows.push(["序号","检修项目","位号","读数","结论","备注","图片"].join("\t"));
  p.steps.forEach(function(s){
    var files = s.shots.map(function(x){ return x.name; }).join(" ");
    var reading = (s.points && s.points.length)
      ? s.points.map(function(p){ return p.name + "=" + (p.value || ""); }).join(" ")
      : (s.value || "");
    rows.push([
      s.order > 0 ? s.order : "",
      s.name, s.refDes || "", reading,
      label[s.verdict] || "", s.remark || "", files
    ].join("\t"));
  });
  return rows.join("\n");
}

render();
</script>

<footer>
  <b>图片怎么传进系统：</b>每一项下面有它的文件夹名，点一下复制，
  去 <b>水印图</b> 里找到那个文件夹，Ctrl+A 全选拖进上传框。<br>
  从这个页面里一次只能拖一张 —— 浏览器的拖拽一次只带一个元素，选中再多张也没用。<br>
  <b>数值填完别忘了点「复制整表」</b>，页面上的内容刷新就没了，不会自动保存。<br>
  <b>report.json</b> 和这个页面同目录，里面是同一份数据，方便以后用脚本或浏览器插件读。
</footer>
</body>
</html>
"""
}
