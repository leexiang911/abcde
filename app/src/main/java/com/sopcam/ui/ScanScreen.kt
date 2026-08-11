package com.sopcam.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 开工页专用的扫码界面。
 *
 * 跟相机界面分开是因为这里不需要 ImageCapture —— 只绑预览和分析两个用例，
 * 启动更快，也不会因为多绑一个拍照用例在某些机型上超出用例组合上限。
 */
@Composable
fun ScanScreen(
    title: String,
    hint: String,
    lastCode: String?,
    bindPreview: (PreviewView) -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
    ) {
        Spacer(Modifier.height(36.dp))
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(hint, color = Steel, fontSize = 13.sp)
        }

        Spacer(Modifier.height(20.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        bindPreview(this)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // 取景提示框：把码对进来
            Canvas(Modifier.size(220.dp)) {
                val arm = size.minDimension * 0.22f
                val w = 3.dp.toPx()
                val pad = w / 2f
                val r = size.width - pad
                val b = size.height - pad
                listOf(
                    Offset(pad, pad + arm) to Offset(pad, pad),
                    Offset(pad, pad) to Offset(pad + arm, pad),
                    Offset(r - arm, pad) to Offset(r, pad),
                    Offset(r, pad) to Offset(r, pad + arm),
                    Offset(r, b - arm) to Offset(r, b),
                    Offset(r, b) to Offset(r - arm, b),
                    Offset(pad + arm, b) to Offset(pad, b),
                    Offset(pad, b) to Offset(pad, b - arm),
                ).forEach { (a, c) -> drawLine(Amber, a, c, strokeWidth = w, cap = StrokeCap.Round) }
            }
        }

        Spacer(Modifier.height(20.dp))

        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            if (lastCode != null) {
                Text(
                    lastCode,
                    color = Done,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Panel, RoundedCornerShape(8.dp))
                        .padding(14.dp)
                )
            } else {
                Text("等待识别…", color = Steel, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(24.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "取消",
                color = Steel,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, Color(0xFF2A3037), RoundedCornerShape(6.dp))
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 28.dp, vertical = 12.dp)
            )
        }
    }
}

/** 相机界面上显示当前记住的码，带一个清除按钮 */
@Composable
fun CodeChip(code: String, onClear: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x332CC38A))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "码 $code",
            color = Done,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            modifier = Modifier.padding(end = 10.dp)
        )
        Text(
            "×",
            color = Steel,
            fontSize = 16.sp,
            modifier = Modifier
                .clickable(onClick = onClear)
                .padding(horizontal = 6.dp)
        )
    }
}
