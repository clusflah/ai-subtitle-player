package com.aisub.player.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.runtime.mutableStateListOf
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
import com.aisub.player.LibraryState
import com.aisub.player.LibraryViewModel
import com.aisub.player.MainViewModel
import com.aisub.player.media.FAVORITE_VIDEOS_PLAYLIST
import com.aisub.player.media.LIKED_PLAYLIST
import com.aisub.player.media.MediaEntry
import com.aisub.player.media.Playlist
import com.aisub.player.media.WATCH_LATER_PLAYLIST
import com.aisub.player.media.formatDuration
import com.aisub.player.music.MusicFeatures
import com.aisub.player.music.MusicSourceKind
import com.aisub.player.music.MusicSourceSpec
import com.aisub.player.music.MusicSourceStore
import com.aisub.player.music.TrackTags
import com.aisub.player.music.WebMusicLoginDialog
import com.aisub.player.music.WebMusicService
import com.aisub.player.music.WebStreamingMusic
import com.aisub.player.music.toMusicMediaEntry
import com.aisub.player.remote.RemoteItem
import com.aisub.player.remote.Server
import com.aisub.player.remote.ServerType
import com.aisub.player.remote.cloud.ServerItem
import com.aisub.player.remote.cloud.ServerProgress
import com.aisub.player.remote.cloud.ServerSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val MUSIC_SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

private fun formatMusicSpeed(s: Float): String =
    if (s == s.toInt().toFloat()) "${s.toInt()}.0" else s.toString()

private enum class MusicTabDialog {
    SLEEP, SPEED, EQUALIZER, INFO, LOCAL_FILTER,
}

private sealed interface AddMusicServerFlow {
    data class Cloud(val type: ServerType, val existing: Server? = null) : AddMusicServerFlow
    data class Storage(val server: Server) : AddMusicServerFlow
}

