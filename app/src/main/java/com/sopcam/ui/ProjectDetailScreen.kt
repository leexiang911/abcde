package com.sopcam.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sopcam.archive.Archive
import com.sopcam.archive.Thumbs
import com.sopcam.capture.Codes
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val shotTimeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

/** 一张原图 + 它随行 json 里的步骤信息 */
data class ShotItem(
    val file: File,
    val stepOrder: Int,
    val stepName: String,
    val at: Long,
    val codeValue: String = "",
)

fun readShots(serialNo: String): List<ShotItem> = Archive.shots(serialNo).map { f ->
    val side = Archive.sidecar(f)
    ShotItem(
        file = f,
        stepOrder = side?.optInt("stepOrder", 0) ?: 0,
        stepName = side?.optString("stepName") ?: "",
        at = side?.optLong("capturedAt", f.lastModified()) ?: f.lastModified(),
        codeValue = side?.optString("codeValue") ?: "",
    )
}

@Composable
fun ProjectDetailScreen(
    project: Archive.Project,
    shots: List<ShotItem>,
    busy: String?,
    onSetStatus: (Archive.Status) -> Unit,
    onSetNote: (String) -> Unit,
    onRestoreOne: (ShotItem) -> Unit,
    onRestoreAll: () -> Unit,
    onDeleteShot: (ShotItem) -> Unit,
    onDeleteProject: (DeleteScope) -> Unit,
    onBatch: (List<ShotItem>, BatchAction) -> Unit,
    onEditShot: (ShotItem, String, List<String>, String) -> Unit,
    onBack: () -> Unit,
) {
    // 打开查看器时记的是下标而不是对象 —— 左右滑动要靠它在整个列表里走
    var viewingAt by remember { mutableStateOf<Int?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmBatchDelete by remember { mutableStateOf(false) }
    var pendingScope by remember { mutableStateOf<DeleteScope?>(null) }
    val selecting = picked.isNotEmpty()

    // 删完图之后下标可能越界
    LaunchedEffect(shots.size) {
        if (viewingAt != null && viewingAt!! >= shots.size) viewingAt = null
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        Column(Modifier.fillMaxSize()) {

            Spacer(Modifier.height(32.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        project.serialNo,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    val tags = listOf(project.model, project.platform, project.fault)
                        .filter { it.isNotBlank() }
                    Text(
                        (if (tags.isEmpty()) "未标注" else tags.joinToString(" · ")) +
                            "  ·  ${shots.size} 张原图",
                        color = Steel,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    "返回",
                    color = Amber,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .border(1.dp, Amber, RoundedCornerShape(4.dp))
                        .clickable(onClick = onBack)
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                )
            }

            Spacer(Modifier.height(14.dp))

            StatusPicker(project.status, onSetStatus)

            Spacer(Modifier.height(10.dp))

            NoteBox(project.note, onSetNote)

            Spacer(Modifier.height(12.dp))

            if (busy != null) {
                Text(
                    busy,
                    color = Amber,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .fillMaxWidth()
                        .background(Panel, RoundedCornerShape(8.dp))
                        .padding(12.dp)
                )
                Spacer(Modifier.height(12.dp))
            }

            if (shots.isEmpty()) {
                Text(
                    "这个项目里没有原图。可能是拍摄时没开「同时保存无水印原图」。",
                    color = Steel,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .fillMaxWidth()
                        .background(Panel, RoundedCornerShape(8.dp))
                        .padding(16.dp)
                )
                Spacer(Modifier.weight(1f))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(shots, key = { _, it -> it.file.absolutePath }) { i, item ->
                        ThumbCell(
                            item = item,
                            checked = item.file.absolutePath in picked,
                            selecting = selecting,
                            onTap = {
                                if (selecting) {
                                    picked = toggle(picked, item)
                                } else {
                                    viewingAt = i
                                }
                            },
                            onLongPress = { picked = toggle(picked, item) }
                        )
                    }
                }
            }

            if (selecting) {
                BatchBar(
                    count = picked.size,
                    busy = busy,
                    onAction = { act ->
                        onBatch(shots.filter { it.file.absolutePath in picked }, act)
                        picked = emptySet()
                    },
                    onRequestDelete = { confirmBatchDelete = true },
                    onCancel = { picked = emptySet() }
                )
            } else if (shots.isNotEmpty()) {
                Text(
                    "把这 ${shots.size} 张全部重烧回相册",
                    color = Ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Amber)
                        .clickable(enabled = busy == null, onClick = onRestoreAll)
                        .padding(vertical = 14.dp),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(10.dp))
            }

            if (!selecting) Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 20.dp)
            ) {
                Text(
                    "删除…",
                    color = Color(0xFFE86A5C),
                    fontSize = 14.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0x55E86A5C), RoundedCornerShape(8.dp))
                        .clickable { confirming = true }
                        .padding(vertical = 14.dp),
                    textAlign = TextAlign.Center
                )
            }
        }

        viewingAt?.let { start ->
            if (start < shots.size) {
                ShotPager(
                    shots = shots,
                    startAt = start,
                    onRestore = onRestoreOne,
                    onEdit = onEditShot,
                    onDelete = { item ->
                        onDeleteShot(item)
                        if (shots.size <= 1) viewingAt = null
                    },
                    onClose = { viewingAt = null }
                )
            }
        }

        pendingScope?.let { scope ->
            ConfirmTypedDialog(
                title = if (scope == DeleteScope.BOTH) "删除整个项目" else "删除全部原图",
                detail = if (scope == DeleteScope.BOTH)
                    "${project.serialNo} 的原图和相册成片会一起清空，不可恢复。"
                else
                    "${project.serialNo} 的 ${shots.size} 张原图会被删除，相册成片保留，" +
                        "但以后不能再重烧。",
                actionLabel = "删除",
                onCancel = { pendingScope = null },
                onConfirm = {
                    pendingScope = null
                    onDeleteProject(scope)
                }
            )
        }

        if (confirmBatchDelete) {
            ConfirmTypedDialog(
                title = "删除 ${picked.size} 张原图",
                detail = "删的是归档里的原图，删完这几张就再也重烧不出水印照片了。" +
                    "相册里已有的成片不受影响。",
                actionLabel = "删除",
                onCancel = { confirmBatchDelete = false },
                onConfirm = {
                    confirmBatchDelete = false
                    onBatch(shots.filter { it.file.absolutePath in picked }, BatchAction.DELETE)
                    picked = emptySet()
                }
            )
        }

        if (confirming) {
            DeleteProjectDialog(
                serialNo = project.serialNo,
                shotCount = shots.size,
                onCancel = { confirming = false },
                onConfirm = { scope ->
                    confirming = false
                    // 只清相册的随时能重烧回来，不设门槛；
                    // 碰到原图的两档不可恢复，要手打确认
                    if (scope == DeleteScope.GALLERY_ONLY) onDeleteProject(scope)
                    else pendingScope = scope
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThumbCell(
    item: ShotItem,
    checked: Boolean,
    selecting: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    var bmp by remember(item.file.absolutePath) { mutableStateOf<Bitmap?>(null) }

    // 解码放到 IO 线程，几十张一起解会把主线程卡死
    LaunchedEffect(item.file.absolutePath) {
        bmp = withContext(Dispatchers.IO) { Thumbs.of(item.file) }
    }

    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(Panel)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
    ) {
        bmp?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = item.stepName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (selecting) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(if (checked) Color(0x66FDCE04) else Color(0x99000000))
            )
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(18.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (checked) Amber else Color(0x66FFFFFF)),
                contentAlignment = Alignment.Center
            ) {
                if (checked) Text("✓", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        // 有码的角上点一个绿点，一眼看出哪些带了码值 —— 误带的也就好找了
        if (item.codeValue.isNotBlank() && !selecting) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Done)
            )
        }

        if (item.stepOrder > 0) {
            Text(
                item.stepOrder.toString().padStart(2, '0'),
                color = Ink,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Amber)
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }

        Text(
            item.stepName.ifBlank { shotTimeFmt.format(Date(item.at)) },
            color = Color.White,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xAA000000))
                .padding(horizontal = 5.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun ShotViewer(
    item: ShotItem,
    indexLabel: String,
    onRestore: () -> Unit,
    onEdit: (String, List<String>, String) -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    var bmp by remember(item.file.absolutePath) { mutableStateOf<Bitmap?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var code by remember(item.file.absolutePath) { mutableStateOf(item.codeValue) }
    var scanning by remember(item.file.absolutePath) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(item.file.absolutePath) {
        bmp = withContext(Dispatchers.IO) { Thumbs.full(item.file) }
    }

    var editing by remember(item.file.absolutePath) { mutableStateOf(false) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        bmp?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = item.stepName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(vertical = 80.dp)
            )
        } ?: Text("加载中…", color = Steel, fontSize = 13.sp)

        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 40.dp, start = 20.dp, end = 20.dp)
                .fillMaxWidth()
        ) {
            Text(
                buildString {
                    if (item.stepOrder > 0) append("${item.stepOrder.toString().padStart(2, '0')} · ")
                    append(item.stepName.ifBlank { "自由拍摄" })
                },
                color = Color.White,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "$indexLabel  ·  ${shotTimeFmt.format(Date(item.at))}",
                color = Steel,
                fontSize = 12.sp
            )
            if (code.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    code,
                    color = Done,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x332CC38A))
                        .clickable { copyText(ctx, code) }
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                )
                Spacer(Modifier.height(3.dp))
                Text("点一下复制", color = Color(0xFF4A525C), fontSize = 10.sp)
            }
        }

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 30.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (confirming) {
                Text(
                    "确认删除这张原图",
                    color = Ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFFE86A5C))
                        .clickable(onClick = onDelete)
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                )
                Text(
                    "取消",
                    color = Steel,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, Color(0xFF2A3037), RoundedCornerShape(6.dp))
                        .clickable { confirming = false }
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                )
            } else {
                if (code.isBlank()) {
                    Text(
                        if (scanning) "识别中…" else "扫码",
                        color = Ink,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (scanning) Steel else Done)
                            .clickable(enabled = !scanning) {
                                scanning = true
                                scope.launch {
                                    // 拿全分辨率原图去扫，不是屏幕上这张缩过的
                                    val hit = withContext(Dispatchers.IO) {
                                        val full = BitmapFactory.decodeFile(item.file.path)
                                        val r = full?.let { Codes.scan(it) }
                                        full?.recycle()
                                        r
                                    }
                                    if (hit != null) {
                                        Archive.updateSidecarCode(item.file, hit.value, hit.format)
                                        code = hit.value
                                    } else {
                                        code = "· 没认出来"
                                    }
                                    scanning = false
                                }
                            }
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                }
                if (code.isNotBlank()) {
                    Text(
                        "清除码值",
                        color = Steel,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .border(1.dp, Color(0xFF2A3037), RoundedCornerShape(6.dp))
                            .clickable {
                                Archive.clearSidecarCode(item.file)
                                code = ""
                            }
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                }
                Text(
                    "改水印",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF262D35))
                        .clickable { editing = true }
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                )
                Text(
                    "重烧回相册",
                    color = Ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Amber)
                        .clickable(onClick = onRestore)
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                )
                Text(
                    "删除这张",
                    color = Color(0xFFE86A5C),
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, Color(0x55E86A5C), RoundedCornerShape(6.dp))
                        .clickable { confirming = true }
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                )
                Text(
                    "关闭",
                    color = Steel,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, Color(0xFF2A3037), RoundedCornerShape(6.dp))
                        .clickable(onClick = onClose)
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }
        }

        if (editing) {
            EditShotDialog(
                item = item,
                onCancel = { editing = false },
                onSave = { h, l, n ->
                    editing = false
                    onEdit(h, l, n)
                }
            )
        }
    }
}

