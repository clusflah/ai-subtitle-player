package com.aisub.player.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import com.aisub.player.AppSettings
import com.aisub.player.MainViewModel
import com.aisub.player.Task
import com.aisub.player.UiState
import com.aisub.player.subtitle.Cue
import com.aisub.player.subtitle.SrtMode
import com.aisub.player.subtitle.cueAt
import kotlinx.coroutines.delay

/**
 * 字幕播放页，三层结构：视频区（画面 + 字幕）→ 字幕工具栏（主操作 + 显示模式 + 任务状态）→ 字幕列表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(vm: MainViewModel, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var exportMode by remember { mutableStateOf(SrtMode.BILINGUAL) }
    var errorDetail by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-subrip")
    ) { uri -> if (uri != null) vm.exportSrt(uri, exportMode) }
    val export: (SrtMode) -> Unit = { mode ->
        exportMode = mode
        val base = state.current?.title?.substringBeforeLast('.') ?: "subtitles"
        val suffix = when (mode) {
            SrtMode.ORIGINAL -> "original"
            SrtMode.TRANSLATION -> "translated"
            SrtMode.BILINGUAL -> "bilingual"
        }
        exportLauncher.launch("$base.$suffix.srt")
    }

    // 播放位置：每 100ms 采样一次，用来同步字幕
    var positionMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            positionMs = vm.player.currentPosition
            delay(100)
        }
    }
    val current = state.cues.cueAt(positionMs)

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                navigationIcon = { IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, "返回") } },
                title = { Text(state.current?.title ?: "播放", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
                actions = { IconButton(onClick = onOpenSettings) { Icon(AppIcons.Settings, "设置") } },
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // ---- 视频区 ----
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = vm.player
                            // 字幕由下面的 SubtitleOverlay 自己画，关掉内置字幕层
                            subtitleView?.visibility = android.view.View.GONE
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (state.videoUri == null) {
                    Text("没有正在播放的媒体", color = Color.White, modifier = Modifier.align(Alignment.Center))
                }
                SubtitleOverlay(
                    cue = current,
                    settings = state.settings,
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .padding(bottom = state.settings.subtitleOffset.dp, start = 12.dp, end = 12.dp),
                )
            }

            // ---- 字幕工具栏 ----
            Toolbar(
                state = state,
                onTranscribe = vm::generateSubtitles,
                onTranslate = vm::startTranslation,
                onStop = vm::cancelAll,
                onExport = export,
                onClearTranslations = vm::clearTranslations,
                onToggleFollow = { vm.updateSettings(state.settings.copy(followPlayback = !state.settings.followPlayback)) },
                onOpenSettings = onOpenSettings,
                onModeChange = { o, t -> vm.updateSettings(state.settings.copy(showOriginal = o, showTranslation = t)) },
                onRetry = {
                    when (state.failedTask) {
                        Task.TRANSCRIBE -> vm.generateSubtitles()
                        Task.TRANSLATE -> vm.startTranslation()
                        null -> {}
                    }
                },
                onShowError = { errorDetail = true },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // ---- 字幕列表 ----
            if (state.cues.isEmpty()) {
                Box(Modifier.weight(1f)) {
                    EmptyState(
                        if (state.transcribing) "正在识别，字幕会陆续出现在这里" else "还没有字幕\n点「识别字幕」用 AI 生成",
                        icon = AppIcons.QueueMusic,
                    )
                }
            } else {
                CueList(
                    cues = state.cues,
                    current = current,
                    settings = state.settings,
                    onSeek = { vm.player.seekTo(it.startMs); vm.player.play() },
                    onRetranslate = { vm.retranslate(it.id) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (errorDetail && state.error != null) {
        AlertDialog(
            onDismissRequest = { errorDetail = false },
            confirmButton = { TextButton(onClick = { errorDetail = false }) { Text("知道了") } },
            title = { Text("出错了") },
            text = { Text(state.error ?: "") },
        )
    }
}

@Composable
private fun SubtitleOverlay(cue: Cue?, settings: AppSettings, modifier: Modifier = Modifier) {
    if (cue == null) return
    val lines = buildList {
        if (settings.showTranslation && cue.translation != null) add(cue.translation to true)
        if (settings.showOriginal || cue.translation == null) add(cue.text to false)
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        for ((text, primary) in lines) {
            Text(
                text = text,
                color = if (primary) Color.White else Color(0xFFE0E0E0),
                fontSize = ((if (primary) 18 else 14) * settings.subtitleScale).sp,
                fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier.background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun Toolbar(
    state: UiState,
    onTranscribe: () -> Unit,
    onTranslate: () -> Unit,
    onStop: () -> Unit,
    onExport: (SrtMode) -> Unit,
    onClearTranslations: () -> Unit,
    onToggleFollow: () -> Unit,
    onOpenSettings: () -> Unit,
    onModeChange: (Boolean, Boolean) -> Unit,
    onRetry: () -> Unit,
    onShowError: () -> Unit,
) {
    val busy = state.transcribing || state.translating
    var menu by remember { mutableStateOf(false) }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 第一层：主要操作完整显示，其余收进"更多"
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (busy) {
                OutlinedButton(onClick = onStop) { Text("停止") }
            } else {
                Button(onClick = onTranscribe, enabled = state.videoUri != null) {
                    Text(if (state.cues.isEmpty()) "识别字幕" else "重新识别")
                }
                FilledTonalButton(onClick = onTranslate, enabled = state.cues.any { it.translation == null }) { Text("翻译") }
            }
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = { menu = true }) { Icon(AppIcons.MoreVert, "更多") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    val hasCues = state.cues.isNotEmpty()
                    DropdownMenuItem(text = { Text("导出双语 SRT") }, enabled = hasCues, onClick = { menu = false; onExport(SrtMode.BILINGUAL) })
                    DropdownMenuItem(text = { Text("导出译文 SRT") }, enabled = hasCues, onClick = { menu = false; onExport(SrtMode.TRANSLATION) })
                    DropdownMenuItem(text = { Text("导出原文 SRT") }, enabled = hasCues, onClick = { menu = false; onExport(SrtMode.ORIGINAL) })
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text((if (state.settings.followPlayback) "✓ " else "") + "列表跟随播放") },
                        onClick = { menu = false; onToggleFollow() },
                    )
                    DropdownMenuItem(text = { Text("清除译文") }, enabled = hasCues && !busy, onClick = { menu = false; onClearTranslations() })
                    DropdownMenuItem(text = { Text("字幕样式与 AI 设置") }, onClick = { menu = false; onOpenSettings() })
                }
            }
        }
        // 第二层：显示模式
        DisplayModeSelector(state.settings.showOriginal, state.settings.showTranslation, onModeChange)
        // 任务状态：弱化的一行文字
        TaskStatus(state, onRetry, onShowError)
    }
}

@Composable
private fun TaskStatus(state: UiState, onRetry: () -> Unit, onShowError: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (state.failedTask != null && !state.transcribing && !state.translating) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.failedTask == Task.TRANSCRIBE) "识别失败" else "翻译失败",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("点击重试") }
            TextButton(onClick = onShowError, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("详情") }
        }
        return
    }
    val translated = state.cues.count { it.translation != null }
    val text = when {
        state.transcribing && state.progress != null && state.progress >= 0f && state.status.startsWith("正在识别") ->
            "识别中 ${(state.progress * 100).toInt()}% · 已出 ${state.cues.size} 句"
        state.transcribing -> state.status
        state.translating -> "翻译中 $translated / ${state.cues.size}"
        state.cues.isNotEmpty() -> "共 ${state.cues.size} 句字幕" + if (translated > 0) "，已翻译 $translated 句" else ""
        else -> state.status
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodySmall, color = muted)
        val p = state.progress
        if (state.transcribing && p != null) {
            if (p < 0f) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            else LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().height(2.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CueList(
    cues: List<Cue>,
    current: Cue?,
    settings: AppSettings,
    onSeek: (Cue) -> Unit,
    onRetranslate: (Cue) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(current?.id, settings.followPlayback) {
        if (!settings.followPlayback) return@LaunchedEffect
        val index = cues.indexOfFirst { it.id == current?.id }
        // 当前句停在列表靠上的位置，前面留两句上下文
        if (index >= 0) listState.animateScrollToItem((index - 2).coerceAtLeast(0))
    }
    LazyColumn(modifier.fillMaxWidth(), state = listState, contentPadding = PaddingValues(vertical = 4.dp)) {
        items(cues, key = { it.id }) { cue ->
            val active = cue.id == current?.id
            var menu by remember { mutableStateOf(false) }
            Box {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .background(if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else Color.Transparent)
                        .combinedClickable(onClick = { onSeek(cue) }, onLongClick = { menu = true }),
                ) {
                    // 左侧细橙线标记当前句
                    Box(Modifier.width(3.dp).fillMaxHeight().background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent))
                    Column(Modifier.padding(start = 13.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
                        Text(
                            "${formatTime(cue.startMs)} – ${formatTime(cue.endMs)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        if (settings.showOriginal || cue.translation == null) {
                            Text(cue.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        }
                        if (settings.showTranslation && cue.translation != null) {
                            Text(cue.translation, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("从这里播放") }, onClick = { menu = false; onSeek(cue) })
                    DropdownMenuItem(text = { Text("复制原文") }, onClick = { menu = false; clipboard.setText(AnnotatedString(cue.text)) })
                    cue.translation?.let { t ->
                        DropdownMenuItem(text = { Text("复制译文") }, onClick = { menu = false; clipboard.setText(AnnotatedString(t)) })
                        DropdownMenuItem(
                            text = { Text("复制双语") },
                            onClick = { menu = false; clipboard.setText(AnnotatedString("${cue.text}\n$t")) },
                        )
                    }
                    DropdownMenuItem(text = { Text("重新翻译这句") }, onClick = { menu = false; onRetranslate(cue) })
                }
            }
        }
    }
}

/** 字幕时间，精确到 0.1 秒，例如 01:23.4 或 1:02:03.4 */
private fun formatTime(ms: Long): String {
    val total = ms / 1000
    val tenth = ms % 1000 / 100
    return if (total >= 3600) "%d:%02d:%02d.%d".format(total / 3600, total / 60 % 60, total % 60, tenth)
    else "%02d:%02d.%d".format(total / 60, total % 60, tenth)
}
