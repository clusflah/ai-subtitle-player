package com.aisub.player.ui

import androidx.compose.runtime.mutableStateListOf

sealed interface Screen {
    data object Home : Screen
    data object Player : Screen
    data object History : Screen
    data class LocalFolder(val path: String, val title: String) : Screen
    data class Remote(val serverId: String, val path: String) : Screen
    data class PlaylistDetail(val id: String) : Screen
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
