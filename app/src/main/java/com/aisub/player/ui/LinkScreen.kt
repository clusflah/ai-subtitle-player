package com.aisub.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aisub.player.MainViewModel
import com.aisub.player.download.LinkStage
import kotlinx.coroutines.launch

/**
 * 下载版：从其他 App 分享链接过来后的进度页。
 * 解析 → 下载 → 保存 → 准备字幕，完成后自动进入播放页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkScreen(vm: MainViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val task = state.linkTask
    LaunchedEffect(task?.done) {
        if (task?.done == true) {
            vm.clearLinkTask()
            onDone()
        }
    }
    val leave = {
        if (task != null && task.error == null && !task.done) vm.cancelLink() else vm.clearLinkTask()
        onBack()
    }
    BackHandler(onBack = leave)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = { IconButton(onClick = leave) { Icon(AppIcons.ArrowBack, "返回") } },
                title = { Text("下载视频") },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (task == null) {
                Text("没有进行中的下载", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Text(task.title ?: "正在获取视频信息…", style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(task.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)

            // 四个步骤，当前步骤高亮
            val steps = listOf(
                listOf(LinkStage.INFO) to "解析链接",
                listOf(LinkStage.DOWNLOAD, LinkStage.MERGE) to "下载视频",
                listOf(LinkStage.SAVE) to "保存到 Movies/Voxtitle",
                listOf(LinkStage.SUBTITLE) to "准备字幕，然后播放",
            )
            val current = steps.indexOfFirst { task.progress.stage in it.first }
            SurfaceCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    steps.forEachIndexed { i, (_, label) ->
                        val done = i < current || task.done
                        val active = i == current && task.error == null
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when {
                                    done -> "✓"
                                    active -> "●"
                                    else -> "○"
                                },
                                color = if (done || active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(end = 12.dp),
                            )
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (done || active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }

            if (task.error != null) {
                Text("下载失败：${task.error}", color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { vm.downloadLink(task.url) }) { Text("重试") }
                    OutlinedButton(onClick = leave) { Text("关闭") }
                }
                Text(
                    "网站改版后旧版 yt-dlp 可能下载失败，可以到「设置 → 视频下载」更新 yt-dlp 后重试",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val p = task.progress
                Text(p.stage.label + (p.percent?.let { " ${(it * 100).toInt()}%" } ?: "…"), style = MaterialTheme.typography.bodyLarge)
                if (p.percent != null) LinearProgressIndicator(progress = { p.percent }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                if (p.detail.isNotEmpty() && p.detail != task.title) {
                    Text(p.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = leave) { Text("取消") }
                Text(
                    "下载完成后会自动载入平台字幕（没有字幕时用 AI 识别），按设置翻译，然后开始播放",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 设置页里的「视频下载」一节（只在下载版显示）。 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LinkDownloadSettings(
    quality: Int,
    autoAsr: Boolean,
    onQuality: (Int) -> Unit,
    onAutoAsr: (Boolean) -> Unit,
    vm: MainViewModel,
) {
    val scope = rememberCoroutineScope()
    var version by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { version = vm.linkDownloader?.version() }
    SurfaceCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("最高画质", style = MaterialTheme.typography.bodyLarge)
            Text(
                "在其他 App（YouTube、B 站等）里点「分享」选择译幕，即可自动下载、准备字幕并播放",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "不限", 1080 to "1080p", 720 to "720p", 480 to "480p").forEach { (q, label) ->
                    androidx.compose.material3.FilterChip(selected = quality == q, onClick = { onQuality(q) }, label = { Text(label) })
                }
            }
        }
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ListRow(
            title = "没有平台字幕时自动 AI 识别",
            subtitle = "下载完先用平台自带的字幕；没有时用设置里的语音识别引擎生成",
            trailing = { androidx.compose.material3.Switch(checked = autoAsr, onCheckedChange = onAutoAsr) },
            onClick = { onAutoAsr(!autoAsr) },
        )
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("yt-dlp", style = MaterialTheme.typography.bodyLarge)
                Text(
                    result ?: ("当前版本：" + (version ?: "首次使用时准备")),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                enabled = !updating,
                onClick = {
                    updating = true
                    scope.launch {
                        result = runCatching { vm.linkDownloader?.update() }.getOrElse { "更新失败：${it.message}" }
                        version = vm.linkDownloader?.version()
                        updating = false
                    }
                },
            ) {
                if (updating) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("检查更新")
            }
        }
    }
}
