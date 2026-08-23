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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 拍完停一下。
 *
 * 三个动作放在一起是有道理的：**你发现拍错，通常就在按下快门那一瞬间**。
 * 这时候确认、重拍、写备注都在同一个屏幕上完成，不用退出相机翻项目。
 *
 * 备注用系统输入法，语音想说就点输入法的麦克风 —— 不自带 ASR，
 * 省掉几百兆模型和一套要长期维护的热词表。
 */
@Composable
fun ShotConfirmScreen(
    preview: Bitmap?,
    stepLabel: String,
    note: String,
    onNote: (String) -> Unit,
    onRetake: () -> Unit,
    onAccept: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(note) { mutableStateOf(note) }
    val keyboard = LocalSoftwareKeyboardController.current

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
    ) {
        Spacer(Modifier.height(28.dp))
        Text(
            stepLabel.ifBlank { "自由拍摄" },
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(Modifier.height(3.dp))
        Text(
            "看清楚了再留下",
            color = Steel,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        Spacer(Modifier.height(14.dp))

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (preview != null) {
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text("处理中…", color = Steel, fontSize = 13.sp)
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(Panel)
                .padding(horizontal = 16.dp)
                .padding(top = 14.dp, bottom = 20.dp)
        ) {
            if (editing) {
                // 限高 + 内部滚动。不限的话说一长段就把下面的按钮顶出屏幕，
                // 这个坑在模板编辑页已经踩过一次
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 76.dp, max = 132.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Ink)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    if (draft.isEmpty()) {
                        Text(
                            "说点什么，或者用输入法的语音",
                            color = Color(0xFF4A525C),
                            fontSize = 14.sp
                        )
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(
                            color = Color.White, fontSize = 14.sp, lineHeight = 21.sp
                        ),
                        cursorBrush = SolidColor(Amber)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Wide("存下备注", primary = true, modifier = Modifier.weight(1f)) {
                        onNote(draft.trim())
                        keyboard?.hide()
                        editing = false
                    }
                    Wide("取消", modifier = Modifier.weight(1f)) {
                        draft = note
                        keyboard?.hide()
                        editing = false
                    }
                }
            } else {
                if (note.isNotBlank()) {
                    Text(
                        note,
                        color = Amber,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(Ink)
                            .clickable { editing = true }
                            .padding(10.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Big("✕", "重拍", Color(0xFFE86A5C), Modifier.weight(1f), onClick = onRetake)
                    Big(
                        "✎",
                        if (note.isBlank()) "备注" else "改备注",
                        Steel,
                        Modifier.weight(1f)
                    ) { editing = true }
                    Big("✓", "留下", Done, Modifier.weight(1.2f), onClick = onAccept)
                }
            }
        }
    }
}

@Composable
private fun Big(
    glyph: String,
    label: String,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF262D35))
            .border(1.dp, tint.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 15.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(glyph, color = tint, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(label, color = tint, fontSize = 13.sp)
    }
}

@Composable
private fun Wide(
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    Text(
        label,
        color = if (primary) Ink else Steel,
        fontSize = 14.sp,
        fontWeight = if (primary) FontWeight.Bold else FontWeight.Normal,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (primary) Amber else Color(0xFF262D35))
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp)
    )
}
