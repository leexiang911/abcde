package com.sopcam.ui

import androidx.camera.view.PreviewView
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.sopcam.watermark.Anchor
import com.sopcam.watermark.OrientationLock

/*
 * 车间工具，不是消费相机：
 *   · 深色到底，唯一的暖色（琥珀）只标"当前生效的锁"，其余全灰阶
 *   · 可点区域 ≥ 56dp，戴手套单手也能按中
 *   · 状态条常驻显示方向锁 + 水印锚点，不进设置就知道成片长什么样
 */

private val Ink = Color(0xFF0E1114)
private val Panel = Color(0xE6161A1F)
private val Amber = Color(0xFFFFD24A)
private val Steel = Color(0xFF8C949E)

@Composable
fun CameraScreen(
    anchor: Anchor,
    lock: OrientationLock,
    queueDepth: Int,
    lastSaved: String?,
    onAnchorToggle: () -> Unit,
    onLockToggle: () -> Unit,
    onShutter: () -> Unit,
    bindPreview: (PreviewView) -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Ink)
    ) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    bindPreview(this)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 顶部：成片预期。锁横屏时提示一句，免得以为相机坏了
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 44.dp, start = 16.dp, end = 16.dp)
        ) {
            if (lock != OrientationLock.AUTO) {
                Text(
                    text = if (lock == OrientationLock.LANDSCAPE)
                        "已锁横构图 · 竖着拿也出横图"
                    else
                        "已锁竖构图 · 横着拿也出竖图",
                    color = Amber,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .background(Panel, RoundedCornerShape(6.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
            lastSaved?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "已存 $it",
                    color = Steel,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .background(Panel, RoundedCornerShape(6.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Panel)
                .padding(vertical = 16.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Chip(
                    label = when (lock) {
                        OrientationLock.AUTO -> "方向 自动"
                        OrientationLock.PORTRAIT -> "方向 锁竖屏"
                        OrientationLock.LANDSCAPE -> "方向 锁横屏"
                    },
                    highlighted = lock != OrientationLock.AUTO,
                    onClick = onLockToggle
                )
                if (queueDepth > 0) {
                    Text(
                        "存盘 $queueDepth",
                        color = Steel,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Chip(
                    label = when (anchor) {
                        Anchor.BOTTOM_LEFT -> "水印 左下"
                        Anchor.BOTTOM_RIGHT -> "水印 右下"
                        Anchor.TOP_RIGHT -> "水印 右上"
                        Anchor.TOP_LEFT -> "水印 左上"
                    },
                    highlighted = true,
                    onClick = onAnchorToggle
                )
            }

            Spacer(Modifier.height(18.dp))

            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(78.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable(onClick = onShutter)
                )
            }
        }
    }
}

@Composable
private fun Chip(label: String, highlighted: Boolean, onClick: () -> Unit) {
    val tint = if (highlighted) Amber else Steel
    Text(
        text = label,
        color = tint,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, tint, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    )
}

@Composable
fun PermissionGate(onRequest: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Ink),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("需要相机权限才能拍摄留档", color = Color.White, fontSize = 16.sp)
            Spacer(Modifier.height(20.dp))
            Chip(label = "授予权限", highlighted = true, onClick = onRequest)
        }
    }
}
