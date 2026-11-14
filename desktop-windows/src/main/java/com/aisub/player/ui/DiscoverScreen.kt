package com.aisub.player.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.aisub.player.meta.SearchCategory
import com.aisub.player.meta.SearchSource
import com.aisub.player.meta.StreamingPlatform
import com.aisub.player.remote.Server
import com.aisub.player.remote.ServerType
import com.aisub.player.remote.cloud.Pan115Client
import com.aisub.player.remote.cloud.PikPakClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 发现页：参考影巢（re0.me）视觉排布，展示「流媒体榜单」平台入口与各分类横向海报栏；
 * 点开任意海报进入影视详情页，可一键跳转 Telegram 全局搜索、影巢、常用网盘与磁力搜索。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiscoverTab(
    settings: AppSettings,
    lib: LibraryViewModel,
    listState: LazyListState,
    onOpenDetail: (DiscoverItem) -> Unit,
    onOpenChart: (String?) -> Unit,
    onOpenTelegramSearch: (String) -> Unit,
    onOpenWeb: (String) -> Unit,
    onOpenMagnet: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wide = LocalWideLayout.current
    val tv = LocalTv.current

    var queryInput by rememberSaveable { mutableStateOf("") }
    var appliedQuery by rememberSaveable { mutableStateOf("") }
    var reloadNonce by remember { mutableIntStateOf(0) }
    var manageSourcesOpen by remember { mutableStateOf(false) }
    var sourcesVersion by remember { mutableIntStateOf(0) }

    var sections by remember { mutableStateOf<List<DiscoverHomeSection>>(emptyList()) }
    var homeLoading by remember { mutableStateOf(true) }
    var homeError by remember { mutableStateOf<String?>(null) }

    var searchResults by remember { mutableStateOf<List<DiscoverItem>>(emptyList()) }
    var searchLoading by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }

    var history by remember(sourcesVersion) { mutableStateOf(DiscoverSearchStore.history(context)) }
    val allSources = remember(sourcesVersion) {
        DiscoverSearchStore.sources(context).filter { it.enabled }
    }

    LaunchedEffect(settings.tmdbKey, settings.doubanEnabled, reloadNonce) {
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
        if (q.isEmpty()) {
            searchResults = emptyList()
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
        if (q.isNotEmpty() && MagnetLinks.normalizeMagnet(q) == null) {
            DiscoverSearchStore.recordHistory(context, q)
            history = DiscoverSearchStore.history(context)
        }
    }

    val detectedMagnet = remember(queryInput) { MagnetLinks.normalizeMagnet(queryInput) }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = 6.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        // 搜索框与快捷工具栏
        item(key = "discover_search_bar") {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = queryInput,
                        onValueChange = {
                            queryInput = it
                            if (it.isBlank()) appliedQuery = ""
                        },
                        singleLine = true,
                        placeholder = { Text("搜电影 / 剧集，或粘贴磁力 / Hash") },
                        leadingIcon = { Icon(AppIcons.Search, null) },
                        trailingIcon = {
                            if (queryInput.isNotEmpty()) {
                                IconButton(onClick = {
                                    queryInput = ""
                                    appliedQuery = ""
                                }) {
                                    Icon(AppIcons.Close, "清空")
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
                        colors = appFieldColors(),
                        phoneLabel = "影视发现搜索",
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { submitSearch() },
                        colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.White),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.tvFocusRing(RoundedCornerShape(10.dp)),
                    ) {
                        Text("搜索")
                    }
                }

                detectedMagnet?.let { mag ->
                    SurfaceCard(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(AppIcons.Link, null, tint = Orange)
                            Column(Modifier.weight(1f)) {
                                Text("识别到磁力链接 / InfoHash", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    MagnetLinks.displayName(mag),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Button(onClick = { onOpenMagnet(mag) }) {
                                Text("离线 / 打开")
                            }
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (settings.tmdbKey.isNotBlank()) "豆瓣热门 + TMDB 流媒体联动" else "豆瓣片单免 Key 直出（填写 TMDB Key 解锁流媒体实时榜）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { manageSourcesOpen = true }) {
                            Text("搜索源 (${allSources.size})")
                        }
                        TextButton(onClick = {
                            DiscoverRepository.clearCache()
                            reloadNonce++
                        }) {
                            Text("刷新")
                        }
                    }
                }

                if (appliedQuery.isBlank() && history.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        history.take(8).forEach { h ->
                            FilterChip(
                                selected = false,
                                onClick = { submitSearch(h) },
                                label = { Text(h, maxLines = 1) },
                            )
                        }
                        TextButton(onClick = {
                            DiscoverSearchStore.clearHistory(context)
                            history = emptyList()
                        }) {
                            Text("清空历史")
                        }
                    }
                }
            }
        }

        if (appliedQuery.isNotBlank()) {
            // 搜索态：先展示站内/站外直达卡片，再展示影视匹配结果
            item(key = "discover_quick_jump") {
                SurfaceCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("直接使用关键词「$appliedQuery」找资源", style = MaterialTheme.typography.titleSmall)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            DiscoverQuickJumpButton(
                                icon = AppIcons.Telegram,
                                label = "Telegram 全频道搜索",
                                onClick = { onOpenTelegramSearch(appliedQuery) },
                            )
                            allSources.firstOrNull { it.category == SearchCategory.RE0 }?.let { re0 ->
                                DiscoverQuickJumpButton(
                                    icon = AppIcons.Web,
                                    label = "影巢 re0.me 搜索",
                                    onClick = { onOpenWeb(re0.buildUrl(appliedQuery)) },
                                )
                            }
                            allSources.filter { it.category == SearchCategory.PAN }.take(2).forEach { src ->
                                DiscoverQuickJumpButton(
                                    icon = AppIcons.Cloud,
                                    label = src.name.substringBefore("（"),
                                    onClick = { onOpenWeb(src.buildUrl(appliedQuery)) },
                                )
                            }
                            allSources.filter { it.category == SearchCategory.MAGNET }.take(2).forEach { src ->
                                DiscoverQuickJumpButton(
                                    icon = AppIcons.Link,
                                    label = src.name.substringBefore("（"),
                                    onClick = { onOpenWeb(src.buildUrl(appliedQuery)) },
                                )
                            }
                        }
                    }
                }
            }

            when {
                searchLoading -> {
                    item(key = "search_loading") {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
                searchError != null && searchResults.isEmpty() -> {
                    item(key = "search_error") {
                        EmptyState("影视数据库搜索失败：$searchError\n仍可使用上方按钮直接去 Telegram / 影巢 / 网盘 / 磁力搜索", icon = AppIcons.Search)
                    }
                }
                searchResults.isEmpty() -> {
                    item(key = "search_empty") {
                        EmptyState("豆瓣与 TMDB 暂未搜到「$appliedQuery」\n可使用上方快捷按钮直接去 Telegram / 影巢 / 网盘找片源", icon = AppIcons.Movie)
                    }
                }
                else -> {
                    item(key = "search_results_header") {
                        SectionHeader("影视条目（点开进详情页搜片源）")
                    }
                    items(searchResults.chunked(if (wide || tv) 5 else 3), key = { row -> row.first().id }) { row ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
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
            // 默认浏览态：流媒体榜单平台条 + 横向海报栏
            item(key = "streaming_platforms") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader(title = "流媒体榜单")
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        items(DiscoverCharts.platforms, key = { it.id }) { platform ->
                            StreamingPlatformCard(
                                platform = platform,
                                onClick = { onOpenChart("platform_${platform.id}") },
                            )
                        }
                        item(key = "all_charts_btn") {
                            Box(
                                Modifier
                                    .tvFocusRing(RoundedCornerShape(10.dp), color = Color.White, scale = true)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Orange)
                                    .clickable { onOpenChart(null) }
                                    .padding(horizontal = 18.dp, vertical = 14.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        "查看全部榜单",
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                    )
                                    Icon(AppIcons.ArrowForward, null, tint = Color.White, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }

            if (homeLoading) {
                item(key = "home_loading") {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator()
                            Text("正在加载剧集与电影榜单…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else if (homeError != null && sections.isEmpty()) {
                item(key = "home_error") {
                    Column(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(homeError!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { DiscoverRepository.clearCache(); reloadNonce++ }) {
                                Text("重新加载")
                            }
                            OutlinedButton(onClick = { onOpenWeb("https://re0.me/") }) {
                                Text("直接访问 re0.me")
                            }
                        }
                    }
                }
            } else {
                items(sections, key = { it.chart.id }) { section ->
                    DiscoverChartRowSection(
                        section = section,
                        cardWidth = if (tv) 148.dp else if (wide) 142.dp else 118.dp,
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

@Composable
private fun StreamingPlatformCard(
    platform: StreamingPlatform,
    onClick: () -> Unit,
) {
    val palette = currentVisualPalette()
    val dark = isAppDarkTheme()
    val cardBg = if (dark) Color(0xFF151922) else palette.cardTint
    val borderColor = if (dark) Color(0x26FFFFFF) else Color(0x18000000)

    Row(
        Modifier
            .tvFocusRing(RoundedCornerShape(10.dp), scale = true)
            .clip(RoundedCornerShape(10.dp))
            .background(cardBg)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(7.dp))
                .background(Color(platform.accentHex))
                .padding(horizontal = 9.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                platform.badge,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.5.sp,
            )
        }
        Text(
            platform.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
private fun DiscoverChartRowSection(
    section: DiscoverHomeSection,
    cardWidth: Dp,
    onSeeAll: () -> Unit,
    onOpenDetail: (DiscoverItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    section.chart.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    section.chart.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onSeeAll) {
                Text("查看全部 →")
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
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
    width: Dp? = 120.dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .let { if (width != null) it.width(width) else it }
            .tvFocusRing(RoundedCornerShape(10.dp), scale = true)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(bottom = 4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            NetImage(
                url = item.posterUrl,
                modifier = Modifier.fillMaxSize(),
                maxSize = 500,
                contentScale = ContentScale.Crop,
                fallback = AppIcons.Movie,
            )

            // 左上角状态角标（连载中 / 已完结），参考 re0.me 风格
            item.ongoing?.let { isOngoing ->
                val bg = if (isOngoing) Orange.copy(alpha = 0.92f) else Color(0xCC1E222B)
                Text(
                    text = if (isOngoing) "连载中" else "已完结",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(bg)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
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
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .liquidGlass(
                        shape = RoundedCornerShape(5.dp),
                        style = GlassStyle.Pill,
                        tint = GlassTokens.DarkBadgeTint,
                        enableBackdrop = false,
                    )
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )

            // 左下角评分角标：★ 评分 来源
            item.rating?.let { rating ->
                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .liquidGlass(
                            shape = RoundedCornerShape(6.dp),
                            style = GlassStyle.Pill,
                            tint = GlassTokens.DarkBadgeTint,
                            enableBackdrop = false,
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(AppIcons.Star, null, tint = Color(0xFFFFC107), modifier = Modifier.size(11.dp))
                    Text(
                        text = buildString {
                            append("%.1f".format(rating))
                            item.ratingLabel?.let { append(" "); append(it) }
                        },
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
        val sub = item.subtitleLine()
        if (sub.isNotEmpty()) {
            Text(
                text = sub,
                style = MaterialTheme.typography.bodySmall,
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
    var page by remember(selectedChartId) { mutableIntStateOf(1) }
    var items by remember(selectedChartId) { mutableStateOf<List<DiscoverItem>>(emptyList()) }
    var note by remember(selectedChartId) { mutableStateOf<String?>(null) }
    var loading by remember(selectedChartId) { mutableStateOf(true) }
    var loadingMore by remember(selectedChartId) { mutableStateOf(false) }
    var error by remember(selectedChartId) { mutableStateOf<String?>(null) }
    var endReached by remember(selectedChartId) { mutableStateOf(false) }

    LaunchedEffect(selectedChartId, page, settings.tmdbKey) {
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
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(DiscoverCharts.allCharts, key = { it.id }) { c ->
                    FilterChip(
                        selected = c.id == chart.id,
                        onClick = {
                            if (selectedChartId != c.id) {
                                selectedChartId = c.id
                            }
                        },
                        label = { Text(c.title) },
                    )
                }
            }

            note?.let { msg ->
                SurfaceCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onOpenSettings) { Text("去填写 TMDB Key") }
                            TextButton(onClick = { onOpenWeb("https://re0.me/") }) { Text("打开影巢 re0.me") }
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
                        columns = GridCells.Adaptive(if (LocalTv.current) 156.dp else 112.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
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
 * 发现页条目进入的影视详情页：展示海报/背景图/Logo/多平台评分/演职员/简介，
 * 检测本地媒体库是否已有同片可直接播放，并提供 Telegram、影巢（re0.me）、常用网盘与磁力搜索入口。
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
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val libState by lib.state.collectAsStateWithLifecycle()
    val wide = LocalWideLayout.current
    val palette = currentVisualPalette()

    var detail by remember(item.id) { mutableStateOf(DetailMeta(loading = true)) }
    var sourcesVersion by remember { mutableIntStateOf(0) }
    var manageSourcesOpen by remember { mutableStateOf(false) }
    var pasteMagnetDialog by remember { mutableStateOf(false) }
    var statusTip by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(item.id, settings.tmdbKey, settings.doubanEnabled, settings.bangumiEnabled, settings.fanartKey) {
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

    val queryCandidates = remember(displayTitle, displayOrigTitle, displayYear) {
        buildList {
            if (displayTitle.isNotBlank()) add(displayTitle)
            if (displayOrigTitle.isNotBlank() && !displayOrigTitle.equals(displayTitle, ignoreCase = true)) {
                add(displayOrigTitle)
            }
            if (displayTitle.isNotBlank() && !displayYear.isNullOrBlank()) {
                add("$displayTitle $displayYear")
            }
        }.distinct()
    }
    var searchQuery by remember(item.id) { mutableStateOf(item.title) }
    val enrichedItem = remember(item, displayTmdbId, displayImdbId, displayDoubanId, displayTitle, displayOrigTitle) {
        item.copy(
            title = displayTitle,
            originalTitle = displayOrigTitle,
            tmdbId = displayTmdbId,
            imdbId = displayImdbId,
            doubanId = displayDoubanId,
        )
    }

    // 检索本地媒体库/播放历史里是否有同名作品，方便用户搜片前发现“本机已有”
    val localMatches = remember(libState.videos, libState.history, displayTitle, displayOrigTitle) {
        fun norm(s: String) = s.lowercase().replace(Regex("""[\s·・:：\-—_!！?？.'"`()\[\]【】]"""), "")
        val targets = listOfNotNull(displayTitle, displayOrigTitle.ifBlank { null }).map(::norm).filter { it.length >= 2 }
        if (targets.isEmpty()) emptyList()
        else {
            val all = (libState.videos + libState.history.map { it.entry }).distinctBy { it.uri }
            all.filter { e ->
                val parsed = norm(NameParser.parse(e.title).title)
                val raw = norm(e.title)
                targets.any { t -> parsed.contains(t) || raw.contains(t) }
            }.take(5)
        }
    }

    val allSources = remember(sourcesVersion) {
        DiscoverSearchStore.sources(context).filter { it.enabled }
    }
    val re0Sources = remember(allSources) { allSources.filter { it.category == SearchCategory.RE0 } }
    val panSources = remember(allSources) { allSources.filter { it.category == SearchCategory.PAN } }
    val magnetSources = remember(allSources) { allSources.filter { it.category == SearchCategory.MAGNET } }
    val telegramSources = remember(allSources) { allSources.filter { it.category == SearchCategory.TELEGRAM } }

    fun openExternalUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            statusTip = "没有可用的外部浏览器打开此链接，已尝试复制地址"
            clipboard.setText(AnnotatedString(url))
        }
    }

    DetailScaffold(displayTitle, onBack) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 顶部海报 + 横幅背景头部
            Box(Modifier.fillMaxWidth()) {
                if (displayBackdrop != null) {
                    Box(Modifier.fillMaxWidth().height(if (wide) 280.dp else 220.dp)) {
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
                                        palette.backdropFadeColor.copy(alpha = 0.72f),
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
                            top = if (displayBackdrop != null) (if (wide) 110.dp else 74.dp) else 12.dp,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    NetImage(
                        url = displayPoster,
                        modifier = Modifier
                            .width(if (wide) 136.dp else 108.dp)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(10.dp)),
                        maxSize = 500,
                        contentScale = ContentScale.Crop,
                        fallback = AppIcons.Movie,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (displayLogo != null) {
                            NetImage(
                                url = displayLogo,
                                modifier = Modifier.height(44.dp).fillMaxWidth(),
                                maxSize = 600,
                                contentScale = ContentScale.Fit,
                                fallback = AppIcons.Movie,
                            )
                        }
                        Text(
                            displayTitle,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        if (displayOrigTitle.isNotBlank() && displayOrigTitle != displayTitle) {
                            Text(
                                displayOrigTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
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

            // 本地已有同片：一键直接播放
            if (localMatches.isNotEmpty()) {
                SurfaceCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("本地 / 观看记录已有相似视频", style = MaterialTheme.typography.titleSmall, color = Orange)
                        localMatches.forEachIndexed { idx, entry ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { play(localMatches, idx) }
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(AppIcons.PlayArrow, null, tint = Orange)
                                Text(
                                    entry.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text("播放", style = MaterialTheme.typography.labelLarge, color = Orange)
                            }
                        }
                    }
                }
            }

            // 资源搜索关键词切换栏
            SurfaceCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("资源搜索关键词", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { manageSourcesOpen = true }) {
                            Text("管理搜索源")
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        queryCandidates.forEach { candidate ->
                            FilterChip(
                                selected = searchQuery == candidate,
                                onClick = { searchQuery = candidate },
                                label = { Text(candidate, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        label = { Text("自定义搜索词（下方全部搜索入口共用此词）") },
                        colors = appFieldColors(),
                        phoneLabel = "搜索关键词",
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(AppIcons.Close, "清空")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    statusTip?.let { tip ->
                        Text(tip, style = MaterialTheme.typography.bodySmall, color = Orange)
                    }
                }
            }

            // 四大资源搜索跳转区
            val activeQ = searchQuery.trim().ifBlank { displayTitle }

            // 1. Telegram 搜索
            ResourceJumpSectionCard(
                title = "Telegram 搜索",
                subtitle = "搜索已加入的影视频道 / 群消息并支持直接边下边播",
                icon = AppIcons.Telegram,
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { onOpenTelegramSearch(activeQ) },
                        colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.White),
                    ) {
                        Icon(AppIcons.Telegram, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("站内全频道搜索「$activeQ」")
                    }
                    OutlinedButton(onClick = {
                        clipboard.setText(AnnotatedString(activeQ))
                        statusTip = "已复制「$activeQ」，正前往 Telegram"
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("tg://")))
                        }.onFailure {
                            openExternalUrl("https://t.me/")
                        }
                    }) {
                        Icon(AppIcons.OpenInNew, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("复制并唤起外部 TG")
                    }
                    telegramSources.forEach { src ->
                        ResourceSourceActionRow(
                            source = src,
                            url = src.buildUrl(activeQ, enrichedItem),
                            onOpenInWeb = onOpenWeb,
                            onOpenExternal = ::openExternalUrl,
                        )
                    }
                }
            }

            // 2. 影巢 (re0.me) 搜索
            ResourceJumpSectionCard(
                title = "影巢（re0.me）搜索",
                subtitle = "跳转影巢站内检索片源、网友评论与分享的网盘/磁力链接",
                icon = AppIcons.Web,
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val re0Search = re0Sources.firstOrNull { it.id == "re0_search" }
                    val re0SearchUrl = (re0Search?.buildUrl(activeQ, enrichedItem))
                        ?: SearchSource("re0", "影巢", SearchCategory.RE0, "https://re0.me/search?q={q}").buildUrl(activeQ, enrichedItem)
                    Button(onClick = { onOpenWeb(re0SearchUrl) }) {
                        Icon(AppIcons.Web, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("内置浏览器搜「$activeQ」")
                    }
                    OutlinedButton(onClick = { openExternalUrl(re0SearchUrl) }) {
                        Icon(AppIcons.OpenInNew, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("系统浏览器打开")
                    }
                    displayTmdbId?.let { tid ->
                        val directRe0Url = "https://re0.me/${if (enrichedItem.kind == "tv") "tv" else "movie"}/$tid"
                        OutlinedButton(onClick = { onOpenWeb(directRe0Url) }) {
                            Text("影巢词条直达")
                        }
                    }
                    re0Sources.filter { it.id != "re0_search" }.forEach { src ->
                        ResourceSourceActionRow(
                            source = src,
                            url = src.buildUrl(activeQ, enrichedItem),
                            onOpenInWeb = onOpenWeb,
                            onOpenExternal = ::openExternalUrl,
                        )
                    }
                }
            }

            // 3. 常用网盘搜索
            ResourceJumpSectionCard(
                title = "常用网盘搜索",
                subtitle = "支持 115、阿里云盘、夸克、百度、迅雷等网盘分享检索（可增删自定义源）",
                icon = AppIcons.Cloud,
            ) {
                if (panSources.isEmpty()) {
                    TextButton(onClick = { manageSourcesOpen = true }) { Text("当前无启用的网盘搜索源，点此配置") }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        panSources.forEach { src ->
                            ResourceSourceActionRow(
                                source = src,
                                url = src.buildUrl(activeQ, enrichedItem),
                                onOpenInWeb = onOpenWeb,
                                onOpenExternal = ::openExternalUrl,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            // 4. 磁力链接搜索 & 一键离线
            ResourceJumpSectionCard(
                title = "磁力链接搜索 & 离线下载",
                subtitle = "在内置浏览器打开磁力站时，点击 magnet: 链接将自动弹出复制 / 外部下载 / 115 & PikPak 一键离线",
                icon = AppIcons.Link,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pasteMagnetDialog = true }) {
                            Icon(AppIcons.Download, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("粘贴磁力 / Hash 投喂离线")
                        }
                    }
                    if (magnetSources.isEmpty()) {
                        TextButton(onClick = { manageSourcesOpen = true }) { Text("当前无启用的磁力搜索源，点此配置") }
                    } else {
                        magnetSources.forEach { src ->
                            ResourceSourceActionRow(
                                source = src,
                                url = src.buildUrl(activeQ, enrichedItem),
                                onOpenInWeb = onOpenWeb,
                                onOpenExternal = ::openExternalUrl,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            // 简介、演职员、豆瓣/TMDB 参考链接
            if (displayOverview.isNotBlank() || info?.directors?.isNotEmpty() == true || info?.cast?.isNotEmpty() == true) {
                SurfaceCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("剧情简介与演职员", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (displayOverview.isNotBlank()) {
                            Text(displayOverview, style = MaterialTheme.typography.bodyMedium)
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
                                Text("提示：前往设置填写 TMDB API Key 可显示高清剧照与更多平台评分")
                            }
                        }
                    }
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
private fun DiscoverQuickJumpButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.tvFocusRing(RoundedCornerShape(10.dp)),
    ) {
        Icon(icon, null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1)
    }
}

@Composable
private fun ResourceJumpSectionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    content: @Composable () -> Unit,
) {
    SurfaceCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, tint = Orange, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
        }
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
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.7f))
            .tvFocusRing(RoundedCornerShape(10.dp))
            .clickable { onOpenInWeb(url) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(source.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (source.description.isNotBlank()) {
                Text(
                    source.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = { onOpenExternal(url) }) {
            Icon(AppIcons.OpenInNew, "系统浏览器打开", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(AppIcons.ArrowForward, null, tint = Orange, modifier = Modifier.size(18.dp))
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
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(AppIcons.Star, null, tint = Color(0xFFFFC107), modifier = Modifier.size(14.dp))
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
                    label = { Text("搜索地址模板（如 https://example.com/s?q={q}）") },
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
