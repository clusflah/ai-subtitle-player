package com.aisub.player.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aisub.player.LibraryViewModel
import com.aisub.player.media.MediaEntry
import com.aisub.player.remote.cloud.LibrarySort
import com.aisub.player.remote.cloud.MediaBrowser
import com.aisub.player.remote.cloud.ServerHub
import com.aisub.player.remote.cloud.ServerItem
import com.aisub.player.remote.cloud.ServerSection
import kotlinx.coroutines.launch

// 媒体服务器（Plex、Emby / Jellyfin）按官方客户端的方式浏览：服务器首页（继续观看、最近添加、媒体库）、媒体库海报墙、详情页（电影 / 剧集分季分集）。

private fun LibraryViewModel.plex(serverId: String): MediaBrowser? = server(serverId)?.let { client(it) as? MediaBrowser }

/** 能播放的条目转成播放队列里的一项 */
private fun ServerItem.toEntry() = MediaEntry(playUrl!!, fileName!!, isVideo = type != "track")

private fun minutes(ms: Long) = "${(ms / 60_000).coerceAtLeast(1)} 分钟"

/** 加载中 / 出错 / 内容 */
@Composable
private fun <T> Loaded(value: T?, error: String?, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    when {
        error != null -> Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(error, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onRetry) { Text("重试") }
        }
        value == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> content(value)
    }
}

// ============================ 服务器首页 ============================

@Composable
fun ServerHomeScreen(
    lib: LibraryViewModel,
    serverId: String,
    title: String,
    onBack: () -> Unit,
    openLibrary: (ServerSection) -> Unit,
    openItem: (ServerItem) -> Unit,
    openFolders: () -> Unit,
) {
    var data by remember { mutableStateOf<Pair<List<ServerSection>, List<ServerHub>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(serverId, reload) {
        error = null
        val plex = lib.plex(serverId) ?: run { error = "找不到这个媒体服务器"; return@LaunchedEffect }
        try {
            data = plex.sections() to plex.hubs()
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }
    DetailScaffold(title, onBack, actions = {
        OverflowMenu(listOf(MenuAction("刷新") { reload++ }, MenuAction("按文件夹浏览", onClick = openFolders)))
    }) {
        Loaded(data, error, { reload++ }) { (sections, hubs) ->
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        sections.forEach { s ->
                            FilterChip(
                                selected = false, onClick = { openLibrary(s) },
                                label = { Text(s.title) },
                                leadingIcon = {
                                    Icon(
                                        when (s.type) { "artist" -> AppIcons.MusicNote; "photo" -> AppIcons.Image; else -> AppIcons.Movie },
                                        null, Modifier.size(18.dp),
                                    )
                                },
                            )
                        }
                    }
                }
                items(hubs, key = { it.id + it.title }) { hub -> HubRow(hub, openItem) }
                if (hubs.isEmpty()) item {
                    Text("媒体库是空的", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
                }
            }
        }
    }
}

@Composable
private fun HubRow(hub: ServerHub, openItem: (ServerItem) -> Unit) {
    // 继续观看用横版卡片显示画面和进度，其他行用竖版海报
    val wide = hub.id == "home.continue" || hub.id == "home.ondeck" || hub.items.all { it.type == "episode" }
    Text(hub.title, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(hub.items, key = { it.ratingKey }) { item ->
            if (wide) WideCard(item, Modifier.width(220.dp)) { openItem(item) }
            else PosterCard(item, Modifier.width(112.dp)) { openItem(item) }
        }
    }
}

/** 竖版海报：右上角未看集数或已看勾，底部进度条，下面片名和年份 */
@Composable
private fun PosterCard(item: ServerItem, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.tvFocusRing(RoundedCornerShape(6.dp), scale = true).clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp))) {
            NetImage(
                if (item.type == "episode") item.showThumb else item.thumb, Modifier.fillMaxSize(), maxSize = 400,
                fallback = if (item.type in setOf("artist", "album", "track")) AppIcons.MusicNote else AppIcons.Movie,
            )
            WatchBadge(item)
            item.progress?.let { ProgressLine(it) }
        }
        Text(
            if (item.type == "episode") item.grandparentTitle ?: item.title else item.title,
            fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, start = 2.dp),
        )
        Text(
            item.episodeLabel ?: item.year?.toString() ?: item.parentTitle.orEmpty(),
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(start = 2.dp),
        )
    }
}

