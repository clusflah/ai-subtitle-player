package com.aisub.player.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aisub.player.LibraryViewModel
import com.aisub.player.MainViewModel
import com.aisub.player.media.Downloads
import com.aisub.player.media.MediaEntry
import com.aisub.player.media.RemoteFiles
import com.aisub.player.media.formatSize
import com.aisub.player.meta.DoubanRating
import com.aisub.player.meta.FolderContext
import com.aisub.player.meta.LocalMeta
import com.aisub.player.meta.LocalMetaLoader
import com.aisub.player.meta.MediaInfo
import com.aisub.player.meta.MetaQuery
import com.aisub.player.meta.MetaRepository
import com.aisub.player.meta.NameParser
import com.aisub.player.remote.RemoteItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun RemoteItem.toMediaEntry() =
    MediaEntry(playUrl!!, name, isVideo = MediaEntry.isVideoName(name), sizeBytes = size)

/** 本地媒体也用详情页：包装成 RemoteItem，path 和 playUrl 都是 content:// 地址。 */
internal fun MediaEntry.toRemoteItem() = RemoteItem(title, uri, isDir = false, size = sizeBytes, playUrl = uri)

/** 异步查询的状态。 */
private sealed interface Lookup<out T> {
    data object Idle : Lookup<Nothing>
    data object Loading : Lookup<Nothing>
    data class Done<T>(val value: T?) : Lookup<T>
    data class Failed(val message: String) : Lookup<Nothing>
}

private val DOUBAN_GREEN = Color(0xFF00B51D)
private val TMDB_BLUE = Color(0xFF01B4E4)

