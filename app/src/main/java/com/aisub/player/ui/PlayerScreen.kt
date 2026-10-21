package com.aisub.player.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import com.aisub.player.MainViewModel
import com.aisub.player.subtitle.Cue
import com.aisub.player.subtitle.SrtMode
import com.aisub.player.subtitle.cueAt
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    var exportMode by remember { mutableStateOf(SrtMode.BILINGUAL) }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            vm.openVideo(uri)
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-subrip")
    ) { uri -> if (uri != null) vm.exportSrt(uri, exportMode) }

    // 播放位置：每 100ms 采样一次，用来同步字幕
    var positionMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            positionMs = vm.player.currentPosition
            delay(100)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 字幕播放器") },
                actions = {
                    TextButton(onClick = { openLauncher.launch(arrayOf("video/*", "audio/*")) }) { Text("打开") }
                    TextButton(onClick = { showSettings = true }) { Text("设置") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
            ) {
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
                    Text(
                        "点击右上角「打开」选择视频",
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                SubtitleOverlay(
                    cue = state.cues.cueAt(positionMs),
                    showOriginal = state.settings.showOriginal,
                    showTranslation = state.settings.showTranslation,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp, start = 12.dp, end = 12.dp),
                )
            }

            Controls(
                vm = vm,
                onExport = { mode ->
                    exportMode = mode
                    val suffix = when (mode) {
                        SrtMode.ORIGINAL -> "original"
                        SrtMode.TRANSLATION -> "translated"
                        SrtMode.BILINGUAL -> "bilingual"
                    }
                    exportLauncher.launch("subtitles.$suffix.srt")
                },
            )

            CueList(
                cues = state.cues,
                current = state.cues.cueAt(positionMs),
                onSeek = { vm.player.seekTo(it.startMs) },
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (showSettings) {
        SettingsDialog(
            initial = state.settings,
            onDismiss = { showSettings = false },
            onSave = {
                vm.updateSettings(it)
                showSettings = false
            },
        )
    }

    state.error?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::dismissError,
            confirmButton = { TextButton(onClick = vm::dismissError) { Text("知道了") } },
            title = { Text("出错了") },
            text = { Text(msg) },
        )
    }
}

@Composable
private fun SubtitleOverlay(
    cue: Cue?,
    showOriginal: Boolean,
    showTranslation: Boolean,
    modifier: Modifier = Modifier,
) {
    if (cue == null) return
    val lines = buildList {
        if (showTranslation && cue.translation != null) add(cue.translation to true)
        if (showOriginal || cue.translation == null) add(cue.text to false)
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        for ((text, primary) in lines) {
            Text(
                text = text,
                color = if (primary) Color.White else Color(0xFFE0E0E0),
                fontSize = if (primary) 18.sp else 14.sp,
                fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(Color(0x99000000))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun Controls(vm: MainViewModel, onExport: (SrtMode) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val busy = state.transcribing || state.translating
    var exportMenu by remember { mutableStateOf(false) }

    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) {
                Button(onClick = vm::cancelAll) { Text("停止") }
            } else {
                Button(onClick = vm::generateSubtitles, enabled = state.videoUri != null) {
                    Text(if (state.cues.isEmpty()) "生成字幕" else "重新识别")
                }
                OutlinedButton(onClick = vm::startTranslation, enabled = state.cues.any { it.translation == null }) {
                    Text("翻译")
                }
            }
            Box {
                OutlinedButton(onClick = { exportMenu = true }, enabled = state.cues.isNotEmpty()) { Text("导出") }
                DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                    DropdownMenuItem(text = { Text("双语 SRT") }, onClick = { exportMenu = false; onExport(SrtMode.BILINGUAL) })
                    DropdownMenuItem(text = { Text("仅译文 SRT") }, onClick = { exportMenu = false; onExport(SrtMode.TRANSLATION) })
                    DropdownMenuItem(text = { Text("仅原文 SRT") }, onClick = { exportMenu = false; onExport(SrtMode.ORIGINAL) })
                    DropdownMenuItem(
                        text = { Text("清除译文") },
                        onClick = { exportMenu = false; vm.clearTranslations() },
                        enabled = !busy,
                    )
                }
            }
            FilterChip(
                selected = state.settings.showOriginal,
                onClick = { vm.updateSettings(state.settings.copy(showOriginal = !state.settings.showOriginal)) },
                label = { Text("原文") },
            )
            FilterChip(
                selected = state.settings.showTranslation,
                onClick = { vm.updateSettings(state.settings.copy(showTranslation = !state.settings.showTranslation)) },
                label = { Text("译文") },
            )
        }
        if (state.status.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(state.status, style = MaterialTheme.typography.bodySmall)
        }
        state.progress?.let { p ->
            Spacer(Modifier.height(4.dp))
            if (p < 0f) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun CueList(cues: List<Cue>, current: Cue?, onSeek: (Cue) -> Unit, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(current?.id) {
        val index = cues.indexOfFirst { it.id == current?.id }
        if (index >= 0 && index !in listState.layoutInfo.visibleItemsInfo.map { it.index }) {
            listState.animateScrollToItem(index)
        }
    }
    LazyColumn(modifier.fillMaxWidth(), state = listState) {
        items(cues, key = { it.id }) { cue ->
            val active = cue.id == current?.id
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSeek(cue) }
                    .background(if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    formatTime(cue.startMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.width(56.dp).padding(top = 2.dp),
                )
                Column {
                    Text(cue.text, style = MaterialTheme.typography.bodyMedium)
                    cue.translation?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = ms / 1000
    return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60)
    else "%02d:%02d".format(total / 60, total % 60)
}
