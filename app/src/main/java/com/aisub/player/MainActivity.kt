package com.aisub.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.aisub.player.ui.FileDetailScreen
import com.aisub.player.ui.ImageViewerScreen
import com.aisub.player.ui.LiveScreen
import com.aisub.player.ui.LocalOpenDetail
import com.aisub.player.ui.toRemoteItem
import androidx.core.content.ContextCompat
import com.aisub.player.media.MediaEntry
import com.aisub.player.media.MediaLibrary
import com.aisub.player.meta.FolderContext
import com.aisub.player.ui.AppTheme
import com.aisub.player.ui.HistoryScreen
import com.aisub.player.ui.HomeScreen
import com.aisub.player.ui.LinkScreen
import com.aisub.player.ui.LocalFolderScreen
import com.aisub.player.ui.Navigator
import com.aisub.player.ui.PlayerScreen
import com.aisub.player.ui.PipState
import com.aisub.player.ui.NowPlayingBar
import com.aisub.player.ui.OnboardingScreen
import com.aisub.player.ui.PlaylistDetailScreen
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aisub.player.ui.ServerDetailScreen
import com.aisub.player.ui.ServerHomeScreen
import com.aisub.player.ui.ServerLibraryScreen
import com.aisub.player.ui.RemoteFolderScreen
import com.aisub.player.ui.Screen
import com.aisub.player.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    private val main: MainViewModel by viewModels()
    private val lib: LibraryViewModel by viewModels()
    private val nav = Navigator()

    private val mediaPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private fun hasMediaPermission() = mediaPermissions.any {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            val ui by main.state.collectAsState()
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { result -> lib.onPermission(result.values.any { it }) }

            // 第一次打开先看说明，再申请权限；已经授权过的老用户直接进
            val appPrefs = remember { getSharedPreferences("app", MODE_PRIVATE) }
            var onboarding by remember { mutableStateOf(!appPrefs.getBoolean("onboarded", false) && !hasMediaPermission()) }
            LaunchedEffect(Unit) {
                if (hasMediaPermission()) lib.onPermission(true) else if (!onboarding) permissionLauncher.launch(mediaPermissions)
            }
            if (onboarding) {
                AppTheme {
                    OnboardingScreen(
                        onStart = {
                            appPrefs.edit().putBoolean("onboarded", true).apply()
                            onboarding = false
                            permissionLauncher.launch(mediaPermissions)
                        },
                        onLater = {
                            appPrefs.edit().putBoolean("onboarded", true).apply()
                            onboarding = false
                        },
                    )
                }
                return@setContent
            }

            val play: (List<MediaEntry>, Int) -> Unit = { list, i ->
                main.play(list, i)
                nav.push(Screen.Player)
            }
            val back: () -> Unit = {
                if (nav.current == Screen.Player) leavePlayer()
                nav.pop()
            }
            BackHandler(enabled = nav.stack.size > 1, onBack = back)

            val openDetail: (MediaEntry) -> Unit = { e -> nav.push(Screen.FileDetail(null, e.toRemoteItem(), listOf(e), 0)) }
            AppTheme {
                CompositionLocalProvider(LocalOpenDetail provides openDetail) {
                  Column(Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.background)) {
                   Box(Modifier.weight(1f)) {
                    when (val screen = nav.current) {
                        Screen.Home -> HomeScreen(lib, main, nav) { permissionLauncher.launch(mediaPermissions) }
                        Screen.Player -> PlayerScreen(main, onBack = back, onOpenSettings = { nav.push(Screen.Settings) })
                        Screen.Settings -> SettingsScreen(main, onBack = { nav.pop() })
                        Screen.Link -> LinkScreen(
                            main, onBack = { nav.pop() },
                            onDone = {
                                nav.pop()
                                nav.push(Screen.Player)
                            },
                        )
                        Screen.History -> HistoryScreen(lib, back, play)
                        Screen.Live -> LiveScreen(back, play)
                        is Screen.LocalFolder -> LocalFolderScreen(
                            lib, screen.path, screen.title, back,
                            openFolder = { path, title -> nav.push(Screen.LocalFolder(path, title)) },
                            play = play,
                        )
                        is Screen.Remote -> RemoteFolderScreen(
                            lib, screen.serverId, screen.path, screen.title, back,
                            openFolder = { dir -> nav.push(Screen.Remote(screen.serverId, dir.path, dir.name)) },
                            play = play,
                            openDetail = { item, queue, i, siblings ->
                                // 上一级目录是页面栈里的前一个同服务器目录页
                                val parent = nav.stack.filterIsInstance<Screen.Remote>().dropLast(1).lastOrNull()
                                    ?.takeIf { it.serverId == screen.serverId }
                                val folder = FolderContext(siblings, screen.title, parent?.path, parent?.title)
                                nav.push(Screen.FileDetail(screen.serverId, item, queue, i, folder))
                            },
                            openImages = { images, i -> nav.push(Screen.ImageViewer(screen.serverId, images, i)) },
                            settings = ui.settings,
                        )
                        is Screen.PlaylistDetail -> PlaylistDetailScreen(lib, screen.id, back, play)
                        is Screen.ServerHome -> ServerHomeScreen(
                            lib, screen.serverId, screen.title, back,
                            openLibrary = { s ->
                                // 音乐、照片库还是按文件夹浏览
                                if (s.type == "movie" || s.type == "show") nav.push(Screen.ServerLibrary(screen.serverId, s.key, s.title))
                                else {
                                    val browser = lib.server(screen.serverId)?.let { lib.client(it) as? com.aisub.player.remote.cloud.MediaBrowser }
                                    nav.push(Screen.Remote(screen.serverId, browser?.folderPath(s) ?: s.key, s.title))
                                }
                            },
                            openItem = { nav.push(Screen.ServerDetail(screen.serverId, it.ratingKey)) },
                            openFolders = { nav.push(Screen.Remote(screen.serverId, "", screen.title)) },
                        )
                        is Screen.ServerLibrary -> ServerLibraryScreen(
                            lib, screen.serverId, screen.sectionKey, screen.title, back,
                            openItem = { nav.push(Screen.ServerDetail(screen.serverId, it.ratingKey)) },
                        )
                        is Screen.ServerDetail -> ServerDetailScreen(
                            lib, screen.serverId, screen.ratingKey, back,
                            openItem = { nav.push(Screen.ServerDetail(screen.serverId, it.ratingKey)) },
                            play = play,
                        )
                        is Screen.FileDetail -> FileDetailScreen(
                            main, lib, screen.serverId, screen.item, screen.queue, screen.index, screen.folder, back,
                            play = play,
                            openImages = { images, i -> nav.push(Screen.ImageViewer(screen.serverId, images, i)) },
                            openFolder = { dir -> screen.serverId?.let { nav.push(Screen.Remote(it, dir.path, dir.name)) } },
                            openSettings = { nav.push(Screen.Settings) },
                        )
                        is Screen.ImageViewer -> ImageViewerScreen(
                            screen.serverId, screen.images, screen.index, back,
                            openDetail = { item -> nav.push(Screen.FileDetail(screen.serverId, item)) },
                        )
                    }
                   }
                    // 音乐在播放页外继续放时，页面底部的「正在播放」小条（占自己的位置，不盖住列表），点它回到播放页。
                    // 首页的小条放在底部标签栏上面，由 HomeScreen 自己显示
                    if (nav.current != Screen.Player && nav.current != Screen.Home) NowPlayingBar(
                        main, bottomPadding = 8.dp, onOpen = { nav.push(Screen.Player) },
                    )
                  }
                }
            }
        }
    }

    /** 离开播放页：视频暂停；音乐（开了后台播放时）继续放，底部显示「正在播放」小条。 */
    private fun leavePlayer() {
        if (!main.keepMusicPlayingOutsidePlayer()) main.player.pause()
        main.savePosition()
        lib.reloadStored()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PipState.inPip = isInPictureInPictureMode
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** 从文件管理器"用此应用打开"视频；下载版还接收其他 App 分享过来的视频链接。 */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && main.linkDownloader != null) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: return
            val url = com.aisub.player.download.extractUrl(text) ?: return
            main.downloadLink(url)
            if (nav.current != Screen.Link) nav.push(Screen.Link)
            return
        }
        if (intent?.action == ACTION_OPEN_PLAYER) {
            if (main.state.value.current != null && nav.current != Screen.Player) nav.push(Screen.Player)
            return
        }
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        main.play(listOf(MediaLibrary.entryFor(this, uri)), 0)
        nav.push(Screen.Player)
    }

    /** 按 Home 或切到别的应用：播放视频且设置为悬浮窗时，缩成画中画继续播放。 */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (nav.current != Screen.Player || !main.wantsAutoPip()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val size = main.player.videoSize
        val ratio = if (size.width > 0 && size.height > 0) {
            // 系统只接受 1:2.39 到 2.39:1 之间的比例
            val r = (size.width * size.pixelWidthHeightRatio / size.height).coerceIn(1 / 2.39f, 2.39f)
            android.util.Rational((r * 1000).toInt(), 1000)
        } else android.util.Rational(16, 9)
        runCatching { enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(ratio).build()) }
    }

    /** 应用不可见了（回桌面、锁屏、关掉悬浮窗）：按设置决定继续播放还是暂停。 */
    override fun onStop() {
        super.onStop()
        main.savePosition()
        if (!main.keepPlayingInBackground()) main.player.pause()
    }

    companion object {
        /** 通知栏、锁屏播放控件点进来：打开播放页 */
        const val ACTION_OPEN_PLAYER = "com.aisub.player.OPEN_PLAYER"
    }
}
