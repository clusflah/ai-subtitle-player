package com.voxmusic.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxmusic.app.meta.DiscoverItem
import com.voxmusic.app.music.SpotifyAppRemoteBridge
import com.voxmusic.app.music.WebMusicLoginDialog
import com.voxmusic.app.music.WebMusicService
import com.voxmusic.app.music.WebStreamingMusic
import com.voxmusic.app.ui.AppIcons
import com.voxmusic.app.ui.AppTheme
import com.voxmusic.app.ui.DockTabItem
import com.voxmusic.app.ui.MusicDiscoverAiRecommendScreen
import com.voxmusic.app.ui.MusicDiscoverChartScreen
import com.voxmusic.app.ui.MusicDiscoverDetailScreen
import com.voxmusic.app.ui.MusicDiscoverTab
import com.voxmusic.app.ui.MusicHistoryScreen
import com.voxmusic.app.ui.MusicMeTab
import com.voxmusic.app.ui.MusicPlaylistDetailScreen
import com.voxmusic.app.ui.MusicSettingsScreen
import com.voxmusic.app.ui.MusicTabScreen
import com.voxmusic.app.ui.NowPlayingBar
import com.voxmusic.app.ui.ProvideScreenClass
import com.voxmusic.app.ui.QuickLoginPromptDialog
import com.voxmusic.app.ui.VoxMusicBottomDock
import com.voxmusic.app.ui.appMainBackground

private sealed interface MusicScreen {
    data object Home : MusicScreen
    data class Chart(val chartId: String) : MusicScreen
    data class SongDetail(val item: DiscoverItem, val queue: List<DiscoverItem>, val index: Int) : MusicScreen
    data class AiRecommend(val prompt: String = "") : MusicScreen
    data class PlaylistDetail(val id: String) : MusicScreen
    data object History : MusicScreen
    data object Settings : MusicScreen
}

private data class PendingSmartPlay(
    val item: DiscoverItem,
    val queue: List<DiscoverItem>,
    val index: Int,
)

class MainActivity : ComponentActivity() {

