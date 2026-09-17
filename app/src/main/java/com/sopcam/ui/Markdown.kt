package com.sopcam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 提示文本的极简 Markdown。
 *
 * 只认这几样，不引任何 Markdown 库 —— 提示词是人手写在后台的短文，
 * 为它拉进来一个完整的解析器不划算，而且库大多要带自己的排版体系，
 * 跟这里的深色配色对不上。
 *
 *   **加粗**
 *   !!红!!      危险、别碰、会烧板
 *   ^^琥珀^^    重点、注意看这里
 *   ++绿++      正常、合格、就该是这样
 *   - 无序项
 *   1. 有序项
 *   ---         分割线
 *
 * 颜色选的是 App 里本来就在用的那三个语义色，跟相机界面、报表的判定色是同一套，
 * 不另起炉灶。
 */

private val MdRed = Color(0xFFE86A5C)
private val MdAmber = Amber
private val MdGreen = Done

// 四种行内标记。都用成对的双字符 —— 单字符太容易被正文里的标点误触发
private val INLINE = Regex("""\*\*(.+?)\*\*|!!(.+?)!!|\^\^(.+?)\^\^|\+\+(.+?)\+\+""")

/*
 * 有序列表。点号和右括号后面必须跟空格，否则「1.5V 是电压」会被当成第 1 项；
 * 中文顿号不要求空格 —— 中文本来就写「1、内容」，而顿号也不会出现在数值里。
 * 限两位数，免得把「2023. 年初」这种年份开头的句子吃掉。
 */
private val ORDERED = Regex("""^(\d{1,2})(?:[.)]\s+|、\s*)(.*)""")

/** 把一行里的行内标记翻成带样式的文本 */
fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    INLINE.findAll(text).forEach { m ->
        if (m.range.first > cursor) append(text.substring(cursor, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() -> withSpan(SpanStyle(fontWeight = FontWeight.Bold), g[1])
            g[2].isNotEmpty() -> withSpan(SpanStyle(color = MdRed, fontWeight = FontWeight.Medium), g[2])
            g[3].isNotEmpty() -> withSpan(SpanStyle(color = MdAmber, fontWeight = FontWeight.Medium), g[3])
            g[4].isNotEmpty() -> withSpan(SpanStyle(color = MdGreen, fontWeight = FontWeight.Medium), g[4])
        }
        cursor = m.range.last + 1
    }
    if (cursor < text.length) append(text.substring(cursor))
}

private fun AnnotatedString.Builder.withSpan(
    style: SpanStyle,
    body: String,
) {
    pushStyle(style)
    append(body)
    pop()
}

/**
 * 按行渲染。
 *
 * 列表用 Row 把标记和正文分成两列，正文换行时会跟首行对齐，
 * 不会绕到标记底下去 —— 那是「把标记和文字拼成一个字符串」最常见的毛病。
 */
@Composable
fun MarkdownText(
    text: String,
    color: Color = Color.White,
    fontSize: TextUnit = 13.sp,
    lineHeight: TextUnit = 20.sp,
    modifier: Modifier = Modifier,
) {
    // 拆行和判断类型跟重组无关，缓存一次
    val blocks = remember(text) { text.split("\n") }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        blocks.forEach { raw ->
            val line = raw.trim()
            // 先匹配一次，下面直接用结果 —— matches 再 find 是跑两遍，
            // 而且那样就得用 !! 把可空性硬拆掉
            val ordered = ORDERED.matchEntire(line)
            when {
                line.isEmpty() -> Spacer(Modifier.height(6.dp))

                line == "---" || line == "***" || line == "___" ->
                    Box(
                        Modifier
                            .padding(vertical = 7.dp)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color(0x33FFFFFF))
                    )

                line.startsWith("- ") || line.startsWith("* ") ->
                    MdListRow("•", line.substring(2), color, fontSize, lineHeight)

                ordered != null -> MdListRow(
                    ordered.groupValues[1] + ".",
                    ordered.groupValues[2],
                    color, fontSize, lineHeight
                )

                else -> Text(
                    inlineMarkdown(line),
                    color = color,
                    fontSize = fontSize,
                    lineHeight = lineHeight
                )
            }
        }
    }
}

@Composable
private fun MdListRow(
    marker: String,
    body: String,
    color: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            marker,
            color = Steel,
            fontSize = fontSize,
            lineHeight = lineHeight,
            modifier = Modifier.width(20.dp)
        )
        Text(
            inlineMarkdown(body),
            color = color,
            fontSize = fontSize,
            lineHeight = lineHeight,
            modifier = Modifier.weight(1f)
        )
    }
}
