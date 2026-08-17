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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sopcam.sop.AppSettings

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    onBack: () -> Unit,
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
            Text("设置", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(
                "返回",
                color = Amber,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.dp, Amber, RoundedCornerShape(4.dp))
                    .clickable(onClick = onBack)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }

        Spacer(Modifier.height(28.dp))
        SectionTitle("水印")

        SwitchRow(
            title = "时间水印",
            desc = "照片上显示拍摄时间，精确到分钟",
            checked = settings.showTimeStamp,
        ) { onChange(settings.copy(showTimeStamp = it)) }

        Spacer(Modifier.height(10.dp))

        SwitchRow(
            title = "SOP 步骤水印",
            desc = "照片上显示当前拍摄点位的序号和名称",
            checked = settings.showSopStep,
        ) { onChange(settings.copy(showSopStep = it)) }

        Spacer(Modifier.height(10.dp))
        Text(
            "工单号、序列号、控制器型号和平台不会画在照片上——那些是给机器读的，" +
                "盖在板子上只会挡视线。它们只写进元数据。" +
                "水印总开关在相机界面的田字格里，随手就能关。",
            color = Steel,
            fontSize = 12.sp,
            lineHeight = 19.sp,
            modifier = Modifier
                .fillMaxWidth()
                .background(Panel, RoundedCornerShape(8.dp))
                .padding(14.dp)
        )

        Spacer(Modifier.height(28.dp))
        SectionTitle("存储")

        SwitchRow(
            title = "同时保存无水印原图",
            desc = "存到工单目录下的 RAW 子文件夹，同名。留着以后重烧水印用，代价是占用翻倍。",
            checked = settings.keepOriginal,
        ) { onChange(settings.copy(keepOriginal = it)) }

        Spacer(Modifier.height(28.dp))
        SectionTitle("快门反馈")

        SwitchRow(
            title = "震动",
            desc = "按下快门时震一下",
            checked = settings.shutterVibrate,
        ) { onChange(settings.copy(shutterVibrate = it)) }

        Spacer(Modifier.height(10.dp))

        SwitchRow(
            title = "提示音",
            desc = "按下快门时响一声。跟随系统媒体音量，静音模式下不响。",
            checked = settings.shutterSound,
        ) { onChange(settings.copy(shutterSound = it)) }

        Spacer(Modifier.height(28.dp))
        SectionTitle("扫码")

        SwitchRow(
            title = "取景时扫码",
            desc = "边取景边识别画面里的条码或二维码，扫到的内容写进照片元数据。不用扫码的活儿可以关掉省电。",
            checked = settings.scanInViewfinder,
        ) { onChange(settings.copy(scanInViewfinder = it)) }

        Spacer(Modifier.height(28.dp))
        SectionTitle("元数据")

        SwitchRow(
            title = "记录 GPS 位置",
            desc = "车间在室内基本收不到卫星信号，多半是空的。需要定位权限。",
            checked = settings.recordGps,
        ) { onChange(settings.copy(recordGps = it)) }

        Spacer(Modifier.height(20.dp))
        Text(
            "无论水印开不开，每张照片都会写入：唯一 ID、拍摄时间、手机型号和系统版本、" +
                "工单号、序列号、控制器型号、平台、SOP 步骤序号和名称。" +
                "所以以后想重新排版水印，照着元数据批量重烧就行。",
            color = Steel,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            modifier = Modifier
                .fillMaxWidth()
                .background(Panel, RoundedCornerShape(8.dp))
                .padding(14.dp)
        )

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun SwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Panel)
            .clickable { onToggle(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 14.dp)) {
            Text(title, color = Color.White, fontSize = 16.sp)
            Spacer(Modifier.height(5.dp))
            Text(desc, color = Steel, fontSize = 12.sp, lineHeight = 18.sp)
        }
        Track(checked)
    }
}

/** 自己画的开关，不用 material3 的 Switch，省得跟主题色打架 */
@Composable
private fun Track(checked: Boolean) {
    Box(
        Modifier
            .width(50.dp)
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(if (checked) Amber else Color(0xFF2A3037)),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            Modifier
                .padding(horizontal = 3.dp)
                .size(24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (checked) Ink else Steel)
        )
    }
}
