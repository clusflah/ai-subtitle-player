package com.aisub.player.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aisub.player.AppSettings
import com.aisub.player.LibraryViewModel
import com.aisub.player.media.MediaEntry
import com.aisub.player.media.formatDuration
import com.aisub.player.meta.AiRecommendedItem
import com.aisub.player.meta.DiscoverItem
import com.aisub.player.meta.DiscoverMode
import com.aisub.player.meta.MusicAiRecommendAngle
import com.aisub.player.meta.MusicDiscoverCharts
import com.aisub.player.meta.MusicDiscoverRepository
import com.aisub.player.meta.MusicDiscoverSearchStore
import com.aisub.player.meta.MusicHomeSection
import com.aisub.player.meta.MusicPlatformCatalog
import com.aisub.player.meta.MusicQuickTag
import com.aisub.player.meta.MusicRecommendationEngine
import com.aisub.player.meta.PlatformSource
import com.aisub.player.meta.RecommendationEngine
import com.aisub.player.music.MusicSourceStore
import com.aisub.player.music.WebMusicLoginDialog
import com.aisub.player.music.WebMusicService
import com.aisub.player.music.WebStreamingMusic
import com.aisub.player.music.toMusicMediaEntry
import com.aisub.player.remote.OpenListClient
import com.aisub.player.remote.RemoteItem
import com.aisub.player.remote.Server
import com.aisub.player.remote.ServerType
import com.aisub.player.remote.cloud.ServerItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 发现页顶栏的「影视 / 音乐」分段切换开关：
 * 默认选中「影视」，切换后持久化保存，下次打开自动恢复上次模式。
 */
