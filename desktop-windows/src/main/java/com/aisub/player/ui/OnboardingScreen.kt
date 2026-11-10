package com.aisub.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 第一次打开时的说明：译幕能做什么、AI 字幕和翻译怎么用（默认免费、要下载模型）、为什么要媒体权限。
 * onStart：申请媒体权限并进入首页；onLater：不授权先进去（可以只用网盘、Plex 等）。
 */
@Composable
fun OnboardingScreen(onStart: () -> Unit, onLater: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
    ) {
        Text("欢迎使用译幕", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("给视频生成字幕、翻译成双语，还能朗读出来", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        Step(
            AppIcons.AutoAwesome, "一键生成字幕",
            "播放没有字幕的视频时，点画面上方的「生成简体中文字幕」（有外语字幕时是「翻译成简体中文字幕」）。第一次会下载约 160 MB 的识别模型，之后在手机上识别、免费、不用联网；也可以在设置里换成云端识别。",
        )
        Step(
            AppIcons.Translate, "自动翻译成双语",
            "默认用本地模型翻译：在手机或局域网电脑上运行 Ollama、LM Studio 等，字幕不出本地、不花钱，到「设置 → 字幕翻译」选好模型就能用；" +
                "也可以装 Offline Translator App 完全离线翻译，或者换成免费的 Index-Translate、Claude、DeepSeek 等云端服务。",
        )
        Step(
            AppIcons.Folder, "哪里的视频都能看",
            "手机里的视频和音乐、NAS（WebDAV / OpenList）、115 / 阿里云盘等网盘，以及 Plex、Emby / Jellyfin 媒体服务器，都在「浏览」里添加。",
        )
        Step(
            AppIcons.Movie, "接下来需要的权限",
            "读取手机里的视频和音频，用来列出你的媒体。不给也能用，只是看不到手机里的文件。",
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onStart, modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Orange),
        ) { Text("开始使用", fontSize = 16.sp) }
        TextButton(onClick = onLater, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("先不授权，直接进去") }
    }
}

@Composable
private fun Step(icon: ImageVector, title: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Orange, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(text, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

