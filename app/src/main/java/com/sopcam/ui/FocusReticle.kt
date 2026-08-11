package com.sopcam.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

enum class FocusStatus { FOCUSING, OK, FAILED }

/**
 * 一个对焦点。
 *
 * x/y 是取景框内的像素坐标，直接拿来摆对焦框，
 * 也直接交给 PreviewView.meteringPointFactory 换算成相机坐标。
 */
data class FocusSpot(
    val x: Float,
    val y: Float,
    val status: FocusStatus,
    val locked: Boolean,
)

private val ReticleSize = 84.dp
private val LockGap = 76.dp

/**
 * 苹果那种四角线对焦框 —— 只画四个角，中间全空。
 *
 * 检修拍的是焊点和丝印，画完整方框会盖住要看的东西，
 * 四角线占的面积小，又能明确框出范围。
 */
@Composable
fun FocusReticle(
    spot: FocusSpot,
    dragX: Float,
    dragging: Boolean,
    lockArmed: Boolean,
) {
    val tint = when (spot.status) {
        FocusStatus.FOCUSING -> Amber
        FocusStatus.OK -> Done
        FocusStatus.FAILED -> Color(0xFFE86A5C)
    }

    Box(
        Modifier
            .offset {
                // 减去半个框，让框以触点为中心
                IntOffset(
                    (spot.x - ReticleSize.toPx() / 2f).roundToInt(),
                    (spot.y - ReticleSize.toPx() / 2f).roundToInt()
                )
            }
            .size(ReticleSize)
    ) {
        Canvas(Modifier.size(ReticleSize)) {
            val arm = size.minDimension * 0.26f
            val w = 2.5.dp.toPx()
            corners(tint, arm, w)
        }
    }

    // 拖拽中：在框左边亮出锁标，拖过去就锁定
    if (dragging || spot.locked) {
        val armed = lockArmed || (spot.locked && !dragging)
        Box(
            Modifier
                .offset {
                    val baseX = spot.x - LockGap.toPx() - 14.dp.toPx()
                    val slide = if (dragging) dragX.coerceIn(-LockGap.toPx(), 0f) else 0f
                    IntOffset(
                        (baseX - slide * 0.15f).roundToInt(),
                        (spot.y - 14.dp.toPx()).roundToInt()
                    )
                }
                .size(28.dp)
        ) {
            Canvas(Modifier.size(28.dp)) {
                lock(if (armed) Amber else Steel, armed)
            }
        }
    }
}

/** 四角短线：每个角画两笔 */
private fun DrawScope.corners(tint: Color, arm: Float, w: Float) {
    val pad = w / 2f
    val right = size.width - pad
    val bottom = size.height - pad
    val segs = listOf(
        // 左上
        Offset(pad, pad + arm) to Offset(pad, pad),
        Offset(pad, pad) to Offset(pad + arm, pad),
        // 右上
        Offset(right - arm, pad) to Offset(right, pad),
        Offset(right, pad) to Offset(right, pad + arm),
        // 右下
        Offset(right, bottom - arm) to Offset(right, bottom),
        Offset(right, bottom) to Offset(right - arm, bottom),
        // 左下
        Offset(pad + arm, bottom) to Offset(pad, bottom),
        Offset(pad, bottom) to Offset(pad, bottom - arm),
    )
    segs.forEach { (a, b) ->
        drawLine(tint, a, b, strokeWidth = w, cap = StrokeCap.Round)
    }
}

/** 锁：下面一个方身，上面一道弧形锁梁。锁上时锁梁合拢，开着时向右偏 */
private fun DrawScope.lock(tint: Color, closed: Boolean) {
    val w = size.width
    val bodyW = w * 0.62f
    val bodyH = w * 0.46f
    val bodyLeft = (w - bodyW) / 2f
    val bodyTop = size.height - bodyH - w * 0.06f
    val stroke = w * 0.09f

    drawRoundRect(
        color = tint,
        topLeft = Offset(bodyLeft, bodyTop),
        size = Size(bodyW, bodyH),
        cornerRadius = CornerRadius(w * 0.08f)
    )

    val arcW = bodyW * 0.62f
    val arcLeft = if (closed) (w - arcW) / 2f else (w - arcW) / 2f + w * 0.16f
    drawArc(
        color = tint,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(arcLeft, bodyTop - arcW * 0.62f),
        size = Size(arcW, arcW * 0.9f),
        style = Stroke(width = stroke, cap = StrokeCap.Round)
    )
}