/** 横版卡片：继续观看、单集 */
@Composable
internal fun WideCard(item: ServerItem, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.tvFocusRing(RoundedCornerShape(6.dp), scale = true).clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp))) {
            NetImage(if (item.type == "episode") item.thumb else item.art ?: item.thumb, Modifier.fillMaxSize(), maxSize = 600)
            WatchBadge(item)
            item.progress?.let { ProgressLine(it) }
        }
        Text(
            if (item.type == "episode") item.grandparentTitle ?: item.title else item.title,
            fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, start = 2.dp),
        )
        Text(
            listOfNotNull(item.episodeLabel, if (item.type == "episode") item.title else item.year?.toString()).joinToString(" · "),
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}

@Composable
private fun BoxScope.WatchBadge(item: ServerItem) {
    when {
        item.isFolder && item.unwatchedLeaves > 0 && item.type != "artist" && item.type != "album" -> Text(
            item.unwatchedLeaves.toString(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).clip(RoundedCornerShape(50)).background(Orange)
                .padding(horizontal = 6.dp, vertical = 1.dp),
        )
        item.watched -> Box(
            Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp).clip(CircleShape).background(Color(0xCC000000)),
            contentAlignment = Alignment.Center,
        ) { Icon(AppIcons.Check, "已看", tint = Orange, modifier = Modifier.size(13.dp)) }
        !item.isFolder && item.viewOffsetMs == 0L && item.type in setOf("movie", "episode") -> Box(
            // 没看过：右上角橙色小三角，和官方客户端一样
            Modifier.align(Alignment.TopEnd).size(14.dp).background(
                Brush.linearGradient(0.5f to Color.Transparent, 0.5f to Orange),
            ),
        )
        else -> {}
    }
}

@Composable
private fun BoxScope.ProgressLine(fraction: Float) {
    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x99000000))) {
        Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(Orange))
    }
}

// ============================ 媒体库 ============================



