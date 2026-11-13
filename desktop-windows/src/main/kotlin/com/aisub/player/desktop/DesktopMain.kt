package com.aisub.player.desktop

import android.content.DesktopContextHolder
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.DesktopBackDispatcher
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.DesktopLifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aisub.player.MainActivity
import com.aisub.player.SettingsStore
import com.aisub.player.VoxtitleApp
import com.aisub.player.AppDarkMode
import com.aisub.player.ui.AppIcons
import com.aisub.player.ui.GlassStyle
import com.aisub.player.ui.GlassTokens
import com.aisub.player.ui.LocalLiquidGlassEnabled
import com.aisub.player.ui.LocalSystemDarkOverride
import com.aisub.player.ui.PopupTheme
import com.aisub.player.ui.glassShadow
import com.aisub.player.ui.isDarkResolved
import com.aisub.player.ui.liquidGlass
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.ptr.IntByReference
import kotlinx.coroutines.delay
import java.awt.RenderingHints
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.swing.UIManager

object DesktopAppRestarter {
    @Volatile
    var onRestart: (() -> Unit)? = null

    fun restart(context: android.content.Context) {
        val action = onRestart
        if (action != null) {
            javax.swing.SwingUtilities.invokeLater {
                action.invoke()
                Toast.makeText(context, "已导入备份并重新加载数据", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

object DesktopGlobalSubtitles {
    var active by mutableStateOf(false)
    var clickThrough by mutableStateOf(false)
    var onToggle: () -> Unit = {}
    var onDisableClickThrough: () -> Unit = {}
}

private fun createAppIconImage(): BufferedImage {
    val size = 64
    val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = java.awt.Color(0x16, 0x18, 0x24)
    g.fillRoundRect(0, 0, size, size, 16, 16)
    g.color = java.awt.Color(0xFF, 0x98, 0x00)
    val xPoints = intArrayOf(22, 22, 46)
    val yPoints = intArrayOf(16, 48, 32)
    g.fillPolygon(xPoints, yPoints, 3)
    g.dispose()
    return img
}

private fun dispatchExternalFileOrUrl(activity: MainActivity, target: String) {
    val trimmed = target.trim()
    if (trimmed.isEmpty()) return
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, trimmed)
        }
        activity.dispatchOnNewIntent(sendIntent)
        return
    }
    val file = File(trimmed)
    if (file.exists()) {
        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.fromFile(file))
        activity.dispatchOnNewIntent(viewIntent)
    }
}

private interface DesktopDwmapi : Library {
    fun DwmSetWindowAttribute(hwnd: Pointer, dwAttribute: Int, pvAttribute: IntByReference, cbAttribute: Int): Int