/**
 * 改水印文字和文件名。
 *
 * 改的是归档里那份随行 json，屏幕上和相册里的成片不会立刻变 ——
 * 要按"重烧回相册"才会按新内容重新生成一张。这样改错了也不至于毁掉已有成片。
 */
@Composable
private fun EditShotDialog(
    item: ShotItem,
    onCancel: () -> Unit,
    onSave: (String, List<String>, String) -> Unit,
) {
    val side = remember(item.file.absolutePath) { Archive.sidecar(item.file) }
    var headline by remember {
        mutableStateOf(side?.optString("headline").orEmpty())
    }
    var body by remember {
        mutableStateOf(
            side?.optJSONArray("lines")?.let { arr ->
                (0 until arr.length()).joinToString("\n") { arr.optString(it) }
            }.orEmpty()
        )
    }
    var name by remember {
        mutableStateOf(
            side?.optString("fileName")?.takeIf { it.isNotBlank() }
                ?: item.file.nameWithoutExtension
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xF0000000))
            .clickable(onClick = onCancel),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .padding(20.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Panel)
                .clickable(enabled = false) {}
                .padding(18.dp)
        ) {
            Text("改水印", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))

            EditField("标题行（步骤）", headline) { headline = it }
            Spacer(Modifier.height(12.dp))
            EditField("正文（一行一条）", body, minLines = 3) { body = it }
            Spacer(Modifier.height(12.dp))
            EditField("文件名（不含扩展名）", name) { name = it }

            Spacer(Modifier.height(8.dp))
            Text(
                "保存后按「重烧回相册」才会生成新的成片，原有的不受影响",
                color = Color(0xFF4A525C),
                fontSize = 11.sp,
                lineHeight = 17.sp
            )

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "保存",
                    color = Ink,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Amber)
                        .clickable {
                            onSave(
                                headline.trim(),
                                body.split("\n").map { it.trim() }.filter { it.isNotEmpty() },
                                name.trim()
                            )
                        }
                        .padding(vertical = 13.dp)
                )
                Text(
                    "取消",
                    color = Steel,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFF2A3037), RoundedCornerShape(8.dp))
                        .clickable(onClick = onCancel)
                        .padding(vertical = 13.dp)
                )
            }
        }
    }
}

