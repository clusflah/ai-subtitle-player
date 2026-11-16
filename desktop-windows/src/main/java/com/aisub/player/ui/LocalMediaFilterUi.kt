package com.aisub.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aisub.player.media.LocalMediaFilter
import com.aisub.player.media.LocalMediaFilterStore

private val VIDEO_DURATION_OPTIONS = listOf(
    0 to "不限",
    15 to "≥ 15 秒",
    60 to "≥ 1 分钟",
    300 to "≥ 5 分钟",
    1200 to "≥ 20 分钟",
)

private val VIDEO_MIN_SIZE_OPTIONS = listOf(
    0L to "不限",
    5L * 1024 to "≥ 5 MB",
    20L * 1024 to "≥ 20 MB",
    100L * 1024 to "≥ 100 MB",
    500L * 1024 to "≥ 500 MB",
)

private val VIDEO_MAX_SIZE_OPTIONS = listOf(
    0L to "不限",
    100L * 1024 to "≤ 100 MB",
    1024L * 1024 to "≤ 1 GB",
    4L * 1024 * 1024 to "≤ 4 GB",
)

private val VIDEO_EXT_OPTIONS = listOf("mp4", "mkv", "avi", "mov", "flv", "webm", "ts", "wmv", "rmvb", "iso")

private val AUDIO_DURATION_OPTIONS = listOf(
    0 to "不限",
    15 to "≥ 15 秒",
    30 to "≥ 30 秒",
    60 to "≥ 1 分钟",
    180 to "≥ 3 分钟",
)

private val AUDIO_MIN_SIZE_OPTIONS = listOf(
    0L to "不限",
    500L to "≥ 500 KB",
    2L * 1024 to "≥ 2 MB",
    10L * 1024 to "≥ 10 MB",
)

private val AUDIO_EXT_OPTIONS = listOf("flac", "wav", "ape", "mp3", "m4a", "aac", "ogg", "opus", "wma", "dsf")

private val IMAGE_MIN_SIZE_OPTIONS = listOf(
    0L to "不限",
    50L to "≥ 50 KB",
    200L to "≥ 200 KB",
    1024L to "≥ 1 MB",
    5L * 1024 to "≥ 5 MB",
)

private val IMAGE_MAX_SIZE_OPTIONS = listOf(
    0L to "不限",
    2L * 1024 to "≤ 2 MB",
    10L * 1024 to "≤ 10 MB",
)

private val IMAGE_EXT_OPTIONS = listOf("jpg", "png", "webp", "heic", "gif", "bmp", "avif")

