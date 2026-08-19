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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sopcam.archive.Thumbs
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 重拍确认。
 *
 * 左旧右新摆在一起对比 —— 重拍多半是因为上一张糊了或者角度不对，
 * 不并排看根本判断不出新的是不是真的更好。
 *
 * 确认之后旧的不会直接消失，会移到 replaced/ 子目录留一份保险：
 * 它不参与列表、不参与导出、不参与恢复，纯粹是拍砸了能回头。
 */
@Composable
fun RetakeConfirmScreen(
    oldFile: File,
    newFile: File?,
    busy: String?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .padding(16.dp)
    ) {
        Spacer(Modifier.height(28.dp))
        Text("换成新拍的这张？", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "旧的会移到 replaced 目录留档，不会直接删掉",
            color = Steel,
            fontSize = 12.sp
        )

        Spacer(Modifier.height(18.dp))

        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SidePane("原来的", oldFile, Steel, Modifier.weight(1f))
            SidePane("新拍的", newFile, Done, Modifier.weight(1f))
        }

        Spacer(Modifier.height(16.dp))

        busy?.let {
            Text(it, color = Amber, fontSize = 13.sp, modifier = Modifier.padding(bottom = 10.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "✓  用新的",
                color = Ink,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (newFile != null && busy == null) Amber else Color(0xFF262D35))
                    .clickable(enabled = newFile != null && busy == null, onClick = onConfirm)
                    .padding(vertical = 15.dp)
            )
            Text(
                "✕  不要",
                color = Steel,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFF2A3037), RoundedCornerShape(8.dp))
                    .clickable(enabled = busy == null, onClick = onCancel)
                    .padding(vertical = 15.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SidePane(label: String, file: File?, tint: Color, modifier: Modifier) {
    var bmp by remember(file?.absolutePath) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(file?.absolutePath) {
        bmp = file?.let { withContext(Dispatchers.IO) { Thumbs.of(it, 720) } }
    }

    Column(modifier) {
        Text(label, color = tint, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(8.dp))
                .background(Panel)
                .border(1.dp, tint.copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            val b = bmp
            if (b != null) {
                Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = label,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(if (file == null) "等待拍摄…" else "加载中…", color = Steel, fontSize = 12.sp)
            }
        }
    }
}
