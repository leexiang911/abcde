package com.sopcam.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sopcam.archive.Archive
import com.sopcam.archive.ExportSettings
import com.sopcam.archive.Exporter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dayFmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

@Composable
fun ProjectsScreen(
    all: List<Archive.Project>,
    selected: Set<String>,
    exporting: String?,
    onToggle: (String) -> Unit,
    onOpen: (Archive.Project) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    onScanSearch: () -> Unit,
    statusFilter: Archive.Status?,
    onStatusFilter: (Archive.Status?) -> Unit,
    onSelectAll: (List<String>) -> Unit,
    onExport: (Exporter.Options) -> Unit,
    onDelete: (DeleteScope) -> Unit,
    exportSettings: ExportSettings,
    onExportSettings: (ExportSettings) -> Unit,
    onBack: () -> Unit,
) {
    val selecting = selected.isNotEmpty()
    // 展开的是哪个抽屉。窄条常驻，抽屉才是占地方的那层
    var tray by remember { mutableStateOf("") }

    // 搜索和状态筛选叠加。扫码搜索本质上就是把码填进搜索框，不用另做一套
    var settingsOpen by remember { mutableStateOf(false) }

    val shown = remember(all, query, statusFilter) {
        val q = query.trim().lowercase()
        all.filter { p ->
            (statusFilter == null || p.status == statusFilter) &&
                (q.isBlank() || p.haystack().contains(q))
        }
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        Column(Modifier.fillMaxSize()) {

            Spacer(Modifier.height(32.dp))

            // 多选时整个顶栏换成「计数 + 全选 + 取消」，跟系统相册一个路子。
            // 平时那些入口在这个模式下没有意义，留着只会误触
            if (selecting) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { onSelectAll(shown.map { it.serialNo }) }
                            .padding(horizontal = 4.dp, vertical = 6.dp)
                    ) {
                        val allOn = shown.isNotEmpty() && selected.size == shown.size
                        Box(
                            Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (allOn) Amber else Color.Transparent)
                                .border(
                                    1.5.dp,
                                    if (allOn) Amber else Steel,
                                    RoundedCornerShape(12.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (allOn) {
                                Text("✓", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "${selected.size}",
                            color = Color.White,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        "取消",
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xFF262D35))
                            .clickable { onSelectAll(emptyList()) }
                            .padding(horizontal = 18.dp, vertical = 9.dp)
                    )
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("检修项目", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            if (shown.size != all.size) "筛出 ${shown.size} / ${all.size} 个"
                            else "${all.size} 个项目",
                            color = Steel,
                            fontSize = 13.sp
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "⚙",
                            color = Steel,
                            fontSize = 17.sp,
                            modifier = Modifier
                                .clickable { settingsOpen = true }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
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
            }

            Spacer(Modifier.height(14.dp))

            SearchRow(query, onQuery, onScanSearch)

            Spacer(Modifier.height(10.dp))

            StatBar(all, statusFilter, onStatusFilter)

            Spacer(Modifier.height(12.dp))

            if (shown.isEmpty()) {
                Text(
                    if (all.isEmpty())
                        "还没有归档。拍照时如果开着「同时保存无水印原图」，每个控制器会自动建一个项目。"
                    else "没有匹配的项目，换个关键词或者取消筛选试试。",
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
                    items(shown, key = { it.serialNo }) { p ->
                        ProjectRow(
                            p = p,
                            checked = p.serialNo in selected,
                            onCheck = { onToggle(p.serialNo) },
                            onOpen = { onOpen(p) }
                        )
                    }
                    // 只给底栏留出它真正占的高度 —— 以前不管展没展开都留 220dp，
                    // 列表被顶掉一大截，滚都滚不到底
                    item {
                        Spacer(
                            Modifier.height(
                                when {
                                    !selecting -> 24.dp
                                    tray.isBlank() -> 84.dp
                                    else -> 300.dp
                                }
                            )
                        )
                    }
                }
            }
        }

        if (settingsOpen) {
            ExportSettingsSheet(
                settings = exportSettings,
                onChange = onExportSettings,
                onDismiss = { settingsOpen = false }
            )
        }

        if (selecting) {
            Box(Modifier.align(Alignment.BottomCenter)) {
                ActionDock(
                    count = selected.size,
                    serials = selected.toList(),
                    exporting = exporting,
                    exportSettings = exportSettings,
                    tray = tray,
                    onTray = { tray = if (tray == it) "" else it },
                    onOpenSettings = { settingsOpen = true },
                    onExport = onExport,
                    onDelete = onDelete
                )
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


        if (p.status != Archive.Status.NONE) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(statusColor(p.status))
            )
            Spacer(Modifier.width(8.dp))
        }

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
            if (p.note.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(
                    p.note,
                    color = Amber,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Column(horizontalAlignment = Alignment.End) {
            Text("${p.shotCount} 张", color = Done, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Text(dayFmt.format(Date(p.updatedAt)), color = Color(0xFF4A525C), fontSize = 11.sp)
        }
    }
}

/**
 * 多选时的底部操作坞。
 *
 * 常驻的只有一条窄图标栏，抽屉点开才展开 —— 以前是选中就弹一个大面板，
 * 占掉半屏，想继续往下滚着选都做不到。
 */
@Composable
private fun ActionDock(
    count: Int,
    serials: List<String>,
    exporting: String?,
    exportSettings: ExportSettings,
    tray: String,
    onTray: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onExport: (Exporter.Options) -> Unit,
    onDelete: (DeleteScope) -> Unit,
) {
    var deleting by remember { mutableStateOf(false) }

    if (deleting) {
        ConfirmTypedDialog(
            title = "删除 ${serials.size} 个项目",
            detail = "这些控制器的原图和水印照片会一起清空，之后再也重烧不出来。" +
                "只想清相册的话，用「删除水印图」。",
            actionLabel = "删除",
            onCancel = { deleting = false },
            onConfirm = {
                deleting = false
                onDelete(DeleteScope.BOTH)
            }
        )
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFA13181D))
    ) {
        if (exporting != null) {
            Text(
                exporting,
                color = Amber,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)
            )
            return@Column
        }

        when (tray) {
            "export" -> ExportTray(serials, exportSettings, onOpenSettings, onExport)
            "delete" -> DeleteTray(count, onDelete) { deleting = true }
            else -> Unit
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .padding(top = 6.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            DockItem("导出", "⬆", tray == "export") { onTray("export") }
            DockItem("删除", "🗑", tray == "delete") { onTray("delete") }
        }
    }
}

@Composable
private fun DockItem(label: String, glyph: String, active: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 30.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(glyph, color = if (active) Amber else Color.White, fontSize = 18.sp)
        Spacer(Modifier.height(3.dp))
        Text(label, color = if (active) Amber else Steel, fontSize = 11.5.sp)
    }
}

@Composable
private fun ExportTray(
    serials: List<String>,
    exportSettings: ExportSettings,
    onOpenSettings: () -> Unit,
    onExport: (Exporter.Options) -> Unit,
) {
    // 按下导出之前先把张数和体积摆出来 —— 微信传文件有上限，
    // 80MB 的包发不出去，事后才发现最耽误事
    val wm = remember(serials) { Exporter.plan(serials, Exporter.Options(true, false)) }
    val raw = remember(serials) { Exporter.plan(serials, Exporter.Options(false, true)) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("导出", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                exportSettings.summary() + "  ⚙",
                color = Amber,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0x22FDCE04))
                    .clickable(onClick = onOpenSettings)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }

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
            "zip 里水印图和原图分开放，导出不会动原来的数据",
            color = Color(0xFF4A525C),
            fontSize = 11.sp
        )
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun DeleteTray(count: Int, onDelete: (DeleteScope) -> Unit, onDangerous: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp)) {
        Text("删除 $count 个项目", color = Color.White, fontSize = 14.sp,
            fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))

        // 只清相册的随时能从原图重烧回来，不设门槛；连原图一起删的走输入确认
        Text(
            "删除水印图片",
            color = Color.White,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF262D35))
                .clickable { onDelete(DeleteScope.GALLERY_ONLY) }
                .padding(vertical = 13.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text("原图留着，随时能重烧回来", color = Steel, fontSize = 11.sp)

        Spacer(Modifier.height(10.dp))

        Text(
            "删除项目",
            color = Color(0xFFE86A5C),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, Color(0x55E86A5C), RoundedCornerShape(8.dp))
                .clickable(onClick = onDangerous)
                .padding(vertical = 13.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text("原图和成片一起清空，不可恢复", color = Color(0xFFE86A5C), fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
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

fun statusColor(s: Archive.Status): Color = when (s) {
    Archive.Status.DONE -> Done
    Archive.Status.ERROR -> Color(0xFFE86A5C)
    Archive.Status.DOING -> Amber
    Archive.Status.NONE -> Steel
}

@Composable
private fun SearchRow(query: String, onQuery: (String) -> Unit, onScan: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Panel)
            .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text("搜序列号、型号、平台、故障", color = Color(0xFF4A525C), fontSize = 14.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                cursorBrush = SolidColor(Amber)
            )
        }
        if (query.isNotEmpty()) {
            Text(
                "×",
                color = Steel,
                fontSize = 17.sp,
                modifier = Modifier.clickable { onQuery("") }.padding(horizontal = 8.dp)
            )
        }
        Box(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onScan)
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            ScanIcon()
        }
    }
}

