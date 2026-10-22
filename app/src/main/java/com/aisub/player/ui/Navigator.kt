package com.aisub.player.ui

import androidx.compose.runtime.mutableStateListOf

sealed interface Screen {
    data object Home : Screen
    data object Player : Screen
    data object History : Screen
    data object Settings : Screen
    data class LocalFolder(val path: String, val title: String) : Screen
    /** path：WebDAV/OpenList 是目录路径，网盘是目录 ID；title 用于标题栏 */
    data class Remote(val serverId: String, val path: String, val title: String) : Screen
    data class PlaylistDetail(val id: String) : Screen
    /**
     * 文件详情：文件信息 + TMDB 简介 + 豆瓣评分。serverId 为 null 表示本地文件；
     * queue / index 是点「播放」时的播放队列（同一文件夹里的媒体，方便连播下一集）。
     */
    data class FileDetail(
        val serverId: String?,
        val item: com.aisub.player.remote.RemoteItem,
        val queue: List<com.aisub.player.media.MediaEntry> = emptyList(),
        val index: Int = 0,
        /** 所在目录，用于读取旁边的 NFO、海报；null 表示不读本地元数据 */
        val folder: com.aisub.player.meta.FolderContext? = null,
    ) : Screen
    /** 看图：同一文件夹里的图片左右滑动切换。 */
    data class ImageViewer(val serverId: String?, val images: List<com.aisub.player.remote.RemoteItem>, val index: Int) : Screen
}

/** 极简页面栈：首页在栈底，返回键逐层弹出。 */
class Navigator {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()

    fun push(screen: Screen) {
        // 播放页只保留一个，重复打开时回到它
        if (screen == Screen.Player) stack.remove(Screen.Player)
        stack.add(screen)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }
}

/** 本地文件的「影视信息」菜单打开详情页；由 MainActivity 提供。 */
val LocalOpenDetail = androidx.compose.runtime.staticCompositionLocalOf<(com.aisub.player.media.MediaEntry) -> Unit> { {} }
