package com.voxmusic.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxmusic.app.AppSettings
import com.voxmusic.app.MusicDiscoverSource
import com.voxmusic.app.MusicViewModel
import com.voxmusic.app.media.formatDuration
import com.voxmusic.app.meta.AiRecommendedItem
import com.voxmusic.app.meta.DiscoverItem
import com.voxmusic.app.meta.MusicAiRecommendAngle
import com.voxmusic.app.meta.MusicDiscoverCharts
import com.voxmusic.app.meta.MusicDiscoverRepository
import com.voxmusic.app.meta.MusicDiscoverSearchStore
import com.voxmusic.app.meta.MusicHomeSection
import com.voxmusic.app.meta.MusicQuickTag
import com.voxmusic.app.meta.MusicRecommendationEngine
import com.voxmusic.app.meta.RecommendationEngine
import com.voxmusic.app.music.MusicSourceStore
import com.voxmusic.app.music.SmartMusicSourceResolver
import com.voxmusic.app.music.WebStreamingMusic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 发现页主视图：
 * 展示流行热歌榜、新音乐速递、飙升趋势榜、华语与欧美热播榜、Billboard、Oricon、K-Pop 等。
 * 点击榜单「播放」或直接点击歌曲封面卡片，无需选源，自动智能选择已登录的 Apple Music / Spotify 源开播；
 * 长按卡片或点击信息按钮可进入歌曲详情、相似歌曲与 AI 荐歌。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MusicDiscoverTab(
    vm: MusicViewModel,
    bottomPadding: Dp = 88.dp,
    embeddedInPlayer: Boolean = false,
    onSmartPlay: (DiscoverItem, List<DiscoverItem>, Int) -> Unit,
    onOpenDetail: (DiscoverItem, List<DiscoverItem>, Int) -> Unit,
    onOpenChart: (String) -> Unit,
    onOpenAiRecommend: (String) -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val wide = LocalWideLayout.current
    val tv = LocalTv.current
    val uiState by vm.state.collectAsStateWithLifecycle()
    val settings = uiState.settings
    val sourcePref = settings.musicDiscoverSource
    val loggedInServices by WebStreamingMusic.loggedInServices.collectAsStateWithLifecycle()
    val activeService by remember(context) { MusicSourceStore.ensureInit(context) }.collectAsStateWithLifecycle()
    val smartTarget = remember(loggedInServices, activeService) {
        SmartMusicSourceResolver.resolve(loggedInServices, activeService)
    }

    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var queryInput by remember { mutableStateOf(MusicDiscoverRepository.sessionQueryInput) }
    var appliedQuery by remember { mutableStateOf(MusicDiscoverRepository.sessionAppliedQuery) }
    var reloadNonce by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    val initialSections = remember(sourcePref) { MusicDiscoverRepository.peekHomeSections(context, sourcePref) }
    var sections by remember(sourcePref) { mutableStateOf(initialSections) }
    var homeLoading by remember(sourcePref) { mutableStateOf(initialSections.isEmpty()) }
    var homeRefreshing by remember(sourcePref) { mutableStateOf(false) }
    var homeError by remember(sourcePref) { mutableStateOf<String?>(null) }

    val initialSearch = remember(appliedQuery, sourcePref, loggedInServices) {
        WebStreamingMusic.peekDiscoverSearchMerged(appliedQuery, sourcePref, loggedInServices)
            ?: MusicDiscoverRepository.peekSearchResults(appliedQuery, sourcePref)
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

    BackHandler(enabled = searchExpanded) { searchExpanded = false }

    LaunchedEffect(reloadNonce, sourcePref) {
        val cached = MusicDiscoverRepository.peekHomeSections(context, sourcePref)
        if (cached.isNotEmpty() && sections.isEmpty()) sections = cached
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
            if (sections.isEmpty()) homeError = e.message ?: "音乐榜单加载失败"
        }
        homeLoading = false
        homeRefreshing = false
    }

    LaunchedEffect(appliedQuery, sourcePref, loggedInServices) {
        val q = appliedQuery.trim()
        MusicDiscoverRepository.sessionAppliedQuery = q
        if (q.isEmpty()) {
            searchResults = emptyList()
            searchError = null
            searchLoading = false
            return@LaunchedEffect
        }
        WebStreamingMusic.peekDiscoverSearchMerged(q, sourcePref, loggedInServices)?.let { cached ->
            searchResults = cached
            searchError = null
            searchLoading = false
            return@LaunchedEffect
        }
        searchLoading = true
        searchError = null
        runCatching {
            WebStreamingMusic.searchDiscoverMerged(context, q, sourcePref = sourcePref)
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

    val playerAccent = LocalPlayerAccentColor.current
    val tokens = playerSurfaceTokens(isAppDarkTheme())
    Column(
        Modifier
            .fillMaxSize()
            .let { if (embeddedInPlayer) it else it.statusBarsPadding() },
    ) {
        // 顶栏：嵌入播放器时移除重复的「直连」徽章（已合并至顶栏右侧统一音源微胶囊），保留极轻盈操作条
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = if (embeddedInPlayer) 4.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!embeddedInPlayer) {
                Text(
                    text = "发现",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (smartTarget != null) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(tokens.pillBg)
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        WebMusicServiceIcon(smartTarget, size = 14.dp)
                        Text(
                            text = smartTarget.label,
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            } else {
                // 嵌入播放页时左侧仅放极简无框刷新微按钮，释放首屏呼吸空间（双态自适应）
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable {
                            if (!homeRefreshing) {
                                MusicDiscoverRepository.clearCache()
                                reloadNonce++
                            }
                        }
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        AppIcons.Refresh,
                        "刷新榜单",
                        tint = if (homeRefreshing) playerAccent else tokens.inkSecondary,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        if (homeRefreshing) "更新中" else "风向榜",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (homeRefreshing) playerAccent else tokens.inkSecondary,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            DiscoverQuickJumpButton(
                icon = AppIcons.AutoAwesome,
                label = "AI 荐歌",
                accent = true,
                onClick = { onOpenAiRecommend("") },
            )
            IconButton(
                onClick = { searchExpanded = !searchExpanded },
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    if (searchExpanded) AppIcons.Close else AppIcons.Search,
                    contentDescription = if (searchExpanded) "关闭搜索" else "搜索",
                    tint = if (embeddedInPlayer) tokens.inkPrimary.copy(alpha = 0.84f) else MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 10.dp, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .let { if (embeddedInPlayer) it.verticalEdgeFade(topFade = 28.dp, bottomFade = 36.dp) else it },
        ) {
            if (searchExpanded) {
                item(key = "music_discover_search_panel") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
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
                            placeholder = "搜歌曲、歌手、专辑，点封面即播",
                            phoneLabel = "搜索歌曲、歌手或专辑",
                            height = 46.dp,
                            cornerRadius = 23.dp,
                            marqueeText = false,
                            trailingAction = {
                                val canSubmit = queryInput.isNotBlank()
                                Box(
                                    Modifier
                                        .padding(end = 3.dp)
                                        .height(36.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(if (canSubmit) Orange else MaterialTheme.colorScheme.surfaceContainerHighest)
                                        .clickable { if (canSubmit) submitSearch() }
                                        .padding(horizontal = 14.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "搜索",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (canSubmit) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )

                        if (appliedQuery.isBlank() && history.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(AppIcons.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text("搜索历史", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                    Text(
                                        "清空",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.clickable {
                                            MusicDiscoverSearchStore.clearHistory(context)
                                            history = emptyList()
                                        }.padding(4.dp),
                                    )
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    history.take(12).forEach { h ->
                                        val pillShape = RoundedCornerShape(50)
                                        Text(
                                            h,
                                            fontSize = 12.5.sp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier
                                                .clip(pillShape)
                                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f), pillShape)
                                                .clickable { submitSearch(h) }
                                                .padding(horizontal = 13.dp, vertical = 6.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (appliedQuery.isNotBlank()) {
                    when {
                        searchLoading -> item(key = "music_search_loading") {
                            Box(Modifier.fillMaxWidth().padding(36.dp), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    CircularProgressIndicator(Modifier.size(28.dp), color = Orange, strokeWidth = 2.6.dp)
                                    Text("正在搜索「$appliedQuery」…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        searchError != null && searchResults.isEmpty() -> item(key = "music_search_error") {
                            EmptyState("音乐搜索失败：$searchError")
                        }
                        searchResults.isEmpty() -> item(key = "music_search_empty") {
                            EmptyState("暂未搜到「$appliedQuery」，试试换个关键词或使用 AI 荐歌")
                        }
                        else -> {
                            item(key = "music_search_count") {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "找到 ${searchResults.size} 首歌曲 · 点击直接播放",
                                        fontSize = 12.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                    DiscoverQuickJumpButton(
                                        icon = AppIcons.AutoAwesome,
                                        label = "AI 按此风格荐歌",
                                        accent = true,
                                        onClick = { onOpenAiRecommend(appliedQuery) },
                                    )
                                }
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
                                                onClick = { onSmartPlay(song, searchResults, fullIdx) },
                                                onDetail = { onOpenDetail(song, searchResults, fullIdx) },
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
                // 快捷榜单标签行（克制微磨砂透明胶囊 + 单色极简线性小图标，收敛所有撞色）
                item(key = "music_quick_tags") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!embeddedInPlayer) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = if (sourcePref == MusicDiscoverSource.AUTO) "热门风向榜" else "热门风向榜 · ${sourcePref.shortLabel}",
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
                                            "刷新音乐榜",
                                            tint = if (homeRefreshing) playerAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(13.dp),
                                        )
                                        Text(
                                            if (homeRefreshing) "刷新中…" else "刷新",
                                            fontSize = 12.sp,
                                            color = if (homeRefreshing) playerAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        text = "全部榜单 ›",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = playerAccent,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { onOpenChart(MusicDiscoverCharts.allCharts.first().id) }
                                            .padding(horizontal = 4.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            items(MusicDiscoverCharts.quickTags, key = { it.chartId }) { tag ->
                                MusicQuickTagCard(
                                    tag = tag,
                                    embeddedInPlayer = embeddedInPlayer,
                                    onClick = { onOpenChart(tag.chartId) },
                                )
                            }
                        }
                    }
                }

                when {
                    homeLoading -> item(key = "music_home_loading") {
                        Box(Modifier.fillMaxWidth().padding(36.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(Modifier.size(28.dp), color = Orange)
                                Text("正在加载流行榜单与新音乐…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    homeError != null && sections.isEmpty() -> item(key = "music_home_error") {
                        Column(
                            Modifier.fillMaxWidth().padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(homeError!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = { MusicDiscoverRepository.clearCache(); reloadNonce++ }) {
                                Text("重新加载")
                            }
                        }
                    }
                    else -> {
                        val cardSize = if (tv) 146.dp else if (wide) 138.dp else 118.dp
                        items(sections, key = { it.chart.id }) { section ->
                            MusicChartRowSection(
                                section = section,
                                cardWidth = cardSize,
                                showRanks = section.chart.id != "music_new_express",
                                onPlayChart = {
                                    val fuller = MusicDiscoverRepository.peekChartPage(section.chart, context, sourcePref)
                                        ?.items
                                        ?.takeIf { it.size > section.items.size }
                                        ?: section.items
                                    fuller.firstOrNull()?.let { first -> onSmartPlay(first, fuller, 0) }
                                },
                                onSeeAll = { onOpenChart(section.chart.id) },
                                onPlaySong = { song, idx ->
                                    val fuller = MusicDiscoverRepository.peekChartPage(section.chart, context, sourcePref)
                                        ?.items
                                        ?.takeIf { it.size > section.items.size }
                                    val matchIdx = fuller?.indexOfFirst { it.id == song.id }?.takeIf { it >= 0 }
                                    if (fuller != null && matchIdx != null) {
                                        onSmartPlay(song, fuller, matchIdx)
                                    } else {
                                        onSmartPlay(song, section.items, idx)
                                    }
                                },
                                onOpenDetail = { song, idx ->
                                    onOpenDetail(song, section.items, idx)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MusicQuickTagCard(
    tag: MusicQuickTag,
    embeddedInPlayer: Boolean = false,
    onClick: () -> Unit,
) {
    val tokens = playerSurfaceTokens(isAppDarkTheme())
    val cardBg = if (tokens.isDark) tokens.pillBg else Color.White.copy(alpha = 0.66f)
    val icon = when (tag.chartId) {
        "music_hot" -> AppIcons.LocalFire
        "music_new_express" -> AppIcons.AutoAwesome
        "music_soar" -> AppIcons.TrendingUp
        "music_apple_tw" -> AppIcons.MusicNote
        else -> AppIcons.Web
    }
    val contentTint = tokens.inkPrimary.copy(alpha = 0.86f)

    // 去除高饱和红橙紫实心色块与 1px 描边：微磨砂透明胶囊 + 单色极简线性小图标（双态自适应）
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(cardBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = contentTint.copy(alpha = 0.72f),
            modifier = Modifier.size(13.dp),
        )
        Text(
            tag.name,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            color = contentTint,
            maxLines = 1,
        )
    }
}

@Composable
private fun MusicChartRowSection(
    section: MusicHomeSection,
    cardWidth: Dp,
    showRanks: Boolean,
    onPlayChart: () -> Unit,
    onSeeAll: () -> Unit,
    onPlaySong: (DiscoverItem, Int) -> Unit,
    onOpenDetail: (DiscoverItem, Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 杂志感可点击主标题行：去掉重复的橙色「▶ 播放」胶囊与「全部 →」，标题本身可点击进入完整榜单
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onSeeAll)
                .padding(vertical = 2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    section.chart.title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.2).sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                )
                Spacer(Modifier.width(2.dp))
                Icon(
                    AppIcons.ChevronRight,
                    contentDescription = "查看全部",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                section.chart.subtitle,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(section.items, key = { idx, it -> "${section.chart.id}:$idx:${it.id}" }) { idx, item ->
                MusicSquareCard(
                    item = item,
                    width = cardWidth,
                    rank = if (showRanks) idx + 1 else null,
                    onClick = { onPlaySong(item, idx) },
                    onDetail = { onOpenDetail(item, idx) },
                    // 一键连播精简为首张封面右下角的半透明毛玻璃小圆钮
                    onQuickPlayAll = if (idx == 0) onPlayChart else null,
                )
            }
        }
    }
}

/**
 * 1:1 正方形单曲封面卡片：
 * - 单击卡片：直接智能选择已登录源开始播放；
 * - 长按卡片或点击右上角「···」：打开歌曲详情（歌词预览、相似歌曲、AI 荐歌）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MusicSquareCard(
    item: DiscoverItem,
    width: Dp? = 118.dp,
    rank: Int? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onDetail: (() -> Unit)? = null,
    onQuickPlayAll: (() -> Unit)? = null,
) {
    val tokens = playerSurfaceTokens(isAppDarkTheme())
    Column(
        modifier
            .let { if (width != null) it.width(width) else it }
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onDetail,
            )
            .padding(bottom = 2.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(tokens.pillBg),
        ) {
            NetImage(
                url = item.posterUrl,
                modifier = Modifier.fillMaxSize(),
                maxSize = 500,
                contentScale = ContentScale.Crop,
                fallback = AppIcons.MusicNote,
            )

            // 克制毛玻璃排名角标：去除红橙色贴片，采用半透明暗玻璃底 + 纯白粗斜体几何数字
            if (rank != null && rank <= 20) {
                val badgeShape = RoundedCornerShape(7.dp)
                Text(
                    text = if (rank < 10) "0$rank" else "$rank",
                    color = if (rank <= 3) Color.White else Color.White.copy(alpha = 0.82f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontStyle = FontStyle.Italic,
                    letterSpacing = (-0.4).sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(badgeShape)
                        .background(Color(0x66080A0F))
                        .border(0.5.dp, Color.White.copy(alpha = if (rank <= 3) 0.28f else 0.14f), badgeShape)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }

            if (onDetail != null) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color(0x52080A0F))
                        .clickable(onClick = onDetail),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(AppIcons.MoreVert, "详情", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(13.dp))
                }
            }

            if (onQuickPlayAll != null) {
                // 榜单首卡右下角精致半透明毛玻璃一键连播小圆钮
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(7.dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color(0x780B0E16))
                        .border(
                            width = 0.5.dp,
                            brush = Brush.verticalGradient(listOf(Color(0x66FFFFFF), Color(0x14FFFFFF))),
                            shape = CircleShape,
                        )
                        .clickable(onClick = onQuickPlayAll),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(AppIcons.PlayArrow, "连播榜单", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            } else if (item.durationMs > 0L) {
                Text(
                    text = formatDuration(item.durationMs),
                    color = Color.White.copy(alpha = 0.86f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0x66080A0F))
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
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
        val sub = item.subtitleLine()
        if (sub.isNotEmpty()) {
            Text(
                text = sub,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

/** 音乐全部榜单网格页：点击「连播榜单」或任意单曲直接智能用已登录源播放，不弹选源框。 */
@Composable
fun MusicDiscoverChartScreen(
    initialChartId: String?,
    settings: AppSettings,
    onBack: () -> Unit,
    onSmartPlay: (DiscoverItem, List<DiscoverItem>, Int) -> Unit,
    onOpenDetail: (DiscoverItem, List<DiscoverItem>, Int) -> Unit,
) {
    val context = LocalContext.current
    val wide = LocalWideLayout.current
    val tv = LocalTv.current
    val sourcePref = settings.musicDiscoverSource

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
            if (page.items.isNotEmpty()) items = page.items
            else if (items.isEmpty()) error = "暂未获取到该榜单歌曲，请稍后重试"
        }.onFailure { e ->
            if (items.isEmpty()) error = e.message ?: "榜单加载失败"
        }
        loading = false
    }

    DetailScaffold(
        title = chart.title,
        onBack = onBack,
        actions = {
            if (items.isNotEmpty()) {
                TextButton(
                    onClick = { onSmartPlay(items.first(), items, 0) },
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
                error != null && items.isEmpty() -> EmptyState(error!!)
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(if (wide || tv) 5 else 3),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 92.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(items, key = { idx, s -> "${chart.id}:$idx:${s.id}" }) { idx, song ->
                        MusicSquareCard(
                            item = song,
                            width = null,
                            rank = if (chart.id != "music_new_express") idx + 1 else null,
                            onClick = { onSmartPlay(song, items, idx) },
                            onDetail = { onOpenDetail(song, items, idx) },
                        )
                    }
                }
            }
        }
    }
}

/** 歌曲详情页：点击「立即播放」直接智能选择已登录源开播；展示专辑信息、歌词预览、相似歌曲与 AI 相似曲风推荐。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MusicDiscoverDetailScreen(
    item: DiscoverItem,
    queueItems: List<DiscoverItem> = emptyList(),
    queueIndex: Int = 0,
    vm: MusicViewModel,
    onBack: () -> Unit,
    onSmartPlay: (DiscoverItem, List<DiscoverItem>, Int) -> Unit,
    onOpenSettings: () -> Unit,
    onSelectSongDetail: (DiscoverItem, List<DiscoverItem>, Int) -> Unit,
) {
    val context = LocalContext.current
    val uiState by vm.state.collectAsStateWithLifecycle()
    val settings = uiState.settings
    val wide = LocalWideLayout.current
    val palette = currentVisualPalette()
    val loggedInServices by WebStreamingMusic.loggedInServices.collectAsStateWithLifecycle()
    val activeService by remember(context) { MusicSourceStore.ensureInit(context) }.collectAsStateWithLifecycle()
    val resolvedTarget = remember(loggedInServices, activeService) {
        SmartMusicSourceResolver.resolve(loggedInServices, activeService)
    }

    var enriched by remember(item.id) { mutableStateOf(item) }
    var lyricsText by remember(item.id) { mutableStateOf<String?>(null) }
    var lyricsExpanded by rememberSaveable(item.id) { mutableStateOf(false) }

    LaunchedEffect(item.id, settings.musicDiscoverSource) {
        withContext(Dispatchers.IO) {
            if (item.album.isBlank() || item.durationMs <= 0L || item.appleUrl == null) {
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

    val recentMusicTitles = remember(uiState.history) {
        uiState.history.map { it.entry.displayTitle }.distinct().take(8)
    }

    DetailScaffold(title = enriched.title, onBack = onBack) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val coverUrl = enriched.posterUrl ?: enriched.backdropUrl
            Box(Modifier.fillMaxWidth()) {
                if (coverUrl != null) {
                    Box(Modifier.fillMaxWidth().height(if (wide) 240.dp else 192.dp)) {
                        NetImage(url = coverUrl, modifier = Modifier.fillMaxSize(), maxSize = 900, contentScale = ContentScale.Crop)
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.verticalGradient(
                                    listOf(Color(0x55000000), palette.backdropFadeColor.copy(alpha = 0.80f), palette.backdropFadeColor),
                                ),
                            ),
                        )
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = if (coverUrl != null) (if (wide) 88.dp else 58.dp) else 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    NetImage(
                        url = coverUrl,
                        modifier = Modifier.size(if (wide) 136.dp else 110.dp).clip(RoundedCornerShape(12.dp)),
                        maxSize = 600,
                        contentScale = ContentScale.Crop,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(enriched.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (enriched.artist.isNotBlank()) {
                            Text(enriched.artist, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = Orange, maxLines = 2)
                        }
                        val metaLine = buildList {
                            enriched.album.takeIf { it.isNotBlank() }?.let { add("《$it》") }
                            enriched.year?.let { add("$it") }
                            if (enriched.durationMs > 0L) add(formatDuration(enriched.durationMs))
                            if (enriched.genres.isNotEmpty()) add(enriched.genres.take(2).joinToString(" / "))
                        }.joinToString(" · ")
                        if (metaLine.isNotEmpty()) {
                            Text(metaLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                    }
                }
            }

            // 直接智能播放按钮（零选源弹窗）
            SurfaceCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onSmartPlay(enriched, queueItems, queueIndex) },
                        colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.White),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                    ) {
                        if (resolvedTarget != null) {
                            WebMusicServiceIcon(resolvedTarget, size = 18.dp)
                            Spacer(Modifier.width(7.dp))
                        } else {
                            Icon(AppIcons.PlayArrow, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            text = when {
                                resolvedTarget != null && queueItems.size > 1 -> "使用 ${resolvedTarget.label} 播放（连播 ${queueItems.size} 首）"
                                resolvedTarget != null -> "使用 ${resolvedTarget.label} 立即播放"
                                queueItems.size > 1 -> "立即播放（连播 ${queueItems.size} 首）"
                                else -> "立即播放"
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            lyricsText?.takeIf { it.isNotBlank() }?.let { lyric ->
                val lines = remember(lyric) { lyric.lines().filter { it.isNotBlank() } }
                if (lines.isNotEmpty()) {
                    SurfaceCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column(
                            Modifier.fillMaxWidth().clickable { lyricsExpanded = !lyricsExpanded }.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("歌词预览", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text(if (lyricsExpanded) "收起" else "展开全部", fontSize = 12.sp, color = Orange, fontWeight = FontWeight.Medium)
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

            MusicRecommendationsSection(
                item = enriched,
                recentMusicTitles = recentMusicTitles,
                settings = settings,
                onUpdateSettings = vm::updateSettings,
                onOpenSettings = onOpenSettings,
                onSmartPlay = onSmartPlay,
                onOpenDetail = onSelectSongDetail,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MusicRecommendationsSection(
    item: DiscoverItem,
    recentMusicTitles: List<String>,
    settings: AppSettings,
    onUpdateSettings: (AppSettings) -> Unit,
    onOpenSettings: () -> Unit,
    onSmartPlay: (DiscoverItem, List<DiscoverItem>, Int) -> Unit,
    onOpenDetail: (DiscoverItem, List<DiscoverItem>, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val wide = LocalWideLayout.current
    val cardWidth = if (wide) 132.dp else 112.dp

    var similarSongs by remember(item.id, settings.musicDiscoverSource) { mutableStateOf<List<DiscoverItem>>(emptyList()) }
    var similarLoading by remember(item.id, settings.musicDiscoverSource) { mutableStateOf(true) }
    var similarNonce by remember(item.id) { mutableIntStateOf(0) }

    LaunchedEffect(item.id, item.neteaseId, item.artist, settings.musicDiscoverSource, similarNonce) {
        similarLoading = true
        similarSongs = runCatching {
            MusicRecommendationEngine.fetchSimilarSongs(item = item, sourcePref = settings.musicDiscoverSource, forceRefresh = similarNonce > 0)
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
        SurfaceCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("相似歌曲推荐", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (!similarLoading) {
                        IconButton(onClick = { similarNonce++ }, modifier = Modifier.size(28.dp)) {
                            Icon(AppIcons.Refresh, "刷新相似歌曲", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                when {
                    similarLoading -> Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Orange)
                    }
                    similarSongs.isEmpty() -> Text(
                        "暂未匹配到关联歌曲，可使用下方 AI 智能荐歌探索相似曲风。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                    )
                    else -> LazyRow(
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(similarSongs, key = { _, s -> s.id }) { idx, sim ->
                            MusicSquareCard(
                                item = sim,
                                width = cardWidth,
                                onClick = { onSmartPlay(sim, similarSongs, idx) },
                                onDetail = { onOpenDetail(sim, similarSongs, idx) },
                            )
                        }
                    }
                }
            }
        }

        SurfaceCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(AppIcons.AutoAwesome, null, tint = Orange, modifier = Modifier.size(19.dp))
                    Column(Modifier.weight(1f)) {
                        Text("AI 相似曲风推荐", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (aiModelLabel.isNotBlank()) {
                            Text(aiModelLabel, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                    TextButton(onClick = { editingPrompt = true }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        Text(if (customPrompt.isBlank()) "定制口味" else "已设口味", fontSize = 12.sp)
                    }
                    IconButton(onClick = { choosingModel = true }, modifier = Modifier.size(30.dp)) {
                        Icon(AppIcons.Settings, "切换模型", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (resolvedChat != null) {
                        IconButton(onClick = { if (!aiLoading) aiTriggerNonce++ }, enabled = !aiLoading, modifier = Modifier.size(30.dp)) {
                            Icon(AppIcons.Refresh, "换一批", modifier = Modifier.size(17.dp), tint = Orange)
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                    resolvedChat == null -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("配置任意大模型（DeepSeek / Gemini / 通义千问 / 豆包 / 本地 Ollama）即可按编曲氛围荐歌。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Button(onClick = onOpenSettings, colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.White)) {
                            Text("配置 AI", fontSize = 12.sp)
                        }
                    }
                    aiLoading -> Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Orange)
                        Spacer(Modifier.width(10.dp))
                        Text("AI 正在分析《${item.title}》的曲风…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    aiError != null && aiItems.isEmpty() -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(aiError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { aiTriggerNonce++ }) { Text("重试", fontSize = 12.sp) }
                    }
                    aiItems.isEmpty() -> Button(onClick = { aiTriggerNonce++ }, colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.White)) {
                        Icon(AppIcons.AutoAwesome, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("生成 AI 相似曲风推荐")
                    }
                    else -> {
                        val aiQueue = remember(aiItems) { aiItems.map { it.item } }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            itemsIndexed(aiItems, key = { _, r -> r.item.id + r.reason.hashCode() }) { idx, rec ->
                                MusicAiSongCard(
                                    rec = rec,
                                    width = if (wide) 262.dp else 232.dp,
                                    onClick = { onSmartPlay(rec.item, aiQueue, idx) },
                                    onDetail = { onOpenDetail(rec.item, aiQueue, idx) },
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

    if (choosingModel) {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MusicAiSongCard(
    rec: AiRecommendedItem,
    width: Dp?,
    onClick: () -> Unit,
    onDetail: () -> Unit,
) {
    val item = rec.item
    Row(
        Modifier
            .let { if (width != null) it.width(width) else it.fillMaxWidth() }
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.85f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onDetail)
            .padding(9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        NetImage(
            url = item.posterUrl,
            modifier = Modifier.size(66.dp).clip(RoundedCornerShape(8.dp)),
            maxSize = 320,
            contentScale = ContentScale.Crop,
            fallback = AppIcons.MusicNote,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = onDetail, modifier = Modifier.size(22.dp)) {
                    Icon(AppIcons.MoreVert, "详情", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                }
            }
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
            Text(rec.reason, fontSize = 11.5.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 独立「AI 推荐音乐」页面：点击推荐单曲立即智能播放。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MusicDiscoverAiRecommendScreen(
    initialPrompt: String = "",
    vm: MusicViewModel,
    onBack: () -> Unit,
    onSmartPlay: (DiscoverItem, List<DiscoverItem>, Int) -> Unit,
    onOpenDetail: (DiscoverItem, List<DiscoverItem>, Int) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val uiState by vm.state.collectAsStateWithLifecycle()
    val settings = uiState.settings

    val resolvedChat = remember(settings.aiRecommendProvider, settings.provider, settings.providerConfigs) {
        RecommendationEngine.resolveChatTranslator(settings)
    }

    var promptInput by rememberSaveable { mutableStateOf(initialPrompt.trim().ifEmpty { MusicDiscoverSearchStore.sessionAiPrompt }) }
    var results by remember { mutableStateOf(MusicDiscoverSearchStore.sessionAiResults) }
    var modelLabel by remember { mutableStateOf(MusicDiscoverSearchStore.sessionAiModelLabel.ifBlank { resolvedChat?.first.orEmpty() }) }
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

    val recentMusicTitles = remember(uiState.history) {
        uiState.history.map { it.entry.displayTitle }.distinct().take(8)
    }

    fun runRecommend(prompt: String = promptInput, loadAnotherBatch: Boolean = false) {
        val clean = prompt.trim()
        if (clean.isEmpty()) {
            error = "请先描述你想听的音乐风格、心境或场景"
            return
        }
        if (RecommendationEngine.resolveChatTranslator(settings) == null) {
            error = "尚未配置 AI 大模型密钥，请先前往设置配置模型"
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
            if (results.isNotEmpty()) {
                val queue = results.map { it.item }
                TextButton(onClick = { onSmartPlay(queue.first(), queue, 0) }) {
                    Icon(AppIcons.PlayArrow, null, tint = Orange, modifier = Modifier.size(18.dp))
                    Text("连播", color = Orange, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
            IconButton(onClick = { choosingModel = true }) {
                Icon(AppIcons.Settings, "切换推荐模型")
            }
        },
    ) {
        LazyColumn(
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 96.dp),
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
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = if (modelLabel.isNotBlank()) "模型：$modelLabel" else "尚未配置 AI 模型",
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (results.isNotEmpty() && !loading) {
                                OutlinedButton(onClick = { runRecommend(promptInput, true) }, shape = RoundedCornerShape(10.dp)) {
                                    Text("换一批", fontSize = 12.5.sp)
                                }
                            }
                            Button(
                                onClick = { if (resolvedChat == null) onOpenSettings() else runRecommend(promptInput, false) },
                                enabled = !loading,
                                colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.White),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Icon(AppIcons.AutoAwesome, null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (resolvedChat == null) "配置模型" else "开始推荐", fontSize = 12.5.sp)
                            }
                        }
                    }
                }
            }

            if (loading) {
                item(key = "music_ai_loading") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(color = Orange)
                            Text("AI 正在为你挑选歌单…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else if (error != null && results.isEmpty()) {
                item(key = "music_ai_error") { EmptyState(error!!) }
            } else if (results.isEmpty()) {
                item(key = "music_ai_presets") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("试试这样描述", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            presets.forEach { p ->
                                Text(
                                    p,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                                        .clickable { promptInput = p; runRecommend(p, false) }
                                        .padding(horizontal = 10.dp, vertical = 7.dp),
                                )
                            }
                        }
                    }
                }
                if (history.isNotEmpty()) {
                    item(key = "music_ai_history") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("历史需求", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                Text("清空", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clickable {
                                    MusicDiscoverSearchStore.clearAiHistory(context)
                                    history = emptyList()
                                })
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                history.forEach { h ->
                                    Text(
                                        h,
                                        fontSize = 12.sp,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                            .clickable { promptInput = h; runRecommend(h, false) }
                                            .padding(horizontal = 11.dp, vertical = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                val aiQueue = results.map { it.item }
                itemsIndexed(results, key = { idx, r -> "$idx:${r.item.id}" }) { idx, rec ->
                    MusicAiSongCard(
                        rec = rec,
                        width = null,
                        onClick = { onSmartPlay(rec.item, aiQueue, idx) },
                        onDetail = { onOpenDetail(rec.item, aiQueue, idx) },
                    )
                }
            }
        }
    }

    if (choosingModel) {
        TranslateModelDialog(
            settings = settings,
            title = "选择 AI 推荐模型",
            onDismiss = { choosingModel = false },
            forAiRecommend = true,
        ) { updated ->
            vm.updateSettings(updated)
            choosingModel = false
        }
    }
}