/**
 * 底部导航「音乐」主页面：
 * - 直接展示完整黑胶唱片 / 同步歌词 / 乐库三合一音乐播放界面；
 * - 左上角显示当前音乐源徽标，右上角设置按钮支持一键切换或接入：
 *   本机音乐、Plex、Navidrome / Subsonic（含 Airsonic / Gonic / Funkwhale / LMS）、
 *   Emby、Jellyfin、WebDAV、OpenList 以及 115 / 阿里云盘 / 百度网盘 / PikPak / OneDrive / Dropbox / Google Drive 等音乐目录。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MusicTabScreen(
    lib: LibraryViewModel,
    main: MainViewModel,
    bottomOverlayPadding: Dp = 0.dp,
    applyNavigationBarInset: Boolean = true,
    onBarsVisibleChange: ((Boolean) -> Unit)? = null,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val context = LocalContext.current
    val tv = LocalTv.current
    val isLandscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE || tv
    val scope = rememberCoroutineScope()
    val libState by lib.state.collectAsStateWithLifecycle()
    val mainState by main.state.collectAsStateWithLifecycle()
    val music by main.music.state.collectAsStateWithLifecycle()
    val player by main.playerFlow.collectAsStateWithLifecycle()

    val sourceFlow = remember(context) { MusicSourceStore.ensureInit(context) }
    val rawSpec by sourceFlow.collectAsStateWithLifecycle()
    val (effectiveSpec, activeServer) = remember(rawSpec, libState.servers) {
        MusicSourceStore.resolveEffective(rawSpec, libState.servers)
    }

    // 播放进度轮询（与 PlayerScreen 一致，仅在音乐标签激活时运行）
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
            main.checkAbLoop(pos)
            delay(150)
        }
    }

    // 当前音源预加载的首批曲目（用于空闲状态下点播放键直接开播）
    val loggedInWebServices by WebStreamingMusic.loggedInServices.collectAsStateWithLifecycle()
    LaunchedEffect(context) { WebStreamingMusic.refreshAuthState(context) }

    var cachedRemoteTracks by remember(effectiveSpec) { mutableStateOf<List<MediaEntry>>(emptyList()) }
    var reloadNonce by remember { mutableIntStateOf(0) }
    LaunchedEffect(effectiveSpec, activeServer?.id, activeServer?.extra, reloadNonce, loggedInWebServices) {
        val webSvc = effectiveSpec.webService
        if (webSvc != null) {
            if (webSvc in loggedInWebServices) {
                cachedRemoteTracks = runCatching {
                    WebStreamingMusic.loadLibraryTracks(context, webSvc).mapNotNull { it.toMusicMediaEntry() }
                }.getOrDefault(emptyList())
            } else {
                cachedRemoteTracks = emptyList()
            }
            return@LaunchedEffect
        }
        val srv = activeServer ?: return@LaunchedEffect
        cachedRemoteTracks = withContext(Dispatchers.IO) {
            runCatching {
                val client = lib.client(srv)
                if (MusicSourceStore.isStructuredMusicServer(srv.type)) {
                    MusicSourceStore.loadServerTracks(client, effectiveSpec.sectionKey)
                        .mapNotNull { it.toMusicMediaEntry(srv.id, srv.name) }
                } else {
                    val startPath = effectiveSpec.folderPath.ifEmpty { srv.rootPath }
                    MusicSourceStore.listStorageMusicFolder(
                        client,
                        startPath,
                        effectiveSpec.label,
                    ).second
                }
            }.getOrDefault(emptyList())
        }
    }

    val activeSourceTracks = if (effectiveSpec.isLocal) libState.audios else cachedRemoteTracks
    val recentMusicHistory = remember(libState.history) {
        libState.history.firstOrNull { it.entry.isMusic }
    }
    val standbyEntry = remember(effectiveSpec, activeSourceTracks, recentMusicHistory) {
        if (effectiveSpec.isLocal) {
            recentMusicHistory?.entry ?: activeSourceTracks.firstOrNull()
        } else {
            activeSourceTracks.firstOrNull() ?: recentMusicHistory?.entry
        }
    }
    val standbyCover by produceState<Bitmap?>(null, standbyEntry?.uri) {
        val u = standbyEntry?.uri ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { MusicFeatures.thumbnail(context, u, 600) }.getOrNull()
        }
    }
    val standbyTags = remember(standbyEntry, effectiveSpec.label) {
        if (standbyEntry != null) {
            val sp = ServerProgress.find(standbyEntry.uri)
            TrackTags(
                title = sp?.title ?: standbyEntry.displayTitle,
                artist = sp?.artist ?: standbyEntry.folder.ifBlank { effectiveSpec.label },
                album = sp?.album,
                durationMs = standbyEntry.durationMs,
            )
        } else {
            TrackTags(
                title = "选择音乐开始播放",
                artist = "当前音源：${effectiveSpec.label} · 点上方「乐库」选歌或右上角切换音源",
                durationMs = 0L,
            )
        }
    }

    val pager = rememberPagerState(initialPage = 0) { 2 }
    var tvPage by rememberSaveable { mutableIntStateOf(0) }
    var toastMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toastMessage) {
        if (toastMessage != null) {
            delay(2200)
            toastMessage = null
        }
    }
    val showToast: (String) -> Unit = { msg -> toastMessage = msg }
    LaunchedEffect(mainState.playbackError) {
        val err = mainState.playbackError?.trim()
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
    var addServerFlow by remember { mutableStateOf<AddMusicServerFlow?>(null) }

    val lyricsImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = main.music.importLyrics(uri)
                showToast(if (ok) "已导入歌词" else "未能识别该歌词文件")
            }
        }
    }

    val jumpToPage: (Int) -> Unit = { targetPage ->
        val safeTarget = targetPage.coerceIn(0, 1)
        if (tv) {
            tvPage = safeTarget
        } else {
            scope.launch { pager.animateScrollToPage(safeTarget) }
        }
    }

    val startQueuePlayback: (List<MediaEntry>, Int, Boolean) -> Unit = { queue, index, switchToVinyl ->
        if (queue.isNotEmpty()) {
            val safeIdx = index.coerceIn(queue.indices)
            main.play(queue, safeIdx)
            val curPage = if (tv) tvPage else pager.currentPage
            if (switchToVinyl && curPage != 0) {
                jumpToPage(0)
            }
        }
    }

    val handleIdlePlay: () -> Unit = {
        val webSvc = effectiveSpec.webService
        when {
            webSvc != null && webSvc !in loggedInWebServices -> loginWebService = webSvc
            activeSourceTracks.isNotEmpty() -> {
                val idx = standbyEntry?.let { s -> activeSourceTracks.indexOfFirst { it.uri == s.uri } }
                    ?.takeIf { it >= 0 } ?: 0
                startQueuePlayback(activeSourceTracks, idx, false)
            }
            standbyEntry != null -> {
                val historyMusic = libState.history.map { it.entry }.filter { it.isMusic }.distinctBy { it.uri }
                val queue = historyMusic.ifEmpty { listOf(standbyEntry) }
                val idx = queue.indexOfFirst { it.uri == standbyEntry.uri }.coerceAtLeast(0)
                startQueuePlayback(queue, idx, false)
            }
            effectiveSpec.isLocal && !libState.hasPermission -> onRequestPermission()
            else -> jumpToPage(1)
        }
    }

    val anyOverlayOpen = showSourceSettings ||
        loginWebService != null ||
        editingSong ||
        musicSearchCovers != null ||
        addingToPlaylist ||
        activeDialog != null ||
        addServerFlow != null

    Box(Modifier.fillMaxSize()) {
        MusicView(
            vm = main,
            player = player,
            music = music,
            current = mainState.current,
            cues = mainState.cues,
            positionMs = if (music.tags != null) positionMs else (recentMusicHistory?.takeIf { it.entry.uri == standbyEntry?.uri }?.positionMs ?: 0L),
            wantsPlay = wantsPlay,
            onBack = null,
            onSeek = { target ->
                val safe = target.coerceAtLeast(0L)
                main.seekMusicTo(safe)
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
            onExternalTvPageChange = { tvPage = it.coerceIn(0, 1) },
            externalHoldVisible = anyOverlayOpen,
            onBarsVisibleChange = onBarsVisibleChange,
            topTrailingContent = {
                MusicSourcePill(
                    spec = effectiveSpec,
                    server = activeServer,
                    onClick = { showSourceSettings = true },
                )
            },
            libraryTabContent = {
                MusicLibraryBrowser(
                    lib = lib,
                    libState = libState,
                    spec = effectiveSpec,
                    server = activeServer,
                    loggedInWebServices = loggedInWebServices,
                    currentPlayingUri = mainState.current?.takeIf { music.tags != null }?.uri,
                    reloadNonce = reloadNonce,
                    onRequestPermission = onRequestPermission,
                    onOpenSourceSettings = { showSourceSettings = true },
                    onRequestWebLogin = { svc -> loginWebService = svc },
                    onLogoutWebService = { svc ->
                        WebStreamingMusic.logout(context, svc)
                        reloadNonce++
                        showToast("已退出 ${svc.label} 账号")
                    },
                    onReauthServer = { srv ->
                        addServerFlow = if (srv.type.isCloud) AddMusicServerFlow.Cloud(srv.type, srv) else AddMusicServerFlow.Storage(srv)
                    },
                    onOpenLocalFilter = { activeDialog = MusicTabDialog.LOCAL_FILTER },
                    onPlayQueue = { queue, idx -> startQueuePlayback(queue, idx, !isLandscape) },
                    onTracksLoaded = { loaded ->
                        if (!effectiveSpec.isLocal && loaded.isNotEmpty()) {
                            cachedRemoteTracks = loaded
                        }
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
                    MusicAction.EQUALIZER -> {
                        if (WebStreamingMusic.isStreamingUri(mainState.current?.uri)) {
                            showToast("当前在线流媒体播放模式暂不支持内置均衡器")
                        } else {
                            activeDialog = MusicTabDialog.EQUALIZER
                        }
                    }
                    MusicAction.INFO -> if (music.tags != null) activeDialog = MusicTabDialog.INFO else showToast("当前暂无正在播放的歌曲")
                    MusicAction.TRACKS -> showToast("当前为纯音频播放模式")
                    MusicAction.ADD_TO_PLAYLIST -> if (music.tags != null) addingToPlaylist = true else showToast("开始播放歌曲后可加入歌单")
                    MusicAction.SETTINGS -> showSourceSettings = true
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

    // ---- 右上角「音乐源与设置」面板 ----
    if (showSourceSettings) {
        AppSheet(
            onDismissRequest = { showSourceSettings = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            MusicSourceSettingsSheet(
                lib = lib,
                libState = libState,
                settings = mainState.settings,
                effectiveSpec = effectiveSpec,
                loggedInWebServices = loggedInWebServices,
                onSelectLocal = {
                    MusicSourceStore.selectLocal(context)
                    showSourceSettings = false
                    showToast("已切换至本机音乐")
                },
                onSelectWebService = { svc ->
                    showSourceSettings = false
                    if (svc in loggedInWebServices) {
                        MusicSourceStore.selectWebService(context, svc)
                        reloadNonce++
                        jumpToPage(1)
                        showToast("已切换音乐源：${svc.label}")
                    } else {
                        loginWebService = svc
                    }
                },
                onLoginWebService = { svc ->
                    showSourceSettings = false
                    loginWebService = svc
                },
                onLogoutWebService = { svc ->
                    WebStreamingMusic.logout(context, svc)
                    reloadNonce++
                    showToast("已退出 ${svc.label} 账号")
                },
                onSelectServer = { srv, sectionKey, folderPath, customLabel ->
                    MusicSourceStore.selectServer(
                        context = context,
                        server = srv,
                        sectionKey = sectionKey,
                        folderPath = folderPath,
                        customLabel = customLabel,
                    )
                    reloadNonce++
                    showSourceSettings = false
                    if (!MusicSourceStore.isStructuredMusicServer(srv.type) && folderPath.isEmpty()) {
                        jumpToPage(1)
                    }
                    showToast("已切换音乐源：${customLabel ?: srv.name}")
                },
                onAddServerType = { type ->
                    showSourceSettings = false
                    addServerFlow = when (type) {
                        ServerType.WEBDAV, ServerType.OPENLIST -> AddMusicServerFlow.Storage(
                            Server(
                                type = type,
                                name = if (type == ServerType.OPENLIST) "OpenList" else "",
                                baseUrl = "",
                            ),
                        )
                        else -> AddMusicServerFlow.Cloud(type)
                    }
                },
                onUpdateSettings = main::updateSettings,
                onOpenLocalFilter = {
                    showSourceSettings = false
                    activeDialog = MusicTabDialog.LOCAL_FILTER
                },
                onOpenEqualizer = {
                    showSourceSettings = false
                    activeDialog = MusicTabDialog.EQUALIZER
                },
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
                MusicSourceStore.selectWebService(context, targetService)
                reloadNonce++
                jumpToPage(1)
                showToast("${targetService.label} 登录成功，已切换为当前音源")
            },
        )
    }

    // ---- 添加音乐服务器 / 网盘对话框 ----
    when (val flow = addServerFlow) {
        null -> {}
        is AddMusicServerFlow.Cloud -> CloudLoginDialog(
            type = flow.type,
            existing = flow.existing,
            onDismiss = { addServerFlow = null },
            onLoggedIn = { saved ->
                lib.saveServer(saved)
                if (effectiveSpec.serverId != saved.id) {
                    MusicSourceStore.selectServer(context, saved)
                }
                reloadNonce++
                addServerFlow = null
                jumpToPage(1)
                showToast(if (flow.existing != null) "已重新登录 ${saved.name}" else "已接入并切换至 ${saved.name}")
            },
        )
        is AddMusicServerFlow.Storage -> ServerDialog(
            initial = flow.server,
            hint = "填写后可在「乐库」页浏览音频目录并设为默认音乐目录",
            onDismiss = { addServerFlow = null },
            onSave = { saved ->
                lib.saveServer(saved)
                if (effectiveSpec.serverId != saved.id) {
                    MusicSourceStore.selectServer(context, saved, folderPath = saved.rootPath)
                }
                reloadNonce++
                addServerFlow = null
                jumpToPage(1)
                showToast("已更新 ${saved.name}")
            },
        )
    }

    // ---- 歌曲操作相关对话框 ----
    if (editingSong && music.tags != null) {
        SongInfoDialog(
            music = music,
            search = { t, a -> main.music.searchSongs(t, a) },
            onSave = { t, a, al, c -> main.music.saveInfo(t, a, al, c); showToast("已保存歌曲信息") },
            onReset = { main.music.resetInfo() },
            onDismiss = { editingSong = false },
        )
    }

    musicSearchCovers?.let { covers ->
        val tags = music.tags
        MusicSearchDialog(
            covers = covers,
            initialTitle = tags?.title.orEmpty(),
            initialArtist = tags?.artist.orEmpty(),
            search = { t, a -> if (covers) main.music.searchCovers(t, a) else main.music.searchLyrics(t, a) },
            onPick = { c -> if (covers) main.music.applyCover(c) else main.music.applyLyrics(c) },
            onDismiss = { musicSearchCovers = null },
        )
    }

    if (addingToPlaylist) {
        AddToPlaylistDialog(
            playlists = libState.playlists,
            onDismiss = { addingToPlaylist = false },
            onPick = { p ->
                main.addCurrentToPlaylist(p.id)
                lib.reloadStored()
                addingToPlaylist = false
                showToast("已加入「${p.name}」")
            },
            onCreate = { name ->
                main.addCurrentToPlaylist(null, name)
                lib.reloadStored()
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
            val selected = when (mainState.sleepAt) {
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
                main.setSleepTimer(options[i].second)
                activeDialog = null
                showToast(if (options[i].second == null) "已关闭睡眠计时" else "将在${options[i].first}暂停")
            }
        }
        MusicTabDialog.EQUALIZER -> EqualizerDialog(main, onDismiss = { activeDialog = null })
        MusicTabDialog.INFO -> InfoDialog(main, onDismiss = { activeDialog = null })
        MusicTabDialog.LOCAL_FILTER -> LocalMediaFilterDialog(
            initialTab = 1,
            availableVideoFolders = libState.availableVideoFolders,
            availableAudioFolders = libState.availableAudioFolders,
            availableImageFolders = libState.availableImageFolders,
            onDismiss = { activeDialog = null },
        )
    }
}

@Composable
private fun MusicSourcePill(
    spec: MusicSourceSpec,
    server: Server?,
    onClick: () -> Unit,
) {
    val webSvc = spec.webService
    val hitShape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .size(34.dp)
            .tvFocusRing(hitShape, color = Color.White)
            .clip(hitShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            webSvc != null -> WebMusicServiceIcon(webSvc, size = 21.dp)
            server != null -> {
                if (server.type.hasSolidIconBackdrop()) {
                    ServerSourceIcon(server, size = 22.dp)
                } else {
                    Box(
                        Modifier
                            .size(22.dp)
                            .liquidGlass(
                                shape = RoundedCornerShape(6.dp),
                                style = GlassStyle.Pill,
                                tint = Color(0x2EFFFFFF),
                                enableBackdrop = false,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        ServerSourceIcon(server, size = 16.dp)
                    }
                }
            }
            else -> SourceBadgeIcon(SourceBadgeKind.LOCAL_MUSIC, size = 21.dp)
        }
    }
}

// ============================================================================
// 「乐库」内嵌浏览页（统一适配本机、Apple Music / Spotify、Plex/Subsonic/Emby、NAS/网盘目录）
// ============================================================================

@Composable
private fun MusicLibraryBrowser(
    lib: LibraryViewModel,
    libState: LibraryState,
    spec: MusicSourceSpec,
    server: Server?,
    loggedInWebServices: Set<WebMusicService>,
    currentPlayingUri: String?,
    reloadNonce: Int,
    onRequestPermission: () -> Unit,
    onOpenSourceSettings: () -> Unit,
    onRequestWebLogin: (WebMusicService) -> Unit,
    onLogoutWebService: (WebMusicService) -> Unit,
    onReauthServer: (Server) -> Unit,
    onOpenLocalFilter: () -> Unit,
    onPlayQueue: (List<MediaEntry>, Int) -> Unit,
    onTracksLoaded: (List<MediaEntry>) -> Unit,
    onShowMessage: (String) -> Unit,
) {
    var searchQuery by rememberSaveable(spec.kind, spec.serverId, spec.sectionKey) { mutableStateOf("") }
    var refreshTick by remember { mutableIntStateOf(0) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        // 顶部搜索框 + 刷新按钮（采用无默认内边距的 BasicTextField，保证文字完整垂直居中显示不裁切）
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val searchShape = RoundedCornerShape(50)
            Row(
                Modifier
                    .weight(1f)
                    .height(42.dp)
                    .liquidGlass(shape = searchShape, style = GlassStyle.Pill, tint = Color(0x28FFFFFF))
                    .padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Search, null, tint = Color(0xB3FFFFFF), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                    ),
                    cursorBrush = SolidColor(Orange),
                    modifier = Modifier.weight(1f),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "搜索歌曲、歌手、专辑",
                                    color = Color(0x99FFFFFF),
                                    fontSize = 14.sp,
                                    lineHeight = 18.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                if (searchQuery.isNotEmpty()) {
                    IconButton(
                        onClick = { searchQuery = "" },
                        modifier = Modifier.size(30.dp).tvFocusRing(CircleShape, color = Color.White),
                    ) {
                        Icon(AppIcons.Close, "清除", tint = Color(0xCCFFFFFF), modifier = Modifier.size(16.dp))
                    }
                }
            }
            IconButton(
                onClick = {
                    if (spec.isLocal) lib.refreshMedia() else refreshTick++
                    onShowMessage("正在刷新音乐库…")
                },
                modifier = Modifier
                    .size(42.dp)
                    .tvFocusRing(CircleShape, color = Color.White)
                    .liquidGlass(shape = CircleShape, style = GlassStyle.Pill, tint = Color(0x28FFFFFF)),
            ) {
                Icon(AppIcons.Refresh, "刷新", tint = Color.White, modifier = Modifier.size(19.dp))
            }
        }

        val webSvc = spec.webService
        when {
            webSvc != null -> WebStreamingMusicLibraryContent(
                service = webSvc,
                isLoggedIn = webSvc in loggedInWebServices,
                query = searchQuery.trim(),
                currentPlayingUri = currentPlayingUri,
                refreshKey = reloadNonce + refreshTick,
                onRequestLogin = { onRequestWebLogin(webSvc) },
                onLogout = { onLogoutWebService(webSvc) },
                onOpenSourceSettings = onOpenSourceSettings,
                onPlayQueue = onPlayQueue,
                onTracksLoaded = onTracksLoaded,
            )
            spec.isLocal || server == null -> LocalMusicLibraryContent(
                libState = libState,
                query = searchQuery.trim(),
                currentPlayingUri = currentPlayingUri,
                onRequestPermission = onRequestPermission,
                onRefresh = lib::refreshMedia,
                onOpenFilter = onOpenLocalFilter,
                onOpenSourceSettings = onOpenSourceSettings,
                onPlayQueue = onPlayQueue,
            )
            MusicSourceStore.isStructuredMusicServer(server.type) -> StructuredServerMusicContent(
                lib = lib,
                server = server,
                sectionKey = spec.sectionKey,
                query = searchQuery.trim(),
                currentPlayingUri = currentPlayingUri,
                refreshKey = reloadNonce + refreshTick,
                onReauth = { onReauthServer(server) },
                onRetry = { refreshTick++ },
                onOpenSourceSettings = onOpenSourceSettings,
                onPlayQueue = onPlayQueue,
                onTracksLoaded = onTracksLoaded,
            )
            else -> StorageFolderMusicContent(
                lib = lib,
                server = server,
                spec = spec,
                query = searchQuery.trim(),
                currentPlayingUri = currentPlayingUri,
                refreshKey = reloadNonce + refreshTick,
                onReauth = { onReauthServer(server) },
                onRetry = { refreshTick++ },
                onOpenSourceSettings = onOpenSourceSettings,
                onPlayQueue = onPlayQueue,
                onTracksLoaded = onTracksLoaded,
                onShowMessage = onShowMessage,
            )
        }
    }
}

@Composable
private fun LocalMusicLibraryContent(
    libState: LibraryState,
    query: String,
    currentPlayingUri: String?,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit,
    onOpenFilter: () -> Unit,
    onOpenSourceSettings: () -> Unit,
    onPlayQueue: (List<MediaEntry>, Int) -> Unit,
) {
    var subTab by rememberSaveable { mutableIntStateOf(0) } // 0 单曲, 1 歌单, 2 文件夹
    var selectedFolder by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedPlaylistId by rememberSaveable { mutableStateOf<String?>(null) }

    BackHandler(enabled = selectedFolder != null || selectedPlaylistId != null) {
        selectedFolder = null
        selectedPlaylistId = null
    }

    if (!libState.hasPermission) {
        DarkLibraryEmptyBox(
            title = "需要读取本机音频权限",
            subtitle = "授予权限以扫描设备中的音乐，或点击右上角切换至 Plex、Navidrome、网盘音乐库",
            primaryLabel = "授予本机权限",
            onPrimary = onRequestPermission,
            secondaryLabel = "切换音乐源",
            onSecondary = onOpenSourceSettings,
        )
        return
    }

    val filteredAudios = remember(libState.audios, query) {
        if (query.isEmpty()) libState.audios
        else libState.audios.filter {
            it.title.contains(query, ignoreCase = true) || it.folder.contains(query, ignoreCase = true)
        }
    }
    val musicPlaylists = remember(libState.playlists) {
        libState.playlists.filter { p ->
            p.name != WATCH_LATER_PLAYLIST && p.name != FAVORITE_VIDEOS_PLAYLIST
        }
    }

    // 分类胶囊切换栏 + 随机播放全部
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            "单曲 (${filteredAudios.size})",
            "歌单 (${musicPlaylists.size})",
            "文件夹",
        ).forEachIndexed { idx, label ->
            DarkSubTabChip(
                label = label,
                selected = subTab == idx,
                onClick = {
                    subTab = idx
                    selectedFolder = null
                    selectedPlaylistId = null
                },
            )
        }
        Spacer(Modifier.weight(1f))
        if (filteredAudios.isNotEmpty() && subTab == 0) {
            DarkActionChip("随机播放", AppIcons.Shuffle) {
                onPlayQueue(filteredAudios.shuffled(), 0)
            }
        }
    }

    when (subTab) {
        0 -> {
            if (filteredAudios.isEmpty()) {
                if (query.isNotEmpty()) {
                    DarkLibraryEmptyBox("没有找到匹配「$query」的本机音乐")
                } else if (libState.hiddenByAudioFilter > 0) {
                    DarkLibraryEmptyBox(
                        title = "当前过滤条件隐藏了 ${libState.hiddenByAudioFilter} 首音频",
                        primaryLabel = "调整音频过滤",
                        onPrimary = onOpenFilter,
                    )
                } else {
                    DarkLibraryEmptyBox(
                        title = "本机暂未扫描到音乐文件",
                        subtitle = "可刷新本机媒体库，或切换至 Plex / Navidrome / Emby / 网盘音乐源",
                        primaryLabel = "刷新本机音乐",
                        onPrimary = onRefresh,
                        secondaryLabel = "切换音乐源",
                        onSecondary = onOpenSourceSettings,
                    )
                }
            } else {
                DarkTrackList(
                    tracks = filteredAudios,
                    currentPlayingUri = currentPlayingUri,
                    onSelect = { idx -> onPlayQueue(filteredAudios, idx) },
                )
            }
        }
        1 -> {
            val activePlaylist = musicPlaylists.firstOrNull { it.id == selectedPlaylistId }
            if (activePlaylist != null) {
                val pTracks = activePlaylist.items.filter { it.isMusic }
                DrilldownHeader(
                    title = activePlaylist.name,
                    subtitle = "${pTracks.size} 首歌曲",
                    onBack = { selectedPlaylistId = null },
                    onPlayAll = if (pTracks.isNotEmpty()) ({ onPlayQueue(pTracks, 0) }) else null,
                )
                if (pTracks.isEmpty()) {
                    DarkLibraryEmptyBox("歌单里还没有歌曲")
                } else {
                    DarkTrackList(pTracks, currentPlayingUri) { idx -> onPlayQueue(pTracks, idx) }
                }
            } else if (musicPlaylists.isEmpty()) {
                DarkLibraryEmptyBox("还没有创建音乐歌单", "播放歌曲时点「♡」或「添加到」即可收藏歌曲")
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(musicPlaylists, key = { it.id }) { pl ->
                        val count = pl.items.count { it.isMusic }
                        DarkFolderOrContainerRow(
                            title = pl.name,
                            subtitle = "$count 首歌曲",
                            icon = if (pl.name == LIKED_PLAYLIST) AppIcons.Favorite else AppIcons.QueueMusic,
                            iconTint = if (pl.name == LIKED_PLAYLIST) Color(0xFFFF5A6E) else Orange,
                            onClick = { selectedPlaylistId = pl.id },
                        )
                    }
                }
            }
        }
        else -> {
            val groups = remember(filteredAudios) {
                filteredAudios.groupBy { it.folder.ifBlank { "根目录" } }
                    .entries
                    .sortedBy { it.key.lowercase() }
            }
            val openFolder = selectedFolder
            if (openFolder != null) {
                val fTracks = groups.firstOrNull { it.key == openFolder }?.value.orEmpty()
                DrilldownHeader(
                    title = openFolder.substringAfterLast('/'),
                    subtitle = "$openFolder · ${fTracks.size} 首",
                    onBack = { selectedFolder = null },
                    onPlayAll = if (fTracks.isNotEmpty()) ({ onPlayQueue(fTracks, 0) }) else null,
                )
                DarkTrackList(fTracks, currentPlayingUri) { idx -> onPlayQueue(fTracks, idx) }
            } else if (groups.isEmpty()) {
                DarkLibraryEmptyBox("暂无音乐文件夹")
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(groups, key = { it.key }) { (folder, list) ->
                        DarkFolderOrContainerRow(
                            title = folder.substringAfterLast('/'),
                            subtitle = "$folder · ${list.size} 首",
                            icon = AppIcons.Folder,
                            onClick = { selectedFolder = folder },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StructuredServerMusicContent(
    lib: LibraryViewModel,
    server: Server,
    sectionKey: String?,
    query: String,
    currentPlayingUri: String?,
    refreshKey: Int,
    onReauth: () -> Unit,
    onRetry: () -> Unit,
    onOpenSourceSettings: () -> Unit,
    onPlayQueue: (List<MediaEntry>, Int) -> Unit,
    onTracksLoaded: (List<MediaEntry>) -> Unit,
) {
    var subTab by rememberSaveable(server.id, sectionKey) { mutableIntStateOf(0) } // 0 单曲, 1 专辑, 2 歌手, 3 歌单
    val containerStack = remember(server.id, sectionKey) { mutableStateListOf<ServerItem>() }
    var lastQuery by remember(server.id, sectionKey) { mutableStateOf(query) }
    if (query != lastQuery) {
        lastQuery = query
        containerStack.clear()
    }

    BackHandler(enabled = containerStack.isNotEmpty()) {
        containerStack.removeAt(containerStack.lastIndex)
    }

    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<ServerItem>>(emptyList()) }

    val topContainer = containerStack.lastOrNull()
    val rootListState = remember(subTab) { LazyListState() }
    val drillListState = remember(topContainer?.ratingKey) { LazyListState() }
    var detailSummary by remember(server.id, topContainer?.ratingKey) {
        mutableStateOf(topContainer?.summary?.trim().orEmpty())
    }

    LaunchedEffect(server.id, server.extra, topContainer?.ratingKey) {
        val target = topContainer ?: return@LaunchedEffect
        detailSummary = target.summary.trim()
        val fetched = withContext(Dispatchers.IO) {
            runCatching { MusicSourceStore.loadContainerDetail(lib.client(server), target) }.getOrNull()
        }
        val richer = fetched?.summary?.trim().orEmpty()
        if (richer.length > detailSummary.length) {
            detailSummary = richer
        }
    }

    LaunchedEffect(server.id, server.extra, sectionKey, subTab, topContainer?.ratingKey, query, refreshKey) {
        loading = true
        error = null
        if (topContainer == null && query.isNotEmpty()) delay(280)
        runCatching {
            withContext(Dispatchers.IO) {
                val client = lib.client(server)
                when {
                    topContainer != null -> MusicSourceStore.loadContainerChildren(client, topContainer)
                    query.isNotEmpty() -> MusicSourceStore.searchServerMusic(client, query)
                    subTab == 0 -> MusicSourceStore.loadServerTracks(client, sectionKey)
                    subTab == 1 -> MusicSourceStore.loadServerAlbums(client, sectionKey)
                    subTab == 2 -> MusicSourceStore.loadServerArtists(client, sectionKey)
                    else -> MusicSourceStore.loadServerPlaylists(client, sectionKey)
                }
            }
        }.onSuccess { list ->
            items = list
            if (query.isEmpty() && topContainer == null && subTab == 0) {
                onTracksLoaded(list.mapNotNull { it.toMusicMediaEntry(server.id, server.name) })
            }
            lib.recordServerVisit(server.id, true)
        }.onFailure { e ->
            error = e.message ?: "连接 ${server.name} 失败"
            lib.recordServerVisit(server.id, false)
        }
        loading = false
    }

    if (query.isEmpty() && topContainer == null) {
        val playableNow = remember(items, server.id) {
            items.filter { it.playUrl != null }.mapNotNull { it.toMusicMediaEntry(server.id, server.name) }
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("单曲", "专辑", "歌手", "歌单").forEachIndexed { idx, label ->
                DarkSubTabChip(
                    label = label,
                    selected = subTab == idx,
                    onClick = {
                        subTab = idx
                        containerStack.clear()
                    },
                )
            }
            Spacer(Modifier.weight(1f))
            if (playableNow.isNotEmpty() && subTab == 0) {
                DarkActionChip("随机", AppIcons.Shuffle) {
                    onPlayQueue(playableNow.shuffled(), 0)
                }
            }
        }
    } else if (topContainer != null) {
        val childPlayable = remember(items, server.id) {
            items.filter { it.playUrl != null }.mapNotNull { it.toMusicMediaEntry(server.id, topContainer.title) }
        }
        val childFolderCount = remember(items) { items.count { it.isFolder && it.playUrl == null } }
        val headerSub = when (topContainer.type) {
            "playlist" -> (childPlayable.size.takeIf { it > 0 } ?: topContainer.leafCount.takeIf { it > 0 })
                ?.let { "$it 首歌曲" }
                .orEmpty()
            "artist" -> (childFolderCount.takeIf { it > 0 } ?: topContainer.childCount.takeIf { it > 0 })
                ?.let { "$it 张专辑" }
                .orEmpty()
            else -> listOfNotNull(
                topContainer.grandparentTitle,
                topContainer.parentTitle,
                topContainer.year?.toString(),
                childPlayable.size.takeIf { it > 0 }?.let { "$it 首" },
            ).filter { it.isNotBlank() }.distinct().joinToString(" · ")
        }.ifBlank { server.name }
        DrilldownHeader(
            title = topContainer.title,
            subtitle = headerSub,
            thumbUrl = topContainer.thumb ?: topContainer.showThumb,
            isArtist = topContainer.type == "artist",
            onBack = { if (containerStack.isNotEmpty()) containerStack.removeAt(containerStack.lastIndex) },
            onPlayAll = if (childPlayable.isNotEmpty()) ({ onPlayQueue(childPlayable, 0) }) else null,
        )
    }

    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Orange)
        }
        error != null -> {
            val authExpired = com.aisub.player.remote.ServerAuth.isServerAuthError(error)
            DarkLibraryEmptyBox(
                title = "无法读取 ${server.name}",
                subtitle = error.orEmpty(),
                primaryLabel = if (authExpired) com.aisub.player.remote.ServerAuth.reauthButtonLabel(server.type) else "重试",
                onPrimary = if (authExpired) onReauth else onRetry,
                secondaryLabel = if (authExpired) "重试" else "切换音源",
                onSecondary = if (authExpired) onRetry else onOpenSourceSettings,
            )
        }
        items.isEmpty() -> Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            if (topContainer != null && detailSummary.isNotBlank()) {
                ContainerFullDescriptionCard(
                    type = topContainer.type,
                    description = detailSummary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            DarkLibraryEmptyBox(
                title = when {
                    topContainer?.type == "playlist" -> "该歌单里还没有歌曲"
                    topContainer != null -> "该条目下暂无可播放曲目"
                    query.isNotEmpty() -> "没有找到匹配「$query」的音乐"
                    else -> "该分类下暂无音乐内容"
                },
                subtitle = when {
                    topContainer != null -> "可在 ${server.name} 中向此${if (topContainer.type == "playlist") "歌单" else "条目"}添加音乐"
                    query.isNotEmpty() -> ""
                    else -> "请确认 ${server.name} 中已建立音乐媒体库"
                },
            )
        }
        else -> {
            val containers = remember(items) { items.filter { it.isFolder && it.playUrl == null } }
            val trackItems = remember(items) { items.filter { it.playUrl != null } }
            val trackEntries = remember(trackItems, server.id) {
                trackItems.mapNotNull { item -> item.toMusicMediaEntry(server.id, server.name)?.let { item to it } }
            }
            val queue = remember(trackEntries) { trackEntries.map { it.second } }

            LazyColumn(
                state = if (topContainer == null) rootListState else drillListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (topContainer != null && detailSummary.isNotBlank()) {
                    item(key = "intro:${topContainer.ratingKey}") {
                        ContainerFullDescriptionCard(
                            type = topContainer.type,
                            description = detailSummary,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                }
                items(containers, key = { "c:${it.ratingKey}" }) { c ->
                    val cleanSummary = c.summary.trim().replace(Regex("\\s+"), " ").takeIf { it.isNotEmpty() }
                    val sub = when (c.type) {
                        "album" -> listOfNotNull(c.parentTitle, c.year?.toString(), c.leafCount.takeIf { it > 0 }?.let { "$it 首" }).joinToString(" · ")
                        "artist" -> listOfNotNull(c.childCount.takeIf { it > 0 }?.let { "$it 张专辑" }, cleanSummary).joinToString(" · ")
                        "playlist" -> listOfNotNull(c.leafCount.takeIf { it > 0 }?.let { "$it 首歌曲" }, cleanSummary).joinToString(" · ")
                        else -> c.parentTitle.orEmpty()
                    }
                    DarkServerContainerRow(
                        item = c,
                        subtitle = sub.ifBlank { server.name },
                        onClick = { containerStack.add(c) },
                    )
                }
                itemsIndexed(trackEntries, key = { i, (item, _) -> "t:$i:${item.ratingKey}" }) { idx, (item, entry) ->
                    DarkTrackRow(
                        title = item.title,
                        subtitle = entry.folder.ifBlank { server.name },
                        durationMs = entry.durationMs,
                        thumbUrl = item.thumb ?: item.showThumb,
                        entryForThumb = entry,
                        active = entry.uri == currentPlayingUri,
                        onClick = { onPlayQueue(queue, idx) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StorageFolderMusicContent(
    lib: LibraryViewModel,
    server: Server,
    spec: MusicSourceSpec,
    query: String,
    currentPlayingUri: String?,
    refreshKey: Int,
    onReauth: () -> Unit,
    onRetry: () -> Unit,
    onOpenSourceSettings: () -> Unit,
    onPlayQueue: (List<MediaEntry>, Int) -> Unit,
    onTracksLoaded: (List<MediaEntry>) -> Unit,
    onShowMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val initialPath = spec.folderPath.ifEmpty { server.rootPath }
    val pathStack = remember(server.id, initialPath) { mutableStateListOf(initialPath to (spec.label.ifBlank { server.name })) }
    val currentPath = pathStack.last().first
    val currentLabel = pathStack.last().second

    BackHandler(enabled = pathStack.size > 1) {
        pathStack.removeAt(pathStack.lastIndex)
    }

    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var dirs by remember { mutableStateOf<List<RemoteItem>>(emptyList()) }
    var tracks by remember { mutableStateOf<List<MediaEntry>>(emptyList()) }

    LaunchedEffect(server.id, server.extra, currentPath, refreshKey) {
        loading = true
        error = null
        runCatching {
            withContext(Dispatchers.IO) {
                MusicSourceStore.listStorageMusicFolder(lib.client(server), currentPath, currentLabel)
            }
        }.onSuccess { (d, t) ->
            dirs = d
            tracks = t
            if (t.isNotEmpty()) onTracksLoaded(t)
            lib.recordServerVisit(server.id, true)
        }.onFailure { e ->
            error = e.message ?: "读取目录失败"
            lib.recordServerVisit(server.id, false)
        }
        loading = false
    }

    val filteredDirs = remember(dirs, query) {
        if (query.isEmpty()) dirs else dirs.filter { it.name.contains(query, ignoreCase = true) }
    }
    val filteredTracks = remember(tracks, query) {
        if (query.isEmpty()) tracks else tracks.filter { it.title.contains(query, ignoreCase = true) }
    }

    // 面包屑操作栏：返回上一级 + 设为默认音乐目录 + 播放全部
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (pathStack.size > 1) {
            DarkActionChip("← 上一级", null) {
                pathStack.removeAt(pathStack.lastIndex)
            }
        }
        Text(
            text = currentLabel,
            color = Color(0xCCFFFFFF),
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (currentPath != spec.folderPath && currentPath.isNotEmpty()) {
            DarkActionChip("设为默认目录", AppIcons.Check) {
                MusicSourceStore.selectServer(
                    context = context,
                    server = server,
                    folderPath = currentPath,
                    customLabel = "${server.name} · $currentLabel",
                )
                onShowMessage("已将「$currentLabel」设为默认音乐目录")
            }
        }
        if (filteredTracks.isNotEmpty()) {
            DarkActionChip("播放全部 (${filteredTracks.size})", AppIcons.PlayArrow) {
                onPlayQueue(filteredTracks, 0)
            }
        }
    }

    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Orange)
        }
        error != null -> {
            val authExpired = com.aisub.player.remote.ServerAuth.isServerAuthError(error)
            DarkLibraryEmptyBox(
                title = "无法读取目录",
                subtitle = error.orEmpty(),
                primaryLabel = if (authExpired) com.aisub.player.remote.ServerAuth.reauthButtonLabel(server.type) else "重试",
                onPrimary = if (authExpired) onReauth else onRetry,
                secondaryLabel = if (authExpired) "重试" else "切换音源",
                onSecondary = if (authExpired) onRetry else onOpenSourceSettings,
            )
        }
        filteredDirs.isEmpty() && filteredTracks.isEmpty() -> DarkLibraryEmptyBox(
            title = if (query.isNotEmpty()) "当前目录无匹配项" else "当前目录下暂无音频文件",
            subtitle = "可进入包含 MP3 / FLAC / M4A / WAV 等音频的子文件夹并设为默认音乐目录",
        )
        else -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(filteredDirs, key = { "d:${it.path}" }) { dir ->
                DarkFolderOrContainerRow(
                    title = dir.name,
                    subtitle = "文件夹",
                    icon = AppIcons.Folder,
                    onClick = { pathStack.add(dir.path to dir.name) },
                )
            }
            itemsIndexed(filteredTracks, key = { i, e -> "f:$i:${e.uri}" }) { idx, entry ->
                DarkTrackRow(
                    title = entry.displayTitle,
                    subtitle = server.name,
                    durationMs = entry.durationMs,
                    thumbUrl = null,
                    entryForThumb = entry,
                    active = entry.uri == currentPlayingUri,
                    onClick = { onPlayQueue(filteredTracks, idx) },
                )
            }
        }
    }
}

@Composable
private fun WebStreamingMusicLibraryContent(
    service: WebMusicService,
    isLoggedIn: Boolean,
    query: String,
    currentPlayingUri: String?,
    refreshKey: Int,
    onRequestLogin: () -> Unit,
    onLogout: () -> Unit,
    onOpenSourceSettings: () -> Unit,
    onPlayQueue: (List<MediaEntry>, Int) -> Unit,
    onTracksLoaded: (List<MediaEntry>) -> Unit,
) {
    val context = LocalContext.current
    var subTab by rememberSaveable(service.name) { mutableIntStateOf(0) } // 0 单曲, 1 专辑, 2 歌单
    val containerStack = remember(service.name) { mutableStateListOf<ServerItem>() }
    var lastQuery by remember(service.name) { mutableStateOf(query) }
    if (query != lastQuery) {
        lastQuery = query
        containerStack.clear()
    }
    val rootListState = remember(service.name, subTab) { LazyListState() }
    val topContainer = containerStack.lastOrNull()
    val drillListState = remember(topContainer?.ratingKey) { LazyListState() }

    BackHandler(enabled = containerStack.isNotEmpty()) {
        containerStack.removeAt(containerStack.lastIndex)
    }

    if (!WebStreamingMusic.isSupportedOnPlatform) {
        DarkLibraryEmptyBox(
            title = "当前桌面平台暂不支持内置 DRM 解码",
            subtitle = "安卓端支持登录 ${service.label} 直接播放",
            primaryLabel = "切换音源",
            onPrimary = onOpenSourceSettings,
        )
        return
    }

    if (!isLoggedIn) {
        DarkLibraryEmptyBox(
            title = "尚未登录 ${service.label}",
            subtitle = "登录账号后即可在当前播放界面浏览你的曲库、歌单并全曲播放（需账号已开通会员）",
            primaryLabel = "登录 ${service.label}",
            onPrimary = onRequestLogin,
            secondaryLabel = "切换音源",
            onSecondary = onOpenSourceSettings,
        )
        return
    }

    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<ServerItem>>(emptyList()) }

    LaunchedEffect(service, isLoggedIn, subTab, topContainer?.ratingKey, query, refreshKey) {
        loading = true
        error = null
        if (topContainer == null && query.isNotEmpty()) delay(320)
        runCatching {
            when {
                topContainer != null -> WebStreamingMusic.loadContainerChildren(context, service, topContainer)
                query.isNotEmpty() -> WebStreamingMusic.searchMusic(context, service, query)
                subTab == 0 -> WebStreamingMusic.loadLibraryTracks(context, service)
                subTab == 1 -> WebStreamingMusic.loadLibraryAlbums(context, service)
                else -> WebStreamingMusic.loadLibraryPlaylists(context, service)
            }
        }.onSuccess { list ->
            items = list
            if (query.isEmpty() && topContainer == null && subTab == 0) {
                onTracksLoaded(list.mapNotNull { it.toMusicMediaEntry() })
            }
        }.onFailure { e ->
            error = e.message ?: "读取 ${service.label} 曲库失败"
        }
        loading = false
    }

    if (query.isEmpty() && topContainer == null) {
        val playableNow = remember(items) {
            items.filter { it.playUrl != null }.mapNotNull { it.toMusicMediaEntry() }
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("单曲", "专辑", "歌单").forEachIndexed { idx, label ->
                DarkSubTabChip(
                    label = label,
                    selected = subTab == idx,
                    onClick = {
                        subTab = idx
                        containerStack.clear()
                    },
                )
            }
            Spacer(Modifier.weight(1f))
            if (playableNow.isNotEmpty() && subTab == 0) {
                DarkActionChip("随机", AppIcons.Shuffle) {
                    onPlayQueue(playableNow.shuffled(), 0)
                }
            }
            DarkActionChip("退出", null, onClick = onLogout)
        }
    } else if (topContainer != null) {
        val childPlayable = remember(items) {
            items.filter { it.playUrl != null }.mapNotNull { it.toMusicMediaEntry() }
        }
        val headerSub = when (topContainer.type) {
            "playlist" -> (childPlayable.size.takeIf { it > 0 } ?: topContainer.leafCount.takeIf { it > 0 })
                ?.let { "$it 首歌曲" }
                .orEmpty()
            else -> listOfNotNull(
                topContainer.grandparentTitle,
                topContainer.parentTitle,
                topContainer.year?.toString(),
                childPlayable.size.takeIf { it > 0 }?.let { "$it 首" },
            ).filter { it.isNotBlank() }.distinct().joinToString(" · ")
        }.ifBlank { service.label }

        DrilldownHeader(
            title = topContainer.title,
            subtitle = headerSub,
            thumbUrl = topContainer.thumb ?: topContainer.showThumb,
            isArtist = topContainer.type == "artist",
            onBack = { if (containerStack.isNotEmpty()) containerStack.removeAt(containerStack.lastIndex) },
            onPlayAll = if (childPlayable.isNotEmpty()) ({ onPlayQueue(childPlayable, 0) }) else null,
        )
    }

    val detailSummary = topContainer?.summary?.trim().orEmpty()

    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Orange)
        }
        error != null -> DarkLibraryEmptyBox(
            title = "读取 ${service.label} 失败",
            subtitle = error.orEmpty(),
            primaryLabel = "重新登录",
            onPrimary = onRequestLogin,
            secondaryLabel = "切换音源",
            onSecondary = onOpenSourceSettings,
        )
        items.isEmpty() -> Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            if (topContainer != null && detailSummary.isNotBlank()) {
                ContainerFullDescriptionCard(
                    type = topContainer.type,
                    description = detailSummary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            DarkLibraryEmptyBox(
                title = when {
                    topContainer?.type == "playlist" -> "该歌单里还没有歌曲"
                    topContainer != null -> "该专辑暂无可播放曲目"
                    query.isNotEmpty() -> "未搜到「$query」"
                    else -> "当前分类暂无收藏内容"
                },
                subtitle = when {
                    topContainer != null -> ""
                    query.isNotEmpty() -> "请尝试其他关键词"
                    else -> "可在上方搜索框直接搜索 ${service.label} 全曲库歌曲点播，或若登录过期可重新登录"
                },
                primaryLabel = if (query.isEmpty() && topContainer == null) "重新验证登录" else null,
                onPrimary = if (query.isEmpty() && topContainer == null) onRequestLogin else null,
            )
        }
        else -> {
            val containers = remember(items) { items.filter { it.isFolder && it.playUrl == null } }
            val trackItems = remember(items) { items.filter { it.playUrl != null } }
            val trackEntries = remember(trackItems) {
                trackItems.mapNotNull { item -> item.toMusicMediaEntry()?.let { item to it } }
            }
            val queue = remember(trackEntries) { trackEntries.map { it.second } }

            LazyColumn(
                state = if (topContainer == null) rootListState else drillListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (topContainer != null && detailSummary.isNotBlank()) {
                    item(key = "web_intro:${topContainer.ratingKey}") {
                        ContainerFullDescriptionCard(
                            type = topContainer.type,
                            description = detailSummary,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                }
                items(containers, key = { "wc:${it.ratingKey}" }) { c ->
                    val cleanSummary = c.summary.trim().replace(Regex("\\s+"), " ").takeIf { it.isNotEmpty() }
                    val sub = when (c.type) {
                        "album" -> listOfNotNull(c.parentTitle, c.year?.toString(), c.leafCount.takeIf { it > 0 }?.let { "$it 首" }).joinToString(" · ")
                        "playlist" -> listOfNotNull(c.leafCount.takeIf { it > 0 }?.let { "$it 首歌曲" }, cleanSummary).joinToString(" · ")
                        else -> c.parentTitle.orEmpty()
                    }
                    DarkServerContainerRow(
                        item = c,
                        subtitle = sub.ifBlank { service.label },
                        onClick = { containerStack.add(c) },
                    )
                }
                itemsIndexed(trackEntries, key = { i, (item, _) -> "wt:$i:${item.ratingKey}" }) { idx, (item, entry) ->
                    DarkTrackRow(
                        title = item.title,
                        subtitle = entry.folder.ifBlank { service.label },
                        durationMs = entry.durationMs,
                        thumbUrl = item.thumb,
                        entryForThumb = entry,
                        active = entry.uri == currentPlayingUri,
                        onClick = { onPlayQueue(queue, idx) },
                    )
                }
            }
        }
    }
}

// ============================================================================
// 右上角「音乐源切换与音乐设置」底部弹窗
// ============================================================================

@Composable
private fun MusicSourceSettingsSheet(
    lib: LibraryViewModel,
    libState: LibraryState,
    settings: com.aisub.player.AppSettings,
    effectiveSpec: MusicSourceSpec,
    loggedInWebServices: Set<WebMusicService>,
    onSelectLocal: () -> Unit,
    onSelectWebService: (WebMusicService) -> Unit,
    onLoginWebService: (WebMusicService) -> Unit,
    onLogoutWebService: (WebMusicService) -> Unit,
    onSelectServer: (Server, sectionKey: String?, folderPath: String, customLabel: String?) -> Unit,
    onAddServerType: (ServerType) -> Unit,
    onUpdateSettings: (com.aisub.player.AppSettings) -> Unit,
    onOpenLocalFilter: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val c = LocalPopupColors.current
    val structuredServers = remember(libState.servers) {
        libState.servers.filter { MusicSourceStore.isStructuredMusicServer(it.type) }
    }
    val storageServers = remember(libState.servers) {
        libState.servers.filter { !MusicSourceStore.isStructuredMusicServer(it.type) }
    }

    // 自动探测已接入 Plex / Navidrome / Emby / Jellyfin 的音乐分区（若存在多个音乐库可分别切换）
    val sectionsByServer by produceState<Map<String, List<ServerSection>>>(emptyMap(), structuredServers) {
        val map = LinkedHashMap<String, List<ServerSection>>()
        withContext(Dispatchers.IO) {
            for (srv in structuredServers) {
                val secs = runCatching { MusicSourceStore.discoverMusicSections(lib.client(srv)) }.getOrDefault(emptyList())
                if (secs.isNotEmpty()) map[srv.id] = secs
            }
        }
        value = map
    }

    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.84f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
    ) {
        Text(
            "音乐源与设置",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        )

        // 1. 当前可选音乐源列表
        SheetSectionTitle("切换音乐源")
        Column(
            Modifier
                .fillMaxWidth()
                .liquidGlass(shape = RoundedCornerShape(16.dp), style = GlassStyle.Card, tint = c.card, enableBackdrop = false)
                .padding(vertical = 4.dp),
        ) {
            SourceOptionRow(
                title = "本机音乐",
                subtitle = if (libState.hasPermission) "设备本地音频 · ${libState.audios.size} 首" else "点击切换并读取本机音乐",
                selected = effectiveSpec.isLocal,
                leading = { SourceBadgeIcon(SourceBadgeKind.LOCAL_MUSIC, size = 34.dp) },
                onClick = onSelectLocal,
            )

            if (WebStreamingMusic.isSupportedOnPlatform) {
                val sheetContext = LocalContext.current
                WebMusicService.entries.forEach { svc ->
                    val logged = svc in loggedInWebServices
                    val selected = effectiveSpec.webService == svc
                    HorizontalDivider(Modifier.padding(start = 60.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    SourceOptionRow(
                        title = svc.label,
                        subtitle = WebStreamingMusic.serviceStatusSubtitle(sheetContext, svc, logged),
                        selected = selected,
                        leading = { WebMusicServiceIcon(svc, size = 34.dp, insetScale = 0.92f) },
                        trailingActionLabel = if (logged) "退出" else "登录",
                        onTrailingAction = {
                            if (logged) onLogoutWebService(svc) else onLoginWebService(svc)
                        },
                        onClick = { onSelectWebService(svc) },
                    )
                    if (WebStreamingMusic.hasHighQualityConfigOption(svc)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onLoginWebService(svc) }
                                .padding(start = 60.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = WebStreamingMusic.highQualityConfigBadgeText(sheetContext, svc),
                                fontSize = 11.sp,
                                color = Orange,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "高音质设置 ›",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Orange,
                            )
                        }
                    }
                }
            }

            structuredServers.forEach { srv ->
                val secs = sectionsByServer[srv.id].orEmpty()
                HorizontalDivider(Modifier.padding(start = 60.dp), color = MaterialTheme.colorScheme.outlineVariant)
                if (secs.size <= 1) {
                    val onlySec = secs.firstOrNull()
                    val selected = !effectiveSpec.isLocal && effectiveSpec.serverId == srv.id
                    SourceOptionRow(
                        title = srv.name,
                        subtitle = buildString {
                            append(srv.type.label)
                            if (onlySec != null) append(" · ${onlySec.title}")
                        },
                        selected = selected,
                        leading = { ServerSourceIcon(srv, size = 34.dp) },
                        onClick = { onSelectServer(srv, onlySec?.key, "", srv.name) },
                    )
                } else {
                    secs.forEachIndexed { idx, sec ->
                        if (idx > 0) HorizontalDivider(Modifier.padding(start = 60.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        val selected = !effectiveSpec.isLocal &&
                            effectiveSpec.serverId == srv.id &&
                            (effectiveSpec.sectionKey == sec.key || (effectiveSpec.sectionKey == null && idx == 0))
                        val label = "${srv.name} · ${sec.title}"
                        SourceOptionRow(
                            title = label,
                            subtitle = srv.type.label,
                            selected = selected,
                            leading = { ServerSourceIcon(srv, size = 34.dp) },
                            onClick = { onSelectServer(srv, sec.key, "", label) },
                        )
                    }
                }
            }

            storageServers.forEach { srv ->
                HorizontalDivider(Modifier.padding(start = 60.dp), color = MaterialTheme.colorScheme.outlineVariant)
                val selected = !effectiveSpec.isLocal && effectiveSpec.serverId == srv.id
                val folderHint = if (selected && effectiveSpec.folderPath.isNotEmpty()) {
                    "目录：${effectiveSpec.folderPath}"
                } else {
                    "${srv.type.label} · 切入后可在「乐库」选定音乐目录"
                }
                SourceOptionRow(
                    title = srv.name,
                    subtitle = folderHint,
                    selected = selected,
                    leading = { ServerSourceIcon(srv, size = 34.dp) },
                    onClick = {
                        val keepFolder = if (effectiveSpec.serverId == srv.id) effectiveSpec.folderPath else srv.rootPath
                        onSelectServer(srv, null, keepFolder, null)
                    },
                )
            }
        }

        // 2. 添加新的音乐库与网盘音源
        SheetSectionTitle("接入新音乐源（Plex / 常见音乐库 / NAS / 网盘）")
        Column(
            Modifier
                .fillMaxWidth()
                .liquidGlass(shape = RoundedCornerShape(16.dp), style = GlassStyle.Card, tint = c.card, enableBackdrop = false)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "专用音乐服务器（支持专辑 / 歌手 / 歌单 / 封面歌词同步）",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    ServerType.PLEX to "Plex",
                    ServerType.SUBSONIC to "Navidrome / Subsonic",
                    ServerType.EMBY to "Emby",
                    ServerType.JELLYFIN to "Jellyfin",
                ).forEach { (type, name) ->
                    QuickAddSourceChip(type = type, label = name) { onAddServerType(type) }
                }
            }

            Text(
                "NAS 与云盘音乐目录（WebDAV / OpenList / 115 / 阿里 / 百度等）",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    ServerType.WEBDAV to "WebDAV",
                    ServerType.OPENLIST to "OpenList",
                    ServerType.PAN115 to "115网盘",
                    ServerType.ALIYUN to "阿里云盘",
                    ServerType.BAIDU to "百度网盘",
                    ServerType.PIKPAK to "PikPak",
                    ServerType.ONEDRIVE to "OneDrive",
                    ServerType.DROPBOX to "Dropbox",
                    ServerType.GDRIVE to "Google Drive",
                ).forEach { (type, name) ->
                    QuickAddSourceChip(type = type, label = name) { onAddServerType(type) }
                }
            }
        }

        // 3. 音乐播放设置
        SheetSectionTitle("音乐播放设置")
        Column(
            Modifier
                .fillMaxWidth()
                .liquidGlass(shape = RoundedCornerShape(16.dp), style = GlassStyle.Card, tint = c.card, enableBackdrop = false)
                .padding(vertical = 4.dp),
        ) {
            SettingToggleItem(
                title = "音乐后台播放",
                subtitle = "切换到其他页面或锁屏后继续播放音乐并显示系统媒体通知",
                checked = settings.musicBackground,
                onCheckedChange = { onUpdateSettings(settings.copy(musicBackground = it)) },
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SettingToggleItem(
                title = "自动在线获取歌词与封面",
                subtitle = "歌曲缺少内嵌封面或 LRC 歌词时自动联网匹配",
                checked = settings.musicOnlineLookup,
                onCheckedChange = { onUpdateSettings(settings.copy(musicOnlineLookup = it)) },
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SimpleSheetActionRow("均衡器与音效", AppIcons.Equalizer, onClick = onOpenEqualizer)
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SimpleSheetActionRow("睡眠定时停止", AppIcons.Timer, onClick = onOpenSleepTimer)
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SimpleSheetActionRow("本机音频过滤规则", AppIcons.Tune, onClick = onOpenLocalFilter)
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SimpleSheetActionRow("更多应用设置…", AppIcons.Settings, onClick = onOpenAppSettings)
        }
    }
}

// ============================================================================
// 复用的暗色玻璃列表与卡片组件
// ============================================================================

@Composable
private fun DarkSubTabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        text = label,
        color = if (selected) Color.White else Color(0xAAFFFFFF),
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .tvFocusRing(shape, color = Color.White)
            .liquidGlass(
                shape = shape,
                style = GlassStyle.Pill,
                tint = if (selected) Color(0x48FFFFFF) else Color(0x1FFFFFFF),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
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
            .liquidGlass(shape = shape, style = GlassStyle.Pill, tint = Color(0x30FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
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
    isArtist: Boolean = false,
    onBack: () -> Unit,
    onPlayAll: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .liquidGlass(shape = RoundedCornerShape(14.dp), style = GlassStyle.Card, tint = Color(0x28FFFFFF))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DarkActionChip("← 返回", null, onClick = onBack)
        Spacer(Modifier.width(10.dp))
        if (!thumbUrl.isNullOrBlank()) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(if (isArtist) CircleShape else RoundedCornerShape(8.dp))
                    .background(Color(0x33000000)),
                contentAlignment = Alignment.Center,
            ) {
                NetImage(
                    url = thumbUrl,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    fallback = if (isArtist) AppIcons.Person else AppIcons.MusicNote,
                )
            }
            Spacer(Modifier.width(9.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = Color(0x99FFFFFF), fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (onPlayAll != null) {
            Spacer(Modifier.width(8.dp))
            DarkActionChip("播放全部", AppIcons.PlayArrow, onClick = onPlayAll)
        }
    }
}

private val NON_TERMINAL_ABBREVS = setOf(
    "mr", "mrs", "ms", "dr", "st", "jr", "sr", "vs",
    "no", "vol", "op", "feat", "ft", "inc", "ltd", "co", "etc",
)

/**
 * 将超长歌手生平传记按完整句号精简至半屏以内（约 5–7 行），避免占满整屏遮挡下方专辑列表。
 */