@Composable
private fun EditField(
    label: String,
    value: String,
    minLines: Int = 1,
    onChange: (String) -> Unit,
) {
    Column {
        Text(label, color = Steel, fontSize = 12.sp)
        Spacer(Modifier.height(5.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
            cursorBrush = SolidColor(Amber),
            minLines = minLines,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(Ink)
                .padding(horizontal = 12.dp, vertical = 11.dp)
        )
    }
}

/** 删哪一份 */
enum class DeleteScope { GALLERY_ONLY, ARCHIVE_ONLY, BOTH }

/**
 * 删除确认。
 *
 * 归档和相册是两份独立的数据，后果完全不同，所以分三个按钮而不是一个"删除"：
 * 只删相册 = 成片没了但随时能重烧回来，最安全；
 * 只删归档 = 成片还在，但从此失去重烧能力；
 * 一起删   = 这个控制器的留档彻底没了。
 */
@Composable
private fun DeleteProjectDialog(
    serialNo: String,
    shotCount: Int,
    onCancel: () -> Unit,
    onConfirm: (DeleteScope) -> Unit,
) {
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
                .clip(RoundedCornerShape(12.dp))
                .background(Panel)
                .clickable(enabled = false) {}
                .padding(20.dp)
        ) {
            Text("删除", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                serialNo,
                color = Steel,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(18.dp))

            ScopeOption(
                title = "只删相册照片",
                desc = "留着 $shotCount 张原图，随时能重烧回来。相册腾干净了但档案还在。",
                tint = Color.White,
                bg = Color(0xFF262D35)
            ) { onConfirm(DeleteScope.GALLERY_ONLY) }

            Spacer(Modifier.height(12.dp))

            ScopeOption(
                title = "只删归档原图",
                desc = "相册里的成片保留，但从此不能再重烧水印。",
                tint = Color.White,
                bg = Color(0xFF262D35)
            ) { onConfirm(DeleteScope.ARCHIVE_ONLY) }

            Spacer(Modifier.height(12.dp))

            ScopeOption(
                title = "两份都删",
                desc = "这个控制器的留档全部清空，不可恢复。",
                tint = Ink,
                bg = Color(0xFFE86A5C),
                descTint = Color(0xFFE86A5C)
            ) { onConfirm(DeleteScope.BOTH) }

            Spacer(Modifier.height(18.dp))
            Text(
                "取消",
                color = Steel,
                fontSize = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onCancel)
                    .padding(vertical = 10.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ScopeOption(
    title: String,
    desc: String,
    tint: Color,
    bg: Color,
    descTint: Color = Steel,
    onClick: () -> Unit,
) {
    Text(
        title,
        color = tint,
        fontSize = 15.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(14.dp)
    )
    Spacer(Modifier.height(4.dp))
    Text(desc, color = descTint, fontSize = 11.sp, lineHeight = 17.sp)
}

/** 状态标记：三个颜色块，点一下切换。再点一次取消标记 */
@Composable
private fun StatusPicker(current: Archive.Status, onPick: (Archive.Status) -> Unit) {
    Row(
        Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Archive.Status.entries.filter { it != Archive.Status.NONE }.forEach { st ->
            val on = current == st
            val tint = statusColor(st)
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) tint else Panel)
                    .border(1.dp, if (on) tint else Color(0xFF232A31), RoundedCornerShape(8.dp))
                    .clickable { onPick(if (on) Archive.Status.NONE else st) }
                    .padding(vertical = 11.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    st.label,
                    color = if (on) Ink else Steel,
                    fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

private fun copyText(ctx: Context, text: String) {
    runCatching {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("SOP Camera", text))
    }
}

enum class BatchAction { SCAN, CLEAR_CODE, DELETE }

private fun toggle(set: Set<String>, item: ShotItem): Set<String> {
    val k = item.file.absolutePath
    return if (k in set) set - k else set + k
}

@Composable
private fun BatchBar(
    count: Int,
    busy: String?,
    onAction: (BatchAction) -> Unit,
    onRequestDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 20.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("已选 $count 张", color = Amber, fontSize = 14.sp)
            Text(
                "取消",
                color = Steel,
                fontSize = 13.sp,
                modifier = Modifier.clickable(onClick = onCancel).padding(8.dp)
            )
        }

        if (busy != null) {
            Text(busy, color = Amber, fontSize = 13.sp)
            return@Column
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BatchButton("批量扫码", Done, Ink, Modifier.weight(1f)) { onAction(BatchAction.SCAN) }
            BatchButton("清除码值", Color(0xFF262D35), Color.White, Modifier.weight(1f)) {
                onAction(BatchAction.CLEAR_CODE)
            }
            BatchButton("删除", Color(0xFF3A2326), Color(0xFFE86A5C), onClick = onRequestDelete)
        }
    }
}

@Composable
private fun BatchButton(
    label: String,
    bg: Color,
    fg: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Text(
        label,
        color = fg,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp)
    )
}

/** 左右滑动看整个项目的图。滑到哪张就查看哪张，操作按钮跟着当前页走 */
@Composable
private fun ShotPager(
    shots: List<ShotItem>,
    startAt: Int,
    onRestore: (ShotItem) -> Unit,
    onEdit: (ShotItem, String, List<String>, String) -> Unit,
    onDelete: (ShotItem) -> Unit,
    onClose: () -> Unit,
) {
    val pager = rememberPagerState(
        initialPage = startAt.coerceIn(0, (shots.size - 1).coerceAtLeast(0)),
        pageCount = { shots.size }
    )

    Box(Modifier.fillMaxSize().background(Color(0xF5000000))) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            shots.getOrNull(page)?.let { item ->
                ShotViewer(
                    item = item,
                    indexLabel = "${page + 1} / ${shots.size}",
                    onRestore = { onRestore(item) },
                    onEdit = { h, l, n -> onEdit(item, h, l, n) },
                    onDelete = { onDelete(item) },
                    onClose = onClose
                )
            }
        }
    }
}

