package com.sopcam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 闪光灯三态。
 *
 * 检修场景里 TORCH 才是主力 —— 焊点和丝印要一边照一边看构图，
 * 拍的瞬间才亮的闪光灯反而没法预判效果。
 */
enum class FlashMode(val label: String) {
    OFF("关闭"),
    ON("开启"),
    TORCH("常亮");

    fun next(): FlashMode = when (this) {
        OFF -> ON
        ON -> TORCH
        TORCH -> OFF
    }
}

/**
 * 变焦档位条。
 *
 * 只列相机真支持的倍率 —— S25+ 的超广角是 0.6x，
 * 近距离拍芯片丝印时它比主摄数码变焦清楚得多（超广角能对焦到 3–4cm）。
 */
@Composable
fun ZoomBar(
    ratio: Float,
    minRatio: Float,
    maxRatio: Float,
    onPick: (Float) -> Unit,
) {
    val presets = listOf(0.6f, 1f, 2f, 5f, 10f).filter { it in minRatio..maxRatio }
    if (presets.size < 2) return

    Row(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0x99101418))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        presets.forEach { p ->
            // 当前倍率落在哪个档位附近就点亮哪个，捏合变焦后也能看出位置
            val active = abs(ratio - p) < p * 0.12f
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (active) Amber else Color.Transparent)
                    .clickable { onPick(p) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (p < 1f) ".${(p * 10).roundToInt()}" else "${p.roundToInt()}",
                    color = if (active) Ink else Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        Text(
            "${(ratio * 10).roundToInt() / 10f}×",
            color = Steel,
            fontSize = 11.sp,
            modifier = Modifier.padding(end = 8.dp)
        )
    }
}

@Composable
fun FlashButton(mode: FlashMode, onToggle: () -> Unit) {
    val lit = mode != FlashMode.OFF
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, if (lit) Amber else Steel, RoundedCornerShape(4.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "⚡",
                color = if (lit) Amber else Color(0xFF4A525C),
                fontSize = 13.sp
            )
            Spacer(Modifier.width(5.dp))
            Text(
                mode.label,
                color = if (lit) Amber else Steel,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun ExposureButton(index: Int, onTap: () -> Unit) {
    val off = index != 0
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, if (off) Amber else Steel, RoundedCornerShape(4.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            if (off) "亮度 ${if (index > 0) "+" else ""}$index" else "亮度",
            color = if (off) Amber else Steel,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * 曝光补偿面板。
 *
 * 用相机报上来的真实档位范围，不写死 —— 不同镜头范围不一样，
 * 写死会出现拖到底却没反应的情况。
 */
@Composable
fun ExposurePanel(
    index: Int,
    range: IntRange,
    evPerStep: Float,
    onChange: (Int) -> Unit,
) {
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xF2161A1F))
            .padding(18.dp)
            .width(280.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("亮度", color = Steel, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            String.format("%+.1f EV", index * evPerStep),
            color = Amber,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(10.dp))

        if (range.first < range.last) {
            Slider(
                value = index.toFloat(),
                onValueChange = { onChange(it.roundToInt()) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = (range.last - range.first - 1).coerceAtLeast(0),
                colors = SliderDefaults.colors(
                    thumbColor = Amber,
                    activeTrackColor = Amber,
                    inactiveTrackColor = Color(0xFF2A3037),
                    activeTickColor = Color(0x66000000),
                    inactiveTickColor = Color(0xFF3A424B),
                )
            )
        } else {
            Text("这颗镜头不支持曝光补偿", color = Steel, fontSize = 12.sp)
        }

        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("暗", color = Steel, fontSize = 11.sp)
            Text(
                "归零",
                color = if (index == 0) Color(0xFF4A525C) else Amber,
                fontSize = 12.sp,
                modifier = Modifier
                    .clickable { onChange(0) }
                    .padding(horizontal = 10.dp, vertical = 2.dp)
            )
            Text("亮", color = Steel, fontSize = 11.sp)
        }
    }
}

/** 双击取景框在 1x 和上一次的倍率之间来回切，单手操作时比捏合快 */
fun Modifier.zoomGestures(
    onPinch: (Float) -> Unit,
    onDoubleTap: () -> Unit,
): Modifier = this
    .pointerInput(Unit) {
        detectTransformGestures { _, _, zoom, _ ->
            if (zoom != 1f) onPinch(zoom)
        }
    }
    .pointerInput(Unit) {
        detectTapGestures(onDoubleTap = { onDoubleTap() })
    }