internal fun condenseArtistBio(raw: String, maxUnits: Int = 330): String {
    val compact = raw.replace(Regex("\\s+"), " ").trim()
    if (compact.isEmpty()) return ""

    fun charUnits(c: Char): Int = if (c.code > 0x2E7F) 2 else 1

    var totalUnits = 0
    for (c in compact) totalUnits += charUnits(c)
    if (totalUnits <= maxUnits) return compact

    var units = 0
    var lastSentenceEnd = -1
    var lastBreakIdx = -1
    var i = 0
    while (i < compact.length) {
        val ch = compact[i]
        units += charUnits(ch)
        if (units > maxUnits) break

        if (ch.isWhitespace() || ch == '，' || ch == '；' || ch == ',') {
            lastBreakIdx = i
        }

        if (ch == '。' || ch == '！' || ch == '？') {
            var endIdx = i + 1
            while (endIdx < compact.length && compact[endIdx] in "\"”'’）)」』") endIdx++
            if (units >= 70) lastSentenceEnd = endIdx
        } else if (ch == '.' || ch == '!' || ch == '?') {
            var endIdx = i + 1
            while (endIdx < compact.length && compact[endIdx] in "\"”'’)") endIdx++
            val atEndOrSpace = endIdx >= compact.length || compact[endIdx].isWhitespace()
            if (atEndOrSpace) {
                var nextNonSpace = endIdx
                while (nextNonSpace < compact.length && compact[nextNonSpace].isWhitespace()) nextNonSpace++
                val validNext = nextNonSpace >= compact.length ||
                    compact[nextNonSpace].isUpperCase() ||
                    compact[nextNonSpace].code > 0x2E7F ||
                    compact[nextNonSpace] in "\"“'‘("
                var isAbbrev = false
                if (ch == '.') {
                    var wb = i - 1
                    while (wb >= 0 && (compact[wb].isLetter() || compact[wb] == '.')) wb--
                    val token = compact.substring(wb + 1, i)
                    if (token.length <= 1 || '.' in token || token.lowercase() in NON_TERMINAL_ABBREVS) {
                        isAbbrev = true
                    }
                }
                if (validNext && !isAbbrev && units >= 80) {
                    lastSentenceEnd = endIdx
                }
            }
        }
        i++
    }

    if (lastSentenceEnd > 0) {
        return compact.substring(0, lastSentenceEnd).trim()
    }
    val fallbackCut = if (lastBreakIdx > 40) lastBreakIdx else i.coerceAtLeast(1)
    return compact.substring(0, fallbackCut).trimEnd(',', '，', ';', '；', ' ') + "…"
}