@Composable
fun ServerLibraryScreen(
    lib: LibraryViewModel,
    serverId: String,
    sectionKey: String,
    title: String,
    onBack: () -> Unit,
    openItem: (ServerItem) -> Unit,
) {
    var sort by remember { mutableStateOf(LibrarySort.NAME) }
    var unwatched by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<ServerItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(serverId, sectionKey, sort, unwatched, reload) {
        error = null
        items = null
        try {
            items = lib.plex(serverId)?.library(sectionKey, sort, unwatched) ?: throw IllegalStateException("找不到这个媒体服务器")
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }
    DetailScaffold(title + (items?.let { " (${it.size})" } ?: ""), onBack) {
        Column {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LibrarySort.entries.forEach { s -> FilterChip(selected = sort == s, onClick = { sort = s }, label = { Text(s.label) }) }
                FilterChip(selected = unwatched, onClick = { unwatched = !unwatched }, label = { Text("只看未看") })
            }
            Loaded(items, error, { reload++ }) { list ->
                if (list.isEmpty()) {
                    Text("没有内容", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
                } else {
                    LazyVerticalGrid(GridCells.Adaptive(if (LocalTv.current) 140.dp else 108.dp), contentPadding = PaddingValues(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(list, key = { it.ratingKey }) { item -> PosterCard(item, Modifier) { openItem(item) } }
                    }
                }
            }
        }
    }
}

// ============================ 详情页 ============================

/**
 * 电影、剧集、季、单集的详情。剧集和季显示分季标签和每集列表；
 * 电影、单集有「播放 / 继续播放」。play 的队列：单集是本季从这一集开始往后连播。
 */
@Composable
fun ServerDetailScreen(
    lib: LibraryViewModel,
    serverId: String,
    ratingKey: String,
    onBack: () -> Unit,
    openItem: (ServerItem) -> Unit,
    play: (List<MediaEntry>, Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var item by remember { mutableStateOf<ServerItem?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    /** 剧集的各季（季页面时是所属剧集的各季） */
    var seasons by remember { mutableStateOf<List<ServerItem>>(emptyList()) }
    var season by remember { mutableStateOf<String?>(null) }
    var episodes by remember { mutableStateOf<List<ServerItem>?>(null) }

    LaunchedEffect(serverId, ratingKey, reload) {
        error = null
        val plex = lib.plex(serverId) ?: run { error = "找不到这个媒体服务器"; return@LaunchedEffect }
        try {
            val it = plex.item(ratingKey) ?: throw IllegalStateException("服务器上没有这个条目")
            item = it
            when (it.type) {
                "show" -> {
                    seasons = plex.children(ratingKey)
                    // 默认选第一个还有没看完的季
                    season = season ?: (seasons.firstOrNull { s -> s.unwatchedLeaves > 0 && (s.index ?: 0) > 0 } ?: seasons.firstOrNull())?.ratingKey
                }
                "season" -> {
                    seasons = it.parentRatingKey?.let { p -> plex.children(p) } ?: listOf(it)
                    season = season ?: ratingKey
                }
                "artist", "album" -> episodes = plex.children(ratingKey)
                // 单集：取本季各集，播放时接着连播后面的
                "episode" -> episodes = it.parentRatingKey?.let { p -> runCatching { plex.children(p) }.getOrNull() }
            }
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }
    LaunchedEffect(season, reload) {
        val s = season ?: return@LaunchedEffect
        episodes = null
        episodes = runCatching { lib.plex(serverId)?.children(s) }.getOrNull().orEmpty()
    }

    fun toggleWatched(target: ServerItem) {
        scope.launch {
            try {
                lib.plex(serverId)?.setWatched(target.ratingKey, !target.watched)
                reload++
            } catch (e: Exception) {
                Toast.makeText(context, "操作失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun playFrom(list: List<ServerItem>, target: ServerItem) {
        val playable = list.filter { it.playUrl != null }
        val i = playable.indexOfFirst { it.ratingKey == target.ratingKey }
        if (i >= 0) play(playable.map { it.toEntry() }, i)
    }

    DetailScaffold(item?.let { it.grandparentTitle?.takeIf { _ -> it.type == "episode" } ?: it.title } ?: "", onBack) {
        Loaded(item, error, { reload++ }) { it ->
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                item { Header(it) }
                item {
                    Actions(
                        it, episodes, onPlay = { target -> playFrom(if (target.type == "episode") episodes.orEmpty().ifEmpty { listOf(target) } else listOf(target), target) },
                        onToggleWatched = { toggleWatched(it) },
                        onOpenShow = it.grandparentRatingKey?.takeIf { _ -> it.type == "episode" }?.let { rk -> { openItem(it.copy(ratingKey = rk, type = "show")) } },
                    )
                }
                if (it.summary.isNotBlank()) item { Summary(it.summary) }
                if (seasons.size > 1 || (seasons.size == 1 && it.type == "show")) item {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        seasons.forEach { s ->
                            FilterChip(
                                selected = season == s.ratingKey, onClick = { season = s.ratingKey },
                                label = { Text(s.title + if (s.unwatchedLeaves > 0) "（${s.unwatchedLeaves}）" else "") },
                            )
                        }
                    }
                }
                if (it.type in setOf("show", "season", "artist", "album")) {
                    val list = episodes
                    if (list == null) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                    else items(list, key = { e -> e.ratingKey }) { e ->
                        if (e.isFolder) ChildRow(e) { openItem(e) }
                        else EpisodeRow(e, onClick = { playFrom(list, e) }, onToggleWatched = { toggleWatched(e) })
                    }
                }
                if (it.roles.isNotEmpty()) item { Cast(it) }
                it.videoInfo?.let { info -> item { Text(info, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) } }
            }
        }
    }
}

/** 背景大图 + 海报 + 片名、年份、时长、评分、类型 */
@Composable
private fun Header(item: ServerItem) {
    Box(Modifier.fillMaxWidth().height(260.dp)) {
        NetImage(item.art, Modifier.fillMaxSize(), maxSize = 1280, fallback = null)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x33000000), MaterialTheme.colorScheme.surfaceContainerLowest))))
        Row(Modifier.align(Alignment.BottomStart).padding(horizontal = 16.dp), verticalAlignment = Alignment.Bottom) {
            val poster = if (item.type == "episode") item.showThumb else item.thumb
            NetImage(poster, Modifier.width(110.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)), maxSize = 400)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                if (item.type == "episode") Text(item.episodeLabel.orEmpty(), color = Orange, fontSize = 13.sp)
                Text(item.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        item.year?.toString(),
                        item.durationMs.takeIf { it > 0 }?.let(::minutes),
                        item.contentRating,
                        item.rating?.let { "★ %.1f".format(it) },
                        item.leafCount.takeIf { it > 0 && item.type == "show" }?.let { "${item.childCount} 季 · $it 集" },
                    ).joinToString(" · "),
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.genres.isNotEmpty()) Text(item.genres.take(4).joinToString(" / "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                if (item.directors.isNotEmpty()) Text("导演：" + item.directors.joinToString("、"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Actions(
    item: ServerItem,
    episodes: List<ServerItem>?,
    onPlay: (ServerItem) -> Unit,
    onToggleWatched: () -> Unit,
    onOpenShow: (() -> Unit)?,
) {
    // 剧集、季：播放下一集没看完的
    val target = when (item.type) {
        "show", "season" -> episodes?.firstOrNull { it.progress != null } ?: episodes?.firstOrNull { !it.watched } ?: episodes?.firstOrNull()
        "movie", "episode", "track" -> item
        else -> null
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (target?.playUrl != null) {
            Button(onClick = { onPlay(target) }, colors = ButtonDefaults.buttonColors(containerColor = Orange)) {
                Icon(AppIcons.PlayArrow, null, Modifier.size(20.dp))
                Spacer(Modifier.width(4.dp))
                val prefix = if (target !== item) (target.episodeLabel?.let { "$it  " } ?: "") else ""
                Text(prefix + if (target.progress != null) "继续播放（还剩 ${minutes(target.durationMs - target.viewOffsetMs)}）" else "播放")
            }
        }
        if (item.type !in setOf("artist", "album", "track")) {
            OutlinedButton(onClick = onToggleWatched) { Text(if (item.watched) "标为未看" else "标为已看") }
        }
        onOpenShow?.let { TextButton(onClick = it) { Text("整部剧") } }
    }
}

@Composable
private fun Summary(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Text(
        text, fontSize = 14.sp, lineHeight = 21.sp, maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** 一集：截图（带进度）、集号和标题、时长、简介 */
@Composable
private fun EpisodeRow(e: ServerItem, onClick: () -> Unit, onToggleWatched: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp)) {
        val isTrack = e.type == "track"
        Box(Modifier.width(if (isTrack) 44.dp else 150.dp).aspectRatio(if (isTrack) 1f else 16f / 9f).clip(RoundedCornerShape(4.dp))) {
            NetImage(e.thumb, Modifier.fillMaxSize(), maxSize = 400, fallback = if (isTrack) AppIcons.MusicNote else AppIcons.Movie)
            if (!isTrack) {
                WatchBadge(e)
                e.progress?.let { ProgressLine(it) }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                (e.index?.let { if (isTrack) "$it. " else "第 $it 集  " } ?: "") + e.title,
                fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    e.durationMs.takeIf { it > 0 }?.let(::minutes),
                    e.progress?.let { "看到 ${minutes(e.viewOffsetMs)}" },
                    "已看".takeIf { e.watched && !isTrack },
                ).joinToString(" · "),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (e.summary.isNotBlank() && !isTrack) Text(e.summary, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (!isTrack) OverflowMenu(listOf(MenuAction(if (e.watched) "标为未看" else "标为已看", onClick = onToggleWatched)), compact = true)
    }
}

/** 歌手页里的专辑等可以再点进去的子条目 */
@Composable
private fun ChildRow(e: ServerItem, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        NetImage(e.thumb, Modifier.size(56.dp).clip(RoundedCornerShape(4.dp)), maxSize = 200, fallback = AppIcons.MusicNote)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            e.year?.let { Text(it.toString(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** 演员：头像、名字、角色 */
@Composable
private fun Cast(item: ServerItem) {
    Text("演职员", fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(item.roles.take(30)) { r ->
            Column(Modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                NetImage(r.thumb, Modifier.size(64.dp).clip(CircleShape), maxSize = 200, fallback = AppIcons.People)
                Text(r.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                r.role?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

