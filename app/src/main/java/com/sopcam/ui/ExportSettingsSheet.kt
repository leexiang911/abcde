package com.sopcam.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sopcam.archive.ExportFormat
import com.sopcam.archive.ExportSettings

/**
 * 项目设置。
 *
 * 挂在项目列表的标题栏上，不进主页设置 —— 这些只在导出那一刻才关心，
 * 跟拍照相关的设置放一起反而要翻两层。
 */
@Composable
fun ExportSettingsSheet(
    settings: ExportSettings,
    onChange: (ExportSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xE60A0C0F))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Panel)
                .clickable(enabled = false) {}
                .padding(18.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("导出设置", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "关闭",
                    color = Steel,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp)
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "只压缩水印图。归档原图是兜底数据，压了就失去意义，永远原样打包。",
                color = Steel,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )

            Spacer(Modifier.height(18.dp))
            Text("压缩比例", color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))

            // 先选比例，再选格式 —— 选了"不压缩"格式就没有意义了
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ExportSettings.LEVELS.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { q ->
                            Chip(
                                label = if (q == 100) "不压缩" else "$q%",
                                active = settings.quality == q,
                                modifier = Modifier.weight(1f)
                            ) { onChange(settings.copy(quality = q)) }
                        }
                        // 补齐空位，免得最后一行的按钮被拉宽
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                "格式",
                color = if (settings.compresses) Color.White else Color(0xFF4A525C),
                fontSize = 14.sp
            )
            Spacer(Modifier.height(8.dp))

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ExportFormat.entries.forEach { f ->
                    FormatRow(
                        format = f,
                        active = settings.format == f && settings.compresses,
                        // 不压缩时整组禁用；MozJPEG 永远禁用，占个位以后再说
                        enabled = settings.compresses && f.enabled,
                    ) { onChange(settings.copy(format = f)) }
                }
            }

            if (settings.compresses) {
                Spacer(Modifier.height(16.dp))
                ToggleRow(
                    title = "保留元数据",
                    desc = if (settings.metadataPossible)
                        "压缩会抹掉 EXIF 和 XMP，这里把 XMP 重新塞回去"
                    else
                        "WebP 不支持，选 JPEG 才能保留",
                    checked = settings.keepMetadata && settings.metadataPossible,
                    enabled = settings.metadataPossible,
                ) { onChange(settings.copy(keepMetadata = it)) }
            }

            Spacer(Modifier.height(20.dp))
            Text("报表", color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))

            ToggleRow(
                title = "HTML 报表",
                desc = "包里带一份网页报表和 report.json，双击就能看，" +
                    "数值可以直接在页面上填，填完复制成表格粘进系统",
                checked = settings.htmlReport,
                enabled = true,
            ) { onChange(settings.copy(htmlReport = it)) }

            Spacer(Modifier.height(6.dp))

            ToggleRow(
                title = "Excel 报表",
                desc = "下一轮做",
                checked = false,
                enabled = false,
            ) { }

            Spacer(Modifier.height(16.dp))
            Text(
                "当前：${settings.summary()}",
                color = Amber,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun Chip(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
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
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) Amber else Color(0xFF262D35))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp)
    )
}

@Composable
private fun FormatRow(
    format: ExportFormat,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    active -> Color(0x33FDCE04)
                    enabled -> Color(0xFF262D35)
                    else -> Color(0xFF1A1F25)
                }
            )
            .border(
                1.dp,
                if (active) Amber else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                format.label,
                color = when {
                    active -> Amber
                    enabled -> Color.White
                    else -> Color(0xFF3A424B)
                },
                fontSize = 14.sp
            )
            Spacer(Modifier.height(3.dp))
            Text(
                format.note,
                color = if (enabled) Steel else Color(0xFF343B44),
                fontSize = 11.sp
            )
        }
        if (active) Text("✓", color = Amber, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ToggleRow(
    title: String,
    desc: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) Ink else Color(0xFF1A1F25))
            .clickable(enabled = enabled) { onToggle(!checked) }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                title,
                color = if (enabled) Color.White else Color(0xFF3A424B),
                fontSize = 14.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                desc,
                color = if (enabled) Steel else Color(0xFF343B44),
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
        Box(
            Modifier
                .size(44.dp, 26.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(if (checked) Amber else Color(0xFF2A3037)),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(20.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (checked) Ink else Steel)
            )
        }
    }
}
