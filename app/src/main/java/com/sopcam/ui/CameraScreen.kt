package com.sopcam.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.sopcam.sop.SopStep
import com.sopcam.watermark.Anchor
import com.sopcam.watermark.OrientationLock

/*
 * 车间工具，不是消费相机：
 *   · 深色到底，唯一的暖色（琥珀）只标当前步骤和生效中的锁
 *   · 可点区域 >= 56dp，戴手套单手能按中
 *   · 签名元素是顶部的步骤梯 —— 已拍够 / 当前 / 待拍 三态一眼可辨，
 *     它同时是进度条、导航器和拍摄提示，取代了传统相机的模式转盘
 */

@Composable
fun CameraScreen(
    steps: List<SopStep>,
    currentIndex: Int,
    shotCounts: Map<Int, Int>,
    anchor: Anchor,
    lock: OrientationLock,
    queueDepth: Int,
    lastSaved: String?,
    onStepSelect: (Int) -> Unit,
    onAnchorToggle: () -> Unit,
    onLockToggle: () -> Unit,
    onShutter: () -> Unit,
    onExit: () -> Unit,
    bindPreview: (PreviewView) -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Ink)) {

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

        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
            Spacer(Modifier.height(36.dp))

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Tag("收工", onExit)
                if (lock != OrientationLock.AUTO) {
                    Text(
                        if (lock == OrientationLock.LANDSCAPE) "成片横向" else "成片竖向",
                        color = Amber,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .background(Panel.copy(alpha = 0.9f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            if (steps.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                StepLadder(steps, currentIndex, shotCounts, onStepSelect)
            }

            lastSaved?.let {
                Spacer(Modifier.height(10.dp))
                Text(
                    "已存 $it",
                    color = Steel,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .background(Panel.copy(alpha = 0.9f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        ControlBar(
            anchor = anchor,
            lock = lock,
            queueDepth = queueDepth,
            onAnchorToggle = onAnchorToggle,
            onLockToggle = onLockToggle,
            onShutter = onShutter,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun StepLadder(
    steps: List<SopStep>,
    currentIndex: Int,
    shotCounts: Map<Int, Int>,
    onSelect: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex) {
        if (currentIndex in steps.indices) listState.animateScrollToItem(currentIndex)
    }

    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(steps) { i, step ->
            val taken = shotCounts[step.order] ?: 0
            val complete = taken >= step.shots
            val active = i == currentIndex
            val tint = when {
                active -> Amber
                complete -> Done
                else -> Steel
            }
            Column(
                Modifier
                    .width(if (active) 210.dp else 120.dp)
                    .height(68.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Panel.copy(alpha = 0.92f))
                    .border(if (active) 2.dp else 1.dp, tint, RoundedCornerShape(6.dp))
                    .clickable { onSelect(i) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        step.order.toString().padStart(2, '0'),
                        color = tint,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "$taken/${step.shots}",
                        color = tint,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Text(
                    step.label(),
                    color = if (active) Color.White else Steel,
                    fontSize = if (active) 15.sp else 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ControlBar(
    anchor: Anchor,
    lock: OrientationLock,
    queueDepth: Int,
    onAnchorToggle: () -> Unit,
    onLockToggle: () -> Unit,
    onShutter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Panel.copy(alpha = 0.94f))
            .padding(vertical = 16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Tag(
                when (lock) {
                    OrientationLock.AUTO -> "方向 自动"
                    OrientationLock.PORTRAIT -> "方向 锁竖屏"
                    OrientationLock.LANDSCAPE -> "方向 锁横屏"
                },
                onLockToggle,
                highlighted = lock != OrientationLock.AUTO
            )
            if (queueDepth > 0) {
                Text(
                    "存盘 $queueDepth",
                    color = Steel,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Tag(
                when (anchor) {
                    Anchor.BOTTOM_LEFT -> "水印 左下"
                    Anchor.BOTTOM_RIGHT -> "水印 右下"
                    Anchor.TOP_RIGHT -> "水印 右上"
                    Anchor.TOP_LEFT -> "水印 左上"
                },
                onAnchorToggle,
                highlighted = true
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

@Composable
private fun Tag(label: String, onClick: () -> Unit, highlighted: Boolean = false) {
    val tint = if (highlighted) Amber else Steel
    Text(
        label,
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
    Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("需要相机权限才能拍摄留档", color = Color.White, fontSize = 16.sp)
            Spacer(Modifier.height(20.dp))
            Tag("授予权限", onRequest, highlighted = true)
        }
    }
}