    private val vm: MusicViewModel by viewModels()
    private val navStack = mutableStateListOf<MusicScreen>(MusicScreen.Home)
    private val activeTabState = mutableIntStateOf(0) // 0=音乐 1=发现 2=我的

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        vm.resumeWidgetPlayback()
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            ProvideScreenClass {
                val ui by vm.state.collectAsStateWithLifecycle()
                val currentScreen = navStack.lastOrNull() ?: MusicScreen.Home
                var currentTab by activeTabState
                var musicTabBarsVisible by remember { mutableStateOf(true) }

                // 点击榜单播放时若两源均未登录：弹出极简双源登录框，登录完毕自动续播
                var pendingSmartPlay by remember { mutableStateOf<PendingSmartPlay?>(null) }
                var loginServiceDialog by remember { mutableStateOf<WebMusicService?>(null) }

                BackHandler(enabled = navStack.size > 1) {
                    if (navStack.size > 1) navStack.removeAt(navStack.lastIndex)
                }

                val push: (MusicScreen) -> Unit = { s -> navStack.add(s) }
                val pop: () -> Unit = { if (navStack.size > 1) navStack.removeAt(navStack.lastIndex) }
                val jumpToPlayer: () -> Unit = {
                    while (navStack.size > 1) navStack.removeAt(navStack.lastIndex)
                    currentTab = 0
                }

                val handleSmartPlay: (DiscoverItem, List<DiscoverItem>, Int) -> Unit = { item, queue, idx ->
                    val chosen = vm.smartPlayDiscover(item, queue, idx)
                    if (chosen != null) {
                        Toast.makeText(this@MainActivity, "正在通过 ${chosen.label} 播放", Toast.LENGTH_SHORT).show()
                        jumpToPlayer()
                    } else {
                        pendingSmartPlay = PendingSmartPlay(item, queue, idx)
                    }
                }

                LaunchedEffect(Unit) {
                    WebStreamingMusic.refreshAuthState(this@MainActivity)
                }

                AppTheme(ui.settings.appBackground, ui.settings.appDarkMode, ui.settings.liquidGlass) {
                    val view = LocalView.current
                    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                    val onMusicPlayerTab = currentScreen == MusicScreen.Home && currentTab == 0
                    SideEffect {
                        val lightBars = !onMusicPlayerTab && !isDark
                        runCatching {
                            WindowCompat.getInsetsController(window, view).apply {
                                isAppearanceLightStatusBars = lightBars
                                isAppearanceLightNavigationBars = lightBars
                            }
                        }
                    }

                    val dockItems = remember {
                        listOf(
                            DockTabItem("音乐", AppIcons.MusicNote),
                            DockTabItem("发现", AppIcons.Explore),
                            DockTabItem("我的", AppIcons.Person),
                        )
                    }

                    Box(Modifier.fillMaxSize().appMainBackground().imePadding()) {
                        when (val screen = currentScreen) {
                            MusicScreen.Home -> {
                                val dockSpace = 84.dp
                                when (currentTab) {
                                    0 -> MusicTabScreen(
                                        vm = vm,
                                        bottomOverlayPadding = if (musicTabBarsVisible) dockSpace else 0.dp,
                                        applyNavigationBarInset = true,
                                        onBarsVisibleChange = { musicTabBarsVisible = it },
                                        onOpenAppSettings = { push(MusicScreen.Settings) },
                                    )
                                    1 -> MusicDiscoverTab(
                                        vm = vm,
                                        bottomPadding = dockSpace + 72.dp,
                                        onSmartPlay = handleSmartPlay,
                                        onOpenDetail = { item, q, idx -> push(MusicScreen.SongDetail(item, q, idx)) },
                                        onOpenChart = { id -> push(MusicScreen.Chart(id)) },
                                        onOpenAiRecommend = { prompt -> push(MusicScreen.AiRecommend(prompt)) },
                                    )
                                    else -> MusicMeTab(
                                        vm = vm,
                                        bottomPadding = dockSpace + 72.dp,
                                        onPlayQueue = { list, i -> vm.play(list, i); jumpToPlayer() },
                                        onOpenPlaylist = { id -> push(MusicScreen.PlaylistDetail(id)) },
                                        onOpenHistory = { push(MusicScreen.History) },
                                        onOpenSettings = { push(MusicScreen.Settings) },
                                    )
                                }

                                // 底部迷你播放条（仅在发现/我的页显示）+ 悬浮 LiquidDock
                                Column(
                                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    if (currentTab != 0) {
                                        NowPlayingBar(
                                            vm = vm,
                                            bottomPadding = 4.dp,
                                            onOpen = jumpToPlayer,
                                            navigationBarInset = false,
                                        )
                                    }
                                    AnimatedVisibility(
                                        visible = currentTab != 0 || musicTabBarsVisible,
                                        enter = slideInVertically { it },
                                        exit = slideOutVertically { it },
                                    ) {
                                        VoxMusicBottomDock(
                                            items = dockItems,
                                            selectedIndex = currentTab,
                                            onSelect = { currentTab = it },
                                            onDarkCanvas = currentTab == 0,
                                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                                        )
                                    }
                                }
                            }
                            is MusicScreen.Chart -> MusicDiscoverChartScreen(
                                initialChartId = screen.chartId,
                                settings = ui.settings,
                                onBack = pop,
                                onSmartPlay = handleSmartPlay,
                                onOpenDetail = { item, q, idx -> push(MusicScreen.SongDetail(item, q, idx)) },
                            )
                            is MusicScreen.SongDetail -> MusicDiscoverDetailScreen(
                                item = screen.item,
                                queueItems = screen.queue,
                                queueIndex = screen.index,
                                vm = vm,
                                onBack = pop,
                                onSmartPlay = handleSmartPlay,
                                onOpenSettings = { push(MusicScreen.Settings) },
                                onSelectSongDetail = { s, q, idx -> push(MusicScreen.SongDetail(s, q, idx)) },
                            )
                            is MusicScreen.AiRecommend -> MusicDiscoverAiRecommendScreen(
                                initialPrompt = screen.prompt,
                                vm = vm,
                                onBack = pop,
                                onSmartPlay = handleSmartPlay,
                                onOpenDetail = { item, q, idx -> push(MusicScreen.SongDetail(item, q, idx)) },
                                onOpenSettings = { push(MusicScreen.Settings) },
                            )
                            is MusicScreen.PlaylistDetail -> MusicPlaylistDetailScreen(
                                playlistId = screen.id,
                                vm = vm,
                                onBack = pop,
                                onPlayQueue = { list, i -> vm.play(list, i); jumpToPlayer() },
                            )
                            MusicScreen.History -> MusicHistoryScreen(
                                vm = vm,
                                onBack = pop,
                                onPlayQueue = { list, i -> vm.play(list, i); jumpToPlayer() },
                            )
                            MusicScreen.Settings -> MusicSettingsScreen(
                                vm = vm,
                                onBack = pop,
                            )
                        }

                        // 二级子页面底部也显示迷你播放条
                        if (currentScreen != MusicScreen.Home) {
                            NowPlayingBar(
                                vm = vm,
                                bottomPadding = 8.dp,
                                onOpen = jumpToPlayer,
                                modifier = Modifier.align(Alignment.BottomCenter),
                                navigationBarInset = true,
                            )
                        }
                    }

                    // 两个音乐源均未登录时的快捷引导弹窗
                    pendingSmartPlay?.let { pending ->
                        if (loginServiceDialog == null) {
                            QuickLoginPromptDialog(
                                songTitle = pending.item.title,
                                onDismiss = { pendingSmartPlay = null },
                                onChooseService = { svc -> loginServiceDialog = svc },
                            )
                        }
                    }

                    loginServiceDialog?.let { svc ->
                        WebMusicLoginDialog(
                            service = svc,
                            onDismiss = {
                                loginServiceDialog = null
                                pendingSmartPlay = null
                            },
                            onLoggedIn = {
                                loginServiceDialog = null
                                val pending = pendingSmartPlay
                                pendingSmartPlay = null
                                if (pending != null) {
                                    vm.playDiscoverWithService(svc, pending.item, pending.queue, pending.index)
                                    Toast.makeText(this@MainActivity, "已登录 ${svc.label}，开始播放", Toast.LENGTH_SHORT).show()
                                    jumpToPlayer()
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        if (!vm.keepPlayingInBackground()) {
            vm.player.pause()
        }
        vm.savePosition(sync = true)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data
        if (SpotifyAppRemoteBridge.isCallbackUri(data)) {
            SpotifyAppRemoteBridge.handleOAuthCallback(this, data!!)
            return
        }
        if (intent?.action == ACTION_OPEN_PLAYER) {
            while (navStack.size > 1) navStack.removeAt(navStack.lastIndex)
            activeTabState.intValue = 0
        }
    }

    companion object {
        const val ACTION_OPEN_PLAYER = "com.voxmusic.app.OPEN_PLAYER"
    }
}
