package com.aisub.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aisub.player.AppSettings
import com.aisub.player.LibraryViewModel
import com.aisub.player.media.CustomMediaLibrary
import com.aisub.player.media.CustomMediaLibraryStore
import com.aisub.player.media.IndexedPlayHit
import com.aisub.player.media.LibraryFolderBinding
import com.aisub.player.media.LocalBrowser
import com.aisub.player.media.MediaEntry
import com.aisub.player.media.MediaLibraryType
import com.aisub.player.media.ScrapeProgress
import com.aisub.player.media.ScrapedEpisode
import com.aisub.player.media.ScrapedMediaItem
import com.aisub.player.media.formatDuration
import com.aisub.player.media.formatSize
import com.aisub.player.meta.DiscoverItem
import com.aisub.player.remote.RemoteItem
import com.aisub.player.remote.Server
import com.aisub.player.remote.ServerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private enum class CustomLibSort(val label: String) {
    RECENT("最近添加"),
    RATING("最高评分"),
    YEAR("最新年份"),
    NAME("按片名"),
}

/**
 * 首页「媒体库」核心板块：
 * - 支持新建/编辑「电影」或「电视剧」媒体库，名称自由定义，可选取任意云存储（115、阿里云盘、百度网盘、PikPak、OneDrive、WebDAV、OpenList 等）或本机特定文件夹自动刮削；
 * - 提供「跨库检索」统一索引入口，一键并发检索自定义刮削媒体库 + Emby / Jellyfin / Plex 媒体服务器并直接播放。
 */
