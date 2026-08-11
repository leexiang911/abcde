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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.sopcam.sop.SopStep
import com.sopcam.watermark.Anchor
import com.sopcam.watermark.TopEdge
import com.sopcam.watermark.quarterTurns

/*
 * 车间工具，不是消费相机：
 *   · 取景框严格按 3:4 摆，等于成片范围，水印画在框内不会被控制条压住
 *   · 深色到底，唯一的暖色只标当前步骤和生效中的设置
 *   · 按钮文字跟着成片方向转，横过来拿也不用歪头读
 *   · 签名元素是顶部的步骤梯 —— 进度条、导航器和拍摄提示三合一
 */

/** 哪个面板展开着 */
enum class OverlayPanel { NONE, ORIENTATION, ANCHOR, EXPOSURE }

@Composable
fun CameraScreen(
    steps: List<SopStep>,
    currentIndex: Int,
    shotCounts: Map<Int, Int>,
    anchor: Anchor,
    watermarkVisible: Boolean,
    edge: TopEdge,
    effectiveEdge: TopEdge,
    panel: OverlayPanel,
    zoomRatio: Float,
    minZoom: Float,
    maxZoom: Float,
    flashMode: FlashMode,
    exposureIndex: Int,
    exposureRange: IntRange,
    evPerStep: Float,
    focusSpot: FocusSpot?,
    watermarkHeadline: String?,
    watermarkLines: List<String>,
    queueDepth: Int,
    lastSaved: String?,
    onStepSelect: (Int) -> Unit,
    onPanelChange: (OverlayPanel) -> Unit,
    onAnchorPick: (Anchor) -> Unit,
    onWatermarkDisable: () -> Unit,
    onEdgePick: (TopEdge) -> Unit,
    onZoomPick: (Float) -> Unit,
    onZoomPinch: (Float) -> Unit,
    onFlashToggle: () -> Unit,
    onExposureChange: (Int) -> Unit,
    onFocusTap: (Float, Float) -> Unit,
    onFocusLongStart: (Float, Float) -> Unit,
    onFocusLongEnd: (Boolean) -> Unit,
    onShutter: () -> Unit,
    onExit: () -> Unit,
    bindPreview: (PreviewView) -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Ink)) {

        Column(Modifier.fillMaxSize()) {

            Spacer(Modifier.height(36.dp))

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Tag("收工", effectiveEdge, onClick = onExit)
                if (queueDepth > 0) {
                    Text(
                        "存盘 $queueDepth",
                        color = Steel,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.rotate(effectiveEdge.quarterTurns() * 90f)
                    )
                }
            }

            if (steps.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                StepLadder(steps, currentIndex, shotCounts, onStepSelect)
            }

            Spacer(Modifier.height(10.dp))

            // 取景框 == 成片范围。3:4 是 ImageCapture 那边锁死的比例，
            // 这样水印画在框里就是所见即所得，也不会被下面的控制条盖住。
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .clip(RectangleShape)
                    .background(Color.Black)
                    .pinchZoom(onZoomPinch)
                    .focusGestures(
                        lockThresholdPx = lockThresholdPx,
                        onTap = onFocusTap,
                        onLongStart = { x, y ->
                            dragX = 0f
                            dragging = true
                            onFocusLongStart(x, y)
                        },
                        onDrag = { dragX = it },
                        onLongEnd = { armed ->
                            dragging = false
                            dragX = 0f
                            onFocusLongEnd(armed)
                        },
                    )
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
                focusSpot?.let {
                    FocusReticle(
                        spot = it,
                        dragX = dragX,
                        dragging = dragging,
                        lockArmed = dragX <= -lockThresholdPx,
                    )
                }

                if (watermarkVisible) {
                    WatermarkPreview(watermarkHeadline, watermarkLines, anchor, effectiveEdge)
                }
                TopEdgeMarker(edge, effectiveEdge)

                // 变焦档位压在取景框底部：手指本来就在这个区域，
                // 不用为了换倍率把手挪到屏幕下沿去。
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                ) {
                    ZoomBar(zoomRatio, minZoom, maxZoom, onZoomPick)
                }
            }

            lastSaved?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    "已存 $it",
                    color = Steel,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            Spacer(Modifier.weight(1f))

            ControlBar(
                anchor = anchor,
                watermarkVisible = watermarkVisible,
                edge = edge,
                effectiveEdge = effectiveEdge,
                flashMode = flashMode,
                exposureIndex = exposureIndex,
                onFlashToggle = onFlashToggle,
                onExposureTap = {
                    onPanelChange(if (panel == OverlayPanel.EXPOSURE) OverlayPanel.NONE else OverlayPanel.EXPOSURE)
                },
                onOrientationTap = {
                    onPanelChange(if (panel == OverlayPanel.ORIENTATION) OverlayPanel.NONE else OverlayPanel.ORIENTATION)
                },
                onAnchorTap = {
                    onPanelChange(if (panel == OverlayPanel.ANCHOR) OverlayPanel.NONE else OverlayPanel.ANCHOR)
                },
                onShutter = onShutter
            )
        }

        if (panel != OverlayPanel.NONE) {
            DialScrim { onPanelChange(OverlayPanel.NONE) }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (panel) {
                    OverlayPanel.ORIENTATION -> OrientationDialPanel(edge) {
                        onEdgePick(it)
                        onPanelChange(OverlayPanel.NONE)
                    }
                    OverlayPanel.ANCHOR -> AnchorGridPanel(
                        anchor = anchor,
                        edge = effectiveEdge,
                        visible = watermarkVisible,
                        onPick = {
                            onAnchorPick(it)
                            onPanelChange(OverlayPanel.NONE)
                        },
                        onDisable = {
                            onWatermarkDisable()
                            onPanelChange(OverlayPanel.NONE)
                        }
                    )
                    OverlayPanel.EXPOSURE -> ExposurePanel(
                        index = exposureIndex,
                        range = exposureRange,
                        evPerStep = evPerStep,
                        onChange = onExposureChange
                    )
                    OverlayPanel.NONE -> Unit
                }
            }
        }
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
                    .width(if (active) 200.dp else 116.dp)
                    .height(62.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Panel)
                    .border(if (active) 2.dp else 1.dp, tint, RoundedCornerShape(6.dp))
                    .clickable { onSelect(i) }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        step.order.toString().padStart(2, '0'),
                        color = tint, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                    )
                    Text(
                        "$taken/${step.shots}",
                        color = tint, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                    )
                }
                Text(
                    step.label(),
                    color = if (active) Color.White else Steel,
                    fontSize = if (active) 14.sp else 11.sp,
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
    watermarkVisible: Boolean,
    edge: TopEdge,
    effectiveEdge: TopEdge,
    flashMode: FlashMode,
    exposureIndex: Int,
    onFlashToggle: () -> Unit,
    onExposureTap: () -> Unit,
    onOrientationTap: () -> Unit,
    onAnchorTap: () -> Unit,
    onShutter: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Panel)
            .padding(vertical = 12.dp)
    ) {
        // 光学控制一行，留档控制一行 —— 两类东西改的频率不一样，分开不容易误触
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FlashButton(flashMode, onFlashToggle)
            ExposureButton(exposureIndex, onExposureTap)
        }

        Spacer(Modifier.height(10.dp))

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OrientationDial(edge, effectiveEdge, onOrientationTap)
            AnchorButton(anchor, effectiveEdge, watermarkVisible, onAnchorTap)
        }

        Spacer(Modifier.height(14.dp))

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(74.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable(onClick = onShutter)
            )
        }
    }
}

@Composable
private fun Tag(
    label: String,
    edge: TopEdge,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (highlighted) Amber else Steel
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, tint, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            label, color = tint, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.rotate(edge.quarterTurns() * 90f)
        )
    }
}

@Composable
fun PermissionGate(onRequest: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("需要相机权限才能拍摄留档", color = Color.White, fontSize = 16.sp)
            Spacer(Modifier.height(20.dp))
            Tag("授予权限", TopEdge.TOP, highlighted = true, onClick = onRequest)
        }
    }
}
