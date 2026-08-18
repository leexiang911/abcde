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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val PHRASE = "Confirm"

/**
 * 不可恢复的操作才用这个。
 *
 * 点两下确认挡不住误触 —— 手指连点、口袋里蹭到都可能过。
 * 要求手打一个词，是让人必须停下来看清楚自己要删的是什么。
 *
 * 判断标准：**这个操作会不会毁掉原图**。原图没了就再也重烧不出水印照片，
 * 而只删相册里的成片随时能恢复，那种不必拦。
 */
@Composable
fun ConfirmTypedDialog(
    title: String,
    detail: String,
    actionLabel: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    val ok = typed.trim().equals(PHRASE, ignoreCase = false)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xF0000000))
            .clickable(onClick = onCancel),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .padding(22.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Panel)
                .clickable(enabled = false) {}
                .padding(20.dp)
        ) {
            Text(title, color = Color(0xFFE86A5C), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(detail, color = Steel, fontSize = 13.sp, lineHeight = 20.sp)

            Spacer(Modifier.height(18.dp))
            Text("输入 $PHRASE 以继续", color = Color.White, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Ink)
                    .border(
                        1.dp,
                        if (ok) Color(0xFFE86A5C) else Color(0xFF2A3037),
                        RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 13.dp)
            ) {
                if (typed.isEmpty()) {
                    Text(PHRASE, color = Color(0xFF3A424B), fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace)
                }
                BasicTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    cursorBrush = SolidColor(Color(0xFFE86A5C)),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    actionLabel,
                    color = if (ok) Ink else Color(0xFF4A525C),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (ok) Color(0xFFE86A5C) else Color(0xFF262D35))
                        .clickable(enabled = ok, onClick = onConfirm)
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
