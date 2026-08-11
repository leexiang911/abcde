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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sopcam.sop.SopParser
import com.sopcam.sop.SopStep
import com.sopcam.sop.SopTemplate

val Ink = Color(0xFF0E1114)
val Panel = Color(0xFF161A1F)
val Amber = Color(0xFFFFD24A)
val Steel = Color(0xFF8C949E)
val Done = Color(0xFF4CC38A)

/* ==================================================================
 * 开工页：填工单、选模板
 * ================================================================== */

@Composable
fun SetupScreen(
    modelOption: PickOption?,
    platformOption: PickOption?,
    platformEnabled: Boolean,
    onModelTap: () -> Unit,
    onPlatformTap: () -> Unit,
    onSettings: () -> Unit,
    onScanWorkOrder: () -> Unit,
    onScanSerial: () -> Unit,
    workOrder: String,
    serialNo: String,
    templates: List<SopTemplate>,
    activeTemplateId: String,
    onWorkOrderChange: (String) -> Unit,
    onSerialChange: (String) -> Unit,
    onTemplatePick: (String) -> Unit,
    onNewTemplate: () -> Unit,
    onDeleteTemplate: (String) -> Unit,
    onStart: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Spacer(Modifier.height(28.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("开工", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Action("设置", onSettings)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "这些信息会写进照片的水印和元数据，也决定照片存在哪个文件夹",
            color = Steel, fontSize = 13.sp
        )

        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth()) {
            PickerField(
                label = "控制器型号",
                selected = modelOption,
                hint = "选择",
                modifier = Modifier.weight(1f).padding(end = 6.dp),
                onTap = onModelTap
            )
            PickerField(
                label = "分类平台",
                selected = platformOption,
                hint = if (platformEnabled) "选择" else "先选型号",
                enabled = platformEnabled,
                modifier = Modifier.weight(1f).padding(start = 6.dp),
                onTap = onPlatformTap
            )
        }

        Spacer(Modifier.height(18.dp))
        Field("工单号", workOrder, "GZJ20260728025832", onWorkOrderChange, onScanWorkOrder)
        Spacer(Modifier.height(14.dp))
        Field("控制器序列号", serialNo, "0104215HZN92952565", onSerialChange, onScanSerial)

        Spacer(Modifier.height(28.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("检修流程", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Action("新建流程", onNewTemplate)
        }

        Spacer(Modifier.height(12.dp))

        if (templates.isEmpty()) {
            Text(
                "还没有流程。点「新建流程」，把检修单上的测试项目整列复制进去，一行一条就能建好。",
                color = Steel, fontSize = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Panel, RoundedCornerShape(8.dp))
                    .padding(16.dp)
            )
        } else {
            templates.forEach { t ->
                val active = t.id == activeTemplateId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Panel)
                        .border(
                            if (active) 2.dp else 1.dp,
                            if (active) Amber else Color(0xFF2A3037),
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { onTemplatePick(t.id) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            t.name,
                            color = if (active) Amber else Color.White,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("${t.steps.size} 个拍摄点位", color = Steel, fontSize = 13.sp)
                    }
                    Text(
                        "删除",
                        color = Steel,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clickable { onDeleteTemplate(t.id) }
                            .padding(horizontal = 10.dp, vertical = 12.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        PrimaryButton(
            label = if (activeTemplateId.isBlank()) "不用流程，直接拍" else "开始拍摄",
            onClick = onStart
        )
        Spacer(Modifier.height(40.dp))
    }
}

/* ==================================================================
 * 模板编辑页：粘贴文本批量建步骤
 * ================================================================== */

@Composable
fun TemplateEditScreen(
    onSave: (SopTemplate) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var raw by remember { mutableStateOf("") }
    val parsed: List<SopStep> = remember(raw) { SopParser.parse(raw) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .padding(20.dp)
    ) {
        Spacer(Modifier.height(28.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("新建流程", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Action("取消", onCancel)
        }

        Spacer(Modifier.height(20.dp))
        Field("流程名称", name, "逆变器控制板检修", { name = it })

        Spacer(Modifier.height(20.dp))
        Text("测试项目", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(
            "一行一条。从检修单表格整列复制过来也行，行首的序号和「正常」那列会自动去掉。",
            color = Steel, fontSize = 13.sp
        )
        Spacer(Modifier.height(10.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Panel)
                .border(1.dp, Color(0xFF2A3037), RoundedCornerShape(8.dp))
                .padding(14.dp)
        ) {
            if (raw.isEmpty()) {
                Text(
                    "控制器编号\n控制器型号\n低压发波\n保险阻值\n母线容值\n上桥管压降",
                    color = Color(0xFF4A525C), fontSize = 15.sp, lineHeight = 24.sp
                )
            }
            BasicTextField(
                value = raw,
                onValueChange = { raw = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp, lineHeight = 24.sp),
                cursorBrush = SolidColor(Amber)
            )
        }

        Spacer(Modifier.height(18.dp))
        Text(
            if (parsed.isEmpty()) "还没识别到项目" else "识别到 ${parsed.size} 个点位",
            color = if (parsed.isEmpty()) Steel else Done,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(10.dp))

        LazyColumn(Modifier.weight(1f)) {
            items(parsed) { s ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        s.order.toString().padStart(2, '0'),
                        color = Amber,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(0.dp))
                    Text(
                        "  ${s.name}",
                        color = Color.White,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        PrimaryButton(
            label = "保存流程",
            enabled = name.isNotBlank() && parsed.isNotEmpty(),
            onClick = {
                onSave(
                    SopTemplate(
                        id = System.currentTimeMillis().toString(),
                        name = name.trim(),
                        steps = parsed
                    )
                )
            }
        )
        Spacer(Modifier.height(20.dp))
    }
}

/* ==================================================================
 * 小部件
 * ================================================================== */

@Composable
private fun Field(
    label: String,
    value: String,
    hint: String,
    onChange: (String) -> Unit,
    onScan: (() -> Unit)? = null,
) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = Steel, fontSize = 13.sp)
            onScan?.let {
                Text(
                    "扫码",
                    color = Amber,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .border(1.dp, Amber, RoundedCornerShape(4.dp))
                        .clickable(onClick = it)
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Panel)
                .border(1.dp, Color(0xFF2A3037), RoundedCornerShape(8.dp))
                .padding(horizontal = 14.dp, vertical = 16.dp)
        ) {
            if (value.isEmpty()) {
                Text(hint, color = Color(0xFF4A525C), fontSize = 16.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                cursorBrush = SolidColor(Amber)
            )
        }
    }
}

@Composable
private fun Action(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Amber,
        fontSize = 14.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, Amber, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    )
}

@Composable
private fun PrimaryButton(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) Amber else Color(0xFF2A3037))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (enabled) Ink else Steel,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
