package com.sopcam.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
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
 * 开工页：填控制器信息、选流程
 * ================================================================== */

@Composable
fun SetupScreen(
    modelOption: PickOption?,
    platformOption: PickOption?,
    platformEnabled: Boolean,
    onModelTap: () -> Unit,
    onPlatformTap: () -> Unit,
    onSettings: () -> Unit,
    onScanSerial: () -> Unit,
    faultOption: PickOption?,
    onFaultTap: () -> Unit,
    serialNo: String,
    templates: List<SopTemplate>,
    activeTemplateId: String,
    onSerialChange: (String) -> Unit,
    templateOption: PickOption?,
    activeTemplate: SopTemplate?,
    onTemplateTap: () -> Unit,
    onStartFreeform: () -> Unit,
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
            "照片自动绑定控制器信息，原图另存归档区",
            color = Steel, fontSize = 13.sp
        )

        Spacer(Modifier.height(24.dp))
        Field("控制器序列号", serialNo, "0104215HZN92952565", onSerialChange, onScanSerial)

        Spacer(Modifier.height(14.dp))
        PickerField(
            label = "控制器型号",
            selected = modelOption,
            hint = "请选择控制器型号",
            modifier = Modifier.fillMaxWidth(),
            onTap = onModelTap
        )
        Spacer(Modifier.height(14.dp))
        PickerField(
            label = "故障类型",
            selected = faultOption,
            hint = "4S 店描述的故障",
            modifier = Modifier.fillMaxWidth(),
            onTap = onFaultTap
        )
        Spacer(Modifier.height(14.dp))
        PickerField(
            label = "所属平台",
            selected = platformOption,
            hint = if (platformEnabled) "请选择平台" else "先选型号",
            enabled = platformEnabled,
            modifier = Modifier.fillMaxWidth(),
            onTap = onPlatformTap
        )

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
            // 流程多起来之后逐条平铺会把页面撑得很长，收进下拉里
            PickerField(
                label = "",
                selected = templateOption,
                hint = "请选择检修流程",
                modifier = Modifier.fillMaxWidth(),
                onTap = onTemplateTap
            )
            activeTemplate?.let { t ->
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${t.steps.size} 个拍摄点位", color = Steel, fontSize = 13.sp)
                    Text(
                        "删除这个流程",
                        color = Steel,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clickable { onDeleteTemplate(t.id) }
                            .padding(vertical = 6.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        PrimaryButton(label = "开始检修拍摄", onClick = onStart)

        // 跳过流程降级成小字：核心价值是按 SOP 拍，不该让人第一眼就想着绕开
        if (activeTemplateId.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "跳过流程，直接拍  ›",
                    color = Steel,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clickable(onClick = onStartFreeform)
                        .padding(vertical = 8.dp, horizontal = 12.dp)
                )
            }
        }
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
        Text(label, color = Steel, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Panel)
                .border(1.dp, Color(0xFF2A3037), RoundedCornerShape(8.dp))
                .padding(start = 14.dp, end = 6.dp, top = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f)) {
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
            // 扫码收进输入框内 —— 扫和手输是同一件事的两种输入方式，
            // 摆在一起视线不用来回跳，也跟微信支付宝的习惯一致
            onScan?.let {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = it)
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    ScanGlyph()
                }
            }
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

/** 扫码图标：四角取景框加一道扫描线，跟相机里的对焦框同一套语言 */
@Composable
private fun ScanGlyph(size: Dp = 22.dp, tint: Color = Amber) {
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
