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
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import com.aisub.player.media.MediaEntry
import com.aisub.player.media.MediaLibrary
import com.aisub.player.ui.AppTheme
import com.aisub.player.ui.HistoryScreen
import com.aisub.player.ui.HomeScreen
import com.aisub.player.ui.LocalFolderScreen
import com.aisub.player.ui.Navigator
import com.aisub.player.ui.PlayerScreen
import com.aisub.player.ui.PlaylistDetailScreen
import com.aisub.player.ui.RemoteFolderScreen
import com.aisub.player.ui.Screen

class MainActivity : ComponentActivity() {

    private val main: MainViewModel by viewModels()
    private val lib: LibraryViewModel by viewModels()
    private val nav = Navigator()

    private val mediaPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
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
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { result -> lib.onPermission(result.values.any { it }) }

            LaunchedEffect(Unit) {
                if (hasMediaPermission()) lib.onPermission(true) else permissionLauncher.launch(mediaPermissions)
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

            AppTheme {
                when (val screen = nav.current) {
                    Screen.Home -> HomeScreen(lib, main, nav) { permissionLauncher.launch(mediaPermissions) }
                    Screen.Player -> PlayerScreen(main, onBack = back)
                    Screen.History -> HistoryScreen(lib, back, play)
                    is Screen.LocalFolder -> LocalFolderScreen(
                        lib, screen.path, screen.title, back,
                        openFolder = { path, title -> nav.push(Screen.LocalFolder(path, title)) },
                        play = play,
                    )
                    is Screen.Remote -> RemoteFolderScreen(
                        lib, screen.serverId, screen.path, back,
                        openFolder = { path -> nav.push(Screen.Remote(screen.serverId, path)) },
                        play = play,
                    )
                    is Screen.PlaylistDetail -> PlaylistDetailScreen(lib, screen.id, back, play)
                }
            }
        }
    }

    /** 离开播放页：暂停并记住进度，刷新首页的历史。 */
    private fun leavePlayer() {
        main.player.pause()
        main.savePosition()
        lib.reloadStored()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** 从文件管理器"用此应用打开"视频。 */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        main.play(listOf(MediaLibrary.entryFor(this, uri)), 0)
        nav.push(Screen.Player)
    }

    override fun onStop() {
        super.onStop()
        main.player.pause()
        main.savePosition()
    }
}
