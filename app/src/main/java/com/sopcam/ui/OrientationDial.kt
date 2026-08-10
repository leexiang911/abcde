package com.sopcam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sopcam.watermark.Anchor
import com.sopcam.watermark.TopEdge
import com.sopcam.watermark.previewCornerIndex
import com.sopcam.watermark.quarterTurns
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

/* ==================================================================
 * 取景器上的实时水印
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
 * 水印位置：田字格，中间禁止符，四周旋转标签
 * ================================================================== */

/** 成片坐标系下的四个角，按行优先排：左上 右上 / 左下 右下 */
private val anchorGrid = listOf(
    listOf(Anchor.TOP_LEFT, Anchor.TOP_RIGHT),
    listOf(Anchor.BOTTOM_LEFT, Anchor.BOTTOM_RIGHT),
)

@Composable
fun AnchorButton(anchor: Anchor, edge: TopEdge, visible: Boolean, onToggle: () -> Unit) {
    val tint = if (visible) Amber else Steel
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, tint, RoundedCornerShape(4.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        if (visible) MiniGrid(anchor, edge) else BlockedBadge(28.dp, 13.sp, Steel)
    }
}

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
fun AnchorGridPanel(
    anchor: Anchor,
    edge: TopEdge,
    visible: Boolean,
    onPick: (Anchor) -> Unit,
    onDisable: () -> Unit,
) {
    val turns = edge.quarterTurns()

    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xF2161A1F))
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        // 标题绕着格子走：它停在哪一边，哪一边就是成片的上方。
        // 这样标题本身就是方向指示，不用再单独画箭头。
        Box(Modifier.size(184.dp), contentAlignment = ringSlot(turns)) {
            Text(
                if (visible) "水印位置" else "不加水印",
                color = if (visible) Steel else Amber,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.rotate(turns * 90f)
            )
        }

        // 关掉时四个角一起变暗，一眼看出现在谁也没选中。
        // 但仍然可点：点任意一格等于重新打开并选中那个角。
        Column(Modifier.rotate(turns * 90f)) {
            anchorGrid.forEach { row ->
                Row {
                    row.forEach { a ->
                        val on = visible && a == anchor
                        Box(
                            Modifier
                                .padding(3.dp)
                                .size(58.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    when {
                                        on -> Amber
                                        visible -> Color(0xF22A3037)
                                        else -> Color(0x552A3037)
                                    }
                                )
                                .clickable { onPick(a) }
                        )
                    }
                }
            }
        }

        // 总闸压在四格正中。它不跟着转 —— 字母 A 得始终正着才认得出。
        Box(
            Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(if (visible) Color(0xFF161A1F) else Amber)
                .clickable(onClick = onDisable),
            contentAlignment = Alignment.Center
        ) {
            BlockedBadge(38.dp, 15.sp, if (visible) Steel else Ink)
        }
    }
}

/** 标题停在格子哪一边，按成片上方所在的屏幕方位来 */
private fun ringSlot(turns: Int) = when (turns) {
    1 -> Alignment.CenterEnd
    2 -> Alignment.BottomCenter
    3 -> Alignment.CenterStart
    else -> Alignment.TopCenter
}

@Composable
private fun BlockedBadge(size: Dp, letter: TextUnit, tint: Color) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = this.size.minDimension * 0.075f
            val r = (this.size.minDimension - stroke) / 2f
            val c = Offset(this.size.width / 2f, this.size.height / 2f)
            drawCircle(tint, radius = r, center = c, style = Stroke(width = stroke))
            // 斜杠只画两截，中间让给字母 A —— 整条压过去 A 就糊了
            val outer = r * 0.707f
            val inner = r * 0.32f
            listOf(1f, -1f).forEach { sign ->
                drawLine(
                    tint,
                    start = Offset(c.x + outer * sign, c.y - outer * sign),
                    end = Offset(c.x + inner * sign, c.y - inner * sign),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            }
        }
        Text("A", color = tint, fontSize = letter, fontWeight = FontWeight.Bold)
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

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