/**
 * 项目备注。
 *
 * 就地编辑，不弹窗 —— 备注是随手记的东西（"客户说间歇性重启"、"等配件"），
 * 每次都要开关一个对话框太重。失焦即存。
 */
@Composable
private fun NoteBox(note: String, onSave: (String) -> Unit) {
    var editing by remember(note) { mutableStateOf(false) }
    var text by remember(note) { mutableStateOf(note) }

    if (!editing) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Panel)
                .clickable { editing = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                note.ifBlank { "加个备注…" },
                color = if (note.isBlank()) Color(0xFF4A525C) else Amber,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                modifier = Modifier.weight(1f)
            )
            Text("✎", color = Steel, fontSize = 14.sp)
        }
        return
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Panel)
            .padding(14.dp)
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = TextStyle(color = Color.White, fontSize = 14.sp, lineHeight = 20.sp),
            cursorBrush = SolidColor(Amber),
            minLines = 2,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(Ink)
                .padding(horizontal = 10.dp, vertical = 10.dp)
        )
        Spacer(Modifier.height(10.dp))
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
                    .clickable {
                        editing = false
                        onSave(text.trim())
                    }
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
                    .border(1.dp, Color(0xFF2A3037), RoundedCornerShape(6.dp))
                    .clickable {
                        text = note
                        editing = false
                    }
                    .padding(vertical = 11.dp)
            )
        }
    }
}