/**
 * 「本机」右上角设置弹出的视频 / 音乐 / 照片可选过滤条件配置窗。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LocalMediaFilterDialog(
    initialTab: Int = 0,
    availableVideoFolders: List<String> = emptyList(),
    availableAudioFolders: List<String> = emptyList(),
    availableImageFolders: List<String> = emptyList(),
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { LocalMediaFilterStore.ensureInit(context) }
    val filter by LocalMediaFilterStore.filter.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(initialTab.coerceIn(0, 2)) }

    fun update(block: (LocalMediaFilter) -> LocalMediaFilter) {
        LocalMediaFilterStore.update(context, block)
    }

    val tabLabels = listOf(
        "视频" + (filter.videoActiveCount.takeIf { it > 0 }?.let { " ($it)" } ?: ""),
        "音乐" + (filter.audioActiveCount.takeIf { it > 0 }?.let { " ($it)" } ?: ""),
        "图片" + (filter.imageActiveCount.takeIf { it > 0 }?.let { " ($it)" } ?: ""),
    )

    AppDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("本机媒体过滤条件", modifier = Modifier.weight(1f))
                if (filter.totalActiveCount > 0) {
                    TextButton(onClick = { LocalMediaFilterStore.resetAll(context) }) {
                        Text("全部重置", fontSize = 12.sp)
                    }
                }
            }
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 470.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tabLabels.forEachIndexed { idx, label ->
                        FilterChip(
                            selected = tab == idx,
                            onClick = { tab = idx },
                            label = { Text(label) },
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                when (tab) {
                    0 -> VideoFilterSection(
                        filter = filter,
                        availableFolders = availableVideoFolders,
                        onUpdate = ::update,
                    )
                    1 -> AudioFilterSection(
                        filter = filter,
                        availableFolders = availableAudioFolders,
                        onUpdate = ::update,
                    )
                    2 -> ImageFilterSection(
                        filter = filter,
                        availableFolders = availableImageFolders,
                        onUpdate = ::update,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
        dismissButton = {
            if (filter.activeCountForTab(tab) > 0) {
                TextButton(onClick = { LocalMediaFilterStore.clearTab(context, tab) }) {
                    Text("重置当前分类")
                }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VideoFilterSection(
    filter: LocalMediaFilter,
    availableFolders: List<String>,
    onUpdate: ((LocalMediaFilter) -> LocalMediaFilter) -> Unit,
) {
    FilterGroupTitle("最小时长（过滤短视频片段）")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        VIDEO_DURATION_OPTIONS.forEach { (sec, label) ->
            FilterChip(
                selected = filter.minVideoDurationSec == sec,
                onClick = { onUpdate { it.copy(minVideoDurationSec = sec) } },
                label = { Text(label, fontSize = 12.sp) },
            )
        }
    }

    FilterGroupTitle("最小文件大小")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        VIDEO_MIN_SIZE_OPTIONS.forEach { (kb, label) ->
            FilterChip(
                selected = filter.minVideoSizeKb == kb,
                onClick = { onUpdate { it.copy(minVideoSizeKb = kb) } },
                label = { Text(label, fontSize = 12.sp) },
            )
        }
    }

    FilterGroupTitle("最大文件大小")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        VIDEO_MAX_SIZE_OPTIONS.forEach { (kb, label) ->
            FilterChip(
                selected = filter.maxVideoSizeKb == kb,
                onClick = { onUpdate { it.copy(maxVideoSizeKb = kb) } },
                label = { Text(label, fontSize = 12.sp) },
            )
        }
    }

    FilterGroupTitle("视频格式（不选代表全部）")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = filter.videoExtensions.isEmpty(),
            onClick = { onUpdate { it.copy(videoExtensions = emptySet()) } },
            label = { Text("全部格式", fontSize = 12.sp) },
        )
        VIDEO_EXT_OPTIONS.forEach { ext ->
            val on = ext in filter.videoExtensions
            FilterChip(
                selected = on,
                onClick = {
                    onUpdate { cur ->
                        val next = if (on) cur.videoExtensions - ext else cur.videoExtensions + ext
                        cur.copy(videoExtensions = next)
                    }
                },
                label = { Text(ext.uppercase(), fontSize = 12.sp) },
            )
        }
    }

    FilterSwitchRow(
        title = "排除录屏与缓存杂项目录",
        subtitle = "自动隐藏 ScreenRecorder、Screenshots、cache、temp 等目录中的零碎视频",
        checked = filter.excludeVideoJunk,
        onCheckedChange = { v -> onUpdate { it.copy(excludeVideoJunk = v) } },
    )

    if (availableFolders.isNotEmpty()) {
        FilterGroupTitle("仅看指定目录（不选代表全部目录）")
        FolderPickerChips(
            available = availableFolders,
            selected = filter.videoFolders,
            onChange = { next -> onUpdate { it.copy(videoFolders = next) } },
        )
    }

    OutlinedTextField(
        value = filter.videoExcludeKeyword,
        onValueChange = { txt -> onUpdate { it.copy(videoExcludeKeyword = txt) } },
        label = { Text("排除关键词（多个用逗号或空格分隔）") },
        placeholder = { Text("例如：sample, 预告, 广告") },
        singleLine = true,
        colors = appFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AudioFilterSection(
    filter: LocalMediaFilter,
    availableFolders: List<String>,
    onUpdate: ((LocalMediaFilter) -> LocalMediaFilter) -> Unit,
) {
    FilterGroupTitle("最小时长（过滤提示音与短语音）")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AUDIO_DURATION_OPTIONS.forEach { (sec, label) ->
            FilterChip(
                selected = filter.minAudioDurationSec == sec,
                onClick = { onUpdate { it.copy(minAudioDurationSec = sec) } },
                label = { Text(label, fontSize = 12.sp) },
            )
        }
    }

    FilterGroupTitle("最小文件大小")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AUDIO_MIN_SIZE_OPTIONS.forEach { (kb, label) ->
            FilterChip(
                selected = filter.minAudioSizeKb == kb,
                onClick = { onUpdate { it.copy(minAudioSizeKb = kb) } },
                label = { Text(label, fontSize = 12.sp) },
            )
        }
    }

    FilterGroupTitle("音频格式（不选代表全部）")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = filter.audioExtensions.isEmpty(),
            onClick = { onUpdate { it.copy(audioExtensions = emptySet()) } },
            label = { Text("全部格式", fontSize = 12.sp) },
        )
        AUDIO_EXT_OPTIONS.forEach { ext ->
            val on = ext in filter.audioExtensions
            FilterChip(
                selected = on,
                onClick = {
                    onUpdate { cur ->
                        val next = if (on) cur.audioExtensions - ext else cur.audioExtensions + ext
                        cur.copy(audioExtensions = next)
                    }
                },
                label = { Text(ext.uppercase(), fontSize = 12.sp) },
            )
        }
    }

    FilterSwitchRow(
        title = "排除铃声、提示音与通话录音",
        subtitle = "自动隐藏 Ringtones、Notifications、Alarms、Recordings 等系统目录音频",
        checked = filter.excludeAudioRingtones,
        onCheckedChange = { v -> onUpdate { it.copy(excludeAudioRingtones = v) } },
    )

    if (availableFolders.isNotEmpty()) {
        FilterGroupTitle("仅看指定目录（不选代表全部目录）")
        FolderPickerChips(
            available = availableFolders,
            selected = filter.audioFolders,
            onChange = { next -> onUpdate { it.copy(audioFolders = next) } },
        )
    }

    OutlinedTextField(
        value = filter.audioExcludeKeyword,
        onValueChange = { txt -> onUpdate { it.copy(audioExcludeKeyword = txt) } },
        label = { Text("排除关键词（多个用逗号或空格分隔）") },
        placeholder = { Text("例如：录音, 伴奏, 音效") },
        singleLine = true,
        colors = appFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImageFilterSection(
    filter: LocalMediaFilter,
    availableFolders: List<String>,
    onUpdate: ((LocalMediaFilter) -> LocalMediaFilter) -> Unit,
) {
    FilterGroupTitle("最小图片大小（过滤缩略图与小图标）")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        IMAGE_MIN_SIZE_OPTIONS.forEach { (kb, label) ->
            FilterChip(
                selected = filter.minImageSizeKb == kb,
                onClick = { onUpdate { it.copy(minImageSizeKb = kb) } },
                label = { Text(label, fontSize = 12.sp) },
            )
        }
    }

    FilterGroupTitle("最大图片大小")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        IMAGE_MAX_SIZE_OPTIONS.forEach { (kb, label) ->
            FilterChip(
                selected = filter.maxImageSizeKb == kb,
                onClick = { onUpdate { it.copy(maxImageSizeKb = kb) } },
                label = { Text(label, fontSize = 12.sp) },
            )
        }
    }

    FilterGroupTitle("图片格式（不选代表全部）")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = filter.imageExtensions.isEmpty(),
            onClick = { onUpdate { it.copy(imageExtensions = emptySet()) } },
            label = { Text("全部格式", fontSize = 12.sp) },
        )
        IMAGE_EXT_OPTIONS.forEach { ext ->
            val on = ext in filter.imageExtensions
            FilterChip(
                selected = on,
                onClick = {
                    onUpdate { cur ->
                        val next = if (on) cur.imageExtensions - ext else cur.imageExtensions + ext
                        cur.copy(imageExtensions = next)
                    }
                },
                label = { Text(ext.uppercase(), fontSize = 12.sp) },
            )
        }
    }

    FilterSwitchRow(
        title = "排除手机 / 屏幕截图",
        subtitle = "自动隐藏 Screenshots、截屏目录及 Screenshot_ 前缀截图",
        checked = filter.excludeScreenshots,
        onCheckedChange = { v -> onUpdate { it.copy(excludeScreenshots = v) } },
    )

    FilterSwitchRow(
        title = "排除应用缓存、缩略图与表情包",
        subtitle = "自动隐藏 .thumbnails、cache、sticker、emoji 等杂图目录",
        checked = filter.excludeCacheImages,
        onCheckedChange = { v -> onUpdate { it.copy(excludeCacheImages = v) } },
    )

    if (availableFolders.isNotEmpty()) {
        FilterGroupTitle("仅看指定相册目录（不选代表全部目录）")
        FolderPickerChips(
            available = availableFolders,
            selected = filter.imageFolders,
            onChange = { next -> onUpdate { it.copy(imageFolders = next) } },
        )
    }

    OutlinedTextField(
        value = filter.imageExcludeKeyword,
        onValueChange = { txt -> onUpdate { it.copy(imageExcludeKeyword = txt) } },
        label = { Text("排除关键词（多个用逗号或空格分隔）") },
        placeholder = { Text("例如：thumb, cover, temp") },
        singleLine = true,
        colors = appFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun FilterGroupTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun FilterSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        onClick = { onCheckedChange(!checked) },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FolderPickerChips(
    available: List<String>,
    selected: Set<String>,
    onChange: (Set<String>) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = selected.isEmpty(),
            onClick = { onChange(emptySet()) },
            label = { Text("全部目录", fontSize = 12.sp) },
        )
        available.take(24).forEach { folder ->
            val on = folder in selected
            val display = folder.ifEmpty { "根目录" }
            FilterChip(
                selected = on,
                onClick = { onChange(if (on) selected - folder else selected + folder) },
                label = { Text(display, fontSize = 12.sp) },
            )
        }
    }
}

/**
 * 本机页顶部提示条：当当前分类启用了过滤条件时显示已隐藏数量，支持一键调整或清除。
 */
