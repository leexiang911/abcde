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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 可搜索的下拉选择框。
 *
 * 收起时是个带标签的框，显示当前选中项；点开铺满屏幕，
 * 顶部一个搜索框，下面是过滤后的列表。
 * 型号和平台现在只有几条，但目录从后台拉下来之后会变长，
 * 所以搜索一开始就得有，不能等长了再补。
 */

data class PickOption(
    val id: String,
    val label: String,
    val sub: String = "",
) {
    fun matches(q: String): Boolean {
        if (q.isBlank()) return true
        val t = "$label $sub".lowercase()
        return q.lowercase().split(' ').filter { it.isNotBlank() }.all { t.contains(it) }
    }
}

@Composable
fun PickerField(
    label: String,
    selected: PickOption?,
    hint: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
) {
    Column(modifier) {
        Text(label, color = Steel, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Panel)
                .border(
                    1.dp,
                    if (selected != null) Amber.copy(alpha = 0.6f) else Color(0xFF2A3037),
                    RoundedCornerShape(8.dp)
                )
                .clickable(enabled = enabled, onClick = onTap)
                .padding(horizontal = 14.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                selected?.label ?: hint,
                color = when {
                    selected != null -> Color.White
                    enabled -> Color(0xFF4A525C)
                    else -> Color(0xFF343B44)
                },
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text("▾", color = if (enabled) Steel else Color(0xFF343B44), fontSize = 14.sp)
        }
    }
}

@Composable
fun PickerSheet(
    title: String,
    options: List<PickOption>,
    selectedId: String,
    onPick: (PickOption?) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, options) { options.filter { it.matches(query) } }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xE60A0C0F))
            .clickable(onClick = onDismiss)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .padding(top = 40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Panel)
                .clickable(enabled = false) {}
                .padding(16.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "关闭",
                    color = Steel,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clickable(onClick = onDismiss)
                        .padding(8.dp)
                )
            }

            Spacer(Modifier.height(12.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Ink)
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                if (query.isEmpty()) {
                    Text("搜索", color = Color(0xFF4A525C), fontSize = 15.sp)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                    cursorBrush = SolidColor(Amber)
                )
            }

            Spacer(Modifier.height(8.dp))

            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(filtered) { opt ->
                    val active = opt.id == selectedId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(opt) }
                            .padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .background(
                                    if (active) Amber else Color.Transparent,
                                    RoundedCornerShape(3.dp)
                                )
                        )
                        Spacer(Modifier.height(0.dp))
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(
                                opt.label,
                                color = if (active) Amber else Color.White,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (opt.sub.isNotBlank()) {
                                Text(opt.sub, color = Steel, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            if (filtered.isEmpty()) {
                Text("没有匹配的项", color = Steel, fontSize = 14.sp, modifier = Modifier.padding(vertical = 12.dp))
            }

            if (selectedId.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "清空选择",
                    color = Steel,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clickable { onPick(null) }
                        .padding(vertical = 10.dp)
                )
            }
        }
    }
}
