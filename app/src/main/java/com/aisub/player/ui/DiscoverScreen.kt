package com.aisub.player.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aisub.player.AppSettings
import com.aisub.player.LibraryViewModel
import com.aisub.player.media.MediaEntry
import com.aisub.player.meta.DetailMeta
import com.aisub.player.meta.DiscoverCharts
import com.aisub.player.meta.DiscoverHomeSection
import com.aisub.player.meta.DiscoverItem
import com.aisub.player.meta.DiscoverRepository
import com.aisub.player.meta.DiscoverSearchStore
import com.aisub.player.meta.MagnetLinks
import com.aisub.player.meta.MetaPipeline
import com.aisub.player.meta.NameParser
import com.aisub.player.meta.PlatformCatalog
import com.aisub.player.meta.PlatformSource
import com.aisub.player.meta.SearchCategory
import com.aisub.player.meta.SearchSource
import com.aisub.player.meta.StreamingPlatform
import com.aisub.player.remote.OpenListClient
import com.aisub.player.remote.RemoteItem
import com.aisub.player.remote.Server
import com.aisub.player.remote.ServerType
import com.aisub.player.remote.cloud.MediaBrowser
import com.aisub.player.remote.cloud.Pan115Client
import com.aisub.player.remote.cloud.PikPakClient
import com.aisub.player.remote.cloud.ServerItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 发现页：紧凑布局，增大海报有效显示面积；
 * 搜索框收进右上角搜索图标，点击展开单行搜索栏（框内文字滚动显示）并展示搜索历史；
 * 流媒体平台（Netflix、HBO 等）采用紧凑微缩卡片。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiscoverTab(
    settings: AppSettings,
    lib: LibraryViewModel,
    listState: LazyListState,
    searchExpanded: Boolean = false,
    onSearchExpandedChange: (Boolean) -> Unit = {},
    onOpenDetail: (DiscoverItem) -> Unit,
    onOpenChart: (String?) -> Unit,
    onOpenTelegramSearch: (String) -> Unit,
    onOpenWeb: (String) -> Unit,
    onOpenMagnet: (String) -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val wide = LocalWideLayout.current
    val tv = LocalTv.current

    var queryInput by remember { mutableStateOf(DiscoverRepository.sessionQueryInput) }
    var appliedQuery by remember { mutableStateOf(DiscoverRepository.sessionAppliedQuery) }
    var reloadNonce by remember { mutableIntStateOf(0) }
    var manageSourcesOpen by remember { mutableStateOf(false) }
    var sourcesVersion by remember { mutableIntStateOf(0) }

    val initialSections = remember(settings.tmdbKey, settings.doubanEnabled) {
        DiscoverRepository.peekHomeSections(settings)
    }
    var sections by remember(settings.tmdbKey, settings.doubanEnabled) {
        mutableStateOf(initialSections)
    }
    var homeLoading by remember(settings.tmdbKey, settings.doubanEnabled) {
        mutableStateOf(initialSections.isEmpty())
    }
    var homeError by remember { mutableStateOf<String?>(null) }

    val initialSearch = remember(appliedQuery, settings.tmdbKey, settings.doubanEnabled) {
        DiscoverRepository.peekSearchResults(settings, appliedQuery)
    }
    var searchResults by remember { mutableStateOf(initialSearch.orEmpty()) }
    var searchLoading by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }

    var history by remember(sourcesVersion) { mutableStateOf(DiscoverSearchStore.history(context)) }
    val allSources = remember(sourcesVersion) {
        DiscoverSearchStore.sources(context).filter { it.enabled }
    }

    fun openSystemBrowser(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // 收起右上角搜索图标时，清空搜索态回到紧凑榜单页
    LaunchedEffect(searchExpanded) {
        DiscoverRepository.setSessionSearchExpanded(searchExpanded)
        if (!searchExpanded) {
            focusManager.clearFocus()
            if (appliedQuery.isNotEmpty() || queryInput.isNotEmpty()) {
                queryInput = ""
                appliedQuery = ""
            }
        }
    }

    BackHandler(enabled = searchExpanded) {
        onSearchExpandedChange(false)
    }

    LaunchedEffect(settings.tmdbKey, settings.doubanEnabled, reloadNonce) {
        // 首次加载完成后，进入详情页/榜单页再退回时不重复刷新，避免频繁转圈；仅在点「刷新」或修改配置时重新拉取
        if (reloadNonce == 0) {
            val cached = DiscoverRepository.peekHomeSections(settings)
            if (cached.isNotEmpty()) {
                if (sections != cached) sections = cached
                homeLoading = false
                homeError = null
                return@LaunchedEffect
            }
        }
        homeLoading = sections.isEmpty()
        homeError = null
        runCatching {
            withContext(Dispatchers.IO) {
                DiscoverRepository.loadHomeSections(settings, forceRefresh = reloadNonce > 0)
            }
        }.onSuccess { loaded ->
            sections = loaded
            if (loaded.isEmpty()) homeError = "暂时未获取到榜单数据，请检查网络或代理设置后重试"
        }.onFailure { e ->
            homeError = e.message ?: "榜单加载失败"
        }
        homeLoading = false
    }

    LaunchedEffect(appliedQuery, settings.tmdbKey, settings.doubanEnabled) {
        val q = appliedQuery.trim()
        DiscoverRepository.sessionAppliedQuery = q
        if (q.isEmpty()) {
            searchResults = emptyList()
            searchError = null
            searchLoading = false
            return@LaunchedEffect
        }
        DiscoverRepository.peekSearchResults(settings, q)?.let { cached ->
            searchResults = cached
            searchError = null
            searchLoading = false
            return@LaunchedEffect
        }
        searchLoading = true
        searchError = null
        runCatching {
            withContext(Dispatchers.IO) {
                DiscoverRepository.search(settings, q)
            }
        }.onSuccess { list ->
            searchResults = list
        }.onFailure { e ->
            searchError = e.message ?: "搜索失败"
        }
        searchLoading = false
    }

    fun submitSearch(raw: String = queryInput) {
        val q = raw.trim()
        queryInput = q
        appliedQuery = q
        DiscoverRepository.sessionQueryInput = q
        DiscoverRepository.sessionAppliedQuery = q
        focusManager.clearFocus()
        if (q.isNotEmpty() && MagnetLinks.normalizeMagnet(q) == null) {
            DiscoverSearchStore.recordHistory(context, q)
            history = DiscoverSearchStore.history(context)
        }
    }

    val detectedMagnet = remember(queryInput) { MagnetLinks.normalizeMagnet(queryInput) }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = 2.dp, bottom = 82.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        // 仅在点击右上角搜索图标展开后，才显示搜索框与搜索历史
        item(key = "discover_search_expandable") {
            AnimatedVisibility(
                visible = searchExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    var sourceMenuOpen by remember { mutableStateOf(false) }
                    SingleLineKeywordSearchBar(
                        value = queryInput,
                        onValueChange = {
                            queryInput = it
                            DiscoverRepository.sessionQueryInput = it
                            if (it.isBlank()) {
                                appliedQuery = ""
                                DiscoverRepository.sessionAppliedQuery = ""
                            }
                        },
                        onSearch = { submitSearch() },
                        placeholder = "搜电影 / 剧集，粘贴磁力 / Hash，或点右侧切换搜索源立即检索",
                        candidates = history.take(8),
                        onPickCandidate = { kw -> submitSearch(kw) },
                        sourceLabel = "搜索源 (${allSources.size})",
                        sourceMenuExpanded = sourceMenuOpen,
                        onSourceClick = { sourceMenuOpen = true },
                        onSourceDismiss = { sourceMenuOpen = false },
                        showSubmitButton = true,
                    ) {
                        val quickQ = queryInput.trim().ifBlank { appliedQuery.trim() }
                        if (quickQ.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text("Telegram 全频道搜「$quickQ」", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = { Icon(AppIcons.Telegram, null, tint = Orange, modifier = Modifier.size(18.dp)) },
                                onClick = {
                                    sourceMenuOpen = false
                                    DiscoverSearchStore.recordHistory(context, quickQ)
                                    history = DiscoverSearchStore.history(context)
                                    onOpenTelegramSearch(quickQ)
                                },
                            )
                            allSources.forEach { src ->
                                val isRe0Builtin = src.id == "re0_search"
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (isRe0Builtin) "影巢 · 系统浏览器搜「$quickQ」" else "${src.name} 搜「$quickQ」",
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(if (isRe0Builtin) AppIcons.OpenInNew else AppIcons.Web, null, modifier = Modifier.size(17.dp))
                                    },
                                    onClick = {
                                        sourceMenuOpen = false
                                        DiscoverSearchStore.recordHistory(context, quickQ)
                                        history = DiscoverSearchStore.history(context)
                                        if (isRe0Builtin) {
                                            openSystemBrowser(src.buildUrl(quickQ))
                                        } else {
                                            onOpenWeb(src.buildUrl(quickQ))
                                        }
                                    },
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        DropdownMenuItem(
                            text = { Text("管理 / 自定义搜索源…") },
                            leadingIcon = { Icon(AppIcons.Settings, null, modifier = Modifier.size(17.dp)) },
                            onClick = {
                                sourceMenuOpen = false
                                manageSourcesOpen = true
                            },
                        )
                    }

                    detectedMagnet?.let { mag ->
                        SurfaceCard(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(AppIcons.Link, null, tint = Orange, modifier = Modifier.size(18.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("识别到磁力链接 / InfoHash", style = MaterialTheme.typography.labelLarge)
                                    Text(
                                        MagnetLinks.displayName(mag),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Button(
                                    onClick = { onOpenMagnet(mag) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp),
                                ) {
                                    Text("离线 / 打开", fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    // 搜索历史仅在点击右上角搜索图标展开后才显示
                    if (history.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "搜索历史",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "清空历史",
                                fontSize = 12.sp,
                                color = Orange,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        DiscoverSearchStore.clearHistory(context)
                                        history = emptyList()
                                    }
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            history.take(10).forEach { h ->
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                        .tvFocusRing(RoundedCornerShape(8.dp))
                                        .clickable { submitSearch(h) }
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    Text(h, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (searchExpanded && appliedQuery.isNotBlank()) {
            // 搜索结果态：紧凑快捷站外直达条 + 海报网格
            item(key = "discover_quick_jump") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item {
                        DiscoverQuickJumpButton(
                            icon = AppIcons.Telegram,
                            label = "TG 搜「$appliedQuery」",
                            onClick = { onOpenTelegramSearch(appliedQuery) },
                        )
                    }
                    val directTarget = searchResults.firstOrNull { it.tmdbId != null }
                    if (directTarget?.tmdbId != null) {
                        item(key = "re0_direct_jump") {
                            DiscoverQuickJumpButton(
                                icon = AppIcons.Web,
                                label = "影巢 · 词条直达",
                                onClick = {
                                    onOpenWeb(DiscoverSearchStore.buildRe0DirectUrl(directTarget.kind, directTarget.tmdbId))
                                },
                            )
                        }
                    }
                    item(key = "re0_external_jump") {
                        DiscoverQuickJumpButton(
                            icon = AppIcons.OpenInNew,
                            label = "影巢 · 系统浏览器",
                            onClick = {
                                val targetUrl = directTarget?.tmdbId?.takeIf {
                                    directTarget.title.equals(appliedQuery.trim(), ignoreCase = true) ||
                                        directTarget.originalTitle.equals(appliedQuery.trim(), ignoreCase = true)
                                }?.let { tid ->
                                    DiscoverSearchStore.buildRe0DirectUrl(directTarget.kind, tid)
                                } ?: DiscoverSearchStore.buildRe0SearchUrl(appliedQuery)
                                openSystemBrowser(targetUrl)
                            },
                        )
                    }
                    items(allSources.filter { it.category == SearchCategory.PAN }.take(2), key = { it.id }) { src ->
                        DiscoverQuickJumpButton(
                            icon = AppIcons.Cloud,
                            label = src.name.substringBefore("（"),
                            onClick = { onOpenWeb(src.buildUrl(appliedQuery)) },
                        )
                    }
                    items(allSources.filter { it.category == SearchCategory.MAGNET }.take(2), key = { it.id }) { src ->
                        DiscoverQuickJumpButton(
                            icon = AppIcons.Link,
                            label = src.name.substringBefore("（"),
                            onClick = { onOpenWeb(src.buildUrl(appliedQuery)) },
                        )
                    }
                }
            }

            when {
                searchLoading -> {
                    item(key = "search_loading") {
                        Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(28.dp))
                        }
                    }
                }
                searchError != null && searchResults.isEmpty() -> {
                    item(key = "search_error") {
                        EmptyState("影视数据库搜索失败：$searchError\n可使用上方按钮直接去 Telegram / 影巢 / 网盘搜索", icon = AppIcons.Search)
                    }
                }
                searchResults.isEmpty() -> {
                    item(key = "search_empty") {
                        EmptyState("暂未搜到「$appliedQuery」\n可点上方按钮直接去 Telegram / 影巢 / 网盘找片源", icon = AppIcons.Movie)
                    }
                }
                else -> {
                    items(searchResults.chunked(if (wide || tv) 5 else 3), key = { row -> row.first().id }) { row ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val cols = if (wide || tv) 5 else 3
                            for (i in 0 until cols) {
                                val item = row.getOrNull(i)
                                if (item != null) {
                                    DiscoverPosterCard(
                                        item = item,
                                        width = null,
                                        modifier = Modifier.weight(1f),
                                        onClick = { onOpenDetail(item) },
                                    )
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // 默认浏览态：微缩流媒体榜单条 + 紧凑高密度横向海报栏
            item(key = "streaming_platforms") {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "流媒体榜单",
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
                                        DiscoverRepository.clearCache()
                                        reloadNonce++
                                    }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Icon(
                                    AppIcons.Refresh,
                                    contentDescription = "刷新榜单",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(13.dp),
                                )
                                Text(
                                    "刷新",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                    .clickable { onOpenChart(null) }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }
                    }
                    LazyRow(
                        state = lib.listState("discover.platforms"),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        items(DiscoverCharts.platforms, key = { it.id }) { platform ->
                            StreamingPlatformCard(
                                platform = platform,
                                onClick = { onOpenChart("platform_${platform.id}") },
                            )
                        }
                    }
                }
            }

            if (homeLoading) {
                item(key = "home_loading") {
                    Box(Modifier.fillMaxWidth().padding(36.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(28.dp))
                            Text("正在加载剧集与电影榜单…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else if (homeError != null && sections.isEmpty()) {
                item(key = "home_error") {
                    Column(
                        Modifier.fillMaxWidth().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(homeError!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { DiscoverRepository.clearCache(); reloadNonce++ }) {
                            Text("重新加载")
                        }
                    }
                }
            } else {
                items(sections, key = { it.chart.id }) { section ->
                    DiscoverChartRowSection(
                        section = section,
                        rowState = lib.listState("discover.row.${section.chart.id}"),
                        cardWidth = if (tv) 144.dp else if (wide) 136.dp else 112.dp,
                        onSeeAll = { onOpenChart(section.chart.id) },
                        onOpenDetail = onOpenDetail,
                    )
                }
            }
        }
    }

    if (manageSourcesOpen) {
        SearchSourcesManageDialog(
            onDismiss = { manageSourcesOpen = false },
            onChanged = { sourcesVersion++ },
        )
    }
}

/**
 * 单行搜索关键词栏：左侧搜索框（框内文字跑马灯滚动、点击弹出可选关键词并支持键盘输入），右侧搜索源按钮。
 */
@Composable
fun SingleLineKeywordSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    placeholder: String,
    candidates: List<String>,
    onPickCandidate: (String) -> Unit,
    sourceLabel: String,
    sourceMenuExpanded: Boolean,
    onSourceClick: () -> Unit,
    onSourceDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    showSubmitButton: Boolean = false,
    sourceMenuContent: @Composable () -> Unit,
) {
    val tv = LocalTv.current
    val barHeight = if (tv) 44.dp else 40.dp
    var candidatesPopupOpen by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.weight(1f)) {
            CompactSearchField(
                value = value,
                onValueChange = onValueChange,
                onSearch = {
                    candidatesPopupOpen = false
                    onSearch()
                },
                placeholder = placeholder,
                phoneLabel = "搜索关键词",
                height = barHeight,
                marqueeText = true,
                onTapOrFocus = {
                    if (candidates.isNotEmpty()) candidatesPopupOpen = true
                },
                trailingAction = if (showSubmitButton && value.isNotBlank()) {
                    {
                        IconButton(
                            onClick = {
                                candidatesPopupOpen = false
                                onSearch()
                            },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                AppIcons.ArrowForward,
                                contentDescription = "执行搜索",
                                tint = Orange,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                } else null,
                modifier = Modifier.fillMaxWidth(),
            )

            // focusable = false 保证弹出可选关键词时不抢占软键盘焦点，用户可随时继续打字输入
            DropdownMenu(
                expanded = candidatesPopupOpen && candidates.isNotEmpty(),
                onDismissRequest = { candidatesPopupOpen = false },
                properties = PopupProperties(focusable = false),
                modifier = Modifier.heightIn(max = 240.dp),
            ) {
                candidates.forEach { kw ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                kw,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                if (kw == value) AppIcons.Check else AppIcons.History,
                                contentDescription = null,
                                tint = if (kw == value) Orange else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        onClick = {
                            candidatesPopupOpen = false
                            onPickCandidate(kw)
                        },
                    )
                }
            }
        }

        Box {
            OutlinedButton(
                onClick = onSourceClick,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier
                    .height(barHeight)
                    .tvFocusRing(RoundedCornerShape(12.dp)),
            ) {
                Icon(AppIcons.Web, null, tint = Orange, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text(sourceLabel, fontSize = 12.sp, maxLines = 1)
                Icon(AppIcons.ArrowDropDown, null, modifier = Modifier.size(16.dp))
            }
            AppMenu(
                expanded = sourceMenuExpanded,
                onDismissRequest = onSourceDismiss,
            ) {
                sourceMenuContent()
            }
        }
    }
}

/** 缩小后的流媒体平台卡片（Netflix、HBO、Apple TV+、Disney+、Prime Video）。 */
@Composable
private fun StreamingPlatformCard(
    platform: StreamingPlatform,
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
                .background(Color(platform.accentHex))
                .padding(horizontal = 5.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                platform.badge,
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.3.sp,
            )
        }
        Text(
            platform.name,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
private fun DiscoverChartRowSection(
    section: DiscoverHomeSection,
    rowState: LazyListState,
    cardWidth: Dp,
    onSeeAll: () -> Unit,
    onOpenDetail: (DiscoverItem) -> Unit,
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
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            items(section.items, key = { "${section.chart.id}:${it.id}" }) { item ->
                DiscoverPosterCard(
                    item = item,
                    width = cardWidth,
                    onClick = { onOpenDetail(item) },
                )
            }
        }
    }
}

@Composable
fun DiscoverPosterCard(
    item: DiscoverItem,
    width: Dp? = 112.dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .let { if (width != null) it.width(width) else it }
            .tvFocusRing(RoundedCornerShape(9.dp), scale = true)
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(bottom = 2.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(9.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            NetImage(
                url = item.posterUrl,
                modifier = Modifier.fillMaxSize(),
                maxSize = 500,
                contentScale = ContentScale.Crop,
                fallback = AppIcons.Movie,
            )

            // 左上角状态角标（连载中 / 已完结）
            item.ongoing?.let { isOngoing ->
                val bg = if (isOngoing) Orange.copy(alpha = 0.92f) else Color(0xCC1E222B)
                Text(
                    text = if (isOngoing) "连载中" else "已完结",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(bg)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }

            // 右上角类型/集数短角标
            val topRightLabel = when {
                !item.episodeInfo.isNullOrBlank() -> item.episodeInfo!!.take(8)
                item.kind == "tv" -> "剧集"
                else -> "电影"
            }
            Text(
                text = topRightLabel,
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .liquidGlass(
                        shape = RoundedCornerShape(4.dp),
                        style = GlassStyle.Pill,
                        tint = GlassTokens.DarkBadgeTint,
                        enableBackdrop = false,
                    )
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )

            // 左下角评分角标
            item.rating?.let { rating ->
                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(5.dp)
                        .liquidGlass(
                            shape = RoundedCornerShape(5.dp),
                            style = GlassStyle.Pill,
                            tint = GlassTokens.DarkBadgeTint,
                            enableBackdrop = false,
                        )
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Icon(AppIcons.Star, null, tint = Color(0xFFFFC107), modifier = Modifier.size(10.dp))
                    Text(
                        text = buildString {
                            append("%.1f".format(rating))
                            item.ratingLabel?.let { append(" "); append(it) }
                        },
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        Text(
            text = item.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
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

/** 全部榜单 / 单一榜单网格页面 */
@Composable
fun DiscoverChartScreen(
    initialChartId: String?,
    settings: AppSettings,
    onBack: () -> Unit,
    onOpenDetail: (DiscoverItem) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWeb: (String) -> Unit,
) {
    var selectedChartId by rememberSaveable(initialChartId) {
        mutableStateOf(initialChartId ?: DiscoverCharts.allCharts.first().id)
    }
    val chart = remember(selectedChartId) {
        DiscoverCharts.findChart(selectedChartId) ?: DiscoverCharts.allCharts.first()
    }
    val cachedFirstPage = remember(selectedChartId, settings.tmdbKey, settings.doubanEnabled) {
        DiscoverRepository.peekChartFirstPage(chart, settings)
    }
    var page by remember(selectedChartId) { mutableIntStateOf(1) }
    var items by remember(selectedChartId) { mutableStateOf(cachedFirstPage?.items.orEmpty()) }
    var note by remember(selectedChartId) { mutableStateOf(cachedFirstPage?.note) }
    var loading by remember(selectedChartId) { mutableStateOf(cachedFirstPage == null) }
    var loadingMore by remember(selectedChartId) { mutableStateOf(false) }
    var error by remember(selectedChartId) { mutableStateOf<String?>(null) }
    var endReached by remember(selectedChartId) { mutableStateOf(false) }

    LaunchedEffect(selectedChartId, page, settings.tmdbKey) {
        if (page == 1 && items.isNotEmpty()) {
            loading = false
            return@LaunchedEffect
        }
        if (page == 1) loading = true else loadingMore = true
        error = null
        runCatching {
            withContext(Dispatchers.IO) {
                DiscoverRepository.loadChartPage(chart, settings, page = page, count = 24)
            }
        }.onSuccess { res ->
            note = res.note
            if (page == 1) {
                items = res.items
            } else {
                val merged = (items + res.items).distinctBy { it.id }
                if (merged.size == items.size) endReached = true
                items = merged
            }
            if (res.items.isEmpty()) endReached = true
        }.onFailure { e ->
            error = e.message ?: "榜单加载失败"
        }
        loading = false
        loadingMore = false
    }

    DetailScaffold(chart.title, onBack) {
        Column(Modifier.fillMaxSize()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(DiscoverCharts.allCharts, key = { it.id }) { c ->
                    FilterChip(
                        selected = c.id == chart.id,
                        onClick = {
                            if (selectedChartId != c.id) {
                                selectedChartId = c.id
                            }
                        },
                        label = { Text(c.title, fontSize = 12.sp) },
                    )
                }
            }

            note?.let { msg ->
                SurfaceCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onOpenSettings) { Text("去填写 TMDB Key") }
                        }
                    }
                }
            }

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null && items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("加载失败：$error", color = MaterialTheme.colorScheme.error)
                        Button(onClick = { page = 1 }) { Text("重试") }
                    }
                }
                items.isEmpty() -> EmptyState("该榜单暂无条目", icon = AppIcons.Explore)
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(if (LocalTv.current) 152.dp else 108.dp),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(items, key = { it.id }) { item ->
                            DiscoverPosterCard(
                                item = item,
                                width = null,
                                onClick = { onOpenDetail(item) },
                            )
                        }
                        if (!endReached) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                    if (loadingMore) {
                                        CircularProgressIndicator(Modifier.size(26.dp))
                                    } else {
                                        OutlinedButton(onClick = { page++ }) {
                                            Text("加载下一页")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 发现页条目进入的影视详情页：
 * 主页面专注展示影视详情（海报、背景、多平台评分、来源播出平台、剧情简介与演职员）；
 * 提供醒目「播放」按钮（一键跳转本地资源、远程服务器/网盘资源或原平台播放）；
 * 资源搜索相关功能收拢进二级菜单，并在菜单顶部采用单行关键词栏（左搜索框弹可选关键词 + 右搜索源）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiscoverDetailScreen(
    item: DiscoverItem,
    settings: AppSettings,
    lib: LibraryViewModel,
    onBack: () -> Unit,
    play: (List<MediaEntry>, Int) -> Unit,
    onOpenTelegramSearch: (String) -> Unit,
    onOpenWeb: (String) -> Unit,
    onOpenMagnet: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenServerDetail: (serverId: String, ratingKey: String) -> Unit = { _, _ -> },
    onOpenRemote: (server: Server, path: String, title: String) -> Unit = { _, _, _ -> },
    onOpenLocalLibrary: () -> Unit = {},
    onOpenCustomItem: (libraryId: String, itemId: String) -> Unit = { _, _ -> },
    onUpdateSettings: ((AppSettings) -> Unit)? = null,
    onSelectWork: (DiscoverItem) -> Unit = {},
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val libState by lib.state.collectAsStateWithLifecycle()
    val wide = LocalWideLayout.current
    val palette = currentVisualPalette()

    var detail by remember(item.id) { mutableStateOf(DetailMeta(loading = true)) }
    var detailReloadNonce by remember { mutableIntStateOf(0) }
    var sourcesVersion by remember { mutableIntStateOf(0) }
    var playDialogOpen by remember { mutableStateOf(false) }
    var searchMenuOpen by remember { mutableStateOf(false) }
    var manageSourcesOpen by remember { mutableStateOf(false) }
    var pasteMagnetDialog by remember { mutableStateOf(false) }
    var statusTip by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(item.id, settings.tmdbKey, settings.doubanEnabled, settings.bangumiEnabled, settings.fanartKey, detailReloadNonce) {
        detail = detail.copy(loading = true)
        withContext(Dispatchers.IO) {
            MetaPipeline.run(
                settings = settings,
                q = item.toMetaQuery(),
                localInfo = null,
                hasLocalPoster = false,
                hasLocalBackdrop = false,
            ) { meta ->
                detail = meta
            }
        }
    }

    val info = detail.info
    val displayTitle = info?.title?.ifBlank { null } ?: item.title
    val displayOrigTitle = info?.originalTitle?.ifBlank { null } ?: item.originalTitle
    val displayPoster = info?.posterUrl ?: item.posterUrl
    val displayBackdrop = info?.backdropUrl ?: item.backdropUrl ?: displayPoster
    val displayLogo = info?.logoUrl
    val displayOverview = info?.overview?.ifBlank { null } ?: item.overview
    val displayGenres = info?.genres?.takeIf { it.isNotEmpty() } ?: item.genres
    val displayCountries = info?.countries?.takeIf { it.isNotEmpty() } ?: item.countries
    val displayYear = info?.year?.ifBlank { null } ?: item.year?.toString()
    val displayLength = info?.length?.ifBlank { null } ?: item.episodeInfo
    val displayTmdbId = info?.tmdbId?.takeIf { it > 0 } ?: item.tmdbId
    val displayImdbId = info?.imdbId ?: item.imdbId
    val displayDoubanId = info?.doubanId ?: item.doubanId
    val detectedPlatforms = remember(info?.platforms, item.platforms) {
        PlatformCatalog.merge(info?.platforms.orEmpty(), item.platforms)
    }

    val queryCandidates = remember(displayTitle, displayOrigTitle, displayYear, sourcesVersion) {
        buildList {
            if (displayTitle.isNotBlank()) add(displayTitle)
            if (displayOrigTitle.isNotBlank() && !displayOrigTitle.equals(displayTitle, ignoreCase = true)) {
                add(displayOrigTitle)
            }
            if (displayTitle.isNotBlank() && !displayYear.isNullOrBlank()) {
                add("$displayTitle $displayYear")
            }
            addAll(DiscoverSearchStore.history(context).take(5))
        }.distinct()
    }
    var searchQuery by remember(item.id) { mutableStateOf(item.title) }
    val enrichedItem = remember(item, displayTmdbId, displayImdbId, displayDoubanId, displayTitle, displayOrigTitle, detectedPlatforms) {
        item.copy(
            title = displayTitle,
            originalTitle = displayOrigTitle,
            tmdbId = displayTmdbId,
            imdbId = displayImdbId,
            doubanId = displayDoubanId,
            platforms = detectedPlatforms,
        )
    }

    val customLibraries by com.aisub.player.media.CustomMediaLibraryStore.libraries.collectAsStateWithLifecycle()
    val customCatalogs by com.aisub.player.media.CustomMediaLibraryStore.itemsByLibrary.collectAsStateWithLifecycle()
    val customLibraryMatches = remember(customLibraries, customCatalogs, displayTitle, displayOrigTitle, displayTmdbId, displayDoubanId, displayImdbId) {
        com.aisub.player.media.CustomMediaLibraryStore.matchInScrapedLibraries(
            context = context,
            title = displayTitle,
            origTitle = displayOrigTitle,
            tmdbId = displayTmdbId,
            doubanId = displayDoubanId,
            imdbId = displayImdbId,
            kind = enrichedItem.kind,
        )
    }

    val recentWatchedTitles = remember(libState.history) {
        libState.history.filter { it.entry.isVideo && !it.entry.isLive }
            .map { it.entry.displayTitle }
            .distinct()
            .take(8)
    }

    // 匹配本地媒体库与本机观看记录
    val localMatches = remember(libState.videos, libState.history, displayTitle, displayOrigTitle) {
        fun norm(s: String) = s.lowercase().replace(Regex("""[\s·・:：\-—_!！?？.'"`()\[\]【】]"""), "")
        val targets = listOfNotNull(displayTitle, displayOrigTitle.ifBlank { null }).map(::norm).filter { it.length >= 2 }
        if (targets.isEmpty()) emptyList()
        else {
            val localUris = libState.videos.mapTo(HashSet()) { it.uri }
            fun isLocalUri(u: String) = u in localUris || u.startsWith("content://") || u.startsWith("file:")
            val localHistory = libState.history.map { it.entry }.filter { isLocalUri(it.uri) }
            val all = (libState.videos + localHistory).distinctBy { it.uri }
            all.filter { e ->
                val parsed = norm(NameParser.parse(e.title).title)
                val raw = norm(e.title)
                targets.any { t -> parsed.contains(t) || raw.contains(t) }
            }.take(6)
        }
    }

    // 匹配远程播放历史记录
    val remoteHistoryMatches = remember(libState.videos, libState.history, displayTitle, displayOrigTitle) {
        fun norm(s: String) = s.lowercase().replace(Regex("""[\s·・:：\-—_!！?？.'"`()\[\]【】]"""), "")
        val targets = listOfNotNull(displayTitle, displayOrigTitle.ifBlank { null }).map(::norm).filter { it.length >= 2 }
        if (targets.isEmpty()) emptyList()
        else {
            val localUris = libState.videos.mapTo(HashSet()) { it.uri }
            fun isLocalUri(u: String) = u in localUris || u.startsWith("content://") || u.startsWith("file:")
            libState.history.map { it.entry }
                .filterNot { isLocalUri(it.uri) }
                .distinctBy { it.uri }
                .filter { e ->
                    val parsed = norm(NameParser.parse(e.title).title)
                    val raw = norm(e.title)
                    targets.any { t -> parsed.contains(t) || raw.contains(t) }
                }.take(5)
        }
    }

    val allSources = remember(sourcesVersion) {
        DiscoverSearchStore.sources(context).filter { it.enabled }
    }

    fun openExternalUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            statusTip = "没有可用的外部应用打开此链接，已复制地址"
            clipboard.setText(AnnotatedString(url))
        }
    }

    DetailScaffold(
        title = displayTitle,
        onBack = onBack,
        actions = {
            IconButton(onClick = { searchMenuOpen = true }) {
                Icon(AppIcons.Search, "搜索片源")
            }
            OverflowMenu(
                listOf(
                    MenuAction("搜索片源") { searchMenuOpen = true },
                    MenuAction("管理搜索源") { manageSourcesOpen = true },
                    MenuAction("粘贴磁力 / 离线下载") { pasteMagnetDialog = true },
                    MenuAction("刷新影视详情") { detailReloadNonce++ },
                ),
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 顶部海报 + 横幅背景头部
            Box(Modifier.fillMaxWidth()) {
                if (displayBackdrop != null) {
                    Box(Modifier.fillMaxWidth().height(if (wide) 260.dp else 204.dp)) {
                        NetImage(
                            url = displayBackdrop,
                            modifier = Modifier.fillMaxSize(),
                            maxSize = 1280,
                            contentScale = ContentScale.Crop,
                            fallback = AppIcons.Movie,
                        )
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color(0x44000000),
                                        palette.backdropFadeColor.copy(alpha = 0.74f),
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
                            top = if (displayBackdrop != null) (if (wide) 98.dp else 64.dp) else 10.dp,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    NetImage(
                        url = displayPoster,
                        modifier = Modifier
                            .width(if (wide) 132.dp else 104.dp)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(10.dp)),
                        maxSize = 500,
                        contentScale = ContentScale.Crop,
                        fallback = AppIcons.Movie,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        if (displayLogo != null) {
                            NetImage(
                                url = displayLogo,
                                modifier = Modifier.height(40.dp).fillMaxWidth(),
                                maxSize = 600,
                                contentScale = ContentScale.Fit,
                                fallback = AppIcons.Movie,
                            )
                        }
                        Text(
                            displayTitle,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        if (displayOrigTitle.isNotBlank() && displayOrigTitle != displayTitle) {
                            Text(
                                displayOrigTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        val metaSummary = buildList {
                            displayYear?.let { add(it) }
                            if (displayCountries.isNotEmpty()) add(displayCountries.joinToString(" / "))
                            if (displayGenres.isNotEmpty()) add(displayGenres.joinToString(" · "))
                            displayLength?.let { add(it) }
                        }.joinToString(" · ")
                        if (metaSummary.isNotEmpty()) {
                            Text(
                                metaSummary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        // 评分胶囊行
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            val ratings = detail.ratings.filter { it.value != null || it.loading }
                            if (ratings.isNotEmpty()) {
                                ratings.forEach { r ->
                                    DiscoverRatingChip(
                                        label = r.label,
                                        value = r.value,
                                        votes = r.votes,
                                        loading = r.loading,
                                    )
                                }
                            } else if (item.rating != null) {
                                DiscoverRatingChip(
                                    label = item.ratingLabel ?: "评分",
                                    value = item.rating,
                                    votes = item.voteCount.takeIf { it > 0 },
                                    loading = false,
                                )
                            }
                            if (detail.loading) {
                                Text(
                                    "正在补全详情…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.align(Alignment.CenterVertically),
                                )
                            }
                        }
                    }
                }
            }

            // 来源平台展示栏 + 主播放按钮 & 二级菜单「搜索片源」按钮
            SurfaceCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(
                    Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "来源平台",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (detectedPlatforms.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                detectedPlatforms.forEach { platform ->
                                    PlatformBadgeChip(
                                        platform = platform,
                                        onClick = {
                                            onOpenWeb(PlatformCatalog.resolvePlayUrl(platform, displayTitle))
                                        },
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = if (detail.loading) "正在识别播出平台…" else "院线 / 暂未标注网播平台",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = { playDialogOpen = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.White),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .tvFocusRing(RoundedCornerShape(12.dp), color = Color.White),
                        ) {
                            Icon(AppIcons.PlayArrow, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "播放",
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        OutlinedButton(
                            onClick = { searchMenuOpen = true },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .height(44.dp)
                                .tvFocusRing(RoundedCornerShape(12.dp)),
                        ) {
                            Icon(AppIcons.Search, null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("搜索片源")
                            Icon(AppIcons.ExpandMore, null, modifier = Modifier.size(16.dp))
                        }
                    }

                    statusTip?.let { tip ->
                        Text(tip, style = MaterialTheme.typography.bodySmall, color = Orange)
                    }
                }
            }

            // 剧情简介、演职员与豆瓣/TMDB 参考链接
            SurfaceCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("影视详情", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (displayOverview.isNotBlank()) {
                        Text(displayOverview, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(
                            if (detail.loading) "正在获取剧情简介…" else "暂无剧情简介",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (info?.directors?.isNotEmpty() == true) {
                        Text(
                            "导演：${info.directors.joinToString("、")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (info?.cast?.isNotEmpty() == true) {
                        Text(
                            "主演：${info.cast.joinToString("、")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val extraLinks = buildList {
                        addAll(detail.links)
                        displayDoubanId?.let { did ->
                            val u = "https://movie.douban.com/subject/$did/"
                            if (none { it.second == u }) add("在豆瓣查看" to u)
                        }
                        displayTmdbId?.let { tid ->
                            val u = "https://www.themoviedb.org/${enrichedItem.kind}/$tid"
                            if (none { it.second == u }) add("在 TMDB 查看" to u)
                        }
                    }
                    if (extraLinks.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            extraLinks.forEach { (label, url) ->
                                TextButton(onClick = { onOpenWeb(url) }) {
                                    Text(label)
                                }
                            }
                        }
                    }
                    if (settings.tmdbKey.isBlank()) {
                        TextButton(onClick = onOpenSettings) {
                            Text("提示：前往设置填写 TMDB API Key 可识别更多全球流媒体来源平台与高清剧照")
                        }
                    }
                }
            }

            // AI 智能推荐 + 相似作品推荐
            MediaRecommendationsSection(
                kind = enrichedItem.kind,
                title = displayTitle,
                originalTitle = displayOrigTitle,
                year = displayYear?.take(4)?.toIntOrNull(),
                tmdbId = displayTmdbId,
                doubanId = displayDoubanId,
                genres = displayGenres,
                directors = info?.directors.orEmpty(),
                cast = info?.cast.orEmpty(),
                overview = displayOverview,
                recentWatchedTitles = recentWatchedTitles,
                settings = settings,
                onUpdateSettings = onUpdateSettings,
                onOpenSettings = onOpenSettings,
                onSelectWork = onSelectWork,
            )
        }
    }

    // 播放来源选择面板：自定义媒体库 / 本地资源 / Emby & Plex 远程资源 / 原平台播放
    if (playDialogOpen) {
        DiscoverPlaySourceDialog(
            title = displayTitle,
            origTitle = displayOrigTitle,
            detectedPlatforms = detectedPlatforms,
            customLibraryMatches = customLibraryMatches,
            localMatches = localMatches,
            remoteHistoryMatches = remoteHistoryMatches,
            lib = lib,
            servers = libState.servers,
            onDismiss = { playDialogOpen = false },
            onPlayEntries = { list, idx ->
                playDialogOpen = false
                play(list, idx)
            },
            onOpenCustomItem = { libId, itemId ->
                playDialogOpen = false
                onOpenCustomItem(libId, itemId)
            },
            onOpenLocalLibrary = {
                playDialogOpen = false
                onOpenLocalLibrary()
            },
            onOpenServerDetail = { sid, rk ->
                playDialogOpen = false
                onOpenServerDetail(sid, rk)
            },
            onOpenRemote = { server, path, name ->
                playDialogOpen = false
                onOpenRemote(server, path, name)
            },
            onOpenWeb = { url ->
                playDialogOpen = false
                onOpenWeb(url)
            },
            onOpenExternal = { url ->
                playDialogOpen = false
                openExternalUrl(url)
            },
        )
    }

    // 二级菜单：资源搜索面板（顶部为单行搜索关键词栏：左搜索框 + 右搜索源）
    if (searchMenuOpen) {
        DiscoverResourceSearchDialog(
            enrichedItem = enrichedItem,
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            queryCandidates = queryCandidates,
            allSources = allSources,
            onDismiss = { searchMenuOpen = false },
            onManageSources = { manageSourcesOpen = true },
            onPasteMagnet = { pasteMagnetDialog = true },
            onOpenTelegramSearch = { q ->
                DiscoverSearchStore.recordHistory(context, q)
                sourcesVersion++
                searchMenuOpen = false
                onOpenTelegramSearch(q)
            },
            onCopyAndLaunchExternalTg = { q ->
                clipboard.setText(AnnotatedString(q))
                statusTip = "已复制「$q」，正在唤起外部 Telegram"
                searchMenuOpen = false
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("tg://")))
                }.onFailure {
                    openExternalUrl("https://t.me/")
                }
            },
            onOpenWeb = { url, q ->
                if (q.isNotBlank()) {
                    DiscoverSearchStore.recordHistory(context, q)
                    sourcesVersion++
                }
                searchMenuOpen = false
                onOpenWeb(url)
            },
            onOpenExternal = { url, q ->
                if (q.isNotBlank()) {
                    DiscoverSearchStore.recordHistory(context, q)
                    sourcesVersion++
                }
                searchMenuOpen = false
                openExternalUrl(url)
            },
        )
    }

    if (manageSourcesOpen) {
        SearchSourcesManageDialog(
            onDismiss = { manageSourcesOpen = false },
            onChanged = { sourcesVersion++ },
        )
    }

    if (pasteMagnetDialog) {
        PasteMagnetDialog(
            initialText = clipboard.getText()?.text.orEmpty(),
            onDismiss = { pasteMagnetDialog = false },
            onSubmit = { magnet ->
                pasteMagnetDialog = false
                onOpenMagnet(magnet)
            },
        )
    }
}

@Composable
private fun PlatformBadgeChip(
    platform: PlatformSource,
    onClick: () -> Unit,
) {
    val spec = PlatformCatalog.specFor(platform.name)
    val accent = Color(PlatformCatalog.accentHex(platform.name))
    Row(
        Modifier
            .tvFocusRing(RoundedCornerShape(7.dp))
            .clip(RoundedCornerShape(7.dp))
            .background(accent.copy(alpha = 0.14f))
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(accent)
                .padding(horizontal = 4.dp, vertical = 1.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                spec?.badge ?: platform.name.take(2),
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        }
        Text(
            platform.name,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

private sealed interface RemotePlayHit {
    data class MediaServerHit(val server: Server, val item: ServerItem) : RemotePlayHit
    data class OpenListHit(val server: Server, val item: RemoteItem) : RemotePlayHit
}

/** 详情页点击「播放」弹出的播放通道选择器：本地资源 / 远程资源 / 原平台播放。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiscoverPlaySourceDialog(
    title: String,
    origTitle: String,
    detectedPlatforms: List<PlatformSource>,
    customLibraryMatches: List<com.aisub.player.media.IndexedPlayHit.CustomLibraryHit> = emptyList(),
    localMatches: List<MediaEntry>,
    remoteHistoryMatches: List<MediaEntry>,
    lib: LibraryViewModel,
    servers: List<Server>,
    onDismiss: () -> Unit,
    onPlayEntries: (List<MediaEntry>, Int) -> Unit,
    onOpenCustomItem: (libraryId: String, itemId: String) -> Unit = { _, _ -> },
    onOpenLocalLibrary: () -> Unit,
    onOpenServerDetail: (serverId: String, ratingKey: String) -> Unit,
    onOpenRemote: (server: Server, path: String, title: String) -> Unit,
    onOpenWeb: (String) -> Unit,
    onOpenExternal: (String) -> Unit,
) {
    var remoteHits by remember(title, origTitle, servers) { mutableStateOf<List<RemotePlayHit>>(emptyList()) }
    var searchingRemote by remember(title, origTitle, servers) { mutableStateOf(false) }

    LaunchedEffect(title, origTitle, servers) {
        val searchable = servers.filter { it.type.isMediaServer || it.type == ServerType.OPENLIST }
        if (searchable.isEmpty()) {
            remoteHits = emptyList()
            searchingRemote = false
            return@LaunchedEffect
        }
        searchingRemote = true
        val found = withContext(Dispatchers.IO) {
            coroutineScope {
                searchable.map { srv ->
                    async {
                        runCatching {
                            when (val client = lib.client(srv)) {
                                is MediaBrowser -> {
                                    val primary = client.search(title)
                                    val merged = if (primary.isNotEmpty() || origTitle.isBlank() || origTitle == title) {
                                        primary
                                    } else {
                                        client.search(origTitle)
                                    }
                                    merged.take(5).map { RemotePlayHit.MediaServerHit(srv, it) }
                                }
                                is OpenListClient -> {
                                    val primary = client.searchVideos(title)
                                    val merged = if (primary.isNotEmpty() || origTitle.isBlank() || origTitle == title) {
                                        primary
                                    } else {
                                        client.searchVideos(origTitle)
                                    }
                                    merged.take(5).map { RemotePlayHit.OpenListHit(srv, it) }
                                }
                                else -> emptyList()
                            }
                        }.getOrDefault(emptyList())
                    }
                }.awaitAll().flatten()
            }
        }
        remoteHits = found
        searchingRemote = false
    }

    val platformOptions = remember(detectedPlatforms) {
        if (detectedPlatforms.isNotEmpty()) {
            PlatformCatalog.merge(detectedPlatforms, PlatformCatalog.fallbackPlatforms().take(3))
        } else {
            PlatformCatalog.fallbackPlatforms()
        }
    }

    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("播放「$title」", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 0. 首页已刮削自定义媒体库命中片源（直接索引播放）
                if (customLibraryMatches.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(AppIcons.Movie, null, tint = Orange, modifier = Modifier.size(18.dp))
                            Text("已索引媒体库", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        }
                        customLibraryMatches.forEach { hit ->
                            val m = hit.item
                            val singleDirect = m.kind == "movie" && m.episodes.size == 1
                            PlayOptionRow(
                                title = m.title,
                                subtitle = buildString {
                                    append("自定义媒体库「${hit.library.name}」")
                                    m.badgeText?.let { append(" · $it") }
                                },
                                actionText = if (singleDirect) "直接播放" else "选集播放",
                                onClick = {
                                    if (singleDirect) {
                                        onPlayEntries(listOf(m.episodes.first().toMediaEntry(null)), 0)
                                    } else {
                                        onOpenCustomItem(hit.library.id, m.id)
                                    }
                                },
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }

                // 1. 本地资源
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(AppIcons.Phone, null, tint = Orange, modifier = Modifier.size(18.dp))
                        Text("本地资源", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = onOpenLocalLibrary) { Text("浏览本机", fontSize = 12.sp) }
                    }
                    if (localMatches.isEmpty()) {
                        Text(
                            "本机未扫描到同名视频，可点右侧「浏览本机」选择本地文件播放。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        localMatches.forEachIndexed { idx, entry ->
                            PlayOptionRow(
                                title = entry.displayTitle,
                                subtitle = entry.folder.ifBlank { "本机视频 / 播放记录" },
                                actionText = "直接播放",
                                onClick = { onPlayEntries(localMatches, idx) },
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // 2. 远程资源
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(AppIcons.Cloud, null, tint = Orange, modifier = Modifier.size(18.dp))
                        Text("远程资源", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (searchingRemote) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        }
                    }

                    remoteHistoryMatches.forEachIndexed { idx, entry ->
                        PlayOptionRow(
                            title = entry.displayTitle,
                            subtitle = "远程播放记录 · 点击继续播放",
                            actionText = "续播",
                            onClick = { onPlayEntries(remoteHistoryMatches, idx) },
                        )
                    }

                    remoteHits.forEach { hit ->
                        when (hit) {
                            is RemotePlayHit.MediaServerHit -> {
                                val it = hit.item
                                val sub = buildString {
                                    append(hit.server.name)
                                    append(" (${hit.server.type.label})")
                                    it.year?.let { y -> append(" · $y") }
                                    it.episodeLabel?.let { ep -> append(" · $ep") }
                                }
                                PlayOptionRow(
                                    title = it.title,
                                    subtitle = sub,
                                    actionText = if (!it.isFolder && it.playUrl != null && it.fileName != null) "直接播放" else "选集",
                                    onClick = {
                                        if (!it.isFolder && it.playUrl != null && it.fileName != null) {
                                            onPlayEntries(listOf(MediaEntry(it.playUrl, it.fileName, isVideo = it.type != "track")), 0)
                                        } else {
                                            onOpenServerDetail(hit.server.id, it.ratingKey)
                                        }
                                    },
                                )
                            }
                            is RemotePlayHit.OpenListHit -> {
                                val it = hit.item
                                PlayOptionRow(
                                    title = it.name,
                                    subtitle = "${hit.server.name} (OpenList) · ${it.path}",
                                    actionText = if (!it.isDir && it.playUrl != null) "直接播放" else "打开目录",
                                    onClick = {
                                        if (!it.isDir && it.playUrl != null) {
                                            onPlayEntries(listOf(MediaEntry(it.playUrl, it.name)), 0)
                                        } else {
                                            onOpenRemote(hit.server, it.path, it.name)
                                        }
                                    },
                                )
                            }
                        }
                    }

                    if (servers.isEmpty()) {
                        Text(
                            "尚未添加媒体服务器或网盘（可在「浏览 → +」添加 Emby / Jellyfin / Plex / OpenList / WebDAV / 115 / PikPak）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            servers.forEach { srv ->
                                OutlinedButton(
                                    onClick = { onOpenRemote(srv, srv.rootPath, srv.name) },
                                    shape = RoundedCornerShape(9.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp),
                                ) {
                                    Text("${srv.name} (${srv.type.label})", fontSize = 12.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // 3. 原平台播放
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(AppIcons.Live, null, tint = Orange, modifier = Modifier.size(18.dp))
                        Text("原平台播放", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "点平台名称使用内置浏览器打开（支持实时生成 AI 字幕），点右侧图标唤起外部原平台 App 或浏览器。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    platformOptions.forEach { p ->
                        val isOfficialSource = detectedPlatforms.any { it.name.equals(p.name, ignoreCase = true) }
                        val targetUrl = PlatformCatalog.resolvePlayUrl(p, title)
                        val accent = Color(PlatformCatalog.accentHex(p.name))
                        val badge = PlatformCatalog.specFor(p.name)?.badge ?: p.name.take(2)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.75f))
                                .tvFocusRing(RoundedCornerShape(10.dp))
                                .clickable { onOpenWeb(targetUrl) }
                                .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(accent)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Text(badge, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = if (isOfficialSource) "${p.name}（本片来源平台）" else p.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isOfficialSource) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = if (p.url != null) "官方直达链接 · 内置网页 AI 字幕" else "站内检索直达 · 内置网页 AI 字幕",
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

@Composable
private fun PlayOptionRow(
    title: String,
    subtitle: String,
    actionText: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.75f))
            .tvFocusRing(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(AppIcons.PlayArrow, null, tint = Orange, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(actionText, style = MaterialTheme.typography.labelMedium, color = Orange, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * 影视详情页的「搜索片源」二级菜单弹窗：
 * 顶部缩减为一行（左侧搜索框点击弹可选关键词并支持键盘输入，右侧搜索源切换/管理），下方展示对应资源搜索入口。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiscoverResourceSearchDialog(
    enrichedItem: DiscoverItem,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    queryCandidates: List<String>,
    allSources: List<SearchSource>,
    onDismiss: () -> Unit,
    onManageSources: () -> Unit,
    onPasteMagnet: () -> Unit,
    onOpenTelegramSearch: (String) -> Unit,
    onCopyAndLaunchExternalTg: (String) -> Unit,
    onOpenWeb: (url: String, query: String) -> Unit,
    onOpenExternal: (url: String, query: String) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var selectedCategory by remember { mutableStateOf<SearchCategory?>(null) }
    var sourceMenuOpen by remember { mutableStateOf(false) }

    val activeQ = searchQuery.trim().ifBlank { enrichedItem.title }
    val re0Sources = remember(allSources) { allSources.filter { it.category == SearchCategory.RE0 } }
    val panSources = remember(allSources) { allSources.filter { it.category == SearchCategory.PAN } }
    val magnetSources = remember(allSources) { allSources.filter { it.category == SearchCategory.MAGNET } }
    val telegramSources = remember(allSources) { allSources.filter { it.category == SearchCategory.TELEGRAM } }

    val sourceButtonLabel = selectedCategory?.label ?: "搜索源 (${allSources.size})"

    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("搜索片源", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 单行关键词栏：左搜索框（弹可选关键词且支持直接输入），右搜索源
                SingleLineKeywordSearchBar(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    onSearch = {
                        focusManager.clearFocus()
                    },
                    placeholder = "点击选关键词或直接输入片名",
                    candidates = queryCandidates,
                    onPickCandidate = { kw -> onSearchQueryChange(kw) },
                    sourceLabel = sourceButtonLabel,
                    sourceMenuExpanded = sourceMenuOpen,
                    onSourceClick = { sourceMenuOpen = true },
                    onSourceDismiss = { sourceMenuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("全部搜索源 (${allSources.size})") },
                        trailingIcon = if (selectedCategory == null) {
                            { Icon(AppIcons.Check, null, tint = Orange, modifier = Modifier.size(16.dp)) }
                        } else null,
                        onClick = {
                            selectedCategory = null
                            sourceMenuOpen = false
                        },
                    )
                    SearchCategory.entries.forEach { cat ->
                        DropdownMenuItem(
                            text = { Text(cat.label) },
                            trailingIcon = if (selectedCategory == cat) {
                                { Icon(AppIcons.Check, null, tint = Orange, modifier = Modifier.size(16.dp)) }
                            } else null,
                            onClick = {
                                selectedCategory = cat
                                sourceMenuOpen = false
                            },
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    DropdownMenuItem(
                        text = { Text("管理 / 增删搜索源…") },
                        leadingIcon = { Icon(AppIcons.Settings, null, modifier = Modifier.size(16.dp)) },
                        onClick = {
                            sourceMenuOpen = false
                            onManageSources()
                        },
                    )
                }

                // 1. 影巢 (re0.me)：仅保留「词条直达」与「系统浏览器」
                if (selectedCategory == null || selectedCategory == SearchCategory.RE0) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("影巢（re0.me）", style = MaterialTheme.typography.labelLarge, color = Orange)
                        val re0Search = re0Sources.firstOrNull { it.id == "re0_search" }
                        val re0SearchUrl = re0Search?.buildUrl(activeQ, enrichedItem)
                            ?: DiscoverSearchStore.buildRe0SearchUrl(activeQ)
                        val directRe0Url = enrichedItem.tmdbId?.let { tid ->
                            DiscoverSearchStore.buildRe0DirectUrl(enrichedItem.kind, tid)
                        }
                        val externalRe0Url = if (directRe0Url != null && (
                                activeQ.equals(enrichedItem.title.trim(), ignoreCase = true) ||
                                    activeQ.equals(enrichedItem.originalTitle.trim(), ignoreCase = true)
                                )
                        ) {
                            directRe0Url
                        } else {
                            re0SearchUrl
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (directRe0Url != null) {
                                // 使用 /tmdb/{movie|tv}/{tmdbId} 路由直达影巢词条详情页
                                Button(
                                    onClick = { onOpenWeb(directRe0Url, activeQ) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.White),
                                ) {
                                    Icon(AppIcons.Web, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text("词条直达")
                                }
                            }
                            OutlinedButton(onClick = { onOpenExternal(externalRe0Url, activeQ) }) {
                                Icon(AppIcons.OpenInNew, null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("系统浏览器")
                            }
                        }
                        re0Sources.filter { !it.builtin }.forEach { src ->
                            ResourceSourceActionRow(
                                source = src,
                                url = src.buildUrl(activeQ, enrichedItem),
                                onOpenInWeb = { onOpenWeb(it, activeQ) },
                                onOpenExternal = { onOpenExternal(it, activeQ) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                // 2. Telegram 搜索（固定单行双按钮：搜索 + 打开tg）
                if (selectedCategory == null || selectedCategory == SearchCategory.TELEGRAM) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Telegram 频道 / 群组", style = MaterialTheme.typography.labelLarge, color = Orange)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(
                                onClick = { onOpenTelegramSearch(activeQ) },
                                colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.White),
                            ) {
                                Icon(AppIcons.Telegram, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("搜索", maxLines = 1)
                            }
                            OutlinedButton(onClick = { onCopyAndLaunchExternalTg(activeQ) }) {
                                Icon(AppIcons.OpenInNew, null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("打开tg", maxLines = 1)
                            }
                        }
                        telegramSources.forEach { src ->
                            ResourceSourceActionRow(
                                source = src,
                                url = src.buildUrl(activeQ, enrichedItem),
                                onOpenInWeb = { onOpenWeb(it, activeQ) },
                                onOpenExternal = { onOpenExternal(it, activeQ) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                // 3. 常用网盘搜索
                if (selectedCategory == null || selectedCategory == SearchCategory.PAN) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("网盘资源搜索", style = MaterialTheme.typography.labelLarge, color = Orange)
                        if (panSources.isEmpty()) {
                            TextButton(onClick = onManageSources) { Text("当前无启用的网盘搜索源，点此配置") }
                        } else {
                            panSources.forEach { src ->
                                ResourceSourceActionRow(
                                    source = src,
                                    url = src.buildUrl(activeQ, enrichedItem),
                                    onOpenInWeb = { onOpenWeb(it, activeQ) },
                                    onOpenExternal = { onOpenExternal(it, activeQ) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }

                // 4. 磁力链接搜索 & 离线下载
                if (selectedCategory == null || selectedCategory == SearchCategory.MAGNET) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("磁力搜索 & 离线下载", style = MaterialTheme.typography.labelLarge, color = Orange, modifier = Modifier.weight(1f))
                            TextButton(onClick = onPasteMagnet) {
                                Icon(AppIcons.Download, null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("粘贴磁力离线", fontSize = 12.sp)
                            }
                        }
                        if (magnetSources.isEmpty()) {
                            TextButton(onClick = onManageSources) { Text("当前无启用的磁力搜索源，点此配置") }
                        } else {
                            magnetSources.forEach { src ->
                                ResourceSourceActionRow(
                                    source = src,
                                    url = src.buildUrl(activeQ, enrichedItem),
                                    onOpenInWeb = { onOpenWeb(it, activeQ) },
                                    onOpenExternal = { onOpenExternal(it, activeQ) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
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

@Composable
private fun DiscoverQuickJumpButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(9.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = Modifier
            .height(34.dp)
            .tvFocusRing(RoundedCornerShape(9.dp)),
    ) {
        Icon(icon, null, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun ResourceSourceActionRow(
    source: SearchSource,
    url: String,
    onOpenInWeb: (String) -> Unit,
    onOpenExternal: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.72f))
            .tvFocusRing(RoundedCornerShape(9.dp))
            .clickable { onOpenInWeb(url) }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(source.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (source.description.isNotBlank()) {
                Text(
                    source.description,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = { onOpenExternal(url) }, modifier = Modifier.size(32.dp)) {
            Icon(AppIcons.OpenInNew, "系统浏览器打开", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
        Icon(AppIcons.ArrowForward, null, tint = Orange, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun DiscoverRatingChip(
    label: String,
    value: Double?,
    votes: Int?,
    loading: Boolean,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(AppIcons.Star, null, tint = Color(0xFFFFC107), modifier = Modifier.size(13.dp))
        Text(
            text = when {
                loading -> "$label …"
                value != null -> buildString {
                    append("$label %.1f".format(value))
                    votes?.takeIf { it > 0 }?.let { append(" ($it)") }
                }
                else -> "$label 暂无"
            },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun PasteMagnetDialog(
    initialText: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    val initialNormalized = remember(initialText) {
        MagnetLinks.extractMagnets(initialText).firstOrNull()
            ?: MagnetLinks.normalizeMagnet(initialText).orEmpty()
    }
    var raw by remember(initialNormalized) { mutableStateOf(initialNormalized) }
    val valid = remember(raw) { MagnetLinks.normalizeMagnet(raw) }

    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("输入或粘贴磁力链接") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "支持完整 magnet:? 链接或 40 位十六进制 / 32 位 Base32 InfoHash。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = raw,
                    onValueChange = { raw = it },
                    minLines = 2,
                    maxLines = 4,
                    placeholder = { Text("magnet:?xt=urn:btih:...") },
                    colors = appFieldColors(),
                    phoneLabel = "磁力链接",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid != null, onClick = { valid?.let(onSubmit) }) {
                Text("下一步")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 磁力链接全局接管弹窗：提供复制链接、调用外部下载器 / BT 客户端、以及一键投喂已登录的 115 / PikPak 离线下载。
 */
@Composable
fun MagnetActionDialog(
    magnetUri: String,
    lib: LibraryViewModel?,
    onDismiss: () -> Unit,
    onOpenServer: ((Server) -> Unit)? = null,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val servers = lib?.state?.collectAsStateWithLifecycle()?.value?.servers.orEmpty()
    val offlineServers = remember(servers) {
        servers.filter { it.type == ServerType.PAN115 || it.type == ServerType.PIKPAK }
    }

    var busyServerId by remember { mutableStateOf<String?>(null) }
    var feedback by remember(magnetUri) { mutableStateOf<String?>(null) }
    var succeededServer by remember(magnetUri) { mutableStateOf<Server?>(null) }

    AppDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                MagnetLinks.displayName(magnetUri),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SelectionContainer {
                    Text(
                        magnetUri,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                HorizontalDivider()

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        clipboard.setText(AnnotatedString(magnetUri))
                        feedback = "已复制磁力链接到剪贴板"
                    }) {
                        Icon(AppIcons.ContentCopy, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("复制链接")
                    }
                    OutlinedButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(magnetUri)))
                            feedback = "已尝试唤起外部下载器"
                        }.onFailure {
                            feedback = "系统未安装可处理 magnet: 的下载器"
                        }
                    }) {
                        Icon(AppIcons.OpenInNew, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("外部打开")
                    }
                }

                Text("网盘一键离线下载", style = MaterialTheme.typography.titleSmall)
                if (lib == null || offlineServers.isEmpty()) {
                    Text(
                        "尚未添加 115 或 PikPak 网盘。可在「浏览 → +」登录 115 或 PikPak 后，在此直接一键离线下载秒传。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    offlineServers.forEach { server ->
                        val busy = busyServerId == server.id
                        Button(
                            onClick = {
                                if (busyServerId != null) return@Button
                                busyServerId = server.id
                                feedback = null
                                scope.launch {
                                    runCatching {
                                        when (val client = lib.client(server)) {
                                            is Pan115Client -> client.addOfflineUrl(magnetUri)
                                            is PikPakClient -> client.addOfflineUrl(magnetUri)
                                            else -> throw IllegalStateException("${server.type.label} 暂不支持离线任务")
                                        }
                                    }.onSuccess { msg ->
                                        feedback = msg
                                        succeededServer = server
                                    }.onFailure { e ->
                                        feedback = e.message ?: "添加离线任务失败"
                                    }
                                    busyServerId = null
                                }
                            },
                            enabled = busyServerId == null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (busy) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                                Spacer(Modifier.width(8.dp))
                            } else {
                                Icon(AppIcons.Cloud, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("离线到 ${server.name}（${server.type.label}）")
                        }
                    }
                }

                feedback?.let { msg ->
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (succeededServer != null) Orange else MaterialTheme.colorScheme.primary,
                    )
                }
                if (succeededServer != null && onOpenServer != null) {
                    TextButton(onClick = {
                        val s = succeededServer!!
                        onDismiss()
                        onOpenServer(s)
                    }) {
                        Text("前往「${succeededServer!!.name}」查看文件 →")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/** 管理与自定义发现页资源搜索源的弹窗 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchSourcesManageDialog(
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    var sources by remember { mutableStateOf(DiscoverSearchStore.sources(context)) }
    var newName by remember { mutableStateOf("") }
    var newCategory by remember { mutableStateOf(SearchCategory.PAN) }
    var newTemplate by remember { mutableStateOf("https://") }

    fun reload() {
        sources = DiscoverSearchStore.sources(context)
        onChanged()
    }

    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("管理资源搜索源") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "模板支持 {q}（编码后片名）、{raw}（原片名）、{tmdb}、{imdb} 占位符。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                sources.forEach { src ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${src.name} [${src.category.label}]", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                src.urlTemplate,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (!src.builtin) {
                            IconButton(onClick = {
                                DiscoverSearchStore.removeCustomSource(context, src.id)
                                reload()
                            }) {
                                Icon(AppIcons.Delete, "删除", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                        Switch(
                            checked = src.enabled,
                            onCheckedChange = { checked ->
                                DiscoverSearchStore.setSourceEnabled(context, src.id, checked)
                                reload()
                            },
                        )
                    }
                }

                HorizontalDivider()
                Text("新增自定义搜索站", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SearchCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = newCategory == cat,
                            onClick = { newCategory = cat },
                            label = { Text(cat.label) },
                        )
                    }
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text("站点名称") },
                    colors = appFieldColors(),
                    phoneLabel = "站点名称",
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = newTemplate,
                    onValueChange = { newTemplate = it },
                    singleLine = true,
                    label = {
                        Text(
                            "搜索地址模板（如 https://example.com/s?q={q}）",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    colors = appFieldColors(),
                    phoneLabel = "搜索模板",
                    modifier = Modifier.fillMaxWidth(),
                )
                val canAdd = newName.isNotBlank() && newTemplate.startsWith("http")
                Button(
                    enabled = canAdd,
                    onClick = {
                        DiscoverSearchStore.addCustomSource(context, newName, newCategory, newTemplate)
                        newName = ""
                        newTemplate = "https://"
                        reload()
                    },
                ) {
                    Text("添加到列表")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}
