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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberDialogState
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
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import kotlinx.coroutines.delay
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import java.awt.image.BufferedImage
import java.io.File
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.plaf.FontUIResource
import kotlin.math.roundToInt

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

private fun ensureSwingCjkFallbackFont() {
    runCatching {
        val probe = UIManager.getFont("MenuItem.font") ?: UIManager.getFont("Label.font") ?: return
        if (probe.canDisplay('中')) return
        val families = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        val chosen = listOf("Microsoft YaHei UI", "Microsoft YaHei", "SimSun", Font.DIALOG)
            .firstOrNull { it in families || it == Font.DIALOG } ?: return
        val defaults = UIManager.getLookAndFeelDefaults()
        for (key in defaults.keys.toList()) {
            val f = defaults[key] as? Font ?: continue
            if (!f.canDisplay('中')) {
                UIManager.put(key, FontUIResource(Font(chosen, f.style, f.size)))
            }
        }
    }
}

internal fun computeTrayMenuPosition(
    anchorX: Int,
    anchorY: Int,
    menuWidth: Int,
    menuHeight: Int,
    workArea: Rectangle,
    margin: Int = 8,
): Point {
    val minX = workArea.x + margin
    val maxX = (workArea.x + workArea.width - menuWidth - margin).coerceAtLeast(minX)
    val x = (anchorX - menuWidth / 2).coerceIn(minX, maxX)

    val minY = workArea.y + margin
    val maxY = (workArea.y + workArea.height - menuHeight - margin).coerceAtLeast(minY)
    val aboveY = anchorY - menuHeight - 6
    val rawY = if (aboveY >= minY) aboveY else anchorY + 6
    val y = rawY.coerceIn(minY, maxY)
    return Point(x, y)
}

