package com.sopcam.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
@Composable
fun ShotViewer(
    item: ShotItem,
    indexLabel: String,
    busy: String?,
    onApply: (Anchor, Int) -> Unit,
    onRetake: () -> Unit,
    onEditText: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var bmp by remember(item.file.absolutePath) { mutableStateOf<Bitmap?>(null) }
    var tray by remember(item.file.absolutePath) { mutableStateOf(Tray.NONE) }
    var code by remember(item.file.absolutePath) { mutableStateOf(item.codeValue) }
    var scanning by remember(item.file.absolutePath) { mutableStateOf(false) }
    var offerCrop by remember(item.file.absolutePath) { mutableStateOf(false) }
    var cropping by remember(item.file.absolutePath) { mutableStateOf(false) }
    var cropNote by remember(item.file.absolutePath) { mutableStateOf<String?>(null) }

    // 水印内容从随行 json 读，预览要跟成片一致
    val side = remember(item.file.absolutePath) { Archive.sidecar(item.file) }
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

    var anchor by remember(item.file.absolutePath) { mutableStateOf(savedAnchor) }
    var rotation by remember(item.file.absolutePath) { mutableStateOf(savedRotation) }
    var showMark by remember(item.file.absolutePath) {
        mutableStateOf(headline != null || lines.isNotEmpty())
    }
    val dirty = anchor != savedAnchor || rotation != savedRotation

    LaunchedEffect(item.file.absolutePath) {
        bmp = withContext(Dispatchers.IO) { Thumbs.full(item.file) }
    }

    var stage by remember { mutableStateOf(IntSize.Zero) }

    Box(Modifier.fillMaxSize()) {

        /* ---------- 图片与水印预览 ---------- */
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = 96.dp, bottom = 150.dp)
                .onSizeChanged { stage = it },
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
                        .clickable { copyToClip(ctx, code) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
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
                    onDelete = onDelete
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
    onScan: () -> Unit,
    onCrop: () -> Unit,
    onClearCode: () -> Unit,
    onEditText: () -> Unit,
    onDelete: () -> Unit,
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
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TrayButton("改水印文字", modifier = Modifier.weight(1f), onClick = onEditText)
            TrayButton(
                "删除这张",
                modifier = Modifier.weight(1f),
                danger = true,
                onClick = onDelete
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
