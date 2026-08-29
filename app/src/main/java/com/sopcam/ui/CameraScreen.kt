package com.sopcam.ui

import androidx.activity.compose.BackHandler
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
import kotlin.math.abs
import androidx.compose.ui.viewinterop.AndroidView
import com.sopcam.sop.Hint
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
    hints: List<Hint>,
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
    focusNote: String?,
    scannedCode: String?,
    watermarkHeadline: String?,
    watermarkLines: List<String>,
    queueDepth: Int,
    lastSaved: String?,
    archiveWarning: String?,
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
    onFocusCancel: () -> Unit,
    onCodeClear: () -> Unit,
    onShutter: () -> Unit,
    bindPreview: (PreviewView) -> Unit,
) {
    // 向左拖多远算锁定。太短容易误锁，太长单手够不着。
    val lockThresholdPx = with(LocalDensity.current) { 64.dp.toPx() }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    // 点在对焦框范围内算取消，点别处算重新对焦
    val reticleHalfPx = with(LocalDensity.current) { 42.dp.toPx() }

    // 步骤里的 hint 是提示库的 id，查得到才给入口 —— 查不到就当没有，别弹一页空白
    var openHint by remember { mutableStateOf<Hint?>(null) }
    val hintOf: (SopStep) -> Hint? = { s ->
        if (s.hint.isBlank()) null else hints.firstOrNull { it.id == s.hint }
    }
    // 提示开着的时候返回键先关提示，别一路退出相机
    BackHandler(enabled = openHint != null) { openHint = null }

    Box(Modifier.fillMaxSize().background(Ink)) {

        Column(Modifier.fillMaxSize()) {

            Spacer(Modifier.height(36.dp))

            // 退出交给系统返回键（MainActivity 里已经接管，还会拦"照片没存完"），
            // 屏幕上少一个常年占位、一天按不到一次的按钮
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                StepLadder(steps, currentIndex, shotCounts, hintOf, { openHint = it }, onStepSelect)
            }

            Spacer(Modifier.height(10.dp))

            // 取景框 == 成片范围。3:4 是 ImageCapture 那边锁死的比例，
            // 这样水印画在框里就是所见即所得，也不会被下面的控制条盖住。
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RectangleShape),
                contentAlignment = Alignment.Center
            ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .clip(RectangleShape)
                    .background(Color.Black)
                    .pinchZoom(onZoomPinch)
                    .focusGestures(
                        lockThresholdPx = lockThresholdPx,
                        onTap = { x, y ->
                            val hit = focusSpot?.let {
                                abs(x - it.x) < reticleHalfPx &&
                                    abs(y - it.y) < reticleHalfPx
                            } ?: false
                            if (hit) onFocusCancel() else onFocusTap(x, y)
                        },
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

                // 水印画在最后 —— 它是所见即所得的那一层，被变焦条压住就等于预览撒谎。
                // 这个 Column 没有任何点击修饰符，盖在变焦条上也不会挡住点它
                if (watermarkVisible) {
                    WatermarkPreview(watermarkHeadline, watermarkLines, anchor, effectiveEdge)
                }
            }
            }

            // 归档警告排在最前，而且不会自己消失 ——
            // 原图没存是"拍了等于白拍"级别的问题，比存盘提示重要得多
            archiveWarning?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    "⚠ $it",
                    color = Color(0xFFE86A5C),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .background(Color(0x33E86A5C), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }

            scannedCode?.let {
                Spacer(Modifier.height(8.dp))
                CodeChip(it, onCodeClear)
            }

            // 对焦提示优先于存盘提示 —— 拍不清楚比存到哪更要紧
            focusNote?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    color = Amber,
                    fontSize = 12.sp,
                    maxLines = 2,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            lastSaved?.let {
                Spacer(Modifier.height(6.dp))
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

        // 提示弹层挂在最外层 Box —— 塞进 StepLadder 的 LazyRow item 里会被列表布局约束住
        openHint?.let { HintSheet(it) { openHint = null } }
    }
}

@Composable
private fun StepLadder(
    steps: List<SopStep>,
    currentIndex: Int,
    shotCounts: Map<Int, Int>,
    hintOf: (SopStep) -> Hint?,
    onHintTap: (Hint) -> Unit,
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
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        step.order.toString().padStart(2, '0'),
                        color = tint, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                    )
                    // 入口只长在当前步骤上：非激活卡片只有 116dp，塞进去会挤掉步骤名。
                    // 不加纵向 padding，行高就不会被撑开，卡片还是 62dp 装得下两行标题
                    val h = if (active) hintOf(step) else null
                    if (h != null) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(3.dp))
                                .background(Amber)
                                .clickable { onHintTap(h) }
                                .padding(horizontal = 6.dp)
                        ) {
                            Text(
                                "图示", color = Ink, fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
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