private fun configureTrayPopupWindow(window: java.awt.Window) {
    runCatching {
        val ptr = Native.getComponentPointer(window) ?: return
        val hwnd = WinDef.HWND(ptr)
        val user32 = User32.INSTANCE
        val exStyle = user32.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE)
        // WS_EX_TOOLWINDOW (0x80) 隐藏任务栏与 Alt+Tab 图标；清除 WS_EX_APPWINDOW (0x40000)
        val nextStyle = (exStyle or 0x00000080) and 0x00040000.inv()
        if (nextStyle != exStyle) {
            user32.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, nextStyle)
        }
        user32.SetForegroundWindow(hwnd)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DesktopSystemTray(
    appIcon: BufferedImage,
    darkTheme: Boolean,
    showFloatingSubtitles: Boolean,
    clickThrough: Boolean,
    onShowMainWindow: () -> Unit,
    onToggleFloatingSubtitles: () -> Unit,
    onDisableClickThrough: () -> Unit,
    onExit: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var menuAnchorX by remember { mutableStateOf(0) }
    var menuAnchorY by remember { mutableStateOf(0) }
    var menuWorkArea by remember { mutableStateOf(Rectangle(0, 0, 1920, 1080)) }
    var menuOpenNonce by remember { mutableStateOf(0) }

    val currentOnShowMainWindow by rememberUpdatedState(onShowMainWindow)

    DisposableEffect(appIcon) {
        if (!SystemTray.isSupported()) {
            return@DisposableEffect onDispose {}
        }
        // 使用无原生 PopupMenu 的 TrayIcon，避免 Windows UTF-8 环境下 AWT 菜单把中文渲染成方框
        val trayIcon = TrayIcon(appIcon, "译幕 Voxtitle (Windows)").apply {
            isImageAutoSize = true
        }
        trayIcon.addActionListener {
            SwingUtilities.invokeLater {
                menuOpen = false
                currentOnShowMainWindow()
            }
        }
        trayIcon.addMouseListener(
            object : MouseAdapter() {
                override fun mouseReleased(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON3 || e.isPopupTrigger) {
                        val pointer = runCatching { MouseInfo.getPointerInfo() }.getOrNull()
                        val gc = pointer?.device?.defaultConfiguration
                            ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
                        val pt = pointer?.location ?: run {
                            val sx = gc.defaultTransform.scaleX.coerceAtLeast(1.0)
                            val sy = gc.defaultTransform.scaleY.coerceAtLeast(1.0)
                            Point((e.xOnScreen / sx).roundToInt(), (e.yOnScreen / sy).roundToInt())
                        }
                        val bounds = gc.bounds
                        val insets = Toolkit.getDefaultToolkit().getScreenInsets(gc)
                        val work = Rectangle(
                            bounds.x + insets.left,
                            bounds.y + insets.top,
                            (bounds.width - insets.left - insets.right).coerceAtLeast(240),
                            (bounds.height - insets.top - insets.bottom).coerceAtLeast(240),
                        )
                        SwingUtilities.invokeLater {
                            menuAnchorX = pt.x
                            menuAnchorY = pt.y
                            menuWorkArea = work
                            menuOpenNonce++
                            menuOpen = true
                        }
                    } else if (e.button == MouseEvent.BUTTON1) {
                        SwingUtilities.invokeLater {
                            if (menuOpen) {
                                menuOpen = false
                            } else {
                                currentOnShowMainWindow()
                            }
                        }
                    }
                }
            },
        )
        val systemTray = runCatching { SystemTray.getSystemTray() }.getOrNull()
        val added = systemTray != null && runCatching { systemTray.add(trayIcon) }.isSuccess
        onDispose {
            if (added) {
                runCatching { systemTray.remove(trayIcon) }
            }
        }
    }

    if (menuOpen) {
        val hasClickThrough = showFloatingSubtitles && clickThrough
        val menuWidthDp = 216
        val menuHeightDp = if (hasClickThrough) 192 else 154
        val pos = computeTrayMenuPosition(menuAnchorX, menuAnchorY, menuWidthDp, menuHeightDp, menuWorkArea)
        val dialogState = rememberDialogState(
            position = WindowPosition(pos.x.dp, pos.y.dp),
            size = DpSize(menuWidthDp.dp, menuHeightDp.dp),
        )
        LaunchedEffect(pos, menuWidthDp, menuHeightDp, menuOpenNonce) {
            dialogState.size = DpSize(menuWidthDp.dp, menuHeightDp.dp)
            dialogState.position = WindowPosition(pos.x.dp, pos.y.dp)
        }

        DialogWindow(
            onCloseRequest = { menuOpen = false },
            state = dialogState,
            title = "译幕菜单",
            undecorated = true,
            transparent = true,
            resizable = false,
            focusable = true,
            alwaysOnTop = true,
            onPreviewKeyEvent = { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) {
                    menuOpen = false
                    true
                } else {
                    false
                }
            },
        ) {
            DisposableEffect(window) {
                var focusArmed = false
                val focusListener = object : WindowFocusListener {
                    override fun windowGainedFocus(e: WindowEvent?) {
                        focusArmed = true
                    }

                    override fun windowLostFocus(e: WindowEvent?) {
                        if (focusArmed) menuOpen = false
                    }
                }
                window.addWindowFocusListener(focusListener)
                SwingUtilities.invokeLater {
                    configureTrayPopupWindow(window)
                    window.toFront()
                    window.requestFocus()
                }
                onDispose {
                    window.removeWindowFocusListener(focusListener)
                }
            }

            LaunchedEffect(window, menuOpenNonce) {
                delay(180L)
                configureTrayPopupWindow(window)
                while (true) {
                    val down = runCatching {
                        (User32.INSTANCE.GetAsyncKeyState(0x01).toInt() and 0x8000) != 0 ||
                            (User32.INSTANCE.GetAsyncKeyState(0x02).toInt() and 0x8000) != 0
                    }.getOrDefault(false)
                    if (down) {
                        val p = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
                        if (p != null && !window.bounds.contains(p)) {
                            menuOpen = false
                            break
                        }
                    }
                    delay(40L)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    shadowElevation = 8.dp,
                    color = if (darkTheme) Color(0xFF22242E) else Color(0xFFFCFCFD),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (darkTheme) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.10f),
                    ),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                    ) {
                        TrayMenuItemRow(
                            label = "显示主窗口",
                            darkTheme = darkTheme,
                            onClick = {
                                menuOpen = false
                                onShowMainWindow()
                            },
                        )
                        TrayMenuItemRow(
                            label = if (showFloatingSubtitles) "关闭桌面全局字幕" else "开启桌面全局字幕",
                            darkTheme = darkTheme,
                            onClick = {
                                menuOpen = false
                                onToggleFloatingSubtitles()
                            },
                        )
                        if (hasClickThrough) {
                            TrayMenuItemRow(
                                label = "解除字幕鼠标穿透",
                                darkTheme = darkTheme,
                                onClick = {
                                    menuOpen = false
                                    onDisableClickThrough()
                                },
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                                .height(1.dp)
                                .background(
                                    if (darkTheme) Color.White.copy(alpha = 0.10f)
                                    else Color.Black.copy(alpha = 0.08f),
                                ),
                        )
                        TrayMenuItemRow(
                            label = "退出译幕",
                            isDestructive = true,
                            darkTheme = darkTheme,
                            onClick = {
                                menuOpen = false
                                onExit()
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TrayMenuItemRow(
    label: String,
    isDestructive: Boolean = false,
    darkTheme: Boolean,
    onClick: () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    val hoverBg = if (darkTheme) {
        Color.White.copy(alpha = 0.10f)
    } else {
        Color.Black.copy(alpha = 0.06f)
    }
    val textColor = when {
        isDestructive && hovered -> if (darkTheme) Color(0xFFFF7B72) else Color(0xFFD32F2F)
        isDestructive -> if (darkTheme) Color(0xFFFF9B93) else Color(0xFFC62828)
        darkTheme -> Color(0xFFF3F4F8)
        else -> Color(0xFF1A1C24)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (hovered) hoverBg else Color.Transparent)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            color = textColor,
            maxLines = 1,
        )
    }
}

fun main(args: Array<String>) {
    runCatching {
        System.setProperty("jna.encoding", "UTF-8")
        if (System.getProperty("skiko.renderApi").isNullOrBlank()) {
            System.setProperty("skiko.renderApi", "OPENGL")
        }
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        ensureSwingCjkFallbackFont()
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
        var mainAwtWindow by remember { mutableStateOf<java.awt.Window?>(null) }
        val restoreMainWindow: () -> Unit = {
            showMainWindow = true
            windowState.isMinimized = false
            mainAwtWindow?.toFront()
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

        // Windows 系统托盘图标与自定义 Compose 菜单
        DesktopSystemTray(
            appIcon = appIcon,
            darkTheme = windowDark,
            showFloatingSubtitles = showFloatingSubtitles,
            clickThrough = floatingState.clickThrough,
            onShowMainWindow = restoreMainWindow,
            onToggleFloatingSubtitles = {
                if (showFloatingSubtitles) {
                    liveTranscriber.stop()
                    showFloatingSubtitles = false
                } else {
                    showFloatingSubtitles = true
                }
            },
            onDisableClickThrough = { liveTranscriber.setClickThrough(false) },
            onExit = {
                liveTranscriber.stop()
                activeActivity.dispatchOnStop()
                exitApplication()
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
                    mainAwtWindow = window
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
                    onDispose {
                        window.dropTarget = null
                        if (mainAwtWindow === window) mainAwtWindow = null
                    }
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
