package com.sopcam.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sopcam.watermark.Anchor
import com.sopcam.watermark.TopEdge
import com.sopcam.watermark.previewCornerIndex
import com.sopcam.watermark.quarterTurns

/* ==================================================================
 * 实时水印预览
 *
 * 画的不是装饰，是成片的等比示意：位置、角度、内容都跟烧录出来的一致。
 * 锁了方向之后水印在屏幕上会躺倒——那正是想传达的信息：
 * 成片会把这一块转正，你现在看到的是它转过去之前的样子。
 * ================================================================== */

/** 顺时针环：左上 → 右上 → 右下 → 左下。previewCornerIndex 返回的就是这个环的下标。 */
private val cornerAlignments = listOf(
    Alignment.TopStart,
    Alignment.TopEnd,
    Alignment.BottomEnd,
    Alignment.BottomStart,
)

@Composable
fun BoxScope.WatermarkPreview(
    headline: String?,
    lines: List<String>,
    anchor: Anchor,
    edge: TopEdge,
) {
    if (headline.isNullOrBlank() && lines.all { it.isBlank() }) return

    val alignment = cornerAlignments[anchor.previewCornerIndex(edge)]
    val degrees = edge.quarterTurns() * 90f

    Column(
        Modifier
            .align(alignment)
            .padding(20.dp)
            .rotate(degrees)
            .widthIn(max = 230.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xA6101418))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        headline?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                color = Amber,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
        }
        lines.filter { it.isNotBlank() }.forEach {
            Text(
                it,
                color = Color.White,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 成片「上」在哪一边的角标。
 * 贴在预览边缘，比水印本身更快能确认方向对不对。
 */
@Composable
fun BoxScope.TopEdgeMarker(edge: TopEdge, effective: TopEdge) {
    val shown = if (edge == TopEdge.AUTO) effective else edge
    val alignment = when (shown) {
        TopEdge.LEFT -> Alignment.CenterStart
        TopEdge.RIGHT -> Alignment.CenterEnd
        TopEdge.BOTTOM -> Alignment.BottomCenter
        else -> Alignment.TopCenter
    }
    Text(
        "成片上方",
        color = Amber,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .align(alignment)
            .padding(6.dp)
            .rotate(shown.quarterTurns() * 90f)
            .background(Color(0xCC101418), RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    )
}

/* ==================================================================
 * 十字方向选择器
 *
 * 收起时是一个小方块，显示当前朝向；点开是个十字，
 * 四个方向按它在手机上的实际位置摆——选「左」就点左边那格，
 * 不用在脑子里做一次映射。中间是自动。
 * ================================================================== */

@Composable
fun OrientationDial(
    edge: TopEdge,
    effective: TopEdge,
    expanded: Boolean,
    onToggle: () -> Unit,
    onPick: (TopEdge) -> Unit,
) {
    Box(contentAlignment = Alignment.Center) {

        AnimatedVisibility(visible = expanded) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Cell("上", edge == TopEdge.TOP) { onPick(TopEdge.TOP) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Cell("左", edge == TopEdge.LEFT) { onPick(TopEdge.LEFT) }
                    Cell("自动", edge == TopEdge.AUTO, wide = true) { onPick(TopEdge.AUTO) }
                    Cell("右", edge == TopEdge.RIGHT) { onPick(TopEdge.RIGHT) }
                }
                Cell("下", edge == TopEdge.BOTTOM) { onPick(TopEdge.BOTTOM) }
            }
        }

        if (!expanded) {
            val label = when (edge) {
                TopEdge.AUTO -> "自动 " + arrowFor(effective)
                else -> "上方 " + arrowFor(edge)
            }
            Text(
                label,
                color = if (edge == TopEdge.AUTO) Steel else Amber,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .border(
                        1.dp,
                        if (edge == TopEdge.AUTO) Steel else Amber,
                        RoundedCornerShape(4.dp)
                    )
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            )
        }
    }
}

private fun arrowFor(edge: TopEdge): String = when (edge) {
    TopEdge.LEFT -> "←"
    TopEdge.RIGHT -> "→"
    TopEdge.BOTTOM -> "↓"
    else -> "↑"
}

@Composable
private fun Cell(
    label: String,
    active: Boolean,
    wide: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .padding(3.dp)
            .size(width = if (wide) 64.dp else 56.dp, height = 52.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) Amber else Color(0xE62A3037))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (active) Ink else Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/** 展开时铺满全屏的遮罩，点空白处收起 */
@Composable
fun DialScrim(onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable(onClick = onDismiss)
    )
}