@Composable
fun HomeMediaLibrariesSection(
    lib: LibraryViewModel,
    settings: AppSettings,
    onOpenLibrary: (String) -> Unit,
    onOpenItem: (libraryId: String, itemId: String) -> Unit,
    onOpenServerDetail: (serverId: String, ratingKey: String) -> Unit,
    onOpenRemote: (server: Server, path: String, title: String) -> Unit,
    onAddCloudSource: () -> Unit,
    play: (List<MediaEntry>, Int) -> Unit,
) {
    val context = LocalContext.current
    val tv = LocalTv.current
    val wide = LocalWideLayout.current
    LaunchedEffect(Unit) { CustomMediaLibraryStore.ensureInit(context) }

    val libraries by CustomMediaLibraryStore.libraries.collectAsStateWithLifecycle()
    val itemsMap by CustomMediaLibraryStore.itemsByLibrary.collectAsStateWithLifecycle()
    val progressMap by CustomMediaLibraryStore.scrapeProgress.collectAsStateWithLifecycle()
    val libState by lib.state.collectAsStateWithLifecycle()

    var editingLibrary by remember { mutableStateOf<CustomMediaLibrary?>(null) }
    var createDialogOpen by remember { mutableStateOf(false) }
    var unifiedSearchOpen by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = PageGutter, end = PageGutter - 6.dp, top = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "媒体库",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (libraries.isNotEmpty() || libState.servers.any { it.type.isMediaServer }) {
                TextButton(
                    onClick = { unifiedSearchOpen = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.tvFocusRing(RoundedCornerShape(8.dp)),
                ) {
                    Icon(AppIcons.Search, null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("跨库检索", fontSize = 13.sp)
                }
            }
            TextButton(
                onClick = {
                    editingLibrary = null
                    createDialogOpen = true
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.tvFocusRing(RoundedCornerShape(8.dp)),
            ) {
                Icon(AppIcons.Add, null, modifier = Modifier.size(16.dp), tint = Orange)
                Spacer(Modifier.width(3.dp))
                Text("添加媒体库", color = Orange, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }

        if (libraries.isEmpty()) {
            SurfaceCard {
                ListRow(
                    title = "添加自定义电影 / 电视剧媒体库",
                    subtitle = "选择云存储（115、阿里云盘、百度网盘、PikPak、WebDAV、OpenList 等）或本机特定文件夹刮削海报墙，并与 Emby / Plex 联动索引播放",
                    icon = AppIcons.Movie,
                    trailing = {
                        Button(
                            onClick = {
                                editingLibrary = null
                                createDialogOpen = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.White),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text("新建", fontSize = 12.sp)
                        }
                    },
                    onClick = {
                        editingLibrary = null
                        createDialogOpen = true
                    },
                )
            }
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(libraries, key = { it.id }) { library ->
                    val libItems = itemsMap[library.id].orEmpty()
                    val prog = progressMap[library.id]
                    LibrarySummaryCard(
                        library = library,
                        scrapedItems = libItems,
                        progress = prog,
                        width = if (tv) 236.dp else if (wide) 220.dp else 196.dp,
                        onClick = { onOpenLibrary(library.id) },
                        onScrapeNow = {
                            CustomMediaLibraryStore.startScrape(context, library.id, settings, lib)
                        },
                        onCancelScrape = {
                            CustomMediaLibraryStore.cancelScrape(library.id)
                        },
                        onEdit = {
                            editingLibrary = library
                            createDialogOpen = true
                        },
                        onDelete = {
                            CustomMediaLibraryStore.deleteLibrary(context, library.id)
                        },
                    )
                }
            }

            // 每个已刮削出条目的媒体库直接在首页展示精简海报预览行，点海报直达影片详情/选集播放
            libraries.forEach { library ->
                val libItems = itemsMap[library.id].orEmpty()
                if (libItems.isNotEmpty()) {
                    SectionHeader(
                        title = "${library.name} · ${library.type.label}",
                        actionLabel = "全部 ${libItems.size} 部",
                        onAction = { onOpenLibrary(library.id) },
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(libItems.take(12), key = { it.id }) { mediaItem ->
                            ScrapedPosterCard(
                                item = mediaItem,
                                width = if (tv) 142.dp else if (wide) 130.dp else 108.dp,
                                onClick = { onOpenItem(library.id, mediaItem.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (createDialogOpen) {
        EditCustomLibraryDialog(
            initial = editingLibrary,
            lib = lib,
            onAddCloudSource = {
                createDialogOpen = false
                onAddCloudSource()
            },
            onDismiss = { createDialogOpen = false },
            onSaved = { saved, scrapeImmediately ->
                createDialogOpen = false
                if (scrapeImmediately) {
                    CustomMediaLibraryStore.startScrape(context, saved.id, settings, lib)
                }
            },
        )
    }

    if (unifiedSearchOpen) {
        UnifiedMediaIndexDialog(
            lib = lib,
            onDismiss = { unifiedSearchOpen = false },
            onOpenCustomItem = { libId, itemId ->
                unifiedSearchOpen = false
                onOpenItem(libId, itemId)
            },
            onOpenServerDetail = { sid, rk ->
                unifiedSearchOpen = false
                onOpenServerDetail(sid, rk)
            },
            onOpenRemote = { srv, path, title ->
                unifiedSearchOpen = false
                onOpenRemote(srv, path, title)
            },
            onPlay = { list, idx ->
                unifiedSearchOpen = false
                play(list, idx)
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibrarySummaryCard(
    library: CustomMediaLibrary,
    scrapedItems: List<ScrapedMediaItem>,
    progress: ScrapeProgress?,
    width: Dp,
    onClick: () -> Unit,
    onScrapeNow: () -> Unit,
    onCancelScrape: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val cover = scrapedItems.firstOrNull { !it.backdropUrl.isNullOrBlank() }?.backdropUrl
        ?: scrapedItems.firstOrNull { !it.posterUrl.isNullOrBlank() }?.posterUrl
        ?: library.coverUrl
    val count = if (scrapedItems.isNotEmpty()) scrapedItems.size else library.itemCount
    val running = progress?.running == true

    Column(
        Modifier
            .width(width)
            .tvFocusRing(RoundedCornerShape(14.dp), scale = true)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            if (!cover.isNullOrBlank()) {
                NetImage(
                    url = cover,
                    modifier = Modifier.fillMaxSize(),
                    maxSize = 640,
                    contentScale = ContentScale.Crop,
                    fallback = AppIcons.Movie,
                )
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                if (library.type == MediaLibraryType.MOVIE) {
                                    listOf(Color(0xFF2B2118), Color(0xFF5D3A1A))
                                } else {
                                    listOf(Color(0xFF172536), Color(0xFF284B63))
                                },
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (library.type == MediaLibraryType.MOVIE) AppIcons.Movie else AppIcons.Live,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.35f),
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.25f to Color.Transparent,
                            1f to Color(0xCC000000),
                        ),
                    ),
            )

            // 左上角类型徽章（电影 / 电视剧）
            Text(
                text = library.type.label,
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(7.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (library.type == MediaLibraryType.MOVIE) Orange else Color(0xFF1976D2))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )

            // 右上角管理菜单
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp),
            ) {
                OverflowMenu(
                    actions = buildList {
                        if (running) {
                            add(MenuAction("取消刮削", onClick = onCancelScrape))
                        } else {
                            add(MenuAction("立即刮削更新", onClick = onScrapeNow))
                        }
                        add(MenuAction("编辑名称与文件夹", onClick = onEdit))
                        add(MenuAction("删除媒体库", onClick = onDelete))
                    },
                    compact = true,
                )
            }

            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    text = library.name,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        running -> progress?.stage ?: "正在刮削…"
                        count > 0 -> "共 $count 部 · ${library.folders.size} 个目录"
                        else -> "点此进入或点右上角刮削目录"
                    },
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (running) {
            val frac = if ((progress?.totalGroups ?: 0) > 0) {
                (progress!!.scrapedCount.toFloat() / progress.totalGroups).coerceIn(0f, 1f)
            } else null
            if (frac != null) {
                LinearProgressIndicator(
                    progress = { frac },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = Orange,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = Orange,
                )
            }
        }
    }
}

@Composable
fun ScrapedPosterCard(
    item: ScrapedMediaItem,
    width: Dp? = null,
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
                maxSize = 480,
                contentScale = ContentScale.Crop,
                fallback = AppIcons.Movie,
            )

            item.badgeText?.let { badge ->
                Text(
                    text = badge,
                    color = Color.White,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .liquidGlass(
                            shape = RoundedCornerShape(5.dp),
                            style = GlassStyle.Pill,
                            tint = GlassTokens.DarkBadgeTint,
                            enableBackdrop = false,
                        )
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }

            item.rating?.let { r ->
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
                            append("%.1f".format(r))
                            item.ratingLabel?.let { append(" "); append(it) }
                        },
                        color = Color.White,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        Text(
            text = item.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
        )
        item.year?.let { y ->
            Text(
                text = y.toString(),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

/** 新建或编辑自定义媒体库（名称可自定义、类型选电影/电视剧、绑定多个云存储特定文件夹或本地文件夹） */
@Composable
fun EditCustomLibraryDialog(
    initial: CustomMediaLibrary?,
    lib: LibraryViewModel,
    onAddCloudSource: () -> Unit,
    onDismiss: () -> Unit,
    onSaved: (CustomMediaLibrary, Boolean) -> Unit,
) {
    val context = LocalContext.current
    var type by remember(initial) { mutableStateOf(initial?.type ?: MediaLibraryType.MOVIE) }
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    val folders = remember(initial) {
        mutableStateListOf<LibraryFolderBinding>().apply {
            initial?.folders?.let { addAll(it) }
        }
    }
    var pickingFolder by remember { mutableStateOf(false) }

    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建媒体库" else "编辑媒体库") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("媒体库类型", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MediaLibraryType.entries.forEach { t ->
                        FilterChip(
                            selected = type == t,
                            onClick = { type = t },
                            leadingIcon = {
                                Icon(
                                    if (t == MediaLibraryType.MOVIE) AppIcons.Movie else AppIcons.Live,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            label = { Text(t.label) },
                        )
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("媒体库名称（可自定义）") },
                    placeholder = { Text(if (type == MediaLibraryType.MOVIE) "例如：4K 院线电影、漫威合集" else "例如：追剧库、日韩美剧") },
                    singleLine = true,
                    phoneInput = true,
                    phoneLabel = "媒体库名称",
                    colors = appFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "绑定的云存储 / 本地文件夹 (${folders.size})",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { pickingFolder = true }) {
                        Icon(AppIcons.Add, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("选择文件夹")
                    }
                }

                if (folders.isEmpty()) {
                    Text(
                        "请点击「选择文件夹」指定云盘（115、阿里云盘、百度网盘、PikPak、OneDrive、WebDAV、OpenList 等）或本机中的特定影视目录，保存后将自动递归扫描并刮削海报、评分与分集信息。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    folders.forEach { binding ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                if (binding.serverId == null) AppIcons.Phone else AppIcons.Cloud,
                                contentDescription = null,
                                tint = Orange,
                                modifier = Modifier.size(18.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(binding.displayPath, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (binding.serverId == null) "本机文件夹" else "云存储 · ${binding.serverName}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(
                                onClick = { folders.removeAll { it.key == binding.key } },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(AppIcons.Close, "移除目录", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (initial != null) {
                    OutlinedButton(
                        enabled = folders.isNotEmpty(),
                        onClick = {
                            val saved = CustomMediaLibraryStore.saveLibrary(
                                context,
                                initial.copy(
                                    name = name.trim().ifEmpty { type.label },
                                    type = type,
                                    folders = folders.toList(),
                                ),
                            )
                            onSaved(saved, false)
                        },
                    ) {
                        Text("仅保存")
                    }
                }
                Button(
                    enabled = folders.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.White),
                    onClick = {
                        val libObj = CustomMediaLibrary(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            name = name.trim().ifEmpty { type.label },
                            type = type,
                            folders = folders.toList(),
                            createdAt = initial?.createdAt ?: System.currentTimeMillis(),
                            lastScrapedAt = initial?.lastScrapedAt ?: 0L,
                            itemCount = initial?.itemCount ?: 0,
                            coverUrl = initial?.coverUrl,
                        )
                        val saved = CustomMediaLibraryStore.saveLibrary(context, libObj)
                        onSaved(saved, true)
                    },
                ) {
                    Text("保存并刮削")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )

    if (pickingFolder) {
        CloudFolderPickerDialog(
            lib = lib,
            onAddCloudSource = {
                pickingFolder = false
                onAddCloudSource()
            },
            onDismiss = { pickingFolder = false },
            onPicked = { binding ->
                if (folders.none { it.key == binding.key }) {
                    folders += binding
                    if (name.isBlank()) {
                        val leaf = binding.displayPath.substringAfterLast('/').trim()
                        if (leaf.isNotBlank() && leaf != "根目录") name = leaf
                    }
                }
                pickingFolder = false
            },
        )
    }
}

/** 逐级浏览云盘 / WebDAV / OpenList / 本机存储并选定特定文件夹 */
@Composable
private fun CloudFolderPickerDialog(
    lib: LibraryViewModel,
    onAddCloudSource: () -> Unit,
    onDismiss: () -> Unit,
    onPicked: (LibraryFolderBinding) -> Unit,
) {
    val libState by lib.state.collectAsStateWithLifecycle()
    val cloudServers = remember(libState.servers) {
        libState.servers.filter { !it.type.isMediaServer }
    }

    // null 代表尚未选择存储源；ChosenSource(null) 代表本机存储；ChosenSource(server) 代表某个网盘/NAS
    var selectedSourceSelected by remember { mutableStateOf(false) }
    var selectedServer by remember { mutableStateOf<Server?>(null) }

    data class Crumb(val path: String, val label: String)
    val stack = remember { mutableStateListOf<Crumb>() }
    var subDirs by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val curCrumb = stack.lastOrNull()

    LaunchedEffect(selectedSourceSelected, selectedServer?.id, curCrumb?.path) {
        if (!selectedSourceSelected || curCrumb == null) return@LaunchedEffect
        loading = true
        error = null
        val srv = selectedServer
        if (srv == null) {
            val (folders, _) = LocalBrowser.children(libState.videos, curCrumb.path)
            subDirs = folders.map { it.path to "${it.name}（${it.fileCount} 个视频）" }
            loading = false
        } else {
            runCatching {
                withContext(Dispatchers.IO) {
                    lib.client(srv).list(curCrumb.path)
                        .filter { it.isDir && !it.name.startsWith(".") }
                        .sortedBy { it.name.lowercase() }
                        .map { it.path to it.name }
                }
            }.onSuccess { list ->
                subDirs = list
            }.onFailure { e ->
                error = e.message ?: "读取网盘目录失败"
                subDirs = emptyList()
            }
            loading = false
        }
    }

    AppDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (!selectedSourceSelected) "选择存储来源"
                else "选择文件夹 · ${selectedServer?.name ?: "本机存储"}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!selectedSourceSelected) {
                    Text(
                        "先选择已登录的云盘 / WebDAV / OpenList 或本机存储，进入后再逐级点进具体文件夹：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    cloudServers.forEach { srv ->
                        ListRow(
                            title = srv.name,
                            subtitle = srv.type.label,
                            leading = { ServerSourceIcon(srv, size = 34.dp) },
                            onClick = {
                                selectedServer = srv
                                stack.clear()
                                stack += Crumb(srv.rootPath, if (srv.rootPath.isBlank() || srv.rootPath == "/") "根目录" else srv.rootPath)
                                selectedSourceSelected = true
                            },
                        )
                    }
                    ListRow(
                        title = "本机存储文件夹",
                        subtitle = "从当前设备的本地视频文件夹中选择",
                        icon = AppIcons.Phone,
                        onClick = {
                            selectedServer = null
                            stack.clear()
                            stack += Crumb("", "内部存储根目录")
                            selectedSourceSelected = true
                        },
                    )
                    OutlinedButton(
                        onClick = onAddCloudSource,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    ) {
                        Icon(AppIcons.Add, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("添加新的网盘 / WebDAV 存储源")
                    }
                } else {
                    val breadcrumbText = stack.joinToString(" / ") { it.label }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AppIcons.Folder, null, tint = Orange, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            breadcrumbText,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                if (stack.size > 1) {
                                    stack.removeAt(stack.lastIndex)
                                } else {
                                    selectedSourceSelected = false
                                }
                            },
                        ) {
                            Icon(AppIcons.ArrowBack, null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (stack.size > 1) "上级目录" else "切换存储源")
                        }
                    }

                    when {
                        loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                        }
                        error != null -> Text(
                            "加载目录失败：$error",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        subDirs.isEmpty() -> Text(
                            "当前目录下没有子文件夹，可直接点击下方「选定当前文件夹」进行刮削。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                        else -> {
                            subDirs.forEach { (childPath, label) ->
                                ListRow(
                                    title = label,
                                    icon = AppIcons.Folder,
                                    trailing = { Icon(AppIcons.ChevronRight, null, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        stack += Crumb(childPath, label.substringBefore("（"))
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (selectedSourceSelected && curCrumb != null) {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.White),
                    onClick = {
                        val srv = selectedServer
                        val readableTrail = stack.drop(if (stack.size > 1) 1 else 0).joinToString("/") { it.label }
                        val display = "${srv?.name ?: "本机"}:/$readableTrail"
                        onPicked(
                            LibraryFolderBinding(
                                serverId = srv?.id,
                                serverName = srv?.name ?: "本机",
                                path = curCrumb.path,
                                displayPath = display,
                            ),
                        )
                    },
                ) {
                    Text("选定当前文件夹")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 自定义媒体库海报墙全屏页：搜索、排序、类型筛选、刮削进度、海报网格。
 */
@Composable
fun CustomLibraryScreen(
    libraryId: String,
    lib: LibraryViewModel,
    settings: AppSettings,
    onBack: () -> Unit,
    onOpenItem: (itemId: String) -> Unit,
    onOpenServerDetail: (serverId: String, ratingKey: String) -> Unit,
    onOpenRemote: (server: Server, path: String, title: String) -> Unit,
    play: (List<MediaEntry>, Int) -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(libraryId) { CustomMediaLibraryStore.ensureInit(context) }

    val libraries by CustomMediaLibraryStore.libraries.collectAsStateWithLifecycle()
    val itemsMap by CustomMediaLibraryStore.itemsByLibrary.collectAsStateWithLifecycle()
    val progressMap by CustomMediaLibraryStore.scrapeProgress.collectAsStateWithLifecycle()

    val library = libraries.firstOrNull { it.id == libraryId }
    val rawItems = itemsMap[libraryId].orEmpty()
    val progress = progressMap[libraryId]

    var sort by remember { mutableStateOf(CustomLibSort.RECENT) }
    var selectedGenre by remember { mutableStateOf<String?>(null) }
    var filterQuery by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var editOpen by remember { mutableStateOf(false) }
    var unifiedSearchOpen by remember { mutableStateOf(false) }

    if (library == null) {
        DetailScaffold("媒体库", onBack) {
            EmptyState("未找到该媒体库，可能已被删除", icon = AppIcons.Movie)
        }
        return
    }

    val allGenres = remember(rawItems) {
        rawItems.flatMap { it.genres }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(12).map { it.key }
    }

    val filteredItems = remember(rawItems, sort, selectedGenre, filterQuery) {
        val q = filterQuery.trim().lowercase()
        val base = rawItems.filter { item ->
            (selectedGenre == null || selectedGenre in item.genres) &&
                (q.isEmpty() || item.title.lowercase().contains(q) || item.originalTitle.lowercase().contains(q))
        }
        when (sort) {
            CustomLibSort.RECENT -> base.sortedByDescending { it.addedAt }
            CustomLibSort.RATING -> base.sortedByDescending { it.rating ?: 0.0 }
            CustomLibSort.YEAR -> base.sortedByDescending { it.year ?: 0 }
            CustomLibSort.NAME -> base.sortedBy { it.title.lowercase() }
        }
    }

    DetailScaffold(
        title = "${library.name} · ${library.type.label} (${rawItems.size})",
        onBack = onBack,
        actions = {
            IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) filterQuery = "" }) {
                Icon(if (searchOpen) AppIcons.Close else AppIcons.Search, "筛选库内影片")
            }
            IconButton(
                onClick = {
                    if (progress?.running == true) CustomMediaLibraryStore.cancelScrape(libraryId)
                    else CustomMediaLibraryStore.startScrape(context, libraryId, settings, lib)
                },
            ) {
                Icon(if (progress?.running == true) AppIcons.Close else AppIcons.Refresh, "刮削更新")
            }
            OverflowMenu(
                listOf(
                    MenuAction("跨媒体库与 Emby/Plex 检索") { unifiedSearchOpen = true },
                    MenuAction("编辑名称与绑定目录") { editOpen = true },
                    MenuAction("重新扫描并刮削") { CustomMediaLibraryStore.startScrape(context, libraryId, settings, lib) },
                    MenuAction("删除该媒体库") {
                        CustomMediaLibraryStore.deleteLibrary(context, libraryId)
                        onBack()
                    },
                ),
            )
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            AnimatedVisibility(visible = searchOpen) {
                OutlinedTextField(
                    value = filterQuery,
                    onValueChange = { filterQuery = it },
                    placeholder = { Text("在「${library.name}」中搜索片名…") },
                    singleLine = true,
                    phoneInput = true,
                    phoneLabel = "搜索媒体库",
                    colors = appFieldColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }

            if (progress != null && (progress.running || progress.error != null)) {
                SurfaceCard(Modifier.padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (progress.running) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Orange)
                            }
                            Text(
                                text = progress.error?.let { "刮削出错：$it" } ?: progress.stage,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (progress.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            if (progress.running) {
                                TextButton(onClick = { CustomMediaLibraryStore.cancelScrape(libraryId) }) {
                                    Text("停止", fontSize = 12.sp)
                                }
                            }
                        }
                        if (progress.running && progress.totalGroups > 0) {
                            LinearProgressIndicator(
                                progress = { (progress.scrapedCount.toFloat() / progress.totalGroups).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                                color = Orange,
                            )
                        }
                    }
                }
            }

            // 排序 + 类型筛选栏
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CustomLibSort.entries.forEach { s ->
                    FilterChip(
                        selected = sort == s,
                        onClick = { sort = s },
                        label = { Text(s.label, fontSize = 12.sp) },
                    )
                }
                allGenres.forEach { g ->
                    FilterChip(
                        selected = selectedGenre == g,
                        onClick = { selectedGenre = if (selectedGenre == g) null else g },
                        label = { Text(g, fontSize = 12.sp) },
                    )
                }
            }

            if (filteredItems.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(AppIcons.Movie, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                        Text(
                            if (rawItems.isEmpty()) "该媒体库尚未刮削出影视条目" else "没有符合筛选条件的影片",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (rawItems.isEmpty() && progress?.running != true) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(onClick = { editOpen = true }) { Text("修改文件夹") }
                                Button(
                                    colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.White),
                                    onClick = { CustomMediaLibraryStore.startScrape(context, libraryId, settings, lib) },
                                ) {
                                    Text("开始刮削")
                                }
                            }
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(if (LocalTv.current) 148.dp else 108.dp),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(filteredItems, key = { it.id }) { item ->
                        ScrapedPosterCard(
                            item = item,
                            width = null,
                            onClick = { onOpenItem(item.id) },
                        )
                    }
                }
            }
        }
    }

    if (editOpen) {
        EditCustomLibraryDialog(
            initial = library,
            lib = lib,
            onAddCloudSource = { editOpen = false },
            onDismiss = { editOpen = false },
            onSaved = { saved, scrapeNow ->
                editOpen = false
                if (scrapeNow) {
                    CustomMediaLibraryStore.startScrape(context, saved.id, settings, lib)
                }
            },
        )
    }

    if (unifiedSearchOpen) {
        UnifiedMediaIndexDialog(
            lib = lib,
            onDismiss = { unifiedSearchOpen = false },
            onOpenCustomItem = { libId, itemId ->
                unifiedSearchOpen = false
                if (libId == libraryId) onOpenItem(itemId)
            },
            onOpenServerDetail = { sid, rk ->
                unifiedSearchOpen = false
                onOpenServerDetail(sid, rk)
            },
            onOpenRemote = { srv, path, title ->
                unifiedSearchOpen = false
                onOpenRemote(srv, path, title)
            },
            onPlay = { list, idx ->
                unifiedSearchOpen = false
                play(list, idx)
            },
        )
    }
}

/**
 * 自定义刮削媒体库单部电影 / 电视剧详情页：
 * - 支持分季分集列表与跨端续播进度展示；
 * - 自动并发索引其他媒体库与 Emby / Jellyfin / Plex 媒体服务器同名片源；
 * - 内置「AI 智能推荐」与「相似作品推荐」。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomLibraryItemDetailScreen(
    libraryId: String,
    itemId: String,
    lib: LibraryViewModel,
    settings: AppSettings,
    onUpdateSettings: (AppSettings) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    play: (List<MediaEntry>, Int) -> Unit,
    onOpenDiscoverDetail: (DiscoverItem) -> Unit,
    onOpenServerDetail: (serverId: String, ratingKey: String) -> Unit,
    onOpenRemote: (server: Server, path: String, title: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wide = LocalWideLayout.current
    val palette = currentVisualPalette()
    val libState by lib.state.collectAsStateWithLifecycle()

    val itemsMap by CustomMediaLibraryStore.itemsByLibrary.collectAsStateWithLifecycle()
    val item = itemsMap[libraryId]?.firstOrNull { it.id == itemId }

    var rematchOpen by remember { mutableStateOf(false) }
    var rematching by remember { mutableStateOf(false) }

    if (item == null) {
        DetailScaffold("影片详情", onBack) {
            EmptyState("未找到该影视条目", icon = AppIcons.Movie)
        }
        return
    }

    val historyByUri = remember(libState.history) {
        libState.history.associateBy { it.entry.uri }
    }
    val seasons = remember(item.episodes) {
        item.episodes.map { it.season }.distinct().sorted()
    }
    var selectedSeason by remember(item.id, seasons) {
        mutableIntStateOf(seasons.firstOrNull { it > 0 } ?: seasons.firstOrNull() ?: 1)
    }
    val seasonEpisodes = remember(item.episodes, selectedSeason) {
        if (seasons.size <= 1) item.episodes else item.episodes.filter { it.season == selectedSeason }
    }
    val fullPlayQueue = remember(item.episodes, item.title) {
        item.episodes.map { it.toMediaEntry(if (item.kind == "tv") item.title else null) }
    }

    // 找到最近观看过的那一集以便一键「继续播放」
    val resumeTargetIndex = remember(item.episodes, libState.history) {
        val watched = item.episodes.mapIndexedNotNull { idx, ep ->
            historyByUri[ep.playUrl]?.let { h -> idx to h }
        }.maxByOrNull { it.second.playedAt }
        watched?.first ?: 0
    }
    val resumeEp = item.episodes.getOrNull(resumeTargetIndex)
    val resumeHistory = resumeEp?.let { historyByUri[it.playUrl] }

    // 跨媒体库与 Emby / Jellyfin / Plex 联动片源索引
    var indexedHits by remember(item.id, libState.servers) { mutableStateOf<List<IndexedPlayHit>>(emptyList()) }
    LaunchedEffect(item.id, item.title, item.tmdbId, libState.servers) {
        val all = CustomMediaLibraryStore.queryAllIndexedSources(
            context = context,
            lib = lib,
            title = item.title,
            origTitle = item.originalTitle,
            tmdbId = item.tmdbId,
            doubanId = item.doubanId,
            imdbId = item.imdbId,
            kind = item.kind,
        )
        // 排除当前正在查看的自身条目，保留其他媒体库及 Emby / Plex / Jellyfin / OpenList 命中源
        indexedHits = all.filterNot {
            it is IndexedPlayHit.CustomLibraryHit && it.library.id == libraryId && it.item.id == item.id
        }
    }

    val recentTitles = remember(libState.history) {
        libState.history.filter { it.entry.isVideo && !it.entry.isLive }
            .map { it.entry.displayTitle }
            .distinct()
            .take(8)
    }

    DetailScaffold(
        title = item.title,
        onBack = onBack,
        actions = {
            OverflowMenu(
                listOf(
                    MenuAction("手动修正影片匹配…") { rematchOpen = true },
                ),
            )
        },
    ) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // 1. 顶部背景横幅 + 海报 + 基本信息
            item(key = "header") {
                val backdrop = item.backdropUrl ?: item.posterUrl
                Box(Modifier.fillMaxWidth()) {
                    if (!backdrop.isNullOrBlank()) {
                        Box(Modifier.fillMaxWidth().height(if (wide) 260.dp else 204.dp)) {
                            NetImage(
                                url = backdrop,
                                modifier = Modifier.fillMaxSize(),
                                maxSize = 1280,
                                contentScale = ContentScale.Crop,
                                fallback = AppIcons.Movie,
                            )
                            Box(
                                Modifier.fillMaxSize().background(
                                    Brush.verticalGradient(
                                        listOf(
                                            Color(0x44000000),
                                            palette.backdropFadeColor.copy(alpha = 0.76f),
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
                                top = if (!backdrop.isNullOrBlank()) (if (wide) 98.dp else 64.dp) else 12.dp,
                            ),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        NetImage(
                            url = item.posterUrl,
                            modifier = Modifier
                                .width(if (wide) 132.dp else 104.dp)
                                .aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(10.dp)),
                            maxSize = 500,
                            contentScale = ContentScale.Crop,
                            fallback = AppIcons.Movie,
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(
                                item.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            if (item.originalTitle.isNotBlank() && item.originalTitle != item.title) {
                                Text(
                                    item.originalTitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            val metaLine = buildList {
                                item.year?.let { add(it.toString()) }
                                if (item.countries.isNotEmpty()) add(item.countries.take(2).joinToString(" / "))
                                if (item.genres.isNotEmpty()) add(item.genres.take(3).joinToString(" · "))
                                item.badgeText?.let { add(it) }
                            }.joinToString(" · ")
                            if (metaLine.isNotEmpty()) {
                                Text(metaLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                item.rating?.let { r ->
                                    Row(
                                        Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                            .padding(horizontal = 8.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Icon(AppIcons.Star, null, tint = Color(0xFFFFC107), modifier = Modifier.size(13.dp))
                                        Text(
                                            "${item.ratingLabel ?: "评分"} %.1f".format(r),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                                if (rematching) {
                                    CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                                }
                            }
                        }
                    }
                }
            }

            // 2. 主播放按钮与重新匹配按钮
            item(key = "actions") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        enabled = fullPlayQueue.isNotEmpty(),
                        onClick = { play(fullPlayQueue, resumeTargetIndex.coerceIn(0, (fullPlayQueue.size - 1).coerceAtLeast(0))) },
                        colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.White),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .tvFocusRing(RoundedCornerShape(12.dp), color = Color.White),
                    ) {
                        Icon(AppIcons.PlayArrow, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        val playLabel = when {
                            item.kind == "tv" && resumeEp != null && (resumeHistory?.positionMs ?: 0L) > 5_000L ->
                                "继续播放 ${resumeEp.episodeLabel}（${formatDuration(resumeHistory!!.positionMs)}）"
                            item.kind == "tv" && resumeEp != null ->
                                "播放 ${resumeEp.episodeLabel}"
                            (resumeHistory?.positionMs ?: 0L) > 10_000L ->
                                "继续播放（${formatDuration(resumeHistory!!.positionMs)}）"
                            else -> "播放"
                        }
                        Text(playLabel, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = { rematchOpen = true },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(44.dp).tvFocusRing(RoundedCornerShape(12.dp)),
                    ) {
                        Icon(AppIcons.Edit, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("匹配修正")
                    }
                }
            }

            // 3. 联动其他媒体库与 Emby / Jellyfin / Plex 索引片源
            if (indexedHits.isNotEmpty()) {
                item(key = "cross_index_hits") {
                    SurfaceCard {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("其他媒体库 / Emby / Plex 联动片源", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            indexedHits.forEach { hit ->
                                IndexedHitRow(
                                    hit = hit,
                                    onPlay = play,
                                    onOpenCustomItem = { _, _ -> },
                                    onOpenServerDetail = onOpenServerDetail,
                                    onOpenRemote = onOpenRemote,
                                )
                            }
                        }
                    }
                }
            }

            // 4. 剧情简介与演职员
            if (item.overview.isNotBlank() || item.directors.isNotEmpty() || item.cast.isNotEmpty()) {
                item(key = "overview") {
                    SurfaceCard {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("剧情简介", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            if (item.overview.isNotBlank()) {
                                Text(item.overview, style = MaterialTheme.typography.bodyMedium)
                            }
                            if (item.directors.isNotEmpty()) {
                                Text(
                                    (if (item.kind == "movie") "导演：" else "主创：") + item.directors.joinToString("、"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (item.cast.isNotEmpty()) {
                                Text(
                                    "主演：" + item.cast.take(10).joinToString("、"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // 5. 电视剧选季与分集列表 / 电影多版本文件列表
            if (item.episodes.isNotEmpty()) {
                item(key = "episodes_section") {
                    SurfaceCard {
                        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = if (item.kind == "tv") "选集播放（共 ${item.episodes.size} 集）" else "片源文件（${item.episodes.size}）",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 14.dp),
                            )
                            if (seasons.size > 1) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .padding(horizontal = 14.dp, vertical = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    seasons.forEach { sNum ->
                                        val countInSeason = item.episodes.count { it.season == sNum }
                                        FilterChip(
                                            selected = selectedSeason == sNum,
                                            onClick = { selectedSeason = sNum },
                                            label = {
                                                Text(
                                                    if (sNum == 0) "特别篇 ($countInSeason)" else "第 $sNum 季 ($countInSeason)",
                                                    fontSize = 12.sp,
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                            seasonEpisodes.forEach { ep ->
                                val globalIndex = item.episodes.indexOfFirst { it.id == ep.id }.coerceAtLeast(0)
                                val hist = historyByUri[ep.playUrl]
                                ScrapedEpisodeRow(
                                    ep = ep,
                                    isTv = item.kind == "tv",
                                    watchedPosMs = hist?.positionMs ?: 0L,
                                    durationMs = hist?.entry?.durationMs ?: 0L,
                                    onClick = { play(fullPlayQueue, globalIndex) },
                                )
                            }
                        }
                    }
                }
            }

            // 6. AI 智能推荐 + 相似作品推荐
            item(key = "recommendations") {
                MediaRecommendationsSection(
                    kind = item.kind,
                    title = item.title,
                    originalTitle = item.originalTitle,
                    year = item.year,
                    tmdbId = item.tmdbId,
                    doubanId = item.doubanId,
                    genres = item.genres,
                    directors = item.directors,
                    cast = item.cast,
                    overview = item.overview,
                    recentWatchedTitles = recentTitles,
                    settings = settings,
                    onUpdateSettings = onUpdateSettings,
                    onOpenSettings = onOpenSettings,
                    onSelectWork = onOpenDiscoverDetail,
                )
            }
        }
    }

    if (rematchOpen) {
        RematchScrapedItemDialog(
            initialTitle = item.title,
            initialYear = item.year,
            onDismiss = { rematchOpen = false },
            onConfirm = { newTitle, newYear ->
                rematchOpen = false
                rematching = true
                scope.launch {
                    CustomMediaLibraryStore.rematchItem(
                        context = context,
                        settings = settings,
                        libraryId = libraryId,
                        itemId = item.id,
                        newTitle = newTitle,
                        newYear = newYear,
                    )
                    rematching = false
                }
            },
        )
    }
}

@Composable
private fun ScrapedEpisodeRow(
    ep: ScrapedEpisode,
    isTv: Boolean,
    watchedPosMs: Long,
    durationMs: Long,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .tvFocusRing(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Orange.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.PlayArrow, null, tint = Orange, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = if (isTv) "${ep.episodeLabel} · ${ep.fileName.substringBeforeLast('.')}" else ep.fileName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = buildList {
                if (ep.sizeBytes > 0) add(formatSize(ep.sizeBytes))
                if (watchedPosMs > 5_000L) add("上次看到 ${formatDuration(watchedPosMs)}")
            }.joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (durationMs > 0 && watchedPosMs > 5_000L) {
                LinearProgressIndicator(
                    progress = { (watchedPosMs.toFloat() / durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp).height(2.5.dp),
                    color = Orange,
                )
            }
        }
    }
}

@Composable
private fun RematchScrapedItemDialog(
    initialTitle: String,
    initialYear: Int?,
    onDismiss: () -> Unit,
    onConfirm: (String, Int?) -> Unit,
) {
    var title by remember { mutableStateOf(initialTitle) }
    var yearStr by remember { mutableStateOf(initialYear?.toString().orEmpty()) }

    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动修正匹配影片") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("正确片名") },
                    singleLine = true,
                    phoneInput = true,
                    phoneLabel = "片名",
                    colors = appFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = yearStr,
                    onValueChange = { yearStr = it.filter(Char::isDigit).take(4) },
                    label = { Text("上映/首播年份（选填）") },
                    singleLine = true,
                    colors = appFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = { onConfirm(title.trim(), yearStr.toIntOrNull()) },
            ) {
                Text("重新刮削")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 跨自定义媒体库 + Emby / Jellyfin / Plex / OpenList 的全局统一检索与直接播放弹窗。
 */
@Composable
fun UnifiedMediaIndexDialog(
    lib: LibraryViewModel,
    onDismiss: () -> Unit,
    onOpenCustomItem: (libraryId: String, itemId: String) -> Unit,
    onOpenServerDetail: (serverId: String, ratingKey: String) -> Unit,
    onOpenRemote: (server: Server, path: String, title: String) -> Unit,
    onPlay: (List<MediaEntry>, Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<IndexedPlayHit>>(emptyList()) }
    var searchedOnce by remember { mutableStateOf(false) }

    fun doSearch() {
        val q = query.trim()
        if (q.isEmpty()) return
        searching = true
        searchedOnce = true
        scope.launch {
            hits = runCatching {
                CustomMediaLibraryStore.queryAllIndexedSources(
                    context = context,
                    lib = lib,
                    title = q,
                )
            }.getOrDefault(emptyList())
            searching = false
        }
    }

    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("跨媒体库与 Emby/Plex 统一检索") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("输入电影或电视剧名称…") },
                        singleLine = true,
                        phoneInput = true,
                        phoneLabel = "跨库搜索片名",
                        colors = appFieldColors(),
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        enabled = query.isNotBlank() && !searching,
                        colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.White),
                        onClick = ::doSearch,
                    ) {
                        Text("检索")
                    }
                }

                when {
                    searching -> Row(
                        Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Orange)
                        Spacer(Modifier.width(8.dp))
                        Text("正在同步检索本机媒体库与 Emby / Plex 服务器…", style = MaterialTheme.typography.bodySmall)
                    }
                    searchedOnce && hits.isEmpty() -> Text(
                        "未在自定义媒体库或媒体服务器中找到匹配「${query.trim()}」的片源。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    hits.isNotEmpty() -> {
                        hits.forEach { hit ->
                            IndexedHitRow(
                                hit = hit,
                                onPlay = onPlay,
                                onOpenCustomItem = onOpenCustomItem,
                                onOpenServerDetail = onOpenServerDetail,
                                onOpenRemote = onOpenRemote,
                            )
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
internal fun IndexedHitRow(
    hit: IndexedPlayHit,
    onPlay: (List<MediaEntry>, Int) -> Unit,
    onOpenCustomItem: (libraryId: String, itemId: String) -> Unit,
    onOpenServerDetail: (serverId: String, ratingKey: String) -> Unit,
    onOpenRemote: (server: Server, path: String, title: String) -> Unit,
) {
    when (hit) {
        is IndexedPlayHit.CustomLibraryHit -> {
            val it = hit.item
            val action = if (it.kind == "movie" && it.episodes.size == 1) "直接播放" else "选集/详情"
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.8f))
                    .tvFocusRing(RoundedCornerShape(9.dp))
                    .clickable {
                        if (it.kind == "movie" && it.episodes.size == 1) {
                            onPlay(listOf(it.episodes.first().toMediaEntry(null)), 0)
                        } else {
                            onOpenCustomItem(hit.library.id, it.id)
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(AppIcons.Movie, null, tint = Orange, modifier = Modifier.size(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(it.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        buildString {
                            append("媒体库「${hit.library.name}」")
                            it.year?.let { y -> append(" · $y") }
                            it.badgeText?.let { b -> append(" · $b") }
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(action, style = MaterialTheme.typography.labelMedium, color = Orange, fontWeight = FontWeight.SemiBold)
            }
        }
        is IndexedPlayHit.MediaServerHit -> {
            val it = hit.item
            val directPlayable = !it.isFolder && it.playUrl != null && it.fileName != null
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.8f))
                    .tvFocusRing(RoundedCornerShape(9.dp))
                    .clickable {
                        if (directPlayable) {
                            onPlay(listOf(MediaEntry(it.playUrl!!, it.fileName!!, isVideo = it.type != "track")), 0)
                        } else {
                            onOpenServerDetail(hit.server.id, it.ratingKey)
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ServerSourceIcon(hit.server, size = 22.dp)
                Column(Modifier.weight(1f)) {
                    Text(it.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        buildString {
                            append("${hit.server.name} (${hit.server.type.label})")
                            it.year?.let { y -> append(" · $y") }
                            it.episodeLabel?.let { ep -> append(" · $ep") }
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    if (directPlayable) "直接播放" else "进入选集",
                    style = MaterialTheme.typography.labelMedium,
                    color = Orange,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        is IndexedPlayHit.OpenListHit -> {
            val it = hit.item
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.8f))
                    .tvFocusRing(RoundedCornerShape(9.dp))
                    .clickable {
                        if (!it.isDir && it.playUrl != null) {
                            onPlay(listOf(MediaEntry(it.playUrl, it.name)), 0)
                        } else {
                            onOpenRemote(hit.server, it.path, it.name)
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ServerSourceIcon(hit.server, size = 22.dp)
                Column(Modifier.weight(1f)) {
                    Text(it.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${hit.server.name} (OpenList) · ${it.path}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    if (!it.isDir && it.playUrl != null) "直接播放" else "打开目录",
                    style = MaterialTheme.typography.labelMedium,
                    color = Orange,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
