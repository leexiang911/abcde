package com.sopcam.archive

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
    ): JSONObject {
        val projects = JSONArray()
        val index = Archive.list().associateBy { it.serialNo }

        serials.forEach { sn ->
            val meta = index[sn]
            val steps = LinkedHashMap<Int, JSONObject>()

            Archive.shots(sn).forEach { raw ->
                val side = Archive.sidecar(raw) ?: JSONObject()
                val order = side.optInt("stepOrder", 0)

                val group = steps.getOrPut(order) {
                    JSONObject()
                        .put("order", order)
                        .put("name", side.optString("stepName").ifBlank { "自由拍摄" })
                        .put("refDes", side.optString("stepRefDes"))
                        .put("shots", JSONArray())
                        .put("value", "")
                        .put("unit", "")
                        .put("verdict", "")
                        .put("remark", "")
                }

                // 报表引用的是包里那份的路径，不是手机上的路径
                val path = if (watermarked) {
                    val name = side.optString("fileName")
                        .ifBlank { raw.nameWithoutExtension }
                    "$sn/水印图/$name.$imageExt"
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
                )
            }

            // 自由拍摄（序号 0）排到最后，按流程走的那些在前
            val ordered = steps.values.sortedWith(
                compareBy({ if (it.optInt("order") == 0) 1 else 0 }, { it.optInt("order") })
            )

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
.title{font-size:16.5px;font-weight:600}
.refdes{font-family:var(--mono);font-size:12px;color:var(--mute);margin-top:3px}
.shots{display:flex;flex-wrap:wrap;gap:10px;margin-top:13px}
figure{margin:0;width:172px}
figure img{width:172px;height:129px;object-fit:cover;border:1px solid var(--rule);
  background:#fff;cursor:zoom-in;display:block}
figure img:hover{border-color:var(--ink)}
figcaption{font-family:var(--mono);font-size:10.5px;color:var(--mute);
  margin-top:4px;word-break:break-all;line-height:1.35}
.code{display:inline-block;margin-top:8px;font-family:var(--mono);font-size:12px;
  padding:3px 8px;background:#fff;border:1px solid var(--rule);cursor:copy}
.code:hover{border-color:var(--ink)}

.data{padding:22px 0 22px 20px;border-left:1px solid var(--rule)}
label{display:block;font-size:11px;letter-spacing:.1em;color:var(--mute);
  text-transform:uppercase;font-family:var(--mono);margin-bottom:5px}
input[type=text]{width:100%;font:inherit;font-family:var(--mono);font-size:15px;
  padding:8px 10px;border:1px solid var(--rule);background:#fff}
input[type=text]:focus{outline:2px solid var(--mark);outline-offset:-1px;border-color:var(--mark)}
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
    html += '<span class="hint">数值和结论可以直接在页面上填，填完点「复制整表」粘进系统</span>';
    html += '</div>';

    p.steps.forEach(function(s, si){
      var num = s.order > 0 ? ("0" + s.order).slice(-2) : "—";
      html += '<div class="step" data-p="' + pi + '" data-s="' + si + '">';

      html += '<div class="rail"><div class="node" data-node>' + num + '</div></div>';

      html += '<div class="body">';
      html += '<div class="title">' + esc(s.name) + '</div>';
      if (s.refDes) html += '<div class="refdes">' + esc(s.refDes) + '</div>';
      html += '<div class="shots">';
      s.shots.forEach(function(sh){
        html += '<figure>';
        html += '<img src="' + esc(sh.file) + '" alt="' + esc(s.name) + '" data-full="' + esc(sh.file) + '">';
        html += '<figcaption>' + esc(sh.time) + ' · ' + esc(sh.name) + '</figcaption>';
        html += '</figure>';
      });
      html += '</div>';
      var codes = [];
      s.shots.forEach(function(sh){ if (sh.code && codes.indexOf(sh.code) < 0) codes.push(sh.code); });
      codes.forEach(function(c){
        html += '<span class="code copyable" data-copy="' + esc(c) + '">' + esc(c) + '</span> ';
      });
      html += '</div>';

      html += '<div class="data">';
      html += '<label>读数</label>';
      html += '<input type="text" data-f="value" placeholder="例如 12.4V" value="' + esc(s.value) + '">';
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
    p.steps.forEach(function(s, si){ paint(pi, si); });
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
  el.querySelectorAll(".verdicts button").forEach(function(b){
    b.classList.toggle("on", b.getAttribute("data-v") === v);
  });
}

function bind(){
  document.querySelectorAll(".copyable").forEach(function(el){
    el.style.cursor = "copy";
    el.addEventListener("click", function(){ copy(el.getAttribute("data-copy")); });
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

  var box = document.getElementById("box");
  document.querySelectorAll("[data-full]").forEach(function(img){
    img.addEventListener("click", function(){
      box.querySelector("img").src = img.getAttribute("data-full");
      box.querySelector(".cap").textContent =
        img.getAttribute("data-full") + "   右键可以复制图片";
      box.style.display = "flex";
    });
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
  var rows = [["序号","检修项目","位号","读数","结论","备注","图片"].join("\t")];
  p.steps.forEach(function(s){
    var files = s.shots.map(function(x){ return x.name; }).join(" ");
    rows.push([
      s.order > 0 ? s.order : "",
      s.name, s.refDes || "", s.value || "",
      label[s.verdict] || "", s.remark || "", files
    ].join("\t"));
  });
  return rows.join("\n");
}

render();
</script>

<footer>
  <b>图片怎么传进系统：</b>点开大图后右键「复制图片」，或者直接从 <b>水印图</b> 文件夹拖进上传框。
  从这个页面里拖图片是拖不进去的 —— 浏览器传的是链接不是文件。<br>
  <b>数值填完别忘了点「复制整表」</b>，页面上的内容刷新就没了，不会自动保存。<br>
  <b>report.json</b> 和这个页面同目录，里面是同一份数据，方便以后用脚本或浏览器插件读。
</footer>
</body>
</html>
"""
}
