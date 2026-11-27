package com.voxmusic.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {

    @Test
    fun comparesMusicTagsAndVersionsCorrectly() {
        assertTrue(AppUpdater.isNewer("music-v1.0.13", "1.0.12"))
        assertTrue(AppUpdater.isNewer("music-v1.1.0", "1.0.12"))
        assertFalse(AppUpdater.isNewer("music-v1.0.12", "1.0.12"))
        assertFalse(AppUpdater.isNewer("music-v1.0.12", "1.0.12-debug"))
        assertFalse(AppUpdater.isNewer("music-v1.0.9", "1.0.12"))
    }

    @Test
    fun filtersMusicTagsOnly() {
        assertTrue(AppUpdater.acceptVoxMusicTag("music-v1.0.12"))
        assertTrue(AppUpdater.acceptVoxMusicTag("music-v2.0.0"))
        assertFalse(AppUpdater.acceptVoxMusicTag("v2.10.39"))
        assertFalse(AppUpdater.acceptVoxMusicTag("tdlib-v1"))
    }

    @Test
    fun picksMatchingVoxMusicApk() {
        val singleApk = listOf("VoxMusic-1.0.13.apk", "checksums.txt")
        assertEquals("VoxMusic-1.0.13.apk", AppUpdater.pickVoxMusicAsset(singleApk, listOf("arm64-v8a")))

        val abiApks = listOf(
            "Voxtitle-2.10.39-arm64-v8a.apk",
            "VoxMusic-1.0.13-armeabi-v7a.apk",
            "VoxMusic-1.0.13-arm64-v8a.apk",
            "VoxMusic-1.0.13-universal.apk",
        )
        assertEquals("VoxMusic-1.0.13-arm64-v8a.apk", AppUpdater.pickVoxMusicAsset(abiApks, listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("VoxMusic-1.0.13-universal.apk", AppUpdater.pickVoxMusicAsset(abiApks, listOf("x86_64")))

        val voxtitleOnly = listOf("Voxtitle-2.10.39-arm64-v8a.apk", "Voxtitle-dl-2.10.39-arm64-v8a.apk")
        assertNull(AppUpdater.pickVoxMusicAsset(voxtitleOnly, listOf("arm64-v8a")))
    }

    @Test
    fun extractsVoxMusicReleaseNotesSection() {
        val notes = """
            ## 安装包下载

            - [🎵 VoxMusic Android 正式版 (`VoxMusic-1.0.13.apk`)](https://github.com/clusflah/ai-subtitle-player/releases/download/music-v1.0.13/VoxMusic-1.0.13.apk) · `28.5 MB`

            ## VoxMusic v1.0.13 更新内容

            - **新增应用内检查更新**：支持检查 GitHub Releases 最新版本并下载安装
            - 新增 `GitHub` 项目跳转入口

            > 提示：访问 GitHub 如受限可在设置中开启代理。
        """.trimIndent()

        assertEquals(
            "• 新增应用内检查更新：支持检查 GitHub Releases 最新版本并下载安装\n" +
                "• 新增 GitHub 项目跳转入口\n\n" +
                "提示：访问 GitHub 如受限可在设置中开启代理。",
            AppUpdater.notesSince(notes, "1.0.12"),
        )
    }

    @Test
    fun includesMultipleNewerVersionsWhenSkipped() {
        val notes = """
            ## 安装包下载

            - 下载链接

            ## VoxMusic v1.0.14 更新内容

            - 新二

            ## VoxMusic v1.0.13 更新内容

            - 新一

            ## VoxMusic v1.0.12 更新内容

            - 已装
        """.trimIndent()

        assertEquals("【1.0.14】\n• 新二\n\n【1.0.13】\n• 新一", AppUpdater.notesSince(notes, "1.0.12"))
        assertEquals("• 新二", AppUpdater.notesSince(notes, "1.0.13"))
    }
}
