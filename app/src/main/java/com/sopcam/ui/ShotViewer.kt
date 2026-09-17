package com.sopcam.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sopcam.archive.Archive
import com.sopcam.archive.Thumbs
import com.sopcam.capture.Codes
import com.sopcam.capture.RegionScan
import com.sopcam.watermark.Anchor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val viewerTimeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

/** 展开的是哪个调节面板 */
private enum class Tray { NONE, WATERMARK, ROTATE, MORE }

/**
 * 大图查看器。
 *
 * 三件事在这里汇合：改水印位置、旋转、重拍。
 * 共同的模式是「改归档 json → 用 overwrite 重烧回相册」——
 * 归档区的原图从头到尾不动，所以每一步都能改回来。
 *
 * 改动是攒着的：调水印位置和角度只改本地状态，
 * 按「保存并应用」才一次性写 json 并重烧。避免调一下烧一次。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShotViewer(
    item: ShotItem,
    indexLabel: String,
    busy: String?,
    onApply: (Anchor, Int) -> Unit,
    onRetake: () -> Unit,
    onEditText: () -> Unit,
    onDelete: () -> Unit,
    /** 改完 AI 读数要通知外面重读一遍，否则缩略图上的 AI 角标不跟着变 */
    onAiEdited: () -> Unit,
    /** 这一项配了扫码或切码规则吗 —— 只有这种才给「手填码值」的入口 */
    codeEditable: Boolean,
    /** 手填的整串码值。切规则由外面按测试项查，跟扫出来的走同一条路 */
    onTypeCode: (String) -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    /*
     * 所有本地状态都挂在这个 key 上，而不是单看文件路径。
     *
     * 改水印、转图、改 AI 读数都不换文件名：只按路径记的话，外面 readShots()
     * 明明已经把新数据读出来了，这里的 remember 却一个都不重算 ——
     * side 还是旧的 JSONObject，savedRotation 还是旧角度，bmp 还是旧位图。
     * 表现就是「退出再进去才看到改动」。stamp 跟着文件的修改时间走，一动就重算。
     */
    val key = remember(item.file.absolutePath, item.stamp) {
        item.file.absolutePath + "@" + item.stamp
    }

    var bmp by remember(key) { mutableStateOf<Bitmap?>(null) }
    // 双指缩放。换图或转向都归位 —— 转完还保持放大的话，
    // 画面会跳到一个跟刚才完全对不上的地方
    var scale by remember(key) { mutableStateOf(1f) }
    var pan by remember(key) { mutableStateOf(Offset.Zero) }
    var tray by remember(key) { mutableStateOf(Tray.NONE) }
    var code by remember(key) { mutableStateOf(item.codeValue) }
    var scanning by remember(key) { mutableStateOf(false) }
    var offerCrop by remember(key) { mutableStateOf(false) }
    var cropping by remember(key) { mutableStateOf(false) }
    var cropNote by remember(key) { mutableStateOf<String?>(null) }
    var confirmDelete by remember(key) { mutableStateOf(false) }
    // AI 读错了要能当场改：组装值和组级提示词都按这个值取数，
    // 一个错值会顺着占位符污染整组的结论
    var aiText by remember(key) { mutableStateOf(item.aiText) }
    var editingAi by remember(key) { mutableStateOf(false) }
    // 扫码枪扫出来的整串往这儿一贴，跟手机扫到的走同一套切码规则
    var typingCode by remember(key) { mutableStateOf(false) }

    // 水印内容从随行 json 读，预览要跟成片一致
    val side = remember(key) { Archive.sidecar(item.file) }
    val headline = remember(side) { side?.optString("headline")?.takeIf { it.isNotBlank() } }
    val lines = remember(side) {
        side?.optJSONArray("lines")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
            ?: emptyList()
    }
    val savedAnchor = remember(side) {
        runCatching { Anchor.valueOf(side?.optString("anchor") ?: "") }
            .getOrDefault(Anchor.BOTTOM_LEFT)
    }
    val savedRotation = remember(side) { side?.optInt("rotation", 0) ?: 0 }

    var anchor by remember(key) { mutableStateOf(savedAnchor) }
    var rotation by remember(key) { mutableStateOf(savedRotation) }

    // 转向后缩放归位：转完还保持放大的话，画面会停在一个跟刚才完全对不上的地方
    LaunchedEffect(rotation) { scale = 1f; pan = Offset.Zero }
    var showMark by remember(key) {
        mutableStateOf(headline != null || lines.isNotEmpty())
    }
    val dirty = anchor != savedAnchor || rotation != savedRotation

    LaunchedEffect(key) {
        bmp = withContext(Dispatchers.IO) { Thumbs.full(item.file) }
    }

    var stage by remember { mutableStateOf(IntSize.Zero) }

    Box(Modifier.fillMaxSize()) {

        /* ---------- 图片与水印预览 ---------- */
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = 96.dp, bottom = 150.dp)
                .onSizeChanged { stage = it }
                .pointerInput(key) {
                    // 手写手势而不用 detectTransformGestures：那个会把所有指针事件
                    // 一律消费掉，外层翻页的 HorizontalPager 就再也收不到横划了。
                    // 这里只在「两指」或者「已经放大」时才接管并消费，
                    // 单指 + 原尺寸原样放过去，翻页照常。
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val e = awaitPointerEvent()
                            val multi = e.changes.count { it.pressed } > 1
                            if (multi || scale > 1f) {
                                val next = (scale * e.calculateZoom()).coerceIn(1f, 6f)
                                val moved =
                                    if (next <= 1f) Offset.Zero else pan + e.calculatePan()
                                // 平移夹在「放大后多出来的那半圈」以内，拖不出画面
                                val maxX = (stage.width * (next - 1f)) / 2f
                                val maxY = (stage.height * (next - 1f)) / 2f
                                scale = next
                                pan = Offset(
                                    moved.x.coerceIn(-maxX, maxX),
                                    moved.y.coerceIn(-maxY, maxY)
                                )
                                e.changes.forEach { it.consume() }
                            }
                        } while (e.changes.any { it.pressed })
                    }
                }
                .pointerInput(key) {
                    detectTapGestures(
                        // 双击在「铺满」和「放大 2.5 倍」之间来回，省得捏半天
                        onDoubleTap = {
                            if (scale > 1f) { scale = 1f; pan = Offset.Zero } else scale = 2.5f
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            val b = bmp
            if (b == null) {
                Text("加载中…", color = Steel, fontSize = 13.sp)
            } else {
                // 转 90/270 时画面宽高互换，预览的摆放要按转后的尺寸算
                val turned = rotation % 180 != 0
                val vw = if (turned) b.height else b.width
                val vh = if (turned) b.width else b.height
                val rect = fittedRect(stage, vw, vh)

                // 缩放和平移加在外面这一层：图和水印要一起变，
                // 分别缩放的话放大后水印就飘到别处去了
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = pan.x
                            translationY = pan.y
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = b.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .then(
                                if (rect != null) Modifier
                                    .size(
                                        with(density) { rect.width.toDp() },
                                        with(density) { rect.height.toDp() }
                                    )
                                else Modifier.fillMaxSize()
                            )
                            .rotate(rotation.toFloat())
                    )

                    // 水印锚在【图片的实际绘制矩形】上，不是容器上 ——
                    // Fit 四周有留白，锚在容器上预览位置就会跟成片对不上
                    if (showMark && rect != null && (headline != null || lines.isNotEmpty())) {
                        WatermarkGhost(rect, anchor, headline, lines)
                    }
                }
            }
        }

        /* ---------- 顶部信息 ---------- */
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 36.dp, start = 20.dp, end = 20.dp)
        ) {
            Text(
                buildString {
                    if (item.stepOrder > 0) {
                        append(item.stepOrder.toString().padStart(2, '0'))
                        append(" · ")
                    }
                    append(item.stepName.ifBlank { "自由拍摄" })
                },
                color = Color.White,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "$indexLabel  ·  ${viewerTimeFmt.format(Date(item.at))}" +
                    if (rotation != 0) "  ·  已转 $rotation°" else "",
                color = Steel,
                fontSize = 12.sp
            )
            if (code.isNotBlank()) {
                Spacer(Modifier.height(7.dp))
                Text(
                    code,
                    color = Done,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x332CC38A))
                        .clickable { copyToClipboard(ctx, code) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
            // 拍照当时写的备注。点一下复制 —— 常常是要抄进检修单的那句话
            if (item.note.isNotBlank()) {
                Spacer(Modifier.height(7.dp))
                Text(
                    item.note,
                    color = Color.White,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x22FFFFFF))
                        .clickable { copyToClipboard(ctx, item.note) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }

            // AI 读出来的值。点一下复制（只复制值本身，不带「AI」前缀 ——
            // 这个数是要抄进系统的），长按改。读错了就地改掉，
            // 组装值和组级提示词都按改后的值取数
            if (aiText.isNotBlank()) {
                Spacer(Modifier.height(7.dp))
                Text(
                    "AI  $aiText",
                    color = Color(0xFF7BC6FF),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x2247A9E8))
                        .combinedClickable(
                            onClick = { copyToClipboard(ctx, aiText) },
                            onLongClick = { editingAi = true }
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
                Text(
                    "长按可以改",
                    color = Steel,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(start = 10.dp, top = 3.dp)
                )
            }
        }

        /* ---------- 底部操作区 ---------- */
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xE00A0C0F))
                .padding(horizontal = 14.dp)
                .padding(top = 12.dp, bottom = 22.dp)
        ) {
            busy?.let {
                Text(it, color = Amber, fontSize = 13.sp, modifier = Modifier.padding(bottom = 10.dp))
            }

            when (tray) {
                Tray.WATERMARK -> AnchorTray(
                    anchor = anchor,
                    enabled = showMark,
                    onPick = {
                        anchor = it
                        showMark = true
                    },
                    onOff = { showMark = false }
                )

                Tray.ROTATE -> RotateTray(
                    rotation = rotation,
                    onTurn = { rotation = ((rotation + it) % 360 + 360) % 360 },
                    onReset = { rotation = 0 }
                )

                Tray.MORE -> MoreTray(
                    hasCode = code.isNotBlank(),
                    scanning = scanning,
                    offerCrop = offerCrop,
                    codeEditable = codeEditable,
                    onTypeCode = { typingCode = true },
                    onScan = {
                        scanning = true
                        scope.launch {
                            val hit = withContext(Dispatchers.IO) {
                                val full = BitmapFactory.decodeFile(item.file.path)
                                val r = full?.let { Codes.scan(it, thorough = true) }
                                full?.recycle()
                                r
                            }
                            if (hit != null) {
                                Archive.updateSidecarCode(item.file, hit.value, hit.format)
                                code = hit.value
                                offerCrop = false
                            } else {
                                offerCrop = true
                            }
                            scanning = false
                        }
                    },
                    onCrop = {
                        cropNote = null
                        cropping = true
                    },
                    onClearCode = {
                        Archive.clearSidecarCode(item.file)
                        code = ""
                        offerCrop = false
                    },
                    onEditText = onEditText,
                    onAskDelete = { confirmDelete = true }
                )

                Tray.NONE -> Unit
            }

            if (tray != Tray.NONE) Spacer(Modifier.height(12.dp))

            if (dirty) {
                Text(
                    "保存并应用",
                    color = Ink,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Amber)
                        .clickable(enabled = busy == null) { onApply(anchor, rotation) }
                        .padding(vertical = 13.dp)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "会按新的水印位置和角度重烧一张覆盖相册里那张",
                    color = Color(0xFF4A525C),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BarButton("重拍", Modifier.weight(1f), onClick = onRetake)
                BarButton(
                    "水印", Modifier.weight(1f),
                    active = tray == Tray.WATERMARK
                ) { tray = if (tray == Tray.WATERMARK) Tray.NONE else Tray.WATERMARK }
                BarButton(
                    "旋转", Modifier.weight(1f),
                    active = tray == Tray.ROTATE
                ) { tray = if (tray == Tray.ROTATE) Tray.NONE else Tray.ROTATE }
                BarButton(
                    "更多", Modifier.weight(1f),
                    active = tray == Tray.MORE
                ) { tray = if (tray == Tray.MORE) Tray.NONE else Tray.MORE }
                BarButton("关闭", Modifier.weight(1f), onClick = onClose)
            }
        }

        // 删的是归档原图，删完这张就再也重烧不出来了，得拦一道
        if (confirmDelete) {
            ConfirmTypedDialog(
                title = "删除这张原图",
                detail = "${item.stepName.ifBlank { "这张照片" }} 的原图会被删除，" +
                    "之后不能再重烧回相册。相册里已有的成片不受影响。",
                actionLabel = "删除",
                onCancel = { confirmDelete = false },
                onConfirm = {
                    confirmDelete = false
                    onDelete()
                }
            )
        }

        if (editingAi) {
            EditValueDialog(
                title = "改 AI 读数",
                hint = item.stepName.ifBlank { "这张照片" } + "　清空保存表示否掉这个读数",
                initial = aiText,
                onCancel = { editingAi = false },
                onSave = { v ->
                    editingAi = false
                    aiText = v
                    scope.launch(Dispatchers.IO) {
                        // 人改过的标 ok，不是 pending —— 否则下次跑批又把它覆盖回去
                        Archive.setAiResult(item.file, v, if (v.isBlank()) "rejected" else "ok")
                        withContext(Dispatchers.Main) { onAiEdited() }
                    }
                }
            )
        }

        if (typingCode) {
            EditValueDialog(
                title = "手填码值",
                hint = "扫码枪扫出来的整串贴进来。会按这一项配的规则切，跟手机扫到的一样",
                initial = item.codeRaw.ifBlank { code },
                onCancel = { typingCode = false },
                onSave = { v ->
                    typingCode = false
                    onTypeCode(v.trim())
                }
            )
        }

        if (cropping) {
            CropScanOverlay(
                bitmapWidth = bmp?.width ?: 0,
                bitmapHeight = bmp?.height ?: 0,
                busy = scanning,
                result = cropNote,
                onScan = { r ->
                    scanning = true
                    cropNote = null
                    scope.launch {
                        val hit = RegionScan.scan(item.file.path, r.left, r.top, r.right, r.bottom)
                        if (hit != null) {
                            Archive.updateSidecarCode(item.file, hit.value, hit.format)
                            code = hit.value
                            offerCrop = false
                            cropping = false
                        } else {
                            cropNote = "· 这块也没认出来，换个框法试试"
                        }
                        scanning = false
                    }
                },
                onClose = { cropping = false }
            ) {
                bmp?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

/* ------------------------------------------------------------------
 * 水印预览
 * ------------------------------------------------------------------ */

/**
 * 叠在图上的水印影子。
 *
 * 位置按【图片实际绘制的矩形】算，不是容器 —— 图是 Fit 摆的，
 * 四周有留白，锚在容器上就会跟成片对不上。
 * 文字永远正立，不跟着图转，这正是「图转水印不动」的意思。
 */
@Composable
private fun WatermarkGhost(
    rect: FitRect,
    anchor: Anchor,
    headline: String?,
    lines: List<String>,
) {
    val density = LocalDensity.current
    val pad = with(density) { 12.dp.toPx() }

    Box(
        Modifier
            .size(
                with(density) { rect.width.toDp() },
                with(density) { rect.height.toDp() }
            ),
        contentAlignment = when (anchor) {
            Anchor.TOP_LEFT -> Alignment.TopStart
            Anchor.TOP_RIGHT -> Alignment.TopEnd
            Anchor.BOTTOM_LEFT -> Alignment.BottomStart
            Anchor.BOTTOM_RIGHT -> Alignment.BottomEnd
        }
    ) {
        Column(
            Modifier
                .offset {
                    IntOffset(
                        when (anchor) {
                            Anchor.TOP_LEFT, Anchor.BOTTOM_LEFT -> pad.roundToInt()
                            else -> -pad.roundToInt()
                        },
                        when (anchor) {
                            Anchor.TOP_LEFT, Anchor.TOP_RIGHT -> pad.roundToInt()
                            else -> -pad.roundToInt()
                        }
                    )
                }
                .widthIn(max = 200.dp)
                .background(Color(0x77000000), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            headline?.let {
                Text(it, color = Amber, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            lines.filter { it.isNotBlank() }.forEach {
                Text(it, color = Color.White, fontSize = 11.sp)
            }
        }
    }
}

/** 图片在容器里实际画出来的那块 */
data class FitRect(val left: Float, val top: Float, val width: Float, val height: Float)

/** Fit 摆放的换算：等比缩放后居中，四周留白 */
fun fittedRect(container: IntSize, w: Int, h: Int): FitRect? {
    if (container.width <= 0 || container.height <= 0 || w <= 0 || h <= 0) return null
    val scale = min(container.width.toFloat() / w, container.height.toFloat() / h)
    val dw = w * scale
    val dh = h * scale
    return FitRect((container.width - dw) / 2f, (container.height - dh) / 2f, dw, dh)
}

/* ------------------------------------------------------------------
 * 各个面板
 * ------------------------------------------------------------------ */

/** 四角选水印位置。跟相机那个田字格不同：照片方向已定，不需要跟着转 */
@Composable
private fun AnchorTray(
    anchor: Anchor,
    enabled: Boolean,
    onPick: (Anchor) -> Unit,
    onOff: () -> Unit,
) {
    val grid = listOf(
        listOf(Anchor.TOP_LEFT, Anchor.TOP_RIGHT),
        listOf(Anchor.BOTTOM_LEFT, Anchor.BOTTOM_RIGHT),
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Panel)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            if (enabled) "水印位置" else "这张不加水印",
            color = if (enabled) Steel else Amber,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(10.dp))
        grid.forEach { row ->
            Row {
                row.forEach { a ->
                    val on = enabled && a == anchor
                    Box(
                        Modifier
                            .padding(3.dp)
                            .size(52.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                when {
                                    on -> Amber
                                    enabled -> Color(0xFF2A3037)
                                    else -> Color(0x552A3037)
                                }
                            )
                            .clickable { onPick(a) }
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "不加水印",
            color = if (enabled) Steel else Ink,
            fontSize = 13.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (enabled) Color(0xFF262D35) else Amber)
                .clickable(onClick = onOff)
                .padding(horizontal = 18.dp, vertical = 9.dp)
        )
    }
}

@Composable
private fun RotateTray(rotation: Int, onTurn: (Int) -> Unit, onReset: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Panel)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("当前 $rotation°", color = Steel, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TrayButton("↺ 左转 90°") { onTurn(-90) }
            TrayButton("↻ 右转 90°") { onTurn(90) }
            TrayButton("复位", enabled = rotation != 0) { onReset() }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "水印锚在成片的角上，图转了水印也不跟着转，文字始终正立",
            color = Color(0xFF4A525C),
            fontSize = 11.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun MoreTray(
    hasCode: Boolean,
    scanning: Boolean,
    offerCrop: Boolean,
    codeEditable: Boolean,
    onTypeCode: () -> Unit,
    onScan: () -> Unit,
    onCrop: () -> Unit,
    onClearCode: () -> Unit,
    onEditText: () -> Unit,
    onAskDelete: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Panel)
            .padding(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TrayButton(
                if (scanning) "识别中…" else "扫码识别",
                enabled = !scanning,
                modifier = Modifier.weight(1f),
                onClick = onScan
            )
            if (offerCrop) {
                TrayButton("框选识别", modifier = Modifier.weight(1f), onClick = onCrop)
            }
            if (hasCode) {
                TrayButton("清除码值", modifier = Modifier.weight(1f), onClick = onClearCode)
            }
        }

        // 只有配了扫码或切码规则的项才给手填 —— 其他项根本不需要值，
        // 多一个按钮只会让人犹豫该不该点
        if (codeEditable) {
            Spacer(Modifier.height(8.dp))
            TrayButton(
                if (hasCode) "改码值（手填）" else "手填码值",
                modifier = Modifier.fillMaxWidth(),
                onClick = onTypeCode
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TrayButton("改水印文字", modifier = Modifier.weight(1f), onClick = onEditText)
            TrayButton(
                "删除这张",
                modifier = Modifier.weight(1f),
                danger = true,
                onClick = onAskDelete
            )
        }
    }
}

@Composable
private fun TrayButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Text(
        label,
        color = when {
            !enabled -> Color(0xFF4A525C)
            danger -> Color(0xFFE86A5C)
            else -> Color.White
        },
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (danger) Color(0xFF3A2326) else Color(0xFF262D35))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp)
    )
}

@Composable
private fun BarButton(
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Text(
        label,
        color = if (active) Ink else Color.White,
        fontSize = 13.sp,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (active) Amber else Color(0xFF1E242B))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
    )
}

/**
 * 改 AI 读数。
 *
 * AI 读错一个数，后果不只是这一张难看：组装值和组级提示词都按占位符从这里取数，
 * 一个错值会顺着 ${'$'}{步骤ID.ai} 污染整组的结论。所以必须能就地改。
 *
 * 清空保存等于否掉这个读数，标成 rejected —— 下次跑批不会再自动把它填回来。
 */
@Composable
private fun EditValueDialog(
    title: String,
    hint: String,
    initial: String,
    onCancel: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xE6000000))
            .clickable(onClick = onCancel),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .padding(24.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Panel)
                .clickable(enabled = false) {}
                .padding(18.dp)
        ) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(hint, color = Steel, fontSize = 11.sp, lineHeight = 17.sp)

            Spacer(Modifier.height(12.dp))
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp, lineHeight = 22.sp),
                cursorBrush = SolidColor(Amber),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Ink)
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            )

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "保存",
                    color = Ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Amber)
                        .clickable { onSave(text.trim()) }
                        .padding(vertical = 11.dp)
                )
                Text(
                    "取消",
                    color = Steel,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, Steel, RoundedCornerShape(6.dp))
                        .clickable(onClick = onCancel)
                        .padding(vertical = 11.dp)
                )
            }
        }
    }
}