@Composable
fun DiscoverModeSwitch(
    mode: DiscoverMode,
    onSelect: (DiscoverMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val glass = LocalLiquidGlassEnabled.current
    val dark = isAppDarkTheme()
    val outerShape = RoundedCornerShape(50)
    val containerBg = when {
        glass && dark -> Color(0x28FFFFFF)
        glass -> Color(0x16000000)
        dark -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.75f)
    }
    val borderColor = if (dark) Color(0x26FFFFFF) else Color(0x18000000)

    Row(
        modifier = modifier
            .height(36.dp)
            .clip(outerShape)
            .background(containerBg)
            .border(1.dp, borderColor, outerShape)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        DiscoverMode.entries.forEach { entry ->
            val selected = mode == entry
            val itemShape = RoundedCornerShape(50)
            Row(
                modifier = Modifier
                    .height(30.dp)
                    .tvFocusRing(itemShape, color = if (selected) Color.White else Orange)
                    .clip(itemShape)
                    .background(if (selected) Orange else Color.Transparent)
                    .clickable {
                        if (!selected) onSelect(entry)
                    }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = if (entry == DiscoverMode.VIDEO) AppIcons.Movie else AppIcons.MusicNote,
                    contentDescription = null,
                    tint = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = entry.label,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/**
 * 发现页「音乐」模式主视图：
 * 展示流行热歌榜、新音乐速递、飙升趋势榜、华语与欧美热播榜、原创音乐等，并支持按设置中的音乐推荐源切换。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MusicDiscoverTab(
    settings: AppSettings = AppSettings(),
    lib: LibraryViewModel,
    listState: LazyListState,
    searchExpanded: Boolean = false,
    onSearchExpandedChange: (Boolean) -> Unit = {},
    onOpenDetail: (DiscoverItem) -> Unit,
    onOpenDetailWithQueue: (DiscoverItem, List<DiscoverItem>, Int) -> Unit = { item, _, _ -> onOpenDetail(item) },
    onOpenChart: (String) -> Unit,
    onOpenPlayWeb: (String) -> Unit,
    onOpenAiRecommend: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val wide = LocalWideLayout.current
    val tv = LocalTv.current
    val sourcePref = settings.musicDiscoverSource

    var queryInput by remember { mutableStateOf(MusicDiscoverRepository.sessionQueryInput) }
    var appliedQuery by remember { mutableStateOf(MusicDiscoverRepository.sessionAppliedQuery) }
    var reloadNonce by remember { mutableIntStateOf(0) }

    val initialSections = remember(sourcePref) { MusicDiscoverRepository.peekHomeSections(context, sourcePref) }
    var sections by remember(sourcePref) { mutableStateOf(initialSections) }
    var homeLoading by remember(sourcePref) { mutableStateOf(initialSections.isEmpty()) }
    var homeRefreshing by remember(sourcePref) { mutableStateOf(false) }
    var homeError by remember(sourcePref) { mutableStateOf<String?>(null) }

    val initialSearch = remember(appliedQuery, sourcePref) {
        MusicDiscoverRepository.peekSearchResults(appliedQuery, sourcePref)
    }
    var searchResults by remember(sourcePref) { mutableStateOf(initialSearch.orEmpty()) }
    var searchLoading by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf(MusicDiscoverSearchStore.history(context)) }

    LaunchedEffect(searchExpanded) {
        if (!searchExpanded) {
            focusManager.clearFocus()
            if (appliedQuery.isNotEmpty() || queryInput.isNotEmpty()) {
                queryInput = ""
                appliedQuery = ""
                MusicDiscoverRepository.sessionQueryInput = ""
                MusicDiscoverRepository.sessionAppliedQuery = ""
            }
        }
    }

    BackHandler(enabled = searchExpanded) {
        onSearchExpandedChange(false)
    }

    LaunchedEffect(reloadNonce, sourcePref) {
        val cached = MusicDiscoverRepository.peekHomeSections(context, sourcePref)
        if (cached.isNotEmpty() && sections.isEmpty()) {
            sections = cached
        }
        val firstAutoRefresh = reloadNonce == 0 && MusicDiscoverRepository.consumeFirstVisitAutoRefresh(sourcePref)
        if (reloadNonce == 0 && !firstAutoRefresh && cached.isNotEmpty()) {
            sections = cached
            homeLoading = false
            homeRefreshing = false
            homeError = null
            return@LaunchedEffect
        }
        homeLoading = sections.isEmpty()
        homeRefreshing = sections.isNotEmpty()
        homeError = null
        runCatching {
            withContext(Dispatchers.IO) {
                MusicDiscoverRepository.loadHomeSections(
                    forceRefresh = firstAutoRefresh || reloadNonce > 0,
                    context = context,
                    sourcePref = sourcePref,
                )
            }
        }.onSuccess { loaded ->
            if (loaded.isNotEmpty()) {
                sections = loaded
                homeError = null
            } else if (sections.isEmpty()) {
                homeError = "暂未获取到音乐榜单数据，请检查网络连接后重试"
            }
        }.onFailure { e ->
            if (sections.isEmpty()) {
                homeError = e.message ?: "音乐榜单加载失败"
            }
        }
        homeLoading = false
        homeRefreshing = false
    }

    LaunchedEffect(appliedQuery, sourcePref) {
        val q = appliedQuery.trim()
        MusicDiscoverRepository.sessionAppliedQuery = q
        if (q.isEmpty()) {
            searchResults = emptyList()
            searchError = null
            searchLoading = false
            return@LaunchedEffect
        }
        MusicDiscoverRepository.peekSearchResults(q, sourcePref)?.let { cached ->
            searchResults = cached
            searchError = null
            searchLoading = false
            return@LaunchedEffect
        }
        searchLoading = true
        searchError = null
        runCatching {
            withContext(Dispatchers.IO) {
                MusicDiscoverRepository.search(q, sourcePref = sourcePref)
            }
        }.onSuccess { list ->
            searchResults = list
        }.onFailure { e ->
            searchError = e.message ?: "音乐搜索失败"
        }
        searchLoading = false
    }

    fun submitSearch(raw: String = queryInput) {
        val q = raw.trim()
        queryInput = q
        appliedQuery = q
        MusicDiscoverRepository.sessionQueryInput = q
        MusicDiscoverRepository.sessionAppliedQuery = q
        focusManager.clearFocus()
        if (q.isNotEmpty()) {
            MusicDiscoverSearchStore.recordHistory(context, q)
            history = MusicDiscoverSearchStore.history(context)
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = 4.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (searchExpanded) {
            item(key = "music_discover_search_panel") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    val searchBarHeight = if (tv) 48.dp else 46.dp
                    CompactSearchField(
                        value = queryInput,
                        onValueChange = {
                            queryInput = it
                            MusicDiscoverRepository.sessionQueryInput = it
                            if (it.isBlank()) {
                                appliedQuery = ""
                                MusicDiscoverRepository.sessionAppliedQuery = ""
                            }
                        },
                        onSearch = { submitSearch() },
                        placeholder = "搜歌曲、歌手、专辑",
                        phoneLabel = "搜索歌曲、歌手或专辑",
                        height = searchBarHeight,
                        cornerRadius = 23.dp,
                        marqueeText = false,
                        trailingAction = {
                            val canSubmit = queryInput.isNotBlank()
                            Box(
                                Modifier
                                    .padding(end = 3.dp)
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(
                                        if (canSubmit) Orange
                                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                                    )
                                    .tvFocusRing(RoundedCornerShape(18.dp))
                                    .clickable {
                                        if (canSubmit) submitSearch()
                                    }
                                    .padding(horizontal = 14.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "搜索",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (canSubmit) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (appliedQuery.isBlank()) {
                        if (history.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        AppIcons.History,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(15.dp),
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        "搜索历史",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Row(
                                        Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .tvFocusRing(RoundedCornerShape(8.dp))
                                            .clickable {
                                                MusicDiscoverSearchStore.clearHistory(context)
                                                history = emptyList()
                                            }
                                            .padding(horizontal = 6.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        Icon(
                                            AppIcons.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(13.dp),
                                        )
                                        Text("清空", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    history.take(12).forEach { h ->
                                        val pillShape = RoundedCornerShape(50)
                                        Row(
                                            Modifier
                                                .clip(pillShape)
                                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f), pillShape)
                                                .tvFocusRing(pillShape)
                                                .clickable { submitSearch(h) }
                                                .padding(horizontal = 13.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                h,
                                                fontSize = 12.5.sp,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        val quickKw = queryInput.trim()
                        SurfaceCard(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(AppIcons.MusicNote, null, tint = Orange, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("音乐平台与 AI 找歌", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                                }
                                Text(
                                    if (quickKw.isNotEmpty()) "点击下方入口，立即在对应平台检索「$quickKw」"
                                    else "输入歌名/歌手点「搜索」查看详情与相似歌曲，或用 AI 按心情曲风荐歌",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    DiscoverQuickJumpButton(
                                        icon = AppIcons.AutoAwesome,
                                        label = "AI 智能荐歌",
                                        accent = true,
                                        onClick = { onOpenAiRecommend(quickKw) },
                                    )
                                    MusicPlatformCatalog.specs.take(6).forEach { spec ->
                                        DiscoverQuickJumpButton(
                                            icon = AppIcons.MusicNote,
                                            label = spec.canonical,
                                            onClick = {
                                                if (quickKw.isNotEmpty()) {
                                                    MusicDiscoverSearchStore.recordHistory(context, quickKw)
                                                    history = MusicDiscoverSearchStore.history(context)
                                                    onOpenPlayWeb(MusicPlatformCatalog.resolveSearchUrl(spec.canonical, quickKw))
                                                } else {
                                                    onOpenPlayWeb(MusicPlatformCatalog.resolveSearchUrl(spec.canonical, "热门音乐"))
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (appliedQuery.isNotBlank()) {
                item(key = "music_search_quick_jump") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item(key = "ai_music_jump") {
                            DiscoverQuickJumpButton(
                                icon = AppIcons.AutoAwesome,
                                label = "AI 按此风格荐歌",
                                accent = true,
                                onClick = { onOpenAiRecommend(appliedQuery) },
                            )
                        }
                        items(MusicPlatformCatalog.specs.take(6), key = { it.canonical }) { spec ->
                            DiscoverQuickJumpButton(
                                icon = AppIcons.MusicNote,
                                label = spec.canonical,
                                onClick = {
                                    onOpenPlayWeb(MusicPlatformCatalog.resolveSearchUrl(spec.canonical, appliedQuery))
                                },
                            )
                        }
                    }
                }

                when {
                    searchLoading -> {
                        item(key = "music_search_loading") {
                            Box(Modifier.fillMaxWidth().padding(36.dp), contentAlignment = Alignment.Center) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    CircularProgressIndicator(Modifier.size(28.dp), color = Orange, strokeWidth = 2.6.dp)
                                    Text(
                                        "正在搜索「$appliedQuery」…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    searchError != null && searchResults.isEmpty() -> {
                        item(key = "music_search_error") {
                            EmptyState("音乐搜索失败：$searchError\n可点击上方音乐平台直接检索", icon = AppIcons.MusicNote)
                        }
                    }
                    searchResults.isEmpty() -> {
                        item(key = "music_search_empty") {
                            EmptyState("暂未搜到「$appliedQuery」\n可点上方平台按钮前往检索，或让 AI 推荐相似音乐", icon = AppIcons.MusicNote)
                        }
                    }
                    else -> {
                        item(key = "music_search_count") {
                            Text(
                                "找到 ${searchResults.size} 首相关歌曲",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 14.dp),
                            )
                        }
                        val cols = if (wide || tv) 5 else 3
                        itemsIndexed(searchResults.chunked(cols), key = { _, row -> row.first().id }) { rowIdx, row ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                for (i in 0 until cols) {
                                    val song = row.getOrNull(i)
                                    if (song != null) {
                                        val fullIdx = rowIdx * cols + i
                                        MusicSquareCard(
                                            item = song,
                                            width = null,
                                            modifier = Modifier.weight(1f),
                                            onClick = { onOpenDetailWithQueue(song, searchResults, fullIdx) },
                                        )
                                    } else {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // 默认浏览态：顶部热门音乐分类榜快捷入口 + 刷新 / 全部榜单
            item(key = "music_quick_tags") {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (sourcePref == com.aisub.player.MusicDiscoverSource.AUTO) {
                                "音乐风向榜"
                            } else {
                                "音乐风向榜 · ${sourcePref.shortLabel}"
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .tvFocusRing(RoundedCornerShape(6.dp))
                                    .clickable {
                                        if (!homeRefreshing) {
                                            MusicDiscoverRepository.clearCache()
                                            reloadNonce++
                                        }
                                    }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Icon(
                                    AppIcons.Refresh,
                                    contentDescription = "刷新音乐榜",
                                    tint = if (homeRefreshing) Orange else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(13.dp),
                                )
                                Text(
                                    if (homeRefreshing) "刷新中…" else "刷新",
                                    fontSize = 12.sp,
                                    color = if (homeRefreshing) Orange else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = "全部榜单 →",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = Orange,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .tvFocusRing(RoundedCornerShape(6.dp))
                                    .clickable { onOpenChart(MusicDiscoverCharts.allCharts.first().id) }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }
                    }
                    LazyRow(
                        state = lib.listState("discover.music.tags"),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        items(MusicDiscoverCharts.quickTags, key = { it.chartId }) { tag ->
                            MusicQuickTagCard(
                                tag = tag,
                                onClick = { onOpenChart(tag.chartId) },
                            )
                        }
                    }
                }
            }

            when {
                homeLoading -> {
                    item(key = "music_home_loading") {
                        Box(Modifier.fillMaxWidth().padding(36.dp), contentAlignment = Alignment.Center) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CircularProgressIndicator(Modifier.size(28.dp), color = Orange)
                                Text(
                                    "正在加载流行榜单与新音乐…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                homeError != null && sections.isEmpty() -> {
                    item(key = "music_home_error") {
                        Column(
                            Modifier.fillMaxWidth().padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                homeError!!,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = { MusicDiscoverRepository.clearCache(); reloadNonce++ }) {
                                Text("重新加载")
                            }
                        }
                    }
                }
                else -> {
                    val cardSize = if (tv) 146.dp else if (wide) 138.dp else 118.dp
                    items(sections, key = { it.chart.id }) { section ->
                        MusicChartRowSection(
                            section = section,
                            rowState = lib.listState("discover.music.row.${section.chart.id}"),
                            cardWidth = cardSize,
                            showRanks = section.chart.id != "music_new_express",
                            onSeeAll = { onOpenChart(section.chart.id) },
                            onOpenDetailWithQueue = { song, idx ->
                                val fuller = MusicDiscoverRepository.peekChartPage(section.chart, context, sourcePref)
                                    ?.items
                                    ?.takeIf { it.size > section.items.size }
                                val matchInFuller = fuller?.indexOfFirst { it.id == song.id }?.takeIf { it >= 0 }
                                if (fuller != null && matchInFuller != null) {
                                    onOpenDetailWithQueue(song, fuller, matchInFuller)
                                } else {
                                    onOpenDetailWithQueue(song, section.items, idx)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MusicQuickTagCard(
    tag: MusicQuickTag,
    onClick: () -> Unit,
) {
    val palette = currentVisualPalette()
    val dark = isAppDarkTheme()
    val cardBg = if (dark) Color(0xFF151922) else palette.cardTint
    val borderColor = if (dark) Color(0x24FFFFFF) else Color(0x16000000)

    Row(
        Modifier
            .tvFocusRing(RoundedCornerShape(8.dp), scale = true)
            .clip(RoundedCornerShape(8.dp))
            .background(cardBg)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(5.dp))
                .background(Color(tag.accentHex))
                .padding(horizontal = 5.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                tag.badge,
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.3.sp,
            )
        }
        Text(
            tag.name,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
private fun MusicChartRowSection(
    section: MusicHomeSection,
    rowState: LazyListState,
    cardWidth: Dp,
    showRanks: Boolean,
    onSeeAll: () -> Unit,
    onOpenDetailWithQueue: (DiscoverItem, Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    section.chart.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                )
                Text(
                    section.chart.subtitle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 1.dp),
                )
            }
            Text(
                text = "全部 →",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Orange,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .tvFocusRing(RoundedCornerShape(6.dp))
                    .clickable(onClick = onSeeAll)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(section.items, key = { idx, it -> "${section.chart.id}:$idx:${it.id}" }) { idx, item ->
                MusicSquareCard(
                    item = item,
                    width = cardWidth,
                    rank = if (showRanks) idx + 1 else null,
                    onClick = { onOpenDetailWithQueue(item, idx) },
                )
            }
        }
    }
}

/** 1:1 正方形音乐单曲封面卡片 */
@Composable
fun MusicSquareCard(
    item: DiscoverItem,
    width: Dp? = 118.dp,
    rank: Int? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .let { if (width != null) it.width(width) else it }
            .tvFocusRing(RoundedCornerShape(10.dp), scale = true)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(bottom = 2.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            NetImage(
                url = item.posterUrl,
                modifier = Modifier.fillMaxSize(),
                maxSize = 500,
                contentScale = ContentScale.Crop,
                fallback = AppIcons.MusicNote,
            )

            if (rank != null && rank <= 20) {
                val rankBg = when (rank) {
                    1 -> Color(0xFFE63946)
                    2 -> Orange
                    3 -> Color(0xFFD97706)
                    else -> GlassTokens.DarkBadgeTint
                }
                Text(
                    text = "#$rank",
                    color = Color.White,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(rankBg)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }

            if (item.durationMs > 0L) {
                Text(
                    text = formatDuration(item.durationMs),
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(5.dp)
                        .liquidGlass(
                            shape = RoundedCornerShape(4.dp),
                            style = GlassStyle.Pill,
                            tint = GlassTokens.DarkBadgeTint,
                            enableBackdrop = false,
                        )
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Text(
            text = item.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 5.dp, start = 2.dp, end = 2.dp),
        )
        val sub = item.subtitleLine()
        if (sub.isNotEmpty()) {
            Text(
                text = sub,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

/** 音乐全部榜单网格页 */
@Composable
fun MusicDiscoverChartScreen(
    initialChartId: String?,
    settings: AppSettings = AppSettings(),
    lib: LibraryViewModel? = null,
    play: ((List<MediaEntry>, Int) -> Unit)? = null,
    onBack: () -> Unit,
    onOpenDetail: (DiscoverItem) -> Unit,
    onOpenDetailWithQueue: (DiscoverItem, List<DiscoverItem>, Int) -> Unit = { item, _, _ -> onOpenDetail(item) },
    onOpenPlayWeb: (String) -> Unit = {},
    onOpenServerDetail: (serverId: String, ratingKey: String) -> Unit = { _, _ -> },
    onOpenRemote: (server: Server, path: String, title: String) -> Unit = { _, _, _ -> },
    onOpenMusicLibrary: () -> Unit = {},
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val wide = LocalWideLayout.current
    val tv = LocalTv.current
    val sourcePref = settings.musicDiscoverSource
    var chartPlayDialogOpen by remember { mutableStateOf(false) }

    val defaultId = initialChartId?.takeIf { MusicDiscoverCharts.findChart(it) != null }
        ?: MusicDiscoverCharts.allCharts.first().id
    var selectedChartId by rememberSaveable(initialChartId) { mutableStateOf(defaultId) }
    val chart = remember(selectedChartId) {
        MusicDiscoverCharts.findChart(selectedChartId) ?: MusicDiscoverCharts.allCharts.first()
    }

    val cachedPage = remember(selectedChartId, sourcePref) {
        MusicDiscoverRepository.peekChartPage(chart, context, sourcePref)
    }
    var items by remember(selectedChartId, sourcePref) { mutableStateOf(cachedPage?.items.orEmpty()) }
    var loading by remember(selectedChartId, sourcePref) { mutableStateOf(cachedPage == null) }
    var reloadNonce by remember(selectedChartId, sourcePref) { mutableIntStateOf(0) }
    var error by remember(selectedChartId, sourcePref) { mutableStateOf<String?>(null) }

    LaunchedEffect(selectedChartId, reloadNonce, sourcePref) {
        if (reloadNonce == 0 && items.isNotEmpty()) {
            loading = false
            return@LaunchedEffect
        }
        loading = items.isEmpty()
        error = null
        runCatching {
            withContext(Dispatchers.IO) {
                MusicDiscoverRepository.loadChartPage(
                    chart = chart,
                    count = 50,
                    forceRefresh = reloadNonce > 0,
                    context = context,
                    sourcePref = sourcePref,
                )
            }
        }.onSuccess { page ->
            if (page.items.isNotEmpty()) {
                items = page.items
            } else if (items.isEmpty()) {
                error = "暂未获取到该榜单歌曲，请稍后重试"
            }
        }.onFailure { e ->
            if (items.isEmpty()) error = e.message ?: "榜单加载失败"
        }
        loading = false
    }

    DetailScaffold(
        title = chart.title,
        onBack = onBack,
        actions = {
            if (items.isNotEmpty() && lib != null && play != null) {
                TextButton(
                    onClick = { chartPlayDialogOpen = true },
                    modifier = Modifier.tvFocusRing(RoundedCornerShape(8.dp)),
                ) {
                    Icon(AppIcons.PlayArrow, null, tint = Orange, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("连播榜单", color = Orange, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            IconButton(onClick = { reloadNonce++ }) {
                Icon(AppIcons.Refresh, "刷新榜单")
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(MusicDiscoverCharts.allCharts, key = { it.id }) { c ->
                    FilterChip(
                        selected = c.id == chart.id,
                        onClick = { selectedChartId = c.id },
                        label = { Text(c.title, fontSize = 12.sp) },
                    )
                }
            }

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Orange)
                }
                error != null && items.isEmpty() -> EmptyState(error!!, icon = AppIcons.MusicNote)
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(if (wide || tv) 5 else 3),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(items, key = { idx, s -> "${chart.id}:$idx:${s.id}" }) { idx, song ->
                        MusicSquareCard(
                            item = song,
                            width = null,
                            rank = if (chart.id != "music_new_express") idx + 1 else null,
                            onClick = { onOpenDetailWithQueue(song, items, idx) },
                        )
                    }
                }
            }
        }
    }

    if (chartPlayDialogOpen && items.isNotEmpty() && lib != null && play != null) {
        val libState by lib.state.collectAsStateWithLifecycle()
        val firstItem = items.first()
        val firstLocalMatches = remember(libState.audios, libState.history, firstItem.title, firstItem.artist) {
            MusicRecommendationEngine.matchLocalTracks(
                item = firstItem,
                localAudios = libState.audios,
                historyEntries = libState.history.map { it.entry },
            )
        }
        MusicDiscoverPlayDialog(
            item = firstItem,
            queueItems = items,
            queueIndex = 0,
            localMatches = firstLocalMatches,
            localAudios = libState.audios,
            historyEntries = libState.history.map { it.entry },
            lib = lib,
            servers = libState.servers,
            onDismiss = { chartPlayDialogOpen = false },
            onPlayEntries = { list, idx ->
                chartPlayDialogOpen = false
                play(list, idx)
            },
            onOpenMusicLibrary = {
                chartPlayDialogOpen = false
                onOpenMusicLibrary()
            },
            onOpenServerDetail = { sid, rk ->
                chartPlayDialogOpen = false
                onOpenServerDetail(sid, rk)
            },
            onOpenRemote = { srv, path, title ->
                chartPlayDialogOpen = false
                onOpenRemote(srv, path, title)
            },
            onOpenPlayWeb = { url ->
                chartPlayDialogOpen = false
                onOpenPlayWeb(url)
            },
            onOpenExternal = { url ->
                chartPlayDialogOpen = false
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure {
                    clipboard.setText(AnnotatedString(url))
                }
            },
        )
    }
}

/**
 * 发现页歌曲详情页：
 * 聚焦展示歌曲专辑信息与可选歌词预览，保留「跳转播放」（本机 / 远程 Plex·Navidrome·Emby 音乐库 / 主流音乐平台直达）与「相似推荐」（相似歌曲 + AI 音乐推荐）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MusicDiscoverDetailScreen(
    item: DiscoverItem,
    queueItems: List<DiscoverItem> = emptyList(),
    queueIndex: Int = 0,
    settings: AppSettings,
    lib: LibraryViewModel,
    onBack: () -> Unit,
    play: (List<MediaEntry>, Int) -> Unit,
    onOpenPlayWeb: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenServerDetail: (serverId: String, ratingKey: String) -> Unit = { _, _ -> },
    onOpenRemote: (server: Server, path: String, title: String) -> Unit = { _, _, _ -> },
    onOpenMusicLibrary: () -> Unit = {},
    onUpdateSettings: ((AppSettings) -> Unit)? = null,
    onSelectSong: (DiscoverItem) -> Unit = {},
    onSelectSongWithQueue: (DiscoverItem, List<DiscoverItem>, Int) -> Unit = { s, _, _ -> onSelectSong(s) },
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val libState by lib.state.collectAsStateWithLifecycle()
    val wide = LocalWideLayout.current
    val palette = currentVisualPalette()

    var enriched by remember(item.id) { mutableStateOf(item) }
    var lyricsText by remember(item.id) { mutableStateOf<String?>(null) }
    var lyricsExpanded by rememberSaveable(item.id) { mutableStateOf(false) }
    var playDialogOpen by remember { mutableStateOf(false) }
    var statusTip by remember { mutableStateOf<String?>(null) }

    // 后台补全缺失的专辑/时长/多平台链接与歌词摘要
    LaunchedEffect(item.id, settings.musicDiscoverSource) {
        withContext(Dispatchers.IO) {
            if (item.album.isBlank() || item.durationMs <= 0L || item.neteaseId == null || item.appleUrl == null) {
                val q = listOfNotNull(item.title, item.artist.takeIf { it.isNotBlank() }).joinToString(" ")
                val hits = runCatching {
                    MusicDiscoverRepository.search(q, 6, settings.musicDiscoverSource)
                }.getOrDefault(emptyList())
                val wantKey = MusicDiscoverRepository.songNormKey(item.title, item.artist)
                val match = hits.firstOrNull { MusicDiscoverRepository.songNormKey(it.title, it.artist) == wantKey }
                    ?: hits.firstOrNull { MusicDiscoverRepository.normMusicText(it.title) == MusicDiscoverRepository.normMusicText(item.title) }
                if (match != null) {
                    enriched = enriched.copy(
                        posterUrl = enriched.posterUrl ?: match.posterUrl,
                        backdropUrl = enriched.backdropUrl ?: match.backdropUrl ?: match.posterUrl,
                        artist = enriched.artist.ifBlank { match.artist },
                        album = enriched.album.ifBlank { match.album },
                        year = enriched.year ?: match.year,
                        durationMs = if (enriched.durationMs > 0L) enriched.durationMs else match.durationMs,
                        genres = if (enriched.genres.isNotEmpty()) enriched.genres else match.genres,
                        neteaseId = enriched.neteaseId ?: match.neteaseId,
                        appleUrl = enriched.appleUrl ?: match.appleUrl,
                        platforms = MusicDiscoverRepository.mergeMusicPlatforms(enriched.platforms, match.platforms),
                    )
                }
            }
            lyricsText = runCatching { MusicDiscoverRepository.fetchLyricsPreview(enriched) }.getOrNull()
        }
    }

    val localMatches = remember(libState.audios, libState.history, enriched.title, enriched.artist) {
        MusicRecommendationEngine.matchLocalTracks(
            item = enriched,
            localAudios = libState.audios,
            historyEntries = libState.history.map { it.entry },
        )
    }

    val recentMusicTitles = remember(libState.history) {
        libState.history.filter { it.entry.isMusic }
            .map { it.entry.displayTitle }
            .distinct()
            .take(8)
    }

    fun openExternalUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            statusTip = "没有可用的外部应用打开此链接，已复制地址"
            clipboard.setText(AnnotatedString(url))
        }
    }

    DetailScaffold(
        title = enriched.title,
        onBack = onBack,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 顶部：氛围横幅 + 1:1 专辑封面 + 歌曲元信息
            val coverUrl = enriched.posterUrl ?: enriched.backdropUrl
            Box(Modifier.fillMaxWidth()) {
                if (coverUrl != null) {
                    Box(Modifier.fillMaxWidth().height(if (wide) 244.dp else 196.dp)) {
                        NetImage(
                            url = coverUrl,
                            modifier = Modifier.fillMaxSize(),
                            maxSize = 900,
                            contentScale = ContentScale.Crop,
                            fallback = AppIcons.MusicNote,
                        )
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color(0x55000000),
                                        palette.backdropFadeColor.copy(alpha = 0.80f),
                                        palette.backdropFadeColor,
                                    ),
                                ),
                            ),
                        )
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 16.dp,
                            end = 16.dp,
                            top = if (coverUrl != null) (if (wide) 92.dp else 62.dp) else 12.dp,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    NetImage(
                        url = coverUrl,
                        modifier = Modifier
                            .size(if (wide) 140.dp else 112.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        maxSize = 600,
                        contentScale = ContentScale.Crop,
                        fallback = AppIcons.MusicNote,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            enriched.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        if (enriched.artist.isNotBlank()) {
                            Text(
                                enriched.artist,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = Orange,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        val metaLine = buildList {
                            enriched.album.takeIf { it.isNotBlank() }?.let { add("《$it》") }
                            enriched.year?.let { add("$it") }
                            if (enriched.durationMs > 0L) add(formatDuration(enriched.durationMs))
                            if (enriched.genres.isNotEmpty()) add(enriched.genres.take(2).joinToString(" / "))
                        }.joinToString(" · ")
                        if (metaLine.isNotEmpty()) {
                            Text(
                                metaLine,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (enriched.overview.isNotBlank()) {
                            Text(
                                enriched.overview,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            // 跳转播放卡片（来源与收听平台统一收进点击「跳转播放」后的二级弹窗）
            SurfaceCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(
                    Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = { playDialogOpen = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.White),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .tvFocusRing(RoundedCornerShape(12.dp), color = Color.White),
                    ) {
                        Icon(AppIcons.PlayArrow, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = when {
                                localMatches.isNotEmpty() -> "播放（本机已匹配曲目）"
                                queueItems.size > 1 -> "跳转播放（连播列表 ${queueItems.size} 首）"
                                else -> "跳转播放"
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    statusTip?.let { tip ->
                        Text(tip, style = MaterialTheme.typography.bodySmall, color = Orange)
                    }
                }
            }

            // 可选歌词预览卡片（存在歌词时展示，默认紧凑 6 行可展开）
            lyricsText?.takeIf { it.isNotBlank() }?.let { lyric ->
                val lines = remember(lyric) { lyric.lines().filter { it.isNotBlank() } }
                if (lines.isNotEmpty()) {
                    SurfaceCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { lyricsExpanded = !lyricsExpanded }
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "歌词预览",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    if (lyricsExpanded) "收起" else "展开全部",
                                    fontSize = 12.sp,
                                    color = Orange,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                            Text(
                                text = (if (lyricsExpanded) lines else lines.take(6)).joinToString("\n"),
                                style = MaterialTheme.typography.bodyMedium,
                                lineHeight = 22.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
                            )
                        }
                    }
                }
            }

            // 相似推荐模块（相似歌曲推荐 + AI 智能荐歌）
            MusicRecommendationsSection(
                item = enriched,
                localAudios = libState.audios,
                recentMusicTitles = recentMusicTitles,
                settings = settings,
                onUpdateSettings = onUpdateSettings,
                onOpenSettings = onOpenSettings,
                onSelectSong = onSelectSong,
                onSelectSongWithQueue = onSelectSongWithQueue,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }

    if (playDialogOpen) {
        MusicDiscoverPlayDialog(
            item = enriched,
            queueItems = queueItems,
            queueIndex = queueIndex,
            localMatches = localMatches,
            localAudios = libState.audios,
            historyEntries = libState.history.map { it.entry },
            lib = lib,
            servers = libState.servers,
            onDismiss = { playDialogOpen = false },
            onPlayEntries = { list, idx ->
                playDialogOpen = false
                play(list, idx)
            },
            onOpenMusicLibrary = {
                playDialogOpen = false
                onOpenMusicLibrary()
            },
            onOpenServerDetail = { sid, rk ->
                playDialogOpen = false
                onOpenServerDetail(sid, rk)
            },
            onOpenRemote = { srv, path, title ->
                playDialogOpen = false
                onOpenRemote(srv, path, title)
            },
            onOpenPlayWeb = { url ->
                playDialogOpen = false
                onOpenPlayWeb(url)
            },
            onOpenExternal = { url ->
                playDialogOpen = false
                openExternalUrl(url)
            },
        )
    }
}

private sealed interface RemoteMusicHit {
    data class TrackHit(val server: Server, val item: ServerItem, val entry: MediaEntry) : RemoteMusicHit
    data class ContainerHit(val server: Server, val item: ServerItem) : RemoteMusicHit
    data class FileHit(val server: Server, val item: RemoteItem) : RemoteMusicHit
}

/** 歌曲详情页「跳转播放」通道选择弹窗：本机音乐 / 远程音乐库（Plex、Subsonic、Emby 等） / 音乐流媒体平台直达 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MusicDiscoverPlayDialog(
    item: DiscoverItem,
    queueItems: List<DiscoverItem> = emptyList(),
    queueIndex: Int = 0,
    localMatches: List<MediaEntry>,
    localAudios: List<MediaEntry> = emptyList(),
    historyEntries: List<MediaEntry> = emptyList(),
    lib: LibraryViewModel,
    servers: List<Server>,
    onDismiss: () -> Unit,
    onPlayEntries: (List<MediaEntry>, Int) -> Unit,
    onOpenMusicLibrary: () -> Unit,
    onOpenServerDetail: (serverId: String, ratingKey: String) -> Unit,
    onOpenRemote: (server: Server, path: String, title: String) -> Unit,
    onOpenPlayWeb: (String) -> Unit,
    onOpenExternal: (String) -> Unit,
) {
    val effectiveQueueCount = queueItems.size.takeIf { it > 1 } ?: 1
    val continuousLocalQueue = remember(queueItems, queueIndex, item.id, localMatches, localAudios, historyEntries) {
        if (queueItems.size <= 1 || localMatches.isEmpty()) {
            null
        } else {
            val startIdx = queueItems.indices
                .firstOrNull { it == queueIndex && queueItems[it].id == item.id }
                ?: queueItems.indexOfFirst { it.id == item.id }.takeIf { it >= 0 }
                ?: queueIndex.coerceIn(queueItems.indices)
            val seenUris = LinkedHashSet<String>()
            val merged = ArrayList<MediaEntry>()
            val currentUriToQueueIndex = LinkedHashMap<String, Int>()
            queueItems.forEachIndexed { i, qItem ->
                if (i == startIdx) {
                    localMatches.forEach { m ->
                        if (seenUris.add(m.uri)) {
                            currentUriToQueueIndex[m.uri] = merged.size
                            merged += m
                        }
                    }
                } else {
                    val m = MusicRecommendationEngine.matchLocalTracks(qItem, localAudios, historyEntries).firstOrNull()
                    if (m != null && seenUris.add(m.uri)) {
                        merged += m
                    }
                }
            }
            if (merged.size > localMatches.size) merged to currentUriToQueueIndex else null
        }
    }
    var remoteHits by remember(item.id, servers) { mutableStateOf<List<RemoteMusicHit>>(emptyList()) }
    var searchingRemote by remember(item.id, servers) { mutableStateOf(false) }

    LaunchedEffect(item.title, item.artist, servers) {
        val searchable = servers.filter {
            MusicSourceStore.isStructuredMusicServer(it.type) || it.type == ServerType.OPENLIST
        }
        if (searchable.isEmpty()) {
            remoteHits = emptyList()
            searchingRemote = false
            return@LaunchedEffect
        }
        searchingRemote = true
        val wantTitle = MusicDiscoverRepository.normMusicText(item.title)
        val wantArtist = item.artist.split('/', '、', ',', '&', '，')
            .map(MusicDiscoverRepository::normMusicText)
            .filter { it.length >= 2 }

        val found = withContext(Dispatchers.IO) {
            coroutineScope {
                searchable.map { srv ->
                    async {
                        runCatching {
                            val client = lib.client(srv)
                            if (MusicSourceStore.isStructuredMusicServer(srv.type)) {
                                val raw = MusicSourceStore.searchServerMusic(client, item.title)
                                val tracks = raw.filter { it.playUrl != null }
                                    .sortedByDescending { s ->
                                        val st = MusicDiscoverRepository.normMusicText(s.title)
                                        val sa = MusicDiscoverRepository.normMusicText(s.grandparentTitle.orEmpty() + s.parentTitle.orEmpty())
                                        (if (st == wantTitle) 4 else if (st.contains(wantTitle)) 2 else 0) +
                                            (if (wantArtist.any { sa.contains(it) }) 3 else 0)
                                    }
                                    .take(4)
                                    .mapNotNull { s ->
                                        s.toMusicMediaEntry(srv.id, srv.name)?.let { entry ->
                                            RemoteMusicHit.TrackHit(srv, s, entry)
                                        }
                                    }
                                if (tracks.isNotEmpty()) tracks
                                else raw.filter { it.isFolder }.take(2).map { RemoteMusicHit.ContainerHit(srv, it) }
                            } else if (client is OpenListClient) {
                                client.searchVideos(item.title)
                                    .filter { it.isDir || MusicSourceStore.isAudioFileName(it.name) }
                                    .take(4)
                                    .map { RemoteMusicHit.FileHit(srv, it) }
                            } else {
                                emptyList()
                            }
                        }.getOrDefault(emptyList())
                    }
                }.awaitAll().flatten()
            }
        }
        remoteHits = found
        searchingRemote = false
    }

    val platforms = remember(item) { MusicPlatformCatalog.platformsForItem(item) }

    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("播放「${item.title}」", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 1. 本机音乐库匹配
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(AppIcons.MusicNote, null, tint = Orange, modifier = Modifier.size(18.dp))
                        Text("本机音乐", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = onOpenMusicLibrary) { Text("打开乐库", fontSize = 12.sp) }
                    }
                    if (localMatches.isEmpty()) {
                        Text(
                            "本机未扫描到同名音频，可在下方远程音乐库或音乐平台收听。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        localMatches.forEachIndexed { idx, entry ->
                            PlayOptionRow(
                                title = entry.displayTitle,
                                subtitle = if (continuousLocalQueue != null) {
                                    "${entry.folder.ifBlank { "本机音频" }} · 连播已匹配 ${continuousLocalQueue.first.size} 首"
                                } else {
                                    entry.folder.ifBlank { "本机音频" }
                                },
                                actionText = "直接播放",
                                onClick = {
                                    if (continuousLocalQueue != null) {
                                        val (q, map) = continuousLocalQueue
                                        onPlayEntries(q, map[entry.uri] ?: 0)
                                    } else {
                                        onPlayEntries(localMatches, idx)
                                    }
                                },
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // 2. 远程音乐服务器匹配（Plex / Navidrome / Subsonic / Emby / Jellyfin / OpenList）
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(AppIcons.Cloud, null, tint = Orange, modifier = Modifier.size(18.dp))
                        Text("远程音乐库", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (searchingRemote) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Orange)
                        }
                    }
                    if (remoteHits.isNotEmpty()) {
                        remoteHits.forEach { hit ->
                            when (hit) {
                                is RemoteMusicHit.TrackHit -> {
                                    val sub = listOfNotNull(
                                        "${hit.server.name} (${hit.server.type.label})",
                                        hit.item.grandparentTitle,
                                        hit.item.parentTitle,
                                    ).filter { it.isNotBlank() }.joinToString(" · ")
                                    PlayOptionRow(
                                        title = hit.item.title,
                                        subtitle = sub,
                                        actionText = "直接播放",
                                        onClick = { onPlayEntries(listOf(hit.entry), 0) },
                                    )
                                }
                                is RemoteMusicHit.ContainerHit -> {
                                    PlayOptionRow(
                                        title = hit.item.title,
                                        subtitle = "${hit.server.name} (${hit.server.type.label}) · 专辑/歌手",
                                        actionText = "查看",
                                        onClick = { onOpenServerDetail(hit.server.id, hit.item.ratingKey) },
                                    )
                                }
                                is RemoteMusicHit.FileHit -> {
                                    val fi = hit.item
                                    PlayOptionRow(
                                        title = fi.name,
                                        subtitle = "${hit.server.name} · ${fi.path}",
                                        actionText = if (!fi.isDir && fi.playUrl != null) "直接播放" else "打开目录",
                                        onClick = {
                                            if (!fi.isDir && fi.playUrl != null) {
                                                onPlayEntries(listOf(MediaEntry(fi.playUrl, fi.name, isVideo = false)), 0)
                                            } else {
                                                onOpenRemote(hit.server, fi.path, fi.name)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    } else if (!searchingRemote) {
                        Text(
                            if (servers.none { MusicSourceStore.isStructuredMusicServer(it.type) }) {
                                "尚未接入 Plex / Navidrome / Emby 等音乐库（可在「音乐 → 右上角音源」接入）。"
                            } else {
                                "已接入的远程音乐库中暂未搜到同名歌曲。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // 2.5. 流媒体账号应用内播放（Apple Music / Spotify）
                if (WebStreamingMusic.isSupportedOnPlatform) {
                    val context = LocalContext.current
                    val loggedServices by WebStreamingMusic.loggedInServices.collectAsStateWithLifecycle()
                    LaunchedEffect(context) { WebStreamingMusic.refreshAuthState(context) }
                    var pendingLoginService by remember { mutableStateOf<WebMusicService?>(null) }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(AppIcons.MusicNote, null, tint = Orange, modifier = Modifier.size(18.dp))
                            Text("应用内账号直接播放", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        }
                        Text(
                            "登录 Apple Music、Spotify、YouTube Music、网易云音乐或 QQ音乐 账号后，可直接在应用自带音乐播放页播放并同步歌词。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        WebMusicService.entries.forEach { svc ->
                            val isLogged = svc in loggedServices
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.75f))
                                    .tvFocusRing(RoundedCornerShape(10.dp))
                                    .clickable {
                                        if (isLogged) {
                                            val (entries, startIdx) = WebStreamingMusic.buildQueueFromDiscoverItems(
                                                service = svc,
                                                currentItem = item,
                                                queueItems = queueItems,
                                                preferredIndex = queueIndex,
                                            )
                                            onPlayEntries(entries, startIdx)
                                        } else {
                                            pendingLoginService = svc
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                WebMusicServiceIcon(svc, size = 26.dp)
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = svc.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                    )
                                    Text(
                                        text = when {
                                            isLogged && effectiveQueueCount > 1 -> "已登录 · 连续播放当前列表（共 $effectiveQueueCount 首）"
                                            isLogged -> "已登录 · 在应用音乐页直接播放"
                                            else -> "未登录 · 点击登录并直接播放"
                                        },
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                                Text(
                                    text = when {
                                        isLogged && effectiveQueueCount > 1 -> "连播"
                                        isLogged -> "直接播放"
                                        else -> "登录播放"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Orange,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }

                    pendingLoginService?.let { svc ->
                        WebMusicLoginDialog(
                            service = svc,
                            onDismiss = { pendingLoginService = null },
                            onLoggedIn = {
                                pendingLoginService = null
                                val (entries, startIdx) = WebStreamingMusic.buildQueueFromDiscoverItems(
                                    service = svc,
                                    currentItem = item,
                                    queueItems = queueItems,
                                    preferredIndex = queueIndex,
                                )
                                onPlayEntries(entries, startIdx)
                            },
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }

                // 3. 来源与收听平台直达播放
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(AppIcons.Live, null, tint = Orange, modifier = Modifier.size(18.dp))
                        Text("来源与收听平台", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "点平台名称在内置网页打开（支持拉起已安装的音乐 App），点右侧图标直接用系统外部打开。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    platforms.forEach { p ->
                        val targetUrl = MusicPlatformCatalog.resolvePlayUrl(p, item)
                        val accentHex = MusicPlatformCatalog.accentHex(p.name)
                        val badge = MusicPlatformCatalog.specFor(p.name)?.badge ?: p.name.take(2)
                        val hasDirectLink = p.url != null ||
                            (p.name == "网易云音乐" && !item.neteaseId.isNullOrBlank()) ||
                            (p.name == "Apple Music" && !item.appleUrl.isNullOrBlank())
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.75f))
                                .tvFocusRing(RoundedCornerShape(10.dp))
                                .clickable { onOpenPlayWeb(targetUrl) }
                                .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MusicPlatformIcon(
                                platformName = p.name,
                                accentHex = accentHex,
                                fallbackBadge = badge,
                                size = 24.dp,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = p.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (hasDirectLink) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = if (hasDirectLink) "单曲直达播放" else "按歌名与歌手检索直达",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            IconButton(
                                onClick = { onOpenExternal(targetUrl) },
                                modifier = Modifier.size(34.dp),
                            ) {
                                Icon(AppIcons.OpenInNew, "外部打开", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(17.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/** 歌曲详情页「相似推荐」区块（相似歌曲横向列表 + AI 智能音乐推荐） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MusicRecommendationsSection(
    item: DiscoverItem,
    localAudios: List<MediaEntry>,
    recentMusicTitles: List<String>,
    settings: AppSettings,
    onUpdateSettings: ((AppSettings) -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    onSelectSong: (DiscoverItem) -> Unit,
    onSelectSongWithQueue: (DiscoverItem, List<DiscoverItem>, Int) -> Unit = { s, _, _ -> onSelectSong(s) },
    modifier: Modifier = Modifier,
) {
    val tv = LocalTv.current
    val wide = LocalWideLayout.current
    val cardWidth = if (tv) 140.dp else if (wide) 132.dp else 112.dp

    var similarSongs by remember(item.id, settings.musicDiscoverSource) { mutableStateOf<List<DiscoverItem>>(emptyList()) }
    var similarLoading by remember(item.id, settings.musicDiscoverSource) { mutableStateOf(true) }
    var similarNonce by remember(item.id) { mutableIntStateOf(0) }

    LaunchedEffect(item.id, item.neteaseId, item.artist, settings.musicDiscoverSource, similarNonce) {
        similarLoading = true
        similarSongs = runCatching {
            MusicRecommendationEngine.fetchSimilarSongs(
                item = item,
                sourcePref = settings.musicDiscoverSource,
                forceRefresh = similarNonce > 0,
            )
        }.getOrDefault(emptyList())
        similarLoading = false
    }

    val resolvedChat = remember(settings) { RecommendationEngine.resolveChatTranslator(settings) }
    var selectedAngle by remember { mutableStateOf(MusicAiRecommendAngle.SIMILAR_STYLE) }
    var customPrompt by remember { mutableStateOf("") }
    var editingPrompt by remember { mutableStateOf(false) }
    var choosingModel by remember { mutableStateOf(false) }

    var aiItems by remember(item.id) { mutableStateOf<List<AiRecommendedItem>>(emptyList()) }
    var aiModelLabel by remember(item.id) { mutableStateOf(resolvedChat?.first.orEmpty()) }
    var aiLoading by remember(item.id) { mutableStateOf(false) }
    var aiError by remember(item.id) { mutableStateOf<String?>(null) }
    var aiTriggerNonce by remember(item.id) { mutableIntStateOf(if (resolvedChat != null) 1 else 0) }
    var seenTitles by remember(item.id) { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(item.id, selectedAngle, aiTriggerNonce, settings.aiRecommendProvider, settings.provider, settings.providerConfigs) {
        if (aiTriggerNonce <= 0) return@LaunchedEffect
        if (RecommendationEngine.resolveChatTranslator(settings) == null) {
            aiError = "尚未配置 AI 大模型密钥"
            return@LaunchedEffect
        }
        aiLoading = true
        aiError = null
        runCatching {
            MusicRecommendationEngine.fetchAiSongRecommendations(
                settings = settings,
                item = item,
                localAudios = localAudios,
                recentMusicTitles = recentMusicTitles,
                angle = selectedAngle,
                customRequirement = customPrompt,
                excludeTitles = if (aiTriggerNonce > 1) seenTitles else emptyList(),
                forceRefresh = aiTriggerNonce > 1,
            )
        }.onSuccess { (label, list) ->
            aiModelLabel = label
            aiItems = list
            seenTitles = (seenTitles + list.map { it.item.title }).distinct()
        }.onFailure { e ->
            aiError = e.message ?: "AI 音乐推荐失败"
        }
        aiLoading = false
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 1. 相似歌曲推荐
        SurfaceCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "相似歌曲推荐",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    if (!similarLoading) {
                        IconButton(onClick = { similarNonce++ }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                AppIcons.Refresh,
                                "刷新相似歌曲",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                when {
                    similarLoading -> {
                        Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Orange)
                        }
                    }
                    similarSongs.isEmpty() -> {
                        Text(
                            "暂未匹配到关联歌曲，可使用下方 AI 智能荐歌深度探索相似曲风。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                        )
                    }
                    else -> {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            itemsIndexed(similarSongs, key = { _, s -> s.id }) { idx, sim ->
                                MusicSquareCard(
                                    item = sim,
                                    width = cardWidth,
                                    onClick = { onSelectSongWithQueue(sim, similarSongs, idx) },
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. AI 智能荐歌
        SurfaceCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(AppIcons.AutoAwesome, null, tint = Orange, modifier = Modifier.size(19.dp))
                    Column(Modifier.weight(1f)) {
                        Text("AI 相似曲风推荐", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (aiModelLabel.isNotBlank()) {
                            Text(
                                aiModelLabel,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    TextButton(
                        onClick = { editingPrompt = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(if (customPrompt.isBlank()) "定制口味" else "已设口味", fontSize = 12.sp)
                    }
                    if (onUpdateSettings != null) {
                        IconButton(onClick = { choosingModel = true }, modifier = Modifier.size(30.dp)) {
                            Icon(AppIcons.Settings, "切换模型", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (resolvedChat != null) {
                        IconButton(
                            onClick = { if (!aiLoading) aiTriggerNonce++ },
                            enabled = !aiLoading,
                            modifier = Modifier.size(30.dp),
                        ) {
                            Icon(AppIcons.Refresh, "换一批", modifier = Modifier.size(17.dp), tint = Orange)
                        }
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MusicAiRecommendAngle.entries.forEach { angle ->
                        FilterChip(
                            selected = selectedAngle == angle,
                            onClick = {
                                selectedAngle = angle
                                if (aiTriggerNonce == 0 && resolvedChat != null) aiTriggerNonce = 1
                            },
                            label = { Text(angle.label, fontSize = 12.sp) },
                        )
                    }
                }

                when {
                    resolvedChat == null -> {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                "配置任意大模型（DeepSeek / Gemini / 通义千问 / 豆包 / 本地 Ollama）即可按编曲氛围定制推荐歌曲。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Button(
                                onClick = {
                                    if (onUpdateSettings != null) choosingModel = true
                                    else onOpenSettings?.invoke()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.White),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text("配置 AI", fontSize = 12.sp)
                            }
                        }
                    }
                    aiLoading -> {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Orange)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "AI 正在分析《${item.title}》的曲风并挑选单曲…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    aiError != null && aiItems.isEmpty() -> {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                aiError!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(onClick = { aiTriggerNonce++ }) {
                                Text("重试", fontSize = 12.sp)
                            }
                        }
                    }
                    aiItems.isEmpty() -> {
                        Button(
                            onClick = { aiTriggerNonce++ },
                            colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.White),
                        ) {
                            Icon(AppIcons.AutoAwesome, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("生成 AI 相似曲风推荐")
                        }
                    }
                    else -> {
                        val aiQueue = remember(aiItems) { aiItems.map { it.item } }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            itemsIndexed(aiItems, key = { _, r -> r.item.id + r.reason.hashCode() }) { idx, rec ->
                                MusicAiSongCard(
                                    rec = rec,
                                    width = if (wide || tv) 262.dp else 232.dp,
                                    onClick = { onSelectSongWithQueue(rec.item, aiQueue, idx) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (editingPrompt) {
        TextInputDialog(
            title = "定制 AI 荐歌口味",
            label = "例如：女声、适合深夜独处、日语或英文、吉他编曲",
            initial = customPrompt,
            confirm = "应用并推荐",
            onDismiss = { editingPrompt = false },
            onConfirm = { input ->
                customPrompt = input
                editingPrompt = false
                if (RecommendationEngine.resolveChatTranslator(settings) != null) aiTriggerNonce++
            },
        )
    }

    if (choosingModel && onUpdateSettings != null) {
        TranslateModelDialog(
            settings = settings,
            title = "选择 AI 推荐模型",
            onDismiss = { choosingModel = false },
            forAiRecommend = true,
        ) { updated ->
            onUpdateSettings(updated)
            choosingModel = false
            aiTriggerNonce++
        }
    }
}

@Composable
private fun MusicAiSongCard(
    rec: AiRecommendedItem,
    width: Dp,
    onClick: () -> Unit,
) {
    val item = rec.item
    Row(
        Modifier
            .width(width)
            .tvFocusRing(RoundedCornerShape(12.dp), scale = true)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.85f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .size(68.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            NetImage(
                url = item.posterUrl,
                modifier = Modifier.fillMaxSize(),
                maxSize = 320,
                contentScale = ContentScale.Crop,
                fallback = AppIcons.MusicNote,
            )
            rec.inLibraryName?.let {
                Text(
                    "本机可播",
                    color = Color.White,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(3.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Orange)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                item.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildList {
                    if (item.artist.isNotBlank()) add(item.artist)
                    item.year?.let { add(it.toString()) }
                }.joinToString(" · ").ifBlank { "精选单曲" },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                rec.reason,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 音乐发现页「AI 推荐音乐」独立交互页面 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MusicDiscoverAiRecommendScreen(
    initialPrompt: String = "",
    settings: AppSettings,
    lib: LibraryViewModel,
    onBack: () -> Unit,
    onOpenDetail: (DiscoverItem) -> Unit,
    onOpenDetailWithQueue: (DiscoverItem, List<DiscoverItem>, Int) -> Unit = { item, _, _ -> onOpenDetail(item) },
    onOpenSettings: () -> Unit,
    onUpdateSettings: ((AppSettings) -> Unit)? = null,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val libState by lib.state.collectAsStateWithLifecycle()

    val resolvedChat = remember(settings.aiRecommendProvider, settings.provider, settings.providerConfigs) {
        RecommendationEngine.resolveChatTranslator(settings)
    }

    var promptInput by rememberSaveable {
        mutableStateOf(initialPrompt.trim().ifEmpty { MusicDiscoverSearchStore.sessionAiPrompt })
    }
    var results by remember { mutableStateOf(MusicDiscoverSearchStore.sessionAiResults) }
    var modelLabel by remember {
        mutableStateOf(MusicDiscoverSearchStore.sessionAiModelLabel.ifBlank { resolvedChat?.first.orEmpty() })
    }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf(MusicDiscoverSearchStore.aiHistory(context)) }
    var choosingModel by remember { mutableStateOf(false) }

    val presets = remember {
        listOf(
            "适合深夜开车听的复古 City Pop 或 R&B",
            "旋律抓耳、前奏一响就很惊艳的华语流行经典",
            "写代码或专注阅读时听的轻快爵士嘻哈与独立电子",
            "雨天窗边适合安静听的治愈系民谣与温柔女声",
            "燃向高能的日系摇滚与动画经典主题曲",
            "90-00年代百听不厌的粤语与华语金曲",
        )
    }

    val recentMusicTitles = remember(libState.history) {
        libState.history.filter { it.entry.isMusic }.map { it.entry.displayTitle }.distinct().take(8)
    }

    fun runRecommend(prompt: String = promptInput, loadAnotherBatch: Boolean = false) {
        val clean = prompt.trim()
        if (clean.isEmpty()) {
            error = "请先描述你想听的音乐风格、心境或场景"
            return
        }
        if (RecommendationEngine.resolveChatTranslator(settings) == null) {
            error = "尚未配置 AI 大模型密钥，请先点击右上角选择或配置模型"
            return
        }
        promptInput = clean
        MusicDiscoverSearchStore.sessionAiPrompt = clean
        MusicDiscoverSearchStore.recordAiHistory(context, clean)
        history = MusicDiscoverSearchStore.aiHistory(context)
        focusManager.clearFocus()

        scope.launch {
            loading = true
            error = null
            val excluded = if (loadAnotherBatch) MusicDiscoverSearchStore.sessionAiSeenTitles else emptyList()
            runCatching {
                MusicRecommendationEngine.fetchAiSongsByDescription(
                    settings = settings,
                    requirement = clean,
                    localAudios = libState.audios,
                    recentMusicTitles = recentMusicTitles,
                    excludeTitles = excluded,
                    forceRefresh = loadAnotherBatch,
                )
            }.onSuccess { (label, list) ->
                modelLabel = label
                results = list
                MusicDiscoverSearchStore.sessionAiModelLabel = label
                MusicDiscoverSearchStore.sessionAiResults = list
                MusicDiscoverSearchStore.sessionAiSeenTitles = if (loadAnotherBatch) {
                    (MusicDiscoverSearchStore.sessionAiSeenTitles + list.map { it.item.title }).distinct()
                } else {
                    list.map { it.item.title }
                }
            }.onFailure { e ->
                error = e.message ?: "AI 荐歌请求失败"
            }
            loading = false
        }
    }

    LaunchedEffect(initialPrompt) {
        val init = initialPrompt.trim()
        if (init.isNotEmpty() &&
            (results.isEmpty() || !init.equals(MusicDiscoverSearchStore.sessionAiPrompt, ignoreCase = true)) &&
            resolvedChat != null
        ) {
            runRecommend(init, loadAnotherBatch = false)
        }
    }

    DetailScaffold(
        title = "AI 推荐音乐",
        onBack = onBack,
        actions = {
            if (onUpdateSettings != null) {
                IconButton(onClick = { choosingModel = true }) {
                    Icon(AppIcons.Settings, "切换推荐模型")
                }
            }
        },
    ) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "music_ai_input") {
                SurfaceCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CompactSearchField(
                            value = promptInput,
                            onValueChange = { promptInput = it },
                            onSearch = { runRecommend(promptInput, false) },
                            placeholder = "描述想听的曲风、心情、场景或类似歌手…",
                            phoneLabel = "描述想听的音乐",
                            height = 46.dp,
                            cornerRadius = 14.dp,
                            marqueeText = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = if (modelLabel.isNotBlank()) "模型：$modelLabel" else "尚未配置 AI 模型",
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (results.isNotEmpty() && !loading) {
                                OutlinedButton(
                                    onClick = { runRecommend(promptInput, loadAnotherBatch = true) },
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                ) {
                                    Icon(AppIcons.Refresh, null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("换一批", fontSize = 12.5.sp)
                                }
                            }
                            Button(
                                onClick = {
                                    if (resolvedChat == null) {
                                        if (onUpdateSettings != null) choosingModel = true else onOpenSettings()
                                    } else {
                                        runRecommend(promptInput, loadAnotherBatch = false)
                                    }
                                },
                                enabled = !loading,
                                colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.White),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            ) {
                                Icon(AppIcons.AutoAwesome, null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (resolvedChat == null) "配置模型" else "开始推荐", fontSize = 12.5.sp)
                            }
                        }
                    }
                }
            }

            if (results.isEmpty() && !loading) {
                item(key = "music_ai_presets") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "试试这样描述",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            presets.forEach { p ->
                                val shape = RoundedCornerShape(10.dp)
                                Text(
                                    text = p,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .clip(shape)
                                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), shape)
                                        .tvFocusRing(shape)
                                        .clickable { runRecommend(p, false) }
                                        .padding(horizontal = 11.dp, vertical = 7.dp),
                                )
                            }
                        }
                    }
                }
            }

            if (history.isNotEmpty() && results.isEmpty() && !loading) {
                item(key = "music_ai_history") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "历史荐歌记录",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                MusicDiscoverSearchStore.clearAiHistory(context)
                                history = emptyList()
                            }) {
                                Text("清空", fontSize = 12.sp)
                            }
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            history.forEach { h ->
                                val shape = RoundedCornerShape(50)
                                Text(
                                    text = h,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .clip(shape)
                                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), shape)
                                        .tvFocusRing(shape)
                                        .clickable { runRecommend(h, false) }
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }
            }

            error?.let { err ->
                item(key = "music_ai_error") {
                    SurfaceCard(Modifier.fillMaxWidth()) {
                        Text(
                            err,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
            }

            if (loading) {
                item(key = "music_ai_loading") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(28.dp), color = Orange, strokeWidth = 2.6.dp)
                            Text("AI 正在为你挑选契合心境的单曲…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                val recSongs = results.map { it.item }
                itemsIndexed(results, key = { _, r -> r.item.id + r.reason.hashCode() }) { idx, rec ->
                    val song = rec.item
                    SurfaceCard(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .tvFocusRing(RoundedCornerShape(14.dp))
                                .clickable { onOpenDetailWithQueue(song, recSongs, idx) }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(76.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            ) {
                                NetImage(
                                    url = song.posterUrl,
                                    modifier = Modifier.fillMaxSize(),
                                    maxSize = 360,
                                    contentScale = ContentScale.Crop,
                                    fallback = AppIcons.MusicNote,
                                )
                                rec.inLibraryName?.let {
                                    Text(
                                        "本机可播",
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .padding(4.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Orange)
                                            .padding(horizontal = 4.dp, vertical = 1.dp),
                                    )
                                }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(song.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val sub = song.subtitleLine()
                                if (sub.isNotEmpty()) {
                                    Text(sub, style = MaterialTheme.typography.bodySmall, color = Orange, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text(rec.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(AppIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    if (choosingModel && onUpdateSettings != null) {
        TranslateModelDialog(
            settings = settings,
            title = "选择 AI 推荐模型",
            onDismiss = { choosingModel = false },
            forAiRecommend = true,
        ) { updated ->
            onUpdateSettings(updated)
            choosingModel = false
        }
    }
}