/**
 * 文件详情：海报 / 背景图、TMDB 简介与演职员、豆瓣评分、文件信息；文本文件显示内容预览。
 * 媒体服务器（Emby / Plex）的条目先显示服务器自带的信息，TMDB 结果回来后再补全。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FileDetailScreen(
    main: MainViewModel,
    lib: LibraryViewModel,
    serverId: String?,
    item: RemoteItem,
    queue: List<MediaEntry>,
    index: Int,
    folder: FolderContext?,
    onBack: () -> Unit,
    play: (List<MediaEntry>, Int) -> Unit,
    openImages: (List<RemoteItem>, Int) -> Unit,
    openFolder: (RemoteItem) -> Unit,
    openSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui by main.state.collectAsStateWithLifecycle()
    val settings = ui.settings
    val server = serverId?.let { lib.server(it) }
    val meta = item.meta
    val name = item.name
    val isVideo = MediaEntry.isVideoName(name) || meta?.kind in setOf("movie", "tv", "season", "episode")
    val isAudio = !item.isDir && MediaEntry.isAudioName(name)
    val isImage = !item.isDir && MediaEntry.isImageName(name)
    val isText = !item.isDir && MediaEntry.isTextName(name)
    val fileKey = item.playUrl ?: "$serverId:${item.path}"

    var reload by remember { mutableIntStateOf(0) }
    var rematching by remember { mutableStateOf(false) }
    val query = remember(item, reload) {
        MetaRepository.override(context, fileKey)?.let { (t, y) -> MetaQuery.manual(t, y) } ?: MetaQuery.of(item)
    }
    // tmdb：本地 NFO 与 TMDB 合并后的影片信息
    var tmdb by remember { mutableStateOf<Lookup<MediaInfo>>(Lookup.Idle) }
    var douban by remember { mutableStateOf<Lookup<DoubanRating>>(Lookup.Idle) }
    var preview by remember { mutableStateOf<Lookup<String>>(Lookup.Idle) }
    var local by remember { mutableStateOf<LocalMeta?>(null) }

    LaunchedEffect(query, settings.tmdbKey, settings.tmdbBaseUrl, settings.doubanEnabled) {
        if (!isVideo) return@LaunchedEffect
        // 1. 先读本地：视频旁边的 NFO、海报、背景图，以及目录名里的 {tmdb-xxx}（媒体服务器条目自带信息，不需要）
        val loc = if (local != null) local else if (server != null && folder != null && meta == null) {
            tmdb = Lookup.Loading
            runCatching { LocalMetaLoader.load(context, lib.client(server), item, folder) }.getOrNull()
        } else null
        local = loc
        // 手动重新匹配过就不用本地的编号和 NFO 文字（多半是它们错了），本地图片照用
        val overridden = MetaRepository.override(context, fileKey) != null
        val localInfo = loc?.info?.takeIf { !overridden }
        val q = if (loc == null || overridden) query else query.copy(
            titles = (listOfNotNull(localInfo?.title?.ifEmpty { null }, localInfo?.originalTitle?.ifEmpty { null }) + query.titles).distinct(),
            year = localInfo?.year?.toIntOrNull() ?: query.year,
            kind = query.kind ?: loc.kind,
            tmdbId = query.tmdbId ?: loc.tmdbId,
            imdbId = query.imdbId ?: loc.imdbId,
        )
        var info = localInfo
        tmdb = if (localInfo != null) Lookup.Done(localInfo) else Lookup.Idle

        // 2. 本地缺简介、海报、背景图、本集信息时，再用 TMDB 补全
        val episodic = q.season != null || q.episode != null
        val missing = localInfo == null || localInfo.overview.isBlank() ||
            (loc.posterUrl == null && localInfo.posterUrl == null) || (loc.backdropUrl == null && localInfo.backdropUrl == null) ||
            (episodic && localInfo.episodeOverview == null)
        if (settings.tmdbKey.isNotBlank() && missing) {
            if (localInfo == null) tmdb = Lookup.Loading
            try {
                val remote = MetaRepository.tmdb(settings, q)
                info = if (localInfo != null && remote != null) localInfo.fillFrom(remote) else localInfo ?: remote
                tmdb = Lookup.Done(info)
            } catch (e: Exception) {
                if (localInfo == null) tmdb = Lookup.Failed(e.message ?: e.toString())
            }
        } else if (localInfo == null) {
            tmdb = if (settings.tmdbKey.isNotBlank()) Lookup.Done<MediaInfo>(null) else Lookup.Idle
        }

        // 3. 豆瓣：NFO 里有豆瓣评分就直接用
        if (settings.doubanEnabled) {
            val nfoRating = info?.doubanRating
            if (nfoRating != null) {
                douban = Lookup.Done(DoubanRating(info.doubanId.orEmpty(), info.title, nfoRating, info.doubanVotes))
            } else {
                douban = Lookup.Loading
                douban = try {
                    Lookup.Done(MetaRepository.douban(q, info))
                } catch (e: Exception) {
                    Lookup.Failed(e.message ?: e.toString())
                }
            }
        }
    }
    LaunchedEffect(item) {
        if (!isText || item.playUrl == null) return@LaunchedEffect
        preview = Lookup.Loading
        preview = try {
            val bytes = withContext(Dispatchers.IO) { RemoteFiles.read(context, item.playUrl, PREVIEW_BYTES, truncate = true) }
            Lookup.Done(RemoteFiles.decodeText(bytes))
        } catch (e: Exception) {
            Lookup.Failed(e.message ?: e.toString())
        }
    }

    val info = (tmdb as? Lookup.Done)?.value
    val parsed = remember(name) { NameParser.parse(name) }
    val title = info?.title?.takeIf { it.isNotEmpty() } ?: meta?.seriesName ?: meta?.title ?: if (isVideo) parsed.title else name
    val backdrop = local?.backdropUrl ?: info?.backdropUrl ?: meta?.backdropUrl
    val poster = when {
        isImage -> item.playUrl
        meta?.posterUrl != null && meta.posterRatio < 1f -> meta.posterUrl
        else -> local?.posterUrl ?: info?.posterUrl ?: meta?.posterUrl
    }
    val doubanHit = (douban as? Lookup.Done)?.value

    fun download() {
        scope.launch {
            try {
                Downloads.enqueue(context, listOf(Downloads.Job(item, "")))
                Toast.makeText(context, "已加入下载，保存到 Download/${Downloads.ROOT}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "下载失败：${e.message ?: e}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun openUrl(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    DetailScaffold(
        title = if (item.isDir) item.name else name.substringBeforeLast('.').ifEmpty { name },
        onBack = onBack,
        actions = {
            OverflowMenu(
                listOfNotNull(
                    MenuAction("重新匹配影片…") { rematching = true }.takeIf { isVideo },
                    doubanHit?.takeIf { it.id.isNotEmpty() }?.let { d -> MenuAction("在豆瓣打开") { openUrl(d.url) } },
                    info?.tmdbUrl?.let { url -> MenuAction("在 TMDB 打开") { openUrl(url) } },
                    info?.imdbId?.let { id -> MenuAction("在 IMDb 打开") { openUrl("https://www.imdb.com/title/$id/") } },
                    MenuAction("下载") { download() }.takeIf { server != null && !item.isDir && item.playUrl != null },
                ),
            )
        },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            // ---------- 背景图 + 海报 + 标题 ----------
            if (backdrop != null && !isImage) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                    NetImage(backdrop, Modifier.fillMaxSize(), maxSize = 1280, fallback = null)
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(0.5f to Color.Transparent, 1f to MaterialTheme.colorScheme.surfaceContainerLowest),
                        ),
                    )
                }
            }
            if (isImage) {
                NetImage(
                    poster, Modifier.fillMaxWidth().heightIn(max = 420.dp).aspectRatio(4f / 3f)
                        .clickable { openImages(listOf(item), 0) },
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit, maxSize = 1600, fallback = AppIcons.Image,
                )
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
                if (!isImage && (poster != null || isVideo)) {
                    NetImage(
                        poster,
                        Modifier.width(112.dp).aspectRatio(2f / 3f).clip(MaterialTheme.shapes.medium),
                        maxSize = 500,
                        fallback = if (item.isDir) AppIcons.Folder else AppIcons.Movie,
                    )
                    Spacer(Modifier.width(16.dp))
                } else if (!isImage) {
                    IconBadge(fileIcon(item), size = 56.dp, muted = true)
                    Spacer(Modifier.width(16.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    val sub = listOf(
                        info?.originalTitle?.takeIf { it != info.title }.orEmpty(),
                        info?.year ?: meta?.year?.toString() ?: parsed.year?.toString().takeIf { isVideo }.orEmpty(),
                    ).filter { it.isNotEmpty() }.joinToString(" · ")
                    if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val tags = listOf(
                        (info?.genres ?: meta?.genres).orEmpty().take(3).joinToString(" / "),
                        info?.length.orEmpty(),
                        info?.countries.orEmpty().take(2).joinToString(" / "),
                    ).filter { it.isNotEmpty() }.joinToString(" · ")
                    if (tags.isNotEmpty()) Text(tags, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    episodeLine(meta, parsed, info)?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    if (isVideo) {
                        Spacer(Modifier.height(4.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (settings.doubanEnabled) RatingChip("豆瓣", DOUBAN_GREEN, douban) { it?.rating to it?.votes }
                            if (settings.tmdbKey.isNotBlank() || (info?.rating ?: 0.0) > 0) {
                                val label = info?.ratingLabel ?: "TMDB"
                                RatingChip(label, if (label == "TMDB") TMDB_BLUE else MaterialTheme.colorScheme.outline, tmdb) { it?.rating?.takeIf { r -> r > 0 } to it?.voteCount }
                            }
                            meta?.rating?.let { r ->
                                RatingChip(server?.type?.label ?: "评分", MaterialTheme.colorScheme.outline, Lookup.Done(r)) { it to null }
                            }
                        }
                    }
                }
            }

            // ---------- 操作 ----------
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    item.isDir -> Button(onClick = { openFolder(item) }) { Icon(AppIcons.Folder, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("打开") }
                    (isVideo || isAudio) && item.playUrl != null -> Button(onClick = {
                        if (queue.isNotEmpty()) play(queue, index.coerceIn(0, queue.lastIndex)) else play(listOf(item.toMediaEntry()), 0)
                    }) { Icon(AppIcons.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("播放") }
                    isImage -> Button(onClick = { openImages(listOf(item), 0) }) { Icon(AppIcons.Image, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("查看图片") }
                }
                if (server != null && !item.isDir && item.playUrl != null) {
                    OutlinedButton(onClick = ::download) { Icon(AppIcons.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("下载") }
                }
                if (isVideo) FilledTonalButton(onClick = { rematching = true }) { Text("重新匹配") }
            }

            if (isVideo && settings.tmdbKey.isBlank() && info?.overview.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                SurfaceCard {
                    ListRow(
                        title = "显示影片简介和演职员",
                        subtitle = "在设置里填写 TMDB API Key（免费申请）",
                        icon = AppIcons.Info,
                        trailing = { TextButton(onClick = openSettings) { Text("去设置") } },
                        onClick = openSettings,
                    )
                }
            }
            (tmdb as? Lookup.Failed)?.let { Hint("TMDB 查询失败：${it.message}") }
            info?.let { Hint("信息来源：${it.source}") }
            if (tmdb is Lookup.Done && info == null) Hint("TMDB 没有找到「${query.titles.firstOrNull().orEmpty()}」，可以点「重新匹配」修改片名")

            // ---------- 简介 ----------
            val overview = info?.overview?.takeIf { it.isNotBlank() } ?: meta?.overview.orEmpty()
            if (overview.isNotBlank()) {
                SectionHeader("简介")
                ExpandableText(overview, Modifier.padding(horizontal = 16.dp))
            }
            if (info?.episodeTitle != null) {
                SectionHeader("本集")
                if (local?.stillUrl != null || info.episodeStill != null) {
                    NetImage(
                        local?.stillUrl ?: info.episodeStill,
                        Modifier.padding(horizontal = 16.dp).fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium),
                        maxSize = 800, fallback = null,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(info.episodeTitle, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp))
                if (!info.episodeOverview.isNullOrBlank()) ExpandableText(info.episodeOverview, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            if (info != null && (info.directors.isNotEmpty() || info.cast.isNotEmpty())) {
                SectionHeader("演职员")
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (info.directors.isNotEmpty()) LabeledText(if (info.kind == "movie") "导演" else "主创", info.directors.joinToString(" / "))
                    if (info.cast.isNotEmpty()) LabeledText("主演", info.cast.joinToString(" / "))
                }
            }

            // ---------- 文件信息 ----------
            SectionHeader("文件信息")
            SurfaceCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LabeledText("名称", name)
                    LabeledText("类型", kindLabel(item))
                    if (item.size > 0) LabeledText("大小", "${formatSize(item.size)}（${"%,d".format(item.size)} 字节）")
                    LabeledText("来源", server?.let { "${it.name}（${it.type.label}）" } ?: "本机")
                    if (server == null || !server.type.isCloud) LabeledText("位置", Uri.decode(item.path))
                }
            }

            // ---------- 文本预览 ----------
            if (isText) {
                SectionHeader("内容预览")
                when (val p = preview) {
                    Lookup.Loading, Lookup.Idle -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    is Lookup.Failed -> Hint("读取失败：${p.message}")
                    is Lookup.Done -> SurfaceCard {
                        SelectionContainer {
                            Text(
                                p.value.orEmpty().ifEmpty { "（空文件）" } + if (item.size > PREVIEW_BYTES) "\n……（只显示前 ${formatSize(PREVIEW_BYTES.toLong())}）" else "",
                                fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp,
                                modifier = Modifier.padding(12.dp).horizontalScroll(rememberScrollState()),
                            )
                        }
                    }
                }
            }
        }
    }

    if (rematching) {
        val current = MetaRepository.override(context, fileKey)
        RematchDialog(
            initialTitle = current?.first ?: query.titles.firstOrNull().orEmpty(),
            initialYear = (current?.second ?: query.year)?.toString().orEmpty(),
            onDismiss = { rematching = false },
            onReset = {
                MetaRepository.setOverride(context, fileKey, "", null)
                rematching = false
                reload++
            },
        ) { t, y ->
            MetaRepository.setOverride(context, fileKey, t, y)
            rematching = false
            reload++
        }
    }
}

private const val PREVIEW_BYTES = 256 * 1024

private fun episodeLine(meta: com.aisub.player.remote.ItemMeta?, parsed: com.aisub.player.meta.ParsedName, info: MediaInfo?): String? {
    if (info?.episodeTitle != null) return null
    val season = meta?.season ?: parsed.season
    val episode = meta?.episode ?: parsed.episode
    return when {
        episode != null && season != null -> "第 $season 季 第 $episode 集"
        episode != null -> "第 $episode 集"
        season != null -> "第 $season 季"
        else -> null
    }?.let { if (meta?.kind == "episode" && meta.title != null) "$it · ${meta.title}" else it }
}

private fun fileIcon(item: RemoteItem) = when {
    item.isDir -> AppIcons.Folder
    MediaEntry.isVideoName(item.name) -> AppIcons.Movie
    MediaEntry.isAudioName(item.name) -> AppIcons.MusicNote
    MediaEntry.isImageName(item.name) -> AppIcons.Image
    MediaEntry.isTextName(item.name) -> AppIcons.Description
    else -> AppIcons.File
}

private fun kindLabel(item: RemoteItem): String {
    if (item.isDir) return "文件夹"
    val ext = item.name.substringAfterLast('.', "").uppercase()
    val kind = when {
        MediaEntry.isVideoName(item.name) -> "视频"
        MediaEntry.isAudioName(item.name) -> "音频"
        MediaEntry.isImageName(item.name) -> "图片"
        MediaEntry.isTextName(item.name) -> "文本"
        else -> "文件"
    }
    return if (ext.isEmpty()) kind else "$kind（$ext）"
}

/** 评分标签：来源色块 + 分数；查询中显示转圈，没查到显示「暂无」。 */
@Composable
private fun <T> RatingChip(label: String, color: Color, state: Lookup<T>, value: (T?) -> Pair<Double?, Int?>) {
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(6.dp))
        when (state) {
            Lookup.Idle, Lookup.Loading -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = color)
            is Lookup.Failed -> Text("失败", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            is Lookup.Done -> {
                val (score, votes) = value(state.value)
                if (score == null) {
                    Text("暂无", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Icon(AppIcons.Star, null, tint = color, modifier = Modifier.size(12.dp))
                    Text(" %.1f".format(score), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    if (votes != null && votes > 0) {
                        Text(" · ${if (votes >= 10_000) "%.1f万".format(votes / 10_000.0) else votes.toString()}人",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun LabeledText(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(48.dp))
        SelectionContainer(Modifier.weight(1f)) { Text(value, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** 长简介默认显示 5 行，点一下展开。 */
@Composable
private fun ExpandableText(text: String, modifier: Modifier = Modifier) {
    var expanded by remember(text) { mutableStateOf(false) }
    var overflow by remember(text) { mutableStateOf(false) }
    Column(modifier.clickable(enabled = overflow || expanded) { expanded = !expanded }) {
        Text(
            text, style = MaterialTheme.typography.bodyMedium, lineHeight = 22.sp,
            maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflow = it.hasVisualOverflow },
        )
        if (overflow || expanded) Text(if (expanded) "收起" else "展开", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun RematchDialog(
    initialTitle: String,
    initialYear: String,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
    onConfirm: (String, Int?) -> Unit,
) {
    var title by remember { mutableStateOf(initialTitle) }
    var year by remember { mutableStateOf(initialYear) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重新匹配影片") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("填写正确的片名（中文或原名都可以），剧集可以带上 S01E02", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(title, { title = it }, label = { Text("片名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    year, { v -> year = v.filter { it.isDigit() }.take(4) },
                    label = { Text("年份（可不填）") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider()
                TextButton(onClick = onReset) { Text("恢复自动识别") }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(title, year.toIntOrNull()) }, enabled = title.isNotBlank()) { Text("搜索") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
