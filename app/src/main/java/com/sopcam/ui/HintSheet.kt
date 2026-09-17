package com.sopcam.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sopcam.archive.Thumbs
import com.sopcam.sop.ConfigSync
import com.sopcam.sop.Hint
import com.sopcam.sop.HintItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 拍摄提示弹层：这一步该拍哪个点位，点开看图。
 *
 * 一条 hint 可以挂多个 item（编号 / 名称 / 图 / 说明），所以这里是列表不是单张图。
 *
 * 图只从本地缓存读，缓存不到就退回文字，绝不现拉 —— 车间可能没网，
 * 而且拍照途中卡在网络请求上比看不到图更糟。图是同步配置时一起下好的。
 */
@Composable
fun HintSheet(hint: Hint, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Ink)) {
        Column(Modifier.fillMaxSize()) {

            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Panel)
                    // 顶上多留一截给状态栏。跟项目里其他整屏页面（相机、项目列表）
                    // 一样用固定值，不引 WindowInsets —— 那套要改 Activity 的
                    // edge-to-edge 设置，为一个浮层不值当
                    .padding(start = 16.dp, end = 16.dp, top = 46.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    hint.name.ifBlank { "拍摄提示" },
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Box(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .border(1.dp, Steel, RoundedCornerShape(4.dp))
                        .clickable(onClick = onClose)
                        .padding(horizontal = 16.dp, vertical = 9.dp)
                ) {
                    Text("关闭", color = Steel, fontSize = 13.sp)
                }
            }

            if (hint.items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("这条提示是空的，检查 hints.json", color = Steel, fontSize = 13.sp)
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(hint.items) { item -> HintCard(item) }
                }
            }
        }
    }
}

@Composable
private fun HintCard(item: HintItem) {
    val ctx = LocalContext.current
    var bmp by remember(item.image) { mutableStateOf<Bitmap?>(null) }
    var missing by remember(item.image) { mutableStateOf(false) }

    LaunchedEffect(item.image) {
        if (item.image.isBlank()) return@LaunchedEffect
        val f = withContext(Dispatchers.IO) { ConfigSync.hintImage(ctx, item.image) }
        if (f == null) {
            missing = true
            return@LaunchedEffect
        }
        // 走 Thumbs 解码：降采样 + LRU，翻回来看同一张不用重解
        val b = withContext(Dispatchers.IO) { Thumbs.of(f, 1080) }
        if (b == null) missing = true else bmp = b
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Panel)
            .padding(12.dp)
    ) {
        val head = listOf(item.no, item.name).filter { it.isNotBlank() }.joinToString(" ")
        if (head.isNotBlank()) {
            Text(head, color = Amber, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
        }

        val b = bmp
        if (item.image.isNotBlank()) {
            when {
                b != null -> Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                )
                missing -> Text(
                    "图没缓存下来。去 设置 → 流程配置 → 检查更新 重下一次",
                    color = Steel, fontSize = 12.sp, lineHeight = 18.sp
                )
                else -> Text("加载中…", color = Steel, fontSize = 12.sp)
            }
        }

        if (item.text.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            MarkdownText(item.text)
        }
    }
}
