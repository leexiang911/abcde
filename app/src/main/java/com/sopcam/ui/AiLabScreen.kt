package com.sopcam.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import com.sopcam.ai.LiteRt
import com.sopcam.archive.Archive
import com.sopcam.archive.Thumbs
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 实验室。
 *
 * 定位是**验证台**，不是功能页 —— 先弄清楚这台机器上模型跑不跑得动、
 * 读表准不准，再决定要不要建 prompt 管理和模型管理那两套界面。
 * 所以这里把耗时、后端、原始输出全都摊开显示，出了问题能一眼看出卡在哪。
 */
@Composable
fun AiLabScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var models by remember { mutableStateOf<List<File>>(emptyList()) }
    var picked by remember { mutableStateOf<File?>(null) }
    var device by remember { mutableStateOf(LiteRt.Device.GPU) }
    var busy by remember { mutableStateOf<String?>(null) }
    var log by remember { mutableStateOf("") }

    var shots by remember { mutableStateOf<List<File>>(emptyList()) }
    var shot by remember { mutableStateOf<File?>(null) }
    var prompt by remember {
        mutableStateOf("读出图中仪表显示的数值，只回答数值和单位，不要解释。")
    }

    LaunchedEffect(Unit) {
        models = withContext(Dispatchers.IO) { LiteRt.findModels() }
        picked = models.firstOrNull()
        shots = withContext(Dispatchers.IO) {
            Archive.list().take(4).flatMap { Archive.shots(it.serialNo).take(6) }
        }
    }

    fun append(line: String) {
        log = (log + "\n" + line).trim().takeLast(6000)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Spacer(Modifier.height(28.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("AI 实验室", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
                Text("先验证跑不跑得动、准不准", color = Steel, fontSize = 12.sp)
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

        Spacer(Modifier.height(20.dp))
        Section("模型文件")

        if (models.isEmpty()) {
            Hint("没找到 .litertlm 文件。放到 Download、Documents 或存储根目录的 Models 文件夹里，再回来。")
        } else {
            models.forEach { f ->
                val on = picked?.absolutePath == f.absolutePath
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (on) Color(0x33FDCE04) else Panel)
                        .border(1.dp, if (on) Amber else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable { picked = f }
                        .padding(12.dp)
                ) {
                    Text(
                        f.name,
                        color = if (on) Amber else Color.White,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "%.2f GB · %s".format(f.length() / 1e9, f.parent ?: ""),
                        color = Steel,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Section("推理后端")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LiteRt.Device.entries.forEach { d ->
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            when {
                                !d.usable -> Color(0xFF1A1F25)
                                device == d -> Amber
                                else -> Panel
                            }
                        )
                        .clickable(enabled = d.usable) { device = d }
                        .padding(vertical = 10.dp, horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        d.label,
                        color = when {
                            !d.usable -> Color(0xFF3A424B)
                            device == d -> Ink
                            else -> Color.White
                        },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        d.note,
                        color = if (d.usable) {
                            if (device == d) Color(0x99000000) else Steel
                        } else Color(0xFF343B44),
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        val ready = LiteRt.isReady
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Big(
                if (ready) "重新加载" else "加载模型",
                enabled = picked != null && busy == null,
                primary = !ready,
                modifier = Modifier.weight(1f)
            ) {
                val f = picked ?: return@Big
                busy = "加载中，可能要十几秒…"
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        LiteRt.load(ctx, f.absolutePath, device)
                    }
                    busy = null
                    r.onSuccess { append("✓ 加载成功 ${it.device.label} 用时 ${it.millis} ms") }
                        .onFailure { append("✗ 加载失败 ${it.javaClass.simpleName}: ${it.message}") }
                }
            }
            if (ready) {
                Big("卸载", enabled = busy == null, modifier = Modifier.weight(1f)) {
                    LiteRt.unload()
                    append("已卸载，显存和内存已释放")
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Section("测试一 · 纯文本")
        Hint("先确认引擎本身通不通。这一步都跑不出来，图片那条更别想。")

        Big("问一句「你好」", enabled = ready && busy == null) {
            busy = "生成中…"
            scope.launch {
                val r = withContext(Dispatchers.Default) { LiteRt.ask("你好，请用一句话自我介绍。") }
                busy = null
                r.onSuccess { append("✓ 文本 ${it.millis} ms\n${it.text}") }
                    .onFailure { append("✗ 文本失败 ${it.javaClass.simpleName}: ${it.message}") }
            }
        }

        Spacer(Modifier.height(20.dp))
        Section("测试二 · 读图")

        if (shots.isEmpty()) {
            Hint("归档区里还没有照片。先拍几张带表计读数的，再回来测。")
        } else {
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                shots.forEach { f -> ShotChip(f, shot?.absolutePath == f.absolutePath) { shot = f } }
            }

            Text("提示词", color = Steel, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            BasicTextField(
                value = prompt,
                onValueChange = { prompt = it },
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp, lineHeight = 20.sp),
                cursorBrush = SolidColor(Amber),
                minLines = 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Panel)
                    .padding(12.dp)
            )

            Spacer(Modifier.height(10.dp))
            Big("识别这张", enabled = ready && shot != null && busy == null, primary = true) {
                val f = shot ?: return@Big
                busy = "识别中，第一张会慢一些…"
                scope.launch {
                    val r = withContext(Dispatchers.Default) {
                        LiteRt.askImage(ctx, f.absolutePath, prompt)
                    }
                    busy = null
                    r.onSuccess { append("✓ 读图 ${it.millis} ms · ${f.name}\n${it.text}") }
                        .onFailure { append("✗ 读图失败 ${it.javaClass.simpleName}: ${it.message}") }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        busy?.let {
            Text(
                it,
                color = Amber,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Panel)
                    .padding(12.dp)
            )
            Spacer(Modifier.height(12.dp))
        }

        Section("输出")
        Text(
            log.ifBlank { "还没有输出" },
            color = if (log.isBlank()) Steel else Color(0xFFD6DBE0),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 18.sp,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Panel)
                .padding(12.dp)
        )

        if (log.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Big("复制输出", modifier = Modifier.weight(1f)) { copyToClipboard(ctx, log) }
                Big("清空", modifier = Modifier.weight(1f)) { log = "" }
            }
        }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun ShotChip(file: File, active: Boolean, onTap: () -> Unit) {
    var bmp by remember(file.absolutePath) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file.absolutePath) {
        bmp = withContext(Dispatchers.IO) { Thumbs.of(file, 200) }
    }
    Box(
        Modifier
            .size(84.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Panel)
            .border(2.dp, if (active) Amber else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable(onClick = onTap)
    ) {
        bmp?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        color = Steel,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
    )
}

@Composable
private fun Big(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    Text(
        label,
        color = when {
            !enabled -> Color(0xFF4A525C)
            primary -> Ink
            else -> Color.White
        },
        fontSize = 14.sp,
        fontWeight = if (primary) FontWeight.Bold else FontWeight.Normal,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    !enabled -> Color(0xFF1E242B)
                    primary -> Amber
                    else -> Color(0xFF262D35)
                }
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 13.dp)
    )
}