/** 统计条。每个格子既是数字也是筛选按钮 —— 想看哪一类点一下就过滤 */
@Composable
private fun StatBar(
    all: List<Archive.Project>,
    active: Archive.Status?,
    onPick: (Archive.Status?) -> Unit,
) {
    val counts = remember(all) {
        mapOf(
            Archive.Status.DOING to all.count { it.status == Archive.Status.DOING },
            Archive.Status.DONE to all.count { it.status == Archive.Status.DONE },
            Archive.Status.ERROR to all.count { it.status == Archive.Status.ERROR },
        )
    }

    Row(
        Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatCell("全部", all.size, Steel, active == null, Modifier.weight(1f)) { onPick(null) }
        Archive.Status.entries.filter { it != Archive.Status.NONE }.forEach { st ->
            StatCell(
                st.label,
                counts[st] ?: 0,
                statusColor(st),
                active == st,
                Modifier.weight(1f)
            ) { onPick(if (active == st) null else st) }
        }
    }
}

@Composable
private fun StatCell(
    label: String,
    count: Int,
    tint: Color,
    active: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) Color(0xFF262D35) else Panel)
            .border(
                1.dp,
                if (active) tint else Color(0xFF232A31),
                RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(count.toString(), color = tint, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(label, color = Steel, fontSize = 10.sp, maxLines = 1)
    }
}

/** 跟开工页输入框里那个同款，四角取景框加一道扫描线 */
@Composable
private fun ScanIcon(size: Dp = 20.dp, tint: Color = Amber) {
    Canvas(Modifier.size(size)) {
        val w = 2.dp.toPx()
        val arm = this.size.minDimension * 0.3f
        val pad = w / 2f
        val r = this.size.width - pad
        val b = this.size.height - pad
        listOf(
            Offset(pad, pad + arm) to Offset(pad, pad),
            Offset(pad, pad) to Offset(pad + arm, pad),
            Offset(r - arm, pad) to Offset(r, pad),
            Offset(r, pad) to Offset(r, pad + arm),
            Offset(r, b - arm) to Offset(r, b),
            Offset(r, b) to Offset(r - arm, b),
            Offset(pad + arm, b) to Offset(pad, b),
            Offset(pad, b) to Offset(pad, b - arm),
        ).forEach { (a, c) -> drawLine(tint, a, c, strokeWidth = w, cap = StrokeCap.Round) }
        drawLine(
            tint,
            Offset(pad + arm * 0.2f, this.size.height / 2f),
            Offset(r - arm * 0.2f, this.size.height / 2f),
            strokeWidth = w,
            cap = StrokeCap.Round
        )
    }
}
