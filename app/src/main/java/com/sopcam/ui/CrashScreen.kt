package com.sopcam.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 上次崩溃的堆栈。
 *
 * 没有调试器的情况下，这一屏就是唯一的现场。
 * 堆栈横向不换行 —— 换行会把类名和行号折得七零八落，反而看不清。
 */
@Composable
fun CrashScreen(trace: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .padding(16.dp)
    ) {
        Spacer(Modifier.height(28.dp))
        Text("上次崩溃了", color = Color(0xFFE86A5C), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("把这段发给我，就能定位问题", color = Steel, fontSize = 13.sp)

        Spacer(Modifier.height(14.dp))

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Panel)
        ) {
            Text(
                trace,
                color = Color(0xFFD6DBE0),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 15.sp,
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            )
        }

        Spacer(Modifier.height(14.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Amber)
                    .clickable { copyToClipboard(ctx, trace) }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("复制", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, Color(0xFF2A3037), RoundedCornerShape(6.dp))
                    .clickable(onClick = onDismiss)
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("知道了", color = Steel, fontSize = 15.sp)
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "同一份也存在 Download/SopCam 目录下",
            color = Color(0xFF4A525C),
            fontSize = 11.sp
        )
    }
}

private fun copyToClipboard(ctx: Context, text: String) {
    runCatching {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("SopCam crash", text))
    }
}
