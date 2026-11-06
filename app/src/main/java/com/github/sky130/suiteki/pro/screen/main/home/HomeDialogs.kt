package com.github.sky130.suiteki.pro.screen.main.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.sky130.suiteki.pro.MainApplication
import com.github.sky130.suiteki.pro.MainApplication.Companion.context
import com.github.sky130.suiteki.pro.MainApplication.Companion.copy
import com.github.sky130.suiteki.pro.R
import com.github.sky130.suiteki.pro.logic.ble.SuitekiManager
import com.github.sky130.suiteki.pro.ui.widget.DialogState
import com.github.sky130.suiteki.pro.ui.widget.SuitekiDialog

/**
 * 关于。原来「关于」和「进群交流」是两张卡片，内容都很少，合成一个对话框，
 * 挂在主页右上角。
 */
@Composable
fun AboutDialog(state: DialogState, logState: DialogState) {
    if (!state.visible.value) return
    SuitekiDialog(
        state = state,
        title = "关于",
        icon = Icons.Outlined.Info,
        onDismissRequest = { state.dismiss() },
        button = {
            OutlinedButton(onClick = { state.dismiss() }) { Text("关闭") }
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row {
                val versionName = androidx.compose.runtime.remember {
                    runCatching {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    }.getOrNull().orEmpty()
                }
                Icon(
                    painter = painterResource(id = R.drawable.ic_suiteki),
                    contentDescription = null,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .padding(18.dp)
                        .size(22.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = "Suiteki Pro",
                        style = TextStyle(fontSize = 22.sp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(text = "by Sky233", style = MaterialTheme.typography.bodyMedium)
                    Text(text = versionName, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "装表盘和小程序不用登录 App、不走云端。小米协议部分参考 Gadgetbridge。",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "GitHub",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    MainApplication.openUrl("https://github.com/xiaoyangtao55/Re-Suiteki-Pro")
                }
            )
            Text(
                text = "更新",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    MainApplication.openUrl("https://github.com/xiaoyangtao55/Re-Suiteki-Pro/releases")
                }
            )
            // 日志日常没用，只在连不上、装不上时才需要，所以收进「关于」，
            // 不占主页的功能入口；反馈问题时从这里复制。
            Text(
                text = "运行日志（反馈问题时用）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    // 两个 AlertDialog 同时开会叠两层遮罩，先关「关于」
                    state.dismiss()
                    logState.show()
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Row {
                Icon(
                    Icons.Outlined.ChatBubbleOutline,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("进群交流", style = MaterialTheme.typography.titleSmall)
            }
            Text(
                text = "Suiteki#2 群 758323879（点击复制）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { "758323879".copy() }
            )
            Text(
                text = "Suiteki#1 群 648849444（尚未开放）",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun HelpDialog(state: DialogState) {
    if (!state.visible.value) return
    SuitekiDialog(
        state = state,
        title = "帮助",
        icon = Icons.AutoMirrored.Outlined.HelpOutline,
        onDismissRequest = { state.dismiss() },
        button = {
            OutlinedButton(onClick = { state.dismiss() }) { Text("返回") }
            OutlinedButton(
                onClick = { MainApplication.openUrl("https://www.bandbbs.cn/threads/11107/") },
                modifier = Modifier.padding(start = 8.dp)
            ) { Text("更多帮助") }
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("1. 设备页「+」添加设备：先选型号，再填 MAC。", style = MaterialTheme.typography.bodyMedium)
            Text("2. 回主页，在「未连接设备」列表里点设备连接。", style = MaterialTheme.typography.bodyMedium)
            Text("3. 连上后再点「安装」，选择表盘或小程序包。", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "手环 7 不需要授权密钥；小米 8 系需要，可以在添加设备时用「通过小米运动健康添加」扫日志自动取。",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "刷表盘有变砖风险，第一次建议先装个小程序试水。",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/** 实时协议日志：连接、认证、收发帧都在这里，排查问题主要看它。 */
@Composable
fun LogDialog(state: DialogState) {
    if (!state.visible.value) return
    val lines = SuitekiManager.logList
    SuitekiDialog(
        state = state,
        title = "日志",
        icon = Icons.Outlined.Code,
        onDismissRequest = { state.dismiss() },
        button = {
            OutlinedButton(onClick = { state.dismiss() }) { Text("关闭") }
            OutlinedButton(
                onClick = { lines.joinToString("\n\n").copy() },
                modifier = Modifier.padding(start = 8.dp)
            ) { Text("复制") }
            OutlinedButton(
                onClick = { SuitekiManager.clearLog() },
                modifier = Modifier.padding(start = 8.dp)
            ) { Text("清空") }
        }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (lines.isEmpty()) {
                Text(
                    "暂无日志。连接设备后，这里会实时输出收发的协议帧。",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(lines) { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "共 ${lines.size} 行（上限 ${SuitekiManager.MAX_LOG_LINES}），含 MAC 与协议帧，贴到公开场合前先看一眼。",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

val hitokoto = listOf(
    "若汝无介意，愿作耳边墙",
    "思君无情拒，唯吾孤自哀",
    "希愿回心意，眷恋越星海",
    "欲采栀子花，赠予手心中",
    "流水长月，依恋不舍",
    "拾栀雨落，忆梦汐潮",
    "思向呼唤，尔已忘期",
    "汝之憨态，流连忘返",
    "氢氯钠钾银，氧镁钙钡锌",
    "Add! Commit! Push!",
    "喵，喵喵喵! 喵喵!?",
    "别让等待成为遗憾",
    "星垂平野阔，月是故乡明",
    "打起精神，准备出发!",
    "岂曰无衣？与子同袍。",
    "你所热爱的，就是你的生活",
    "我们的目标是星辰大海",
    "Nvidia, fuck you.",
    "Never gonna give you up",
    "出发咯！丢～～～",
    "我很可爱，v我50",
    "听着，所谓杀手",
    "我的热情，无穷无尽",
    "你是不是嫌我傻了吧唧的",
    "早上好 中午好 晚上好",
    "₍˄·͈༝·͈˄*₎◞ ̑̑Nya~",
    "Change the boring world!",
    "Ciallo～(∠・ω< )⌒☆",
    "心灵美，一切都美",
    "你干嘛～哎呦～",
    "安民之道，在察其疾苦",
    "不以物喜，不以己悲",
    "水不在深，有龙则灵",
    "不日新者必日退",
    "一定要好好活着啊喵",
    "祝你快乐，喵喵",
    "这白开水怎么没味儿啊",
    "变亦变，不变亦变",
    "技术宅拯救世界",
)
