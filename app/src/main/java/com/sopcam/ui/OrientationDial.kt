package com.sopcam.ui

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
import androidx.compose.foundation.layout.width
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
 * 取景器上的实时水印
 *
 * 不是装饰，是成片的等比示意：位置、角度、内容都跟烧录出来的一致。
 * 锁了方向之后它在屏幕上会躺倒——那正是要传达的信息：
 * 成片会把这块转正，你看到的是它转过去之前的样子。
 * ================================================================== */

/** 顺时针环：左上 → 右上 → 右下 → 左下 */
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

    Column(
        Modifier
            .align(cornerAlignments[anchor.previewCornerIndex(edge)])
            .padding(14.dp)
            .rotate(edge.quarterTurns() * 90f)
            .widthIn(max = 210.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xA6101418))
            .padding(horizontal = 9.dp, vertical = 7.dp)
    ) {
        headline?.takeIf { it.isNotBlank() }?.let {
            Text(
                it, color = Amber, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
        }
        lines.filter { it.isNotBlank() }.forEach {
            Text(
                it, color = Color.White, fontSize = 9.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 贴在取景框边缘的「成片上方」角标，比水印本身更快能确认方向 */
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
            .padding(4.dp)
            .rotate(shown.quarterTurns() * 90f)
            .background(Color(0xCC101418), RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    )
}

/* ==================================================================
 * 方向：十字盘
 *
 * 四个方向按它在手机上的实际位置摆——想让成片顶部朝左，就点左边那格，
 * 不用在脑子里做一次映射。中间是自动。
 * ================================================================== */

@Composable
fun OrientationDial(edge: TopEdge, effective: TopEdge, onToggle: () -> Unit) {
    val shown = if (edge == TopEdge.AUTO) effective else edge
    Text(
        (if (edge == TopEdge.AUTO) "自动 " else "上方 ") + arrowFor(shown),
        color = if (edge == TopEdge.AUTO) Steel else Amber,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, if (edge == TopEdge.AUTO) Steel else Amber, RoundedCornerShape(4.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    )
}

@Composable
fun OrientationDialPanel(edge: TopEdge, onPick: (TopEdge) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        DialCell("上 ↑", edge == TopEdge.TOP) { onPick(TopEdge.TOP) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            DialCell("← 左", edge == TopEdge.LEFT) { onPick(TopEdge.LEFT) }
            DialCell("自动", edge == TopEdge.AUTO, wide = true) { onPick(TopEdge.AUTO) }
            DialCell("右 →", edge == TopEdge.RIGHT) { onPick(TopEdge.RIGHT) }
        }
        DialCell("下 ↓", edge == TopEdge.BOTTOM) { onPick(TopEdge.BOTTOM) }
    }
}

private fun arrowFor(edge: TopEdge): String = when (edge) {
    TopEdge.LEFT -> "←"
    TopEdge.RIGHT -> "→"
    TopEdge.BOTTOM -> "↓"
    else -> "↑"
}

@Composable
private fun DialCell(label: String, active: Boolean, wide: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(3.dp)
            .size(width = if (wide) 68.dp else 62.dp, height = 54.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) Amber else Color(0xF22A3037))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (active) Ink else Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/* ==================================================================
 * 水印位置：田字格
 *
 * 整块格子跟着成片方向一起转，所以「点哪个小格」= 水印出现在屏幕的哪个位置，
 * 不用再想成片转过去之后会落到哪。
 * ================================================================== */

/** 成片坐标系下的四个角，按行优先排：左上 右上 / 左下 右下 */
private val anchorGrid = listOf(
    listOf(Anchor.TOP_LEFT, Anchor.TOP_RIGHT),
    listOf(Anchor.BOTTOM_LEFT, Anchor.BOTTOM_RIGHT),
)

@Composable
fun AnchorButton(anchor: Anchor, edge: TopEdge, onToggle: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, Amber, RoundedCornerShape(4.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        MiniGrid(anchor, edge)
    }
}

/** 收起时的小图标：一个田字，亮着的那格就是水印的位置 */
@Composable
private fun MiniGrid(anchor: Anchor, edge: TopEdge) {
    Column(Modifier.rotate(edge.quarterTurns() * 90f)) {
        anchorGrid.forEach { row ->
            Row {
                row.forEach { a ->
                    Box(
                        Modifier
                            .padding(1.dp)
                            .size(12.dp)
                            .background(
                                if (a == anchor) Amber else Color(0x552A3037),
                                RoundedCornerShape(2.dp)
                            )
                    )
                }
            }
        }
    }
}

@Composable
fun AnchorGridPanel(anchor: Anchor, edge: TopEdge, onPick: (Anchor) -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xF2161A1F))
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("水印位置", color = Steel, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        Column(Modifier.rotate(edge.quarterTurns() * 90f)) {
            anchorGrid.forEach { row ->
                Row {
                    row.forEach { a ->
                        Box(
                            Modifier
                                .padding(3.dp)
                                .size(58.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (a == anchor) Amber else Color(0xF22A3037))
                                .clickable { onPick(a) }
                        )
                    }
                }
            }
        }
    }
}

/** 面板展开时铺满全屏的遮罩，点空白处收起 */
@Composable
fun DialScrim(onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xB3000000))
            .clickable(onClick = onDismiss)
    )
}

/** 控制条上的按钮文字，跟着成片方向一起转 */
@Composable
fun RotatingTag(
    label: String,
    edge: TopEdge,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (highlighted) Amber else Steel
    Box(
        Modifier
            .width(96.dp)
            .height(48.dp)
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, tint, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = tint,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.rotate(edge.quarterTurns() * 90f)
        )
    }
}
