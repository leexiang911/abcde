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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sopcam.archive.Archive
import com.sopcam.archive.Exporter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dayFmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

@Composable
fun ProjectsScreen(
    projects: List<Archive.Project>,
    selected: Set<String>,
    exporting: String?,
    onToggle: (String) -> Unit,
    onOpen: (Archive.Project) -> Unit,
    onSelectAll: () -> Unit,
    onExport: (Exporter.Options) -> Unit,
    onBack: () -> Unit,
) {
    val sheetOpen = selected.isNotEmpty()

    Box(Modifier.fillMaxSize().background(Ink)) {
        Column(Modifier.fillMaxSize()) {

            Spacer(Modifier.height(32.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("检修项目", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (selected.isEmpty()) "${projects.size} 个项目"
                        else "已选 ${selected.size} 个",
                        color = if (selected.isEmpty()) Steel else Amber,
                        fontSize = 13.sp
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (projects.isNotEmpty()) {
                        Text(
                            if (selected.size == projects.size) "取消全选" else "全选",
                            color = Steel,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .clickable(onClick = onSelectAll)
                                .padding(10.dp)
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
            }

            Spacer(Modifier.height(14.dp))

            if (projects.isEmpty()) {
                Text(
                    "还没有归档。拍照时如果开着「同时保存无水印原图」，每个控制器会自动建一个项目。",
                    color = Steel,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .fillMaxWidth()
                        .background(Panel, RoundedCornerShape(8.dp))
                        .padding(16.dp)
                )
            } else {
                LazyColumn(Modifier.weight(1f).padding(horizontal = 20.dp)) {
                    items(projects, key = { it.serialNo }) { p ->
                        ProjectRow(
                            p = p,
                            checked = p.serialNo in selected,
                            onCheck = { onToggle(p.serialNo) },
                            onOpen = { onOpen(p) }
                        )
                    }
                    item { Spacer(Modifier.height(if (sheetOpen) 220.dp else 24.dp)) }
                }
            }
        }

        if (sheetOpen) {
            Box(Modifier.align(Alignment.BottomCenter)) {
                ExportBar(selected.toList(), exporting, onExport)
            }
        }
    }
}

@Composable
private fun ProjectRow(
    p: Archive.Project,
    checked: Boolean,
    onCheck: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Panel)
            .border(
                if (checked) 2.dp else 1.dp,
                if (checked) Amber else Color(0xFF2A3037),
                RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onOpen)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(20.dp)
                .clickable(onClick = onCheck)
                .clip(RoundedCornerShape(4.dp))
                .background(if (checked) Amber else Color.Transparent)
                .border(
                    1.5.dp,
                    if (checked) Amber else Color(0xFF3A424B),
                    RoundedCornerShape(4.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (checked) Text("✓", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.width(12.dp))


        Column(Modifier.weight(1f)) {
            Text(
                p.serialNo,
                color = Color.White,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(5.dp))
            val tags = listOf(p.model, p.platform, p.fault).filter { it.isNotBlank() }
            Text(
                if (tags.isEmpty()) "未标注型号" else tags.joinToString(" · "),
                color = Steel,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text("${p.shotCount} 张", color = Done, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Text(dayFmt.format(Date(p.updatedAt)), color = Color(0xFF4A525C), fontSize = 11.sp)
        }
    }
}

@Composable
private fun ExportBar(
    serials: List<String>,
    exporting: String?,
    onExport: (Exporter.Options) -> Unit,
) {
    // 按下导出之前先把张数和体积摆出来 —— 微信传文件有上限，
    // 80MB 的包发不出去，事后才发现最耽误事
    val wm = remember(serials) { Exporter.plan(serials, Exporter.Options(true, false)) }
    val raw = remember(serials) { Exporter.plan(serials, Exporter.Options(false, true)) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
            .background(Color(0xFA1B2026))
            .padding(20.dp)
    ) {
        if (exporting != null) {
            Text(exporting, color = Amber, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Text("打包中，别退出这个页面", color = Steel, fontSize = 12.sp)
            return@Column
        }

        Text("导出", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(12.dp))

        ExportButton(
            "只导水印图",
            "${wm.fileCount} 张 · ${Exporter.humanSize(wm.bytes)}",
            wm.fileCount > 0
        ) { onExport(Exporter.Options(watermarked = true, original = false)) }

        Spacer(Modifier.height(8.dp))

        ExportButton(
            "只导原图",
            "${raw.fileCount} 张 · ${Exporter.humanSize(raw.bytes)}",
            raw.fileCount > 0
        ) { onExport(Exporter.Options(watermarked = false, original = true)) }

        Spacer(Modifier.height(8.dp))

        ExportButton(
            "两样都要",
            "${wm.fileCount + raw.fileCount} 张 · ${Exporter.humanSize(wm.bytes + raw.bytes)}",
            wm.fileCount + raw.fileCount > 0,
            primary = true
        ) { onExport(Exporter.Options(watermarked = true, original = true)) }

        Spacer(Modifier.height(10.dp))
        Text(
            "点项目可以进去看图。zip 里水印图和原图分开放，导出不会动原来的数据",
            color = Color(0xFF4A525C),
            fontSize = 11.sp
        )
    }
}

@Composable
private fun ExportButton(
    label: String,
    detail: String,
    enabled: Boolean,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    !enabled -> Color(0xFF20262C)
                    primary -> Amber
                    else -> Color(0xFF262D35)
                }
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = when {
                !enabled -> Color(0xFF4A525C)
                primary -> Ink
                else -> Color.White
            },
            fontSize = 15.sp,
            fontWeight = if (primary) FontWeight.Bold else FontWeight.Normal
        )
        Text(
            if (enabled) detail else "没有内容",
            color = when {
                !enabled -> Color(0xFF4A525C)
                primary -> Color(0x99000000)
                else -> Steel
            },
            fontSize = 12.sp
        )
    }
}