@Composable
fun ActiveLocalFilterBanner(
    activeCount: Int,
    hiddenByFilterCount: Int,
    onEdit: () -> Unit,
    onClear: () -> Unit,
) {
    if (activeCount <= 0) return
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .liquidGlass(
                shape = RoundedCornerShape(12.dp),
                style = GlassStyle.Card,
                tint = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                enableBackdrop = false,
            ),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                AppIcons.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "已启用 $activeCount 项过滤" + if (hiddenByFilterCount > 0) "（隐藏 $hiddenByFilterCount 项）" else "",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                Text("设置", fontSize = 12.sp)
            }
            TextButton(onClick = onClear, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                Text("清除", fontSize = 12.sp)
            }
        }
    }
}

/**
 * 全局「设置」页面中的「本机媒体过滤条件」快捷配置卡片（与本机页右上角过滤设置互通）。
 */
@Composable
fun LocalMediaFilterSettingsRow() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { LocalMediaFilterStore.ensureInit(context) }
    val filter by LocalMediaFilterStore.filter.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }

    val summary = if (filter.totalActiveCount == 0) {
        "未启用过滤（可按时长、体积、格式、目录过滤本机视频、音乐与图片）"
    } else {
        buildList {
            if (filter.videoActiveCount > 0) add("视频 ${filter.videoActiveCount} 项")
            if (filter.audioActiveCount > 0) add("音乐 ${filter.audioActiveCount} 项")
            if (filter.imageActiveCount > 0) add("图片 ${filter.imageActiveCount} 项")
        }.joinToString(" · ")
    }

    ListRow(
        title = "本机媒体过滤条件",
        subtitle = summary,
        icon = AppIcons.Tune,
        onClick = { showDialog = true },
    )

    if (showDialog) {
        LocalMediaFilterDialog(onDismiss = { showDialog = false })
    }
}