    companion object {
        val INSTANCE: DesktopDwmapi? = runCatching {
            Native.load("dwmapi", DesktopDwmapi::class.java)
        }.getOrNull()
    }
}

private fun queryWindowsDarkTheme(): Boolean = runCatching {
    Advapi32Util.registryGetIntValue(
        WinReg.HKEY_CURRENT_USER,
        "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
        "AppsUseLightTheme",
    ) == 0
}.getOrDefault(false)

private fun applyWindowDarkTitleBar(window: java.awt.Window, dark: Boolean) {
    runCatching {
        val ptr = Native.getComponentPointer(window) ?: return
        val ref = IntByReference(if (dark) 1 else 0)
        // DWMWA_USE_IMMERSIVE_DARK_MODE = 20 (Win10 2004+ / Win11), 19 为旧版回退
        val res = DesktopDwmapi.INSTANCE?.DwmSetWindowAttribute(ptr, 20, ref, 4) ?: -1
        if (res != 0) DesktopDwmapi.INSTANCE?.DwmSetWindowAttribute(ptr, 19, ref, 4)
    }
}

fun main(args: Array<String>) {
    runCatching {
        System.setProperty("jna.encoding", "UTF-8")
        if (System.getProperty("skiko.renderApi").isNullOrBlank()) {
            System.setProperty("skiko.renderApi", "OPENGL")
        }
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    }

    val voxApp = VoxtitleApp()
    DesktopContextHolder.appContext = voxApp
    voxApp.onCreate()
    // 后台线程提前预热 VLC NativeDiscovery、共享 MediaPlayerFactory 及双地址服务器可达性探测，消除首次点播卡顿
    DesktopPlayerEngine.prewarm(voxApp)

    val mainActivity = MainActivity()
    // Windows 桌面版默认已拥有本地媒体库访问权限
    mainActivity.getSharedPreferences("app", 0).edit().putBoolean("onboarded", true).apply()
    mainActivity.dispatchOnCreate(null)

    if (args.isNotEmpty()) {
        dispatchExternalFileOrUrl(mainActivity, args[0])
    }

    val liveTranscriber = DesktopLiveTranscriber(voxApp)
    val settingsStore = SettingsStore(voxApp)
    val appIcon = createAppIconImage()

    application {
        var activeActivity by remember { mutableStateOf(mainActivity) }
        var restartGeneration by remember { mutableStateOf(0) }
        var showMainWindow by remember { mutableStateOf(true) }
        var showFloatingSubtitles by remember { mutableStateOf(false) }
        val floatingState by liveTranscriber.state.collectAsState()
        DesktopGlobalSubtitles.active = showFloatingSubtitles
        DesktopGlobalSubtitles.clickThrough = showFloatingSubtitles && floatingState.clickThrough
        DesktopGlobalSubtitles.onToggle = {
            if (showFloatingSubtitles) {
                liveTranscriber.stop()
                showFloatingSubtitles = false
            } else {
                showFloatingSubtitles = true
            }
        }
        DesktopGlobalSubtitles.onDisableClickThrough = {
            liveTranscriber.setClickThrough(false)
        }
        val windowState = rememberWindowState(
            size = DpSize(1280.dp, 820.dp),
            position = WindowPosition(Alignment.Center),
        )
        val iconPainter = remember { BitmapPainter(appIcon.toComposeImageBitmap()) }

        DisposableEffect(Unit) {
            DesktopAppRestarter.onRestart = {
                runCatching { activeActivity.dispatchOnStop() }
                androidx.lifecycle.DesktopViewModelStore.clearAll()
                com.aisub.player.SettingsRepository(voxApp).load().let {
                    com.aisub.player.net.AppProxy.apply(voxApp, it.proxyType, it.proxyHost, it.proxyPort)
                }
                val store = com.aisub.player.media.AppStore(voxApp)
                com.aisub.player.remote.cloud.CloudRegistry.serverLookup = { id -> store.servers().firstOrNull { it.id == id } }
                com.aisub.player.remote.cloud.CloudRegistry.serverSaver = { server -> store.updateServerCredentials(server) }
                store.servers().forEach { com.aisub.player.remote.cloud.CloudRegistry.forget(it.id) }
                val next = MainActivity()
                next.getSharedPreferences("app", 0).edit().putBoolean("onboarded", true).apply()
                next.dispatchOnCreate(null)
                activeActivity = next
                restartGeneration++
            }
            onDispose {
                DesktopAppRestarter.onRestart = null
            }
        }

        // Windows 系统托盘图标与快捷菜单
        Tray(
            icon = iconPainter,
            tooltip = "译幕 Voxtitle (Windows)",
            onAction = { showMainWindow = true },
            menu = {
                Item("显示主窗口") { showMainWindow = true }
                Item(if (showFloatingSubtitles) "关闭桌面全局字幕" else "开启桌面全局字幕") {
                    if (showFloatingSubtitles) {
                        liveTranscriber.stop()
                        showFloatingSubtitles = false
                    } else {
                        showFloatingSubtitles = true
                    }
                }
                if (showFloatingSubtitles && floatingState.clickThrough) {
                    Item("解除字幕鼠标穿透") {
                        liveTranscriber.setClickThrough(false)
                    }
                }
                Separator()
                Item("退出译幕") {
                    liveTranscriber.stop()
                    activeActivity.dispatchOnStop()
                    exitApplication()
                }
            },
        )

        if (showFloatingSubtitles) {
            FloatingSubtitleWindow(
                transcriber = liveTranscriber,
                settingsStore = settingsStore,
                onClose = { showFloatingSubtitles = false },
            )
        }

        if (showMainWindow) {
            Window(
                onCloseRequest = {
                    if (showFloatingSubtitles) {
                        // 如果开着桌面全局字幕，关主窗口时最小化到托盘继续运行
                        showMainWindow = false
                    } else {
                        liveTranscriber.stop()
                        activeActivity.dispatchOnStop()
                        exitApplication()
                    }
                },
                state = windowState,
                title = "译幕 Voxtitle",
                icon = iconPainter,
                onPreviewKeyEvent = { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Escape) {
                        if (DesktopBackDispatcher.hasEnabledCallback()) {
                            DesktopBackDispatcher.dispatchBack()
                        } else false
                    } else false
                },
            ) {
                // 支持把本地视频、音频、漫画压缩包、PDF、电子书或视频网址直接拖进窗口打开
                DisposableEffect(window) {
                    val dropTarget = DropTarget(
                        window,
                        DnDConstants.ACTION_COPY,
                        object : DropTargetAdapter() {
                            override fun drop(dtde: DropTargetDropEvent) {
                                runCatching {
                                    dtde.acceptDrop(DnDConstants.ACTION_COPY)
                                    val transferable = dtde.transferable
                                    when {
                                        transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) -> {
                                            val list = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
                                            val firstFile = list?.firstOrNull() as? File
                                            if (firstFile != null) {
                                                dispatchExternalFileOrUrl(activeActivity, firstFile.absolutePath)
                                            }
                                        }
                                        transferable.isDataFlavorSupported(DataFlavor.stringFlavor) -> {
                                            val str = transferable.getTransferData(DataFlavor.stringFlavor) as? String
                                            if (!str.isNullOrBlank()) {
                                                dispatchExternalFileOrUrl(activeActivity, str)
                                            }
                                        }
                                    }
                                    dtde.dropComplete(true)
                                }
                            }
                        },
                    )
                    window.dropTarget = dropTarget
                    onDispose { window.dropTarget = null }
                }

                val density = LocalDensity.current
                val winWidthDp = windowState.size.width.value.toInt().coerceAtLeast(360)
                val winHeightDp = windowState.size.height.value.toInt().coerceAtLeast(360)
                val configuration = remember(winWidthDp, winHeightDp, density.density) {
                    Configuration(
                        screenWidthDp = winWidthDp,
                        screenHeightDp = winHeightDp,
                        smallestScreenWidthDp = minOf(winWidthDp, winHeightDp),
                        densityDpi = (density.density * 160f).toInt(),
                    )
                }

                var toastMessage by remember { mutableStateOf<String?>(null) }
                var winSystemDark by remember { mutableStateOf(queryWindowsDarkTheme()) }
                val settingsPrefs = remember { voxApp.getSharedPreferences("settings", 0) }
                var savedDarkMode by remember {
                    mutableStateOf(
                        runCatching {
                            AppDarkMode.valueOf(settingsPrefs.getString("appDarkMode", AppDarkMode.SYSTEM.name)!!)
                        }.getOrDefault(AppDarkMode.SYSTEM),
                    )
                }
                var savedLiquidGlass by remember {
                    mutableStateOf(settingsPrefs.getBoolean("liquidGlass", false))
                }
                LaunchedEffect(Unit) {
                    var tick = 0
                    while (true) {
                        toastMessage = Toast.currentMessage
                        val rawMode = settingsPrefs.getString("appDarkMode", null)
                        savedDarkMode = when {
                            rawMode != null -> runCatching { AppDarkMode.valueOf(rawMode) }.getOrDefault(AppDarkMode.SYSTEM)
                            settingsPrefs.getString("appBackground", null) == "DARK" -> AppDarkMode.DARK
                            else -> AppDarkMode.SYSTEM
                        }
                        savedLiquidGlass = settingsPrefs.getBoolean("liquidGlass", false)
                        if (tick++ % 6 == 0) {
                            winSystemDark = queryWindowsDarkTheme()
                        }
                        delay(150)
                    }
                }
                val windowDark = savedDarkMode.isDarkResolved(winSystemDark)
                LaunchedEffect(window, windowDark) {
                    applyWindowDarkTitleBar(window, windowDark)
                }

                androidx.compose.runtime.key(restartGeneration) {
                    CompositionLocalProvider(
                        LocalContext provides activeActivity,
                        LocalConfiguration provides configuration,
                        LocalLifecycleOwner provides DesktopLifecycleOwner,
                        LocalSystemDarkOverride provides winSystemDark,
                        LocalLiquidGlassEnabled provides savedLiquidGlass,
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            activeActivity.desktopContent?.invoke()

                        // Toast 提示浮层
                        AnimatedVisibility(
                            visible = toastMessage != null,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 84.dp),
                        ) {
                            PopupTheme {
                                val toastShape = RoundedCornerShape(50)
                                Box(
                                    modifier = Modifier
                                        .glassShadow(toastShape, GlassStyle.Floating, 14.dp)
                                        .liquidGlass(
                                            shape = toastShape,
                                            style = GlassStyle.Floating,
                                            tint = GlassTokens.DarkOverlayTint,
                                            enableBackdrop = false,
                                        )
                                        .padding(horizontal = 18.dp, vertical = 10.dp),
                                ) {
                                    Text(
                                        text = toastMessage.orEmpty(),
                                        color = Color.White,
                                        fontSize = 14.sp,
                                    )
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
