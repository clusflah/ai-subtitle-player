package com.voxmusic.app.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.voxmusic.app.AppDarkMode
import com.voxmusic.app.AppSettings
import com.voxmusic.app.MusicViewModel
import com.voxmusic.app.media.CatalogItem
import com.voxmusic.app.media.MediaEntry
import com.voxmusic.app.media.TrackMetadataRegistry
import com.voxmusic.app.media.formatDuration
import com.voxmusic.app.media.toMusicMediaEntry
import com.voxmusic.app.meta.DiscoverItem
import com.voxmusic.app.music.MusicFeatures
import com.voxmusic.app.music.MusicSourceStore
import com.voxmusic.app.music.TrackTags
import com.voxmusic.app.music.WebMusicLoginDialog
import com.voxmusic.app.music.WebMusicService
import com.voxmusic.app.music.WebStreamingMusic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val MUSIC_SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

private fun formatMusicSpeed(s: Float): String =
    if (s == s.toInt().toFloat()) "${s.toInt()}.0" else s.toString()

private enum class MusicTabDialog {
    SLEEP, SPEED, INFO,
}

private const val TAB_DISCOVER_PAGE = 0
private const val TAB_SONG_PAGE = 1
private const val TAB_LIBRARY_PAGE = 2

/**
 * 音乐播放器主页面（无底栏全屏三页横向滑动）：
 * 发现（左侧） / 歌曲（黑胶唱机与歌词融合） / 乐库，右上角低调收起音乐源与设置入口。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MusicTabScreen(
    vm: MusicViewModel,
    bottomOverlayPadding: Dp = 0.dp,
    applyNavigationBarInset: Boolean = true,
    onBarsVisibleChange: ((Boolean) -> Unit)? = null,
    jumpToPlayerNonce: Int = 0,
    onSmartPlay: (DiscoverItem, List<DiscoverItem>, Int) -> Unit,
    onOpenDetail: (DiscoverItem, List<DiscoverItem>, Int) -> Unit,
    onOpenChart: (String) -> Unit,
    onOpenAiRecommend: (String) -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val context = LocalContext.current
    val tv = LocalTv.current
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE || tv
    val scope = rememberCoroutineScope()
    val uiState by vm.state.collectAsStateWithLifecycle()
    val music by vm.music.state.collectAsStateWithLifecycle()
    val player by vm.playerFlow.collectAsStateWithLifecycle()

    val sourceFlow = remember(context) { MusicSourceStore.ensureInit(context) }
    val activeService by sourceFlow.collectAsStateWithLifecycle()
    val loggedInServices by WebStreamingMusic.loggedInServices.collectAsStateWithLifecycle()
    LaunchedEffect(context) { WebStreamingMusic.refreshAuthState(context) }

    var positionMs by remember(player) { mutableLongStateOf(player.currentPosition.coerceAtLeast(0L)) }
    var wantsPlay by remember { mutableStateOf(player.playWhenReady) }
    var speed by remember(player) { mutableFloatStateOf(player.playbackParameters.speed) }
    LaunchedEffect(player) {
        while (true) {
            val pos = player.currentPosition.coerceAtLeast(0L)
            positionMs = pos
            wantsPlay = player.playWhenReady &&
                player.playbackState != Player.STATE_IDLE &&
                player.playbackState != Player.STATE_ENDED
            vm.checkAbLoop(pos)
            delay(150)
        }
    }

    var cachedServiceTracks by remember(activeService) { mutableStateOf<List<MediaEntry>>(emptyList()) }
    var reloadNonce by remember { mutableIntStateOf(0) }
    LaunchedEffect(activeService, reloadNonce, loggedInServices) {
        if (activeService in loggedInServices) {
            cachedServiceTracks = runCatching {
                WebStreamingMusic.loadLibraryTracks(context, activeService).mapNotNull { it.toMusicMediaEntry(activeService.label) }
            }.getOrDefault(emptyList())
        } else {
            cachedServiceTracks = emptyList()
        }
    }

    val recentHistory = remember(uiState.history) { uiState.history.firstOrNull() }
    val standbyEntry = remember(cachedServiceTracks, recentHistory) {
        cachedServiceTracks.firstOrNull() ?: recentHistory?.entry
    }
    val standbyCover by produceState<Bitmap?>(null, standbyEntry?.uri) {
        val e = standbyEntry ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { MusicFeatures.thumbnail(context, e.uri, 600, e.coverUrl) }.getOrNull()
        }
    }
    val standbyTags = remember(standbyEntry, activeService) {
        if (standbyEntry != null) {
            val sp = TrackMetadataRegistry.find(standbyEntry.uri)
            TrackTags(
                title = sp?.title ?: standbyEntry.displayTitle,
                artist = sp?.artist ?: standbyEntry.folder.ifBlank { activeService.label },
                album = sp?.album,
                durationMs = standbyEntry.durationMs,
            )
        } else {
            TrackTags(
                title = "VoxMusic",
                artist = "当前音源：${activeService.label} · 左侧「发现」或右侧「乐库」直接开播",
                durationMs = 0L,
            )
        }
    }

    val pager = rememberPagerState(initialPage = TAB_SONG_PAGE) { 3 }
    var tvPage by rememberSaveable { mutableIntStateOf(TAB_SONG_PAGE) }
    var toastMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toastMessage) {
        if (toastMessage != null) {
            delay(2200)
            toastMessage = null
        }
    }
    val showToast: (String) -> Unit = { msg -> toastMessage = msg }
    LaunchedEffect(uiState.playbackError) {
        val err = uiState.playbackError?.trim()
        if (!err.isNullOrEmpty() && music.tags != null) {
            showToast(err)
        }
    }

    var showSourceSettings by remember { mutableStateOf(false) }
    var loginWebService by remember { mutableStateOf<WebMusicService?>(null) }
    var editingSong by remember { mutableStateOf(false) }
    var musicSearchCovers by remember { mutableStateOf<Boolean?>(null) }
    var addingToPlaylist by remember { mutableStateOf(false) }
    var activeDialog by remember { mutableStateOf<MusicTabDialog?>(null) }

    val lyricsImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = vm.music.importLyrics(uri)
                showToast(if (ok) "已导入歌词" else "未能识别该歌词文件")
            }
        }
    }

    val jumpToPage: (Int) -> Unit = { targetPage ->
        val safeTarget = targetPage.coerceIn(0, 2)
        if (tv) tvPage = safeTarget else scope.launch { pager.animateScrollToPage(safeTarget) }
    }

    LaunchedEffect(jumpToPlayerNonce) {
        if (jumpToPlayerNonce > 0) jumpToPage(TAB_SONG_PAGE)
    }

    val startQueuePlayback: (List<MediaEntry>, Int, Boolean) -> Unit = { queue, index, switchToVinyl ->
        if (queue.isNotEmpty()) {
            vm.play(queue, index.coerceIn(queue.indices))
            val curPage = if (tv) tvPage else pager.currentPage
            if (switchToVinyl && curPage != TAB_SONG_PAGE) jumpToPage(TAB_SONG_PAGE)
        }
    }

    val handleIdlePlay: () -> Unit = {
        when {
            activeService !in loggedInServices -> loginWebService = activeService
            cachedServiceTracks.isNotEmpty() -> {
                val idx = standbyEntry?.let { s -> cachedServiceTracks.indexOfFirst { it.uri == s.uri } }
                    ?.takeIf { it >= 0 } ?: 0
                startQueuePlayback(cachedServiceTracks, idx, false)
            }
            standbyEntry != null -> {
                val histQueue = uiState.history.map { it.entry }.distinctBy { it.uri }.ifEmpty { listOf(standbyEntry) }
                val idx = histQueue.indexOfFirst { it.uri == standbyEntry.uri }.coerceAtLeast(0)
                startQueuePlayback(histQueue, idx, false)
            }
            else -> jumpToPage(TAB_LIBRARY_PAGE)
        }
    }

    val anyOverlayOpen = showSourceSettings ||
        loginWebService != null ||
        editingSong ||
        musicSearchCovers != null ||
        addingToPlaylist ||
        activeDialog != null

    val currentPage = if (tv) tvPage else pager.currentPage
    BackHandler(enabled = !anyOverlayOpen && currentPage != TAB_SONG_PAGE) {
        jumpToPage(TAB_SONG_PAGE)
    }

    Box(Modifier.fillMaxSize()) {
        MusicView(
            vm = vm,
            player = player,
            music = music,
            current = uiState.current,
            positionMs = if (music.tags != null) positionMs else (recentHistory?.takeIf { it.entry.uri == standbyEntry?.uri }?.positionMs ?: 0L),
            wantsPlay = wantsPlay,
            onBack = null,
            onSeek = { target ->
                val safe = target.coerceAtLeast(0L)
                vm.seekTo(safe)
                positionMs = safe
            },
            onMessage = showToast,
            inHomeTab = true,
            bottomOverlayPadding = bottomOverlayPadding,
            applyNavigationBarInset = applyNavigationBarInset,
            standbyTags = standbyTags,
            standbyCover = standbyCover,
            onIdlePlay = handleIdlePlay,
            externalPager = pager,
            externalTvPage = tvPage,
            onExternalTvPageChange = { tvPage = it.coerceIn(0, 2) },
            externalHoldVisible = anyOverlayOpen,
            onBarsVisibleChange = onBarsVisibleChange,
            topTrailingContent = {
                MusicSourcePill(
                    service = activeService,
                    loggedIn = activeService in loggedInServices,
                    onClick = { showSourceSettings = true },
                )
            },
            discoverTabContent = {
                AppTheme(uiState.settings.appBackground, AppDarkMode.DARK, uiState.settings.liquidGlass) {
                    MusicDiscoverTab(
                        vm = vm,
                        bottomPadding = if (!isLandscape && music.tags != null) 88.dp else 20.dp,
                        embeddedInPlayer = true,
                        onSmartPlay = onSmartPlay,
                        onOpenDetail = onOpenDetail,
                        onOpenChart = onOpenChart,
                        onOpenAiRecommend = onOpenAiRecommend,
                    )
                }
            },
            libraryTabContent = {
                StreamingLibraryBrowser(
                    service = activeService,
                    loggedInServices = loggedInServices,
                    currentPlayingUri = uiState.current?.takeIf { music.tags != null }?.uri,
                    reloadNonce = reloadNonce,
                    bottomPadding = if (!isLandscape && music.tags != null) 88.dp else 20.dp,
                    onOpenSourceSettings = { showSourceSettings = true },
                    onSwitchService = { svc ->
                        MusicSourceStore.select(context, svc)
                        reloadNonce++
                    },
                    onRequestLogin = { svc -> loginWebService = svc },
                    onLogout = { svc ->
                        WebStreamingMusic.logout(context, svc)
                        reloadNonce++
                        showToast("已退出 ${svc.label} 账号")
                    },
                    onPlayQueue = { queue, idx -> startQueuePlayback(queue, idx, !isLandscape) },
                    onTracksLoaded = { loaded ->
                        if (loaded.isNotEmpty()) cachedServiceTracks = loaded
                    },
                    onShowMessage = showToast,
                )
            },
            onAction = { action ->
                when (action) {
                    MusicAction.EDIT_INFO -> if (music.tags != null) editingSong = true else showToast("开始播放歌曲后可编辑信息")
                    MusicAction.SEARCH_LYRICS -> if (music.tags != null) musicSearchCovers = false else showToast("开始播放歌曲后可搜索歌词")
                    MusicAction.SEARCH_COVER -> if (music.tags != null) musicSearchCovers = true else showToast("开始播放歌曲后可更换封面")
                    MusicAction.IMPORT_LYRICS -> if (music.tags != null) lyricsImportLauncher.launch(arrayOf("*/*")) else showToast("开始播放歌曲后可导入歌词")
                    MusicAction.SLEEP -> activeDialog = MusicTabDialog.SLEEP
                    MusicAction.SPEED -> activeDialog = MusicTabDialog.SPEED
                    MusicAction.INFO -> if (music.tags != null) activeDialog = MusicTabDialog.INFO else showToast("当前暂无正在播放的歌曲")
                    MusicAction.ADD_TO_PLAYLIST -> if (music.tags != null) addingToPlaylist = true else showToast("开始播放歌曲后可加入歌单")
                    MusicAction.SETTINGS -> showSourceSettings = true
                    else -> {}
                }
            },
        )

        AnimatedVisibility(
            visible = toastMessage != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 68.dp),
        ) {
            val text = toastMessage.orEmpty()
            val shape = RoundedCornerShape(50)
            Box(
                Modifier
                    .glassShadow(shape, GlassStyle.Floating)
                    .liquidGlass(shape = shape, style = GlassStyle.Floating, tint = GlassTokens.DarkOverlayTint)
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            ) {
                Text(text, color = Color.White, fontSize = 14.sp)
            }
        }
    }

    // ---- 右上角「音乐源与设置」面板（仅保留 Apple Music & Spotify） ----
    if (showSourceSettings) {
        AppSheet(
            onDismissRequest = { showSourceSettings = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            MusicSourceSettingsSheet(
                settings = uiState.settings,
                activeService = activeService,
                loggedInServices = loggedInServices,
                onSelectService = { svc ->
                    showSourceSettings = false
                    if (svc in loggedInServices) {
                        MusicSourceStore.select(context, svc)
                        reloadNonce++
                        jumpToPage(TAB_LIBRARY_PAGE)
                        showToast("已切换音乐源：${svc.label}")
                    } else {
                        loginWebService = svc
                    }
                },
                onLoginService = { svc ->
                    showSourceSettings = false
                    loginWebService = svc
                },
                onLogoutService = { svc ->
                    WebStreamingMusic.logout(context, svc)
                    reloadNonce++
                    showToast("已退出 ${svc.label} 账号")
                },
                onUpdateSettings = vm::updateSettings,
                onOpenSleepTimer = {
                    showSourceSettings = false
                    activeDialog = MusicTabDialog.SLEEP
                },
                onOpenAppSettings = {
                    showSourceSettings = false
                    onOpenAppSettings()
                },
            )
        }
    }

    loginWebService?.let { targetService ->
        WebMusicLoginDialog(
            service = targetService,
            onDismiss = { loginWebService = null },
            onLoggedIn = {
                loginWebService = null
                MusicSourceStore.select(context, targetService)
                reloadNonce++
                jumpToPage(TAB_LIBRARY_PAGE)
                showToast("${targetService.label} 登录成功，已切换为当前音源")
            },
        )
    }

    if (editingSong && music.tags != null) {
        SongInfoDialog(
            music = music,
            search = { t, a -> vm.music.searchSongs(t, a) },
            onSave = { t, a, al, c -> vm.music.saveInfo(t, a, al, c); showToast("已保存歌曲信息") },
            onReset = { vm.music.resetInfo() },
            onDismiss = { editingSong = false },
        )
    }

    musicSearchCovers?.let { covers ->
        val tags = music.tags
        MusicSearchDialog(
            covers = covers,
            initialTitle = tags?.title.orEmpty(),
            initialArtist = tags?.artist.orEmpty(),
            search = { t, a -> if (covers) vm.music.searchCovers(t, a) else vm.music.searchLyrics(t, a) },
            onPick = { c -> if (covers) vm.music.applyCover(c) else vm.music.applyLyrics(c) },
            onDismiss = { musicSearchCovers = null },
        )
    }

    if (addingToPlaylist) {
        AddToPlaylistDialog(
            playlists = uiState.playlists,
            onDismiss = { addingToPlaylist = false },
            onPick = { p ->
                vm.addCurrentToPlaylist(p.id)
                addingToPlaylist = false
                showToast("已加入「${p.name}」")
            },
            onCreate = { name ->
                vm.addCurrentToPlaylist(null, name)
                addingToPlaylist = false
                showToast("已加入「$name」")
            },
        )
    }

    when (activeDialog) {
        null -> {}
        MusicTabDialog.SPEED -> ChoiceDialog(
            title = "播放速度",
            options = MUSIC_SPEEDS.map { "${formatMusicSpeed(it)}x" },
            selected = MUSIC_SPEEDS.indexOf(speed),
            onDismiss = { activeDialog = null },
        ) { i ->
            speed = MUSIC_SPEEDS[i]
            player.setPlaybackSpeed(speed)
            activeDialog = null
            showToast("播放速度 ${formatMusicSpeed(speed)}x")
        }
        MusicTabDialog.SLEEP -> {
            val options = listOf<Pair<String, Int?>>(
                "关闭" to null,
                "本首结束后" to 0,
                "15 分钟" to 15,
                "30 分钟" to 30,
                "45 分钟" to 45,
                "60 分钟" to 60,
                "90 分钟" to 90,
            )
            val selected = when (uiState.sleepAt) {
                null -> 0
                -1L -> 1
                else -> -1
            }
            ChoiceDialog(
                title = "睡眠计时器",
                options = options.map { it.first },
                selected = selected,
                onDismiss = { activeDialog = null },
            ) { i ->
                vm.setSleepTimer(options[i].second)
                activeDialog = null
                showToast(if (options[i].second == null) "已关闭睡眠计时" else "将在${options[i].first}暂停")
            }
        }
        MusicTabDialog.INFO -> TrackInfoDialog(vm, onDismiss = { activeDialog = null })
    }
}