@Composable
private fun ContainerFullDescriptionCard(
    type: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    val isArtist = type == "artist"
    val displayText = remember(type, description) {
        val clean = description
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
        if (isArtist) condenseArtistBio(clean) else clean
    }
    if (displayText.isEmpty()) return
    val headerLabel = when (type) {
        "artist" -> "歌手介绍"
        "playlist" -> "歌单描述"
        "album" -> "专辑简介"
        else -> "详细介绍"
    }
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val artistMaxCardHeight = (screenHeightDp * 0.28f).dp.coerceIn(112.dp, 195.dp)

    Column(
        modifier
            .fillMaxWidth()
            .let { if (isArtist) it.heightIn(max = artistMaxCardHeight) else it }
            .liquidGlass(shape = RoundedCornerShape(14.dp), style = GlassStyle.Card, tint = Color(0x20FFFFFF))
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = headerLabel,
            color = Orange,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = displayText,
            color = Color(0xDDFFFFFF),
            fontSize = 13.sp,
            lineHeight = 18.5.sp,
            maxLines = if (isArtist) 7 else Int.MAX_VALUE,
            overflow = if (isArtist) TextOverflow.Ellipsis else TextOverflow.Clip,
        )
    }
}

@Composable
private fun DarkTrackList(
    tracks: List<MediaEntry>,
    currentPlayingUri: String?,
    onSelect: (Int) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        itemsIndexed(tracks, key = { i, e -> "$i:${e.uri}" }) { index, entry ->
            val sp = ServerProgress.find(entry.uri)
            DarkTrackRow(
                title = sp?.title ?: entry.displayTitle,
                subtitle = listOfNotNull(sp?.artist, sp?.album).joinToString(" - ").ifBlank { entry.folder },
                durationMs = entry.durationMs,
                thumbUrl = sp?.coverUrl,
                entryForThumb = entry,
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
    entryForThumb: MediaEntry,
    active: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .tvFocusRing(shape, color = Color.White)
            .clip(shape)
            .background(if (active) Color(0x36F28C00) else Color(0x1CFFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x33000000)),
            contentAlignment = Alignment.Center,
        ) {
            if (!thumbUrl.isNullOrBlank()) {
                NetImage(
                    url = thumbUrl,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    fallback = AppIcons.MusicNote,
                )
            } else {
                MediaThumbnail(entryForThumb, Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (active) {
                    Icon(AppIcons.Equalizer, "正在播放", tint = Orange, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    text = title,
                    color = if (active) Orange else Color.White,
                    fontSize = 15.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = Color(0x99FFFFFF),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (durationMs > 0) {
            Spacer(Modifier.width(8.dp))
            Text(formatDuration(durationMs), color = Color(0x88FFFFFF), fontSize = 12.sp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DarkServerContainerRow(
    item: ServerItem,
    subtitle: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val isPlaylist = item.type == "playlist"
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (isPlaylist) it.height(68.dp) else it }
            .tvFocusRing(shape, color = Color.White)
            .clip(shape)
            .background(Color(0x1FFFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(if (item.type == "artist") CircleShape else RoundedCornerShape(8.dp))
                .background(Color(0x33000000)),
            contentAlignment = Alignment.Center,
        ) {
            NetImage(
                url = item.thumb ?: item.showThumb,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                fallback = if (item.type == "artist") AppIcons.Person else AppIcons.MusicNote,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                if (isPlaylist) {
                    Text(
                        text = subtitle,
                        color = Color(0x99FFFFFF),
                        fontSize = 12.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier
                            .fillMaxWidth()
                            .basicMarquee(
                                iterations = Int.MAX_VALUE,
                                initialDelayMillis = 1_200,
                                repeatDelayMillis = 1_800,
                            ),
                    )
                } else {
                    Text(
                        text = subtitle,
                        color = Color(0x99FFFFFF),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        Icon(AppIcons.ChevronRight, null, tint = Color(0x88FFFFFF), modifier = Modifier.size(20.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DarkFolderOrContainerRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color = Orange,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(62.dp)
            .tvFocusRing(shape, color = Color.White)
            .clip(shape)
            .background(Color(0x1FFFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0x33000000)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = subtitle,
                color = Color(0x99FFFFFF),
                fontSize = 12.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .fillMaxWidth()
                    .basicMarquee(
                        iterations = Int.MAX_VALUE,
                        initialDelayMillis = 1_200,
                        repeatDelayMillis = 1_800,
                    ),
            )
        }
        Icon(AppIcons.ChevronRight, null, tint = Color(0x88FFFFFF), modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun DarkLibraryEmptyBox(
    title: String,
    subtitle: String = "",
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
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
            if (primaryLabel != null || secondaryLabel != null) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (primaryLabel != null && onPrimary != null) {
                        DarkActionChip(primaryLabel, null, onClick = onPrimary)
                    }
                    if (secondaryLabel != null && onSecondary != null) {
                        DarkActionChip(secondaryLabel, AppIcons.Settings, onClick = onSecondary)
                    }
                }
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
        modifier = Modifier.padding(start = 6.dp, top = 14.dp, bottom = 6.dp),
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
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailingActionLabel != null && onTrailingAction != null) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .tvFocusRing(RoundedCornerShape(50))
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
private fun QuickAddSourceChip(
    type: ServerType,
    label: String,
    onClick: () -> Unit,
) {
    val c = LocalPopupColors.current
    val chipShape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .tvFocusRing(chipShape)
            .clip(chipShape)
            .background(c.button)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ServerTypeIcon(type, size = 22.dp)
        Spacer(Modifier.width(7.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
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
            .tvFocusRing(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
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
            .tvFocusRing(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Icon(AppIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}
