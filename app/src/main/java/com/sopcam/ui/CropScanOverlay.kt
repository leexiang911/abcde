package com.sopcam.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 框出来的区域，已经换算成原图像素坐标 */
data class CropRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * 在图上拖一个框，只识别框里的那块。
 *
 * 屏幕坐标要换算回原图坐标：图是 ContentScale.Fit 摆的，
 * 实际绘制区域比容器小，四周有留白，直接按比例换算会整体偏移。
 */
@Composable
fun CropScanOverlay(
    bitmapWidth: Int,
    bitmapHeight: Int,
    busy: Boolean,
    result: String?,
    onScan: (CropRect) -> Unit,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    var container by remember { mutableStateOf(IntSize.Zero) }
    var start by remember { mutableStateOf<Offset?>(null) }
    var current by remember { mutableStateOf<Offset?>(null) }

    // 图实际画在容器里的哪块 —— Fit 是等比缩放后居中
    val drawn: Rect? = remember(container, bitmapWidth, bitmapHeight) {
        if (container.width == 0 || bitmapWidth == 0) null
        else {
            val scale = min(
                container.width.toFloat() / bitmapWidth,
                container.height.toFloat() / bitmapHeight
            )
            val w = bitmapWidth * scale
            val h = bitmapHeight * scale
            Rect(
                Offset((container.width - w) / 2f, (container.height - h) / 2f),
                Size(w, h)
            )
        }
    }

    val box: Rect? = remember(start, current) {
        val a = start
        val b = current
        if (a == null || b == null) null
        else Rect(
            min(a.x, b.x), min(a.y, b.y),
            max(a.x, b.x), max(a.y, b.y)
        ).takeIf { abs(b.x - a.x) > 24f && abs(b.y - a.y) > 24f }
    }

    BackHandler { onClose() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xF7000000))
            .onSizeChanged { container = it }
    ) {
        content()

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(bitmapWidth, bitmapHeight) {
                    detectDragGestures(
                        onDragStart = { off ->
                            start = off
                            current = off
                        },
                        onDrag = { change, _ ->
                            current = change.position
                            change.consume()
                        },
                    )
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                box?.let { r ->
                    drawRect(
                        color = Color(0xFFFDCE04),
                        topLeft = r.topLeft,
                        size = r.size,
                        style = Stroke(width = 3.dp.toPx())
                    )
                }
            }
        }

        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 36.dp, start = 20.dp, end = 20.dp)
        ) {
            Text(
                "在码的位置拖一个框",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "框住整个码，四周留一点空白 —— 太贴边反而认不出来",
                color = Steel,
                fontSize = 12.sp
            )
            result?.let {
                Spacer(Modifier.height(10.dp))
                Text(
                    it,
                    color = if (it.startsWith("·")) Color(0xFFE86A5C) else Done,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x33FFFFFF))
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                )
            }
        }

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 30.dp)
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val ready = box != null && drawn != null && !busy
            Text(
                if (busy) "识别中…" else "识别选中区域",
                color = if (ready) Ink else Color(0xFF4A525C),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (ready) Amber else Color(0xFF262D35))
                    .clickable(enabled = ready) {
                        val r = box!!
                        val d = drawn!!
                        val scale = d.width / bitmapWidth
                        onScan(
                            CropRect(
                                left = ((r.left - d.left) / scale).roundToInt(),
                                top = ((r.top - d.top) / scale).roundToInt(),
                                right = ((r.right - d.left) / scale).roundToInt(),
                                bottom = ((r.bottom - d.top) / scale).roundToInt(),
                            )
                        )
                    }
                    .padding(vertical = 14.dp)
            )
            Text(
                "关闭",
                color = Steel,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFF2A3037), RoundedCornerShape(8.dp))
                    .clickable(onClick = onClose)
                    .padding(vertical = 14.dp)
            )
        }
    }
}