@Composable
private fun MusicSourcePill(
    service: WebMusicService,
    loggedIn: Boolean,
    onClick: () -> Unit,
) {
    val accent = LocalPlayerAccentColor.current
    val shape = RoundedCornerShape(50)
    // 统一顶栏右侧音源状态微胶囊：半透明无框微磨砂，点击切换音源与设置
    Row(
        modifier = Modifier
            .tvFocusRing(shape, color = Color.White)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        WebMusicServiceIcon(service, size = 16.dp)
        Text(
            text = service.label,
            color = Color.White.copy(alpha = 0.86f),
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
        )
        if (loggedIn) {
            Box(
                Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
        }
    }
}

// ============================================================================
// 「乐库」内嵌浏览页（去四层胶囊堆叠 + 去卡片化通透列表 + 折叠式磨砂搜索）
// ============================================================================

@Composable
private fun StreamingLibraryBrowser(
    service: WebMusicService,
    loggedInServices: Set<WebMusicService>,
    currentPlayingUri: String?,
    reloadNonce: Int,
    bottomPadding: Dp = 20.dp,
    onOpenSourceSettings: () -> Unit,
    onSwitchService: (WebMusicService) -> Unit,
    onRequestLogin: (WebMusicService) -> Unit,
    onLogout: (WebMusicService) -> Unit,
    onPlayQueue: (List<MediaEntry>, Int) -> Unit,
    onTracksLoaded: (List<MediaEntry>) -> Unit,
    onShowMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val accent = LocalPlayerAccentColor.current
    val isLogged = service in loggedInServices
    var searchExpanded by rememberSaveable(service) { mutableStateOf(false) }
    var searchQuery by rememberSaveable(service) { mutableStateOf("") }
    var refreshTick by remember { mutableIntStateOf(0) }
    var tab by rememberSaveable(service) { mutableIntStateOf(0) } // 0=单曲 1=专辑 2=歌单
    var tracks by remember(service) { mutableStateOf<List<CatalogItem>>(emptyList()) }
    var albums by remember(service) { mutableStateOf<List<CatalogItem>>(emptyList()) }
    var playlists by remember(service) { mutableStateOf<List<CatalogItem>>(emptyList()) }
    var loading by remember(service) { mutableStateOf(false) }

    var searchHits by remember(service) { mutableStateOf<List<CatalogItem>>(emptyList()) }
    var searching by remember(service) { mutableStateOf(false) }

    var openedContainer by remember(service) { mutableStateOf<CatalogItem?>(null) }
    var containerTracks by remember(service, openedContainer?.ratingKey) { mutableStateOf<List<CatalogItem>>(emptyList()) }
    var containerLoading by remember(service, openedContainer?.ratingKey) { mutableStateOf(false) }

    BackHandler(enabled = openedContainer != null) { openedContainer = null }
    BackHandler(enabled = openedContainer == null && searchExpanded) {
        searchExpanded = false
        searchQuery = ""
    }

    LaunchedEffect(service, isLogged, reloadNonce, refreshTick) {
        if (!isLogged) {
            tracks = emptyList()
            albums = emptyList()
            playlists = emptyList()
            return@LaunchedEffect
        }
        loading = true
        val t = runCatching { WebStreamingMusic.loadLibraryTracks(context, service) }.getOrDefault(emptyList())
        tracks = t
        onTracksLoaded(t.mapNotNull { it.toMusicMediaEntry(service.label) })
        loading = false
        albums = runCatching { WebStreamingMusic.loadLibraryAlbums(context, service) }.getOrDefault(emptyList())
        playlists = runCatching { WebStreamingMusic.loadLibraryPlaylists(context, service) }.getOrDefault(emptyList())
    }

    LaunchedEffect(service, isLogged, searchQuery) {
        val q = searchQuery.trim()
        if (!isLogged || q.isEmpty()) {
            searchHits = emptyList()
            searching = false
            return@LaunchedEffect
        }
        delay(350)
        searching = true
        searchHits = runCatching { WebStreamingMusic.searchMusic(context, service, q) }.getOrDefault(emptyList())
        searching = false
    }

    LaunchedEffect(service, openedContainer?.ratingKey) {
        val c = openedContainer ?: return@LaunchedEffect
        containerLoading = true
        containerTracks = runCatching { WebStreamingMusic.loadContainerChildren(context, service, c) }.getOrDefault(emptyList())
        containerLoading = false
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 2.dp),
    ) {
        // 顶部精简控制行：无描边音源切换 + 收纳式搜索图标 + 极简刷新图标
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                WebMusicService.entries.forEach { svc ->
                    val selected = svc == service
                    val svcLogged = svc in loggedInServices
                    val shape = RoundedCornerShape(50)
                    Row(
                        Modifier
                            .clip(shape)
                            .background(if (selected) Color.White.copy(alpha = 0.10f) else Color.Transparent)
                            .clickable { onSwitchService(svc) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        WebMusicServiceIcon(svc, size = 15.dp)
                        Text(
                            text = svc.label,
                            color = if (selected) Color.White else Color.White.copy(alpha = 0.48f),
                            fontSize = 12.5.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            softWrap = false,
                        )
                        if (svcLogged && selected) {
                            Box(
                                Modifier.size(4.5.dp).clip(CircleShape).background(accent),
                            )
                        }
                    }
                }
            }
            if (isLogged) {
                IconButton(
                    onClick = {
                        if (searchExpanded) {
                            searchExpanded = false
                            searchQuery = ""
                        } else {
                            searchExpanded = true
                        }
                    },
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        if (searchExpanded) AppIcons.Close else AppIcons.Search,
                        contentDescription = if (searchExpanded) "关闭搜索" else "搜索乐库",
                        tint = if (searchExpanded) accent else Color.White.copy(alpha = 0.72f),
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
            IconButton(
                onClick = {
                    refreshTick++
                    onShowMessage("正在刷新 ${service.label} 乐库…")
                },
                modifier = Modifier.size(34.dp),
            ) {
                Icon(AppIcons.Refresh, "刷新", tint = Color.White.copy(alpha = 0.72f), modifier = Modifier.size(18.dp))
            }
        }

        if (!isLogged) {
            Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    WebMusicServiceIcon(service, size = 56.dp)
                    Text("登录 ${service.label}", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        WebStreamingMusic.serviceStatusSubtitle(context, service, false),
                        color = Color.White.copy(alpha = 0.62f),
                        fontSize = 13.sp,
                    )
                    DarkActionChip("立即登录 ${service.label}", AppIcons.Person) {
                        onRequestLogin(service)
                    }
                }
            }
            return
        }

        // 收纳式无描边极淡磨砂搜索条：平时折叠释放空间，点击搜索图标丝滑展开
        AnimatedVisibility(
            visible = searchExpanded || searchQuery.isNotEmpty(),
            enter = fadeIn(tween(200)) + expandVertically(tween(220)),
            exit = fadeOut(tween(160)) + shrinkVertically(tween(200)),
        ) {
            val searchShape = RoundedCornerShape(50)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .height(40.dp)
                    .clip(searchShape)
                    .background(Color.White.copy(alpha = 0.08f))
                    .padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Search, null, tint = Color.White.copy(alpha = 0.56f), modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 14.sp, lineHeight = 18.sp),
                    cursorBrush = SolidColor(accent),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    "在 ${service.label} 中搜索歌曲、歌手、专辑",
                                    color = Color.White.copy(alpha = 0.48f),
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            inner()
                        }
                    },
                )
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(30.dp)) {
                        Icon(AppIcons.Close, "清除", tint = Color.White.copy(alpha = 0.72f), modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        val container = openedContainer
        if (container != null) {
            val entries = remember(containerTracks) {
                containerTracks.mapNotNull { it.toMusicMediaEntry(service.label) }
            }
            DrilldownHeader(
                title = container.title,
                subtitle = container.summary.ifBlank { container.parentTitle.orEmpty() },
                thumbUrl = container.thumb,
                onBack = { openedContainer = null },
                onPlayAll = entries.takeIf { it.isNotEmpty() }?.let { list -> { onPlayQueue(list, 0) } },
            )
            if (containerLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = accent)
                }
            } else if (entries.isEmpty()) {
                DarkLibraryEmptyBox("该列表暂无可播曲目", primaryLabel = "返回", onPrimary = { openedContainer = null })
            } else {
                DarkTrackList(entries, currentPlayingUri, bottomPadding = bottomPadding) { idx -> onPlayQueue(entries, idx) }
            }
            return
        }

        if (searchQuery.isNotBlank()) {
            if (searching) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = accent)
                }
            } else if (searchHits.isEmpty()) {
                DarkLibraryEmptyBox("未搜到「$searchQuery」", "试试换个关键词")
            } else {
                val trackHits = remember(searchHits) {
                    searchHits.filter { it.type == "track" }.mapNotNull { it.toMusicMediaEntry(service.label) }
                }
                val containerHits = remember(searchHits) { searchHits.filter { it.type != "track" } }
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .verticalEdgeFade(topFade = 24.dp, bottomFade = 36.dp),
                    contentPadding = PaddingValues(top = 6.dp, bottom = bottomPadding),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(trackHits, key = { i, e -> "st:$i:${e.uri}" }) { idx, entry ->
                        val sp = TrackMetadataRegistry.find(entry.uri)
                        DarkTrackRow(
                            title = sp?.title ?: entry.displayTitle,
                            subtitle = listOfNotNull(sp?.artist, sp?.album).joinToString(" - ").ifBlank { entry.folder },
                            durationMs = entry.durationMs,
                            thumbUrl = sp?.coverUrl ?: entry.coverUrl,
                            active = entry.uri == currentPlayingUri,
                            onClick = { onPlayQueue(trackHits, idx) },
                        )
                    }
                    items(containerHits, key = { "sc:${it.ratingKey}" }) { item ->
                        DarkContainerRow(
                            item = item,
                            subtitle = item.summary.ifBlank { item.parentTitle.orEmpty() },
                            onClick = { openedContainer = item },
                        )
                    }
                }
            }
            return
        }

        // 子分类栏：未选中仅留纯净文字，仅选中态以柔和毛玻璃底托承接；右侧极简无框「播放全部」
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf("单曲", "专辑", "歌单").forEachIndexed { i, label ->
                    DarkFilterChip(label = label, selected = tab == i) { tab = i }
                }
            }
            if (tab == 0 && tracks.isNotEmpty()) {
                val entries = remember(tracks) { tracks.mapNotNull { it.toMusicMediaEntry(service.label) } }
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { if (entries.isNotEmpty()) onPlayQueue(entries, 0) }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(AppIcons.PlayArrow, null, tint = accent, modifier = Modifier.size(16.dp))
                    Text("播放全部", color = Color.White.copy(alpha = 0.82f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        if (loading && tracks.isEmpty() && albums.isEmpty() && playlists.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(color = accent)
                    Text("正在加载 ${service.label} 个人曲库…", color = Color.White.copy(alpha = 0.62f), fontSize = 13.sp)
                }
            }
        } else {
            when (tab) {
                0 -> {
                    val entries = remember(tracks) { tracks.mapNotNull { it.toMusicMediaEntry(service.label) } }
                    if (entries.isEmpty()) {
                        DarkLibraryEmptyBox("暂无单曲", "可点右上角搜索或到「发现」榜单播放", primaryLabel = "刷新", onPrimary = { refreshTick++ })
                    } else {
                        DarkTrackList(entries, currentPlayingUri, bottomPadding = bottomPadding) { idx -> onPlayQueue(entries, idx) }
                    }
                }
                1 -> {
                    if (albums.isEmpty()) {
                        DarkLibraryEmptyBox("暂无收藏专辑", primaryLabel = "刷新", onPrimary = { refreshTick++ })
                    } else {
                        LazyColumn(
                            Modifier
                                .fillMaxSize()
                                .verticalEdgeFade(topFade = 24.dp, bottomFade = 36.dp),
                            contentPadding = PaddingValues(top = 6.dp, bottom = bottomPadding),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            items(albums, key = { it.ratingKey }) { item ->
                                DarkContainerRow(
                                    item = item,
                                    subtitle = listOfNotNull(item.parentTitle, item.year?.toString()).joinToString(" · "),
                                    onClick = { openedContainer = item },
                                )
                            }
                        }
                    }
                }
                else -> {
                    if (playlists.isEmpty()) {
                        DarkLibraryEmptyBox("暂无歌单", primaryLabel = "刷新", onPrimary = { refreshTick++ })
                    } else {
                        LazyColumn(
                            Modifier
                                .fillMaxSize()
                                .verticalEdgeFade(topFade = 24.dp, bottomFade = 36.dp),
                            contentPadding = PaddingValues(top = 6.dp, bottom = bottomPadding),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            items(playlists, key = { it.ratingKey }) { item ->
                                DarkContainerRow(
                                    item = item,
                                    subtitle = item.summary.ifBlank {
                                        if (item.leafCount > 0) "${item.leafCount} 首歌曲" else item.parentTitle.orEmpty()
                                    },
                                    onClick = { openedContainer = item },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// 右上角「音源与播放设置」底部抽屉（仅 Apple Music & Spotify）
// ============================================================================

@Composable
private fun MusicSourceSettingsSheet(
    settings: AppSettings,
    activeService: WebMusicService,
    loggedInServices: Set<WebMusicService>,
    onSelectService: (WebMusicService) -> Unit,
    onLoginService: (WebMusicService) -> Unit,
    onLogoutService: (WebMusicService) -> Unit,
    onUpdateSettings: (AppSettings) -> Unit,
    onOpenSleepTimer: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp)
            .padding(bottom = 22.dp),
    ) {
        Text(
            "音乐源与播放设置",
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
        )
        Text(
            "榜单播放会自动智能选择已登录源；此处可切换默认乐库或管理账号",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )

        SheetSectionTitle("流媒体音乐源（Apple Music / Spotify / YouTube Music）")
        WebMusicService.entries.forEach { svc ->
            val logged = svc in loggedInServices
            SourceOptionRow(
                title = svc.label,
                subtitle = WebStreamingMusic.serviceStatusSubtitle(context, svc, logged),
                selected = activeService == svc,
                leading = { WebMusicServiceIcon(svc, size = 28.dp) },
                trailingActionLabel = if (logged) (if (svc == WebMusicService.SPOTIFY) "管理" else "退出") else "登录",
                onTrailingAction = {
                    if (!logged || svc == WebMusicService.SPOTIFY) onLoginService(svc)
                    else onLogoutService(svc)
                },
                onClick = { onSelectService(svc) },
            )
            if (logged && svc == WebMusicService.SPOTIFY) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 56.dp, end = 12.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "配置高音质 / Client ID",
                        fontSize = 12.sp,
                        color = Orange,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable { onLoginService(svc) }.padding(vertical = 2.dp),
                    )
                    Text(
                        text = "退出 Spotify",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { onLogoutService(svc) }.padding(vertical = 2.dp),
                    )
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)

        SheetSectionTitle("播放与歌词")
        SettingToggleItem(
            title = "后台继续播放",
            subtitle = "锁屏或切换应用后保持音乐播放与通知栏控制",
            checked = settings.musicBackground,
            onCheckedChange = { onUpdateSettings(settings.copy(musicBackground = it)) },
        )
        SettingToggleItem(
            title = "在线匹配同步歌词与封面",
            subtitle = "自动联网补齐同步歌词与高清专辑封面",
            checked = settings.musicOnlineLookup,
            onCheckedChange = { onUpdateSettings(settings.copy(musicOnlineLookup = it)) },
        )
        SimpleSheetActionRow("睡眠定时", AppIcons.Timer, onOpenSleepTimer)
        SimpleSheetActionRow("我的歌单、历史与完整设置", AppIcons.Settings, onOpenAppSettings)
    }
}

@Composable
private fun DarkFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    // 未选中态彻底去除描边与灰框，仅保留纯净文字；选中态以柔和液态毛玻璃底托承接
    Text(
        text = label,
        color = if (selected) Color.White else Color.White.copy(alpha = 0.50f),
        fontSize = 13.5.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .tvFocusRing(shape, color = Color.White)
            .let {
                if (selected) it.liquidGlass(shape = shape, style = GlassStyle.Pill, tint = Color.White.copy(alpha = 0.14f))
                else it.clip(shape)
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 6.dp),
    )
}

@Composable
private fun DarkActionChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .tvFocusRing(shape, color = Color.White)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun DrilldownHeader(
    title: String,
    subtitle: String,
    thumbUrl: String? = null,
    onBack: () -> Unit,
    onPlayAll: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DarkActionChip("← 返回", null, onClick = onBack)
        Spacer(Modifier.width(10.dp))
        if (!thumbUrl.isNullOrBlank()) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x33000000)),
                contentAlignment = Alignment.Center,
            ) {
                NetImage(url = thumbUrl, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, fallback = AppIcons.MusicNote)
            }
            Spacer(Modifier.width(9.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, color = Color.White.copy(alpha = 0.56f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (onPlayAll != null) {
            Spacer(Modifier.width(8.dp))
            DarkActionChip("播放全部", AppIcons.PlayArrow, onClick = onPlayAll)
        }
    }
}

@Composable
private fun DarkTrackList(
    tracks: List<MediaEntry>,
    currentPlayingUri: String?,
    bottomPadding: Dp = 20.dp,
    onSelect: (Int) -> Unit,
) {
    LazyColumn(
        Modifier
            .fillMaxSize()
            .verticalEdgeFade(topFade = 24.dp, bottomFade = 36.dp),
        contentPadding = PaddingValues(top = 6.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(tracks, key = { i, e -> "$i:${e.uri}" }) { index, entry ->
            val sp = TrackMetadataRegistry.find(entry.uri)
            DarkTrackRow(
                title = sp?.title ?: entry.displayTitle,
                subtitle = listOfNotNull(sp?.artist, sp?.album).joinToString(" - ").ifBlank { entry.folder },
                durationMs = entry.durationMs,
                thumbUrl = sp?.coverUrl ?: entry.coverUrl,
                active = entry.uri == currentPlayingUri,
                onClick = { onSelect(index) },
            )
        }
    }
}

@Composable
private fun DarkTrackRow(
    title: String,
    subtitle: String,
    durationMs: Long,
    thumbUrl: String?,
    active: Boolean,
    onClick: () -> Unit,
) {
    val accent = LocalPlayerAccentColor.current
    val shape = RoundedCornerShape(12.dp)
    // 去卡片化列表：去掉每首单曲独立的深灰圆角底块，仅用舒适留白与通透封面呈现层次
    Row(
        Modifier
            .fillMaxWidth()
            .tvFocusRing(shape, color = Color.White)
            .clip(shape)
            .background(if (active) accent.copy(alpha = 0.11f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center,
        ) {
            if (!thumbUrl.isNullOrBlank()) {
                NetImage(url = thumbUrl, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, fallback = AppIcons.MusicNote)
            } else {
                Icon(AppIcons.MusicNote, null, tint = Color.White.copy(alpha = 0.48f), modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (active) {
                    Icon(AppIcons.Equalizer, "正在播放", tint = accent, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    text = title,
                    color = if (active) accent else Color.White.copy(alpha = 0.94f),
                    fontSize = 15.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    color = Color.White.copy(alpha = 0.52f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (durationMs > 0L) {
            Spacer(Modifier.width(8.dp))
            Text(formatDuration(durationMs), color = Color.White.copy(alpha = 0.42f), fontSize = 11.5.sp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DarkContainerRow(
    item: CatalogItem,
    subtitle: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    // 专辑/歌单行同样去卡片化，融入深邃背景
    Row(
        Modifier
            .fillMaxWidth()
            .tvFocusRing(shape, color = Color.White)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(50.dp).clip(RoundedCornerShape(9.dp)).background(Color.White.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center,
        ) {
            NetImage(url = item.thumb, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, fallback = AppIcons.MusicNote)
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, color = Color.White.copy(alpha = 0.94f), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.52f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Icon(AppIcons.ChevronRight, null, tint = Color.White.copy(alpha = 0.40f), modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun DarkLibraryEmptyBox(
    title: String,
    subtitle: String = "",
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(AppIcons.MusicNote, null, tint = Color(0x66FFFFFF), modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(10.dp))
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(subtitle, color = Color(0x99FFFFFF), fontSize = 13.sp)
            }
            if (primaryLabel != null && onPrimary != null) {
                Spacer(Modifier.height(14.dp))
                DarkActionChip(primaryLabel, null, onClick = onPrimary)
            }
        }
    }
}

@Composable
private fun SheetSectionTitle(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 6.dp),
    )
}

@Composable
private fun SourceOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    leading: @Composable () -> Unit,
    trailingActionLabel: String? = null,
    onTrailingAction: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val c = LocalPopupColors.current
    val rowShape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .tvFocusRing(rowShape)
            .clip(rowShape)
            .background(if (selected) c.selectedBg else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (selected) c.selectedText else MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailingActionLabel != null && onTrailingAction != null) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(c.button)
                    .clickable(onClick = onTrailingAction)
                    .padding(horizontal = 11.dp, vertical = 5.dp),
            ) {
                Text(
                    text = trailingActionLabel,
                    color = if (trailingActionLabel == "登录") Orange else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        if (selected) {
            Spacer(Modifier.width(6.dp))
            Icon(AppIcons.Check, "当前音乐源", tint = c.selectedText, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun SettingToggleItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SimpleSheetActionRow(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Icon(AppIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}
