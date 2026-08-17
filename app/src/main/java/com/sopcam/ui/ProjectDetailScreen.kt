package com.sopcam.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sopcam.archive.Archive
import com.sopcam.archive.Thumbs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val shotTimeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

/** 一张原图 + 它随行 json 里的步骤信息 */
data class ShotItem(val file: File, val stepOrder: Int, val stepName: String, val at: Long)

fun readShots(serialNo: String): List<ShotItem> = Archive.shots(serialNo).map { f ->
    val side = Archive.sidecar(f)
    ShotItem(
        file = f,
        stepOrder = side?.optInt("stepOrder", 0) ?: 0,
        stepName = side?.optString("stepName") ?: "",
        at = side?.optLong("capturedAt", f.lastModified()) ?: f.lastModified(),
    )
}

@Composable
fun ProjectDetailScreen(
    project: Archive.Project,
    shots: List<ShotItem>,
    onDeleteShot: (ShotItem) -> Unit,
    onDeleteProject: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    var viewing by remember { mutableStateOf<ShotItem?>(null) }
    var confirming by remember { mutableStateOf(false) }

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
                    items(shots, key = { it.file.absolutePath }) { item ->
                        ThumbCell(item) { viewing = item }
                    }
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    "删除整个项目",
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

        viewing?.let { item ->
            ShotViewer(
                item = item,
                onDelete = {
                    onDeleteShot(item)
                    viewing = null
                },
                onClose = { viewing = null }
            )
        }

        if (confirming) {
            DeleteProjectDialog(
                serialNo = project.serialNo,
                shotCount = shots.size,
                onCancel = { confirming = false },
                onConfirm = { alsoGallery ->
                    confirming = false
                    onDeleteProject(alsoGallery)
                }
            )
        }
    }
}

@Composable
private fun ThumbCell(item: ShotItem, onTap: () -> Unit) {
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
            .clickable(onClick = onTap)
    ) {
        bmp?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = item.stepName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
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
private fun ShotViewer(item: ShotItem, onDelete: () -> Unit, onClose: () -> Unit) {
    var bmp by remember(item.file.absolutePath) { mutableStateOf<Bitmap?>(null) }
    var confirming by remember { mutableStateOf(false) }

    LaunchedEffect(item.file.absolutePath) {
        bmp = withContext(Dispatchers.IO) { Thumbs.full(item.file) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xF2000000))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center
    ) {
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
            Text(shotTimeFmt.format(Date(item.at)), color = Steel, fontSize = 12.sp)
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
    }
}

/**
 * 删除确认。
 *
 * 归档和相册是两份独立的数据，后果不一样，所以分成两个按钮而不是一个"删除"：
 * 只删归档 = 成片还在，只是以后不能重烧水印了；
 * 一起删   = 这个控制器的留档彻底没了。
 */
@Composable
private fun DeleteProjectDialog(
    serialNo: String,
    shotCount: Int,
    onCancel: () -> Unit,
    onConfirm: (Boolean) -> Unit,
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
            Text("删除项目", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                serialNo,
                color = Steel,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(16.dp))

            Text(
                "只删归档",
                color = Color.White,
                fontSize = 15.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF262D35))
                    .clickable { onConfirm(false) }
                    .padding(14.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "删掉 $shotCount 张原图，相册里的水印照片保留。删了就不能再重烧水印。",
                color = Steel,
                fontSize = 11.sp,
                lineHeight = 17.sp
            )

            Spacer(Modifier.height(14.dp))

            Text(
                "归档和相册一起删",
                color = Color.White,
                fontSize = 15.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFE86A5C))
                    .clickable { onConfirm(true) }
                    .padding(14.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "这个控制器的留档全部清空，不可恢复。",
                color = Color(0xFFE86A5C),
                fontSize = 11.sp
            )

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
