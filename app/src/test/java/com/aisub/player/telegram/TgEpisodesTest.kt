package com.aisub.player.telegram

import org.junit.Assert.assertEquals
import org.junit.Test

class TgEpisodesTest {
    private fun v(id: Long, name: String) = TgVideo(1, id, "番剧: #魔女守护者", "", name, emptyList(), 0, 0, 0, null, null)

    @Test
    fun episodeNumbers() {
        assertEquals(8, TgEpisodes.episodeOf("Dynamis_One_Witch_Watch_08_ABEMA_1920x1080_AVC_AAC_MP4.mp4"))
        assertEquals(7, TgEpisodes.episodeOf("[Sakurato] Witch Watch [07][AVC-8bit 1080p AAC][CHS].mp4"))
        assertEquals(20, TgEpisodes.episodeOf("Dynamis_One_魔神創造傳_20_Baha_1920x1080_AVC_AAC.mp4"))
        assertEquals(12, TgEpisodes.episodeOf("庆余年 第12集.mp4"))
        assertEquals(3, TgEpisodes.episodeOf("Show.S01E03.1080p.mkv"))
        assertEquals(null, TgEpisodes.episodeOf("trailer_1920x1080.mp4"))
    }

    @Test
    fun sameReleaseInEpisodeOrder() {
        val abema7 = v(10, "Dynamis_One_Witch_Watch_07_ABEMA_1920x1080_AVC_AAC_MP4.mp4")
        val baha7 = v(11, "Dynamis_One_WITCH_WATCH_魔女守護者_07_Baha_1920x1080_AVC_AAC.mp4")
        val saku7 = v(12, "[Sakurato] Witch Watch [07][AVC-8bit 1080p AAC][CHS].mp4")
        val abema8 = v(20, "Dynamis_One_Witch_Watch_08_ABEMA_1920x1080_AVC_AAC_MP4.mp4")
        val baha8 = v(21, "Dynamis_One_WITCH_WATCH_魔女守護者_08_Baha_1920x1080_AVC_AAC.mp4")
        val abema6 = v(5, "Dynamis_One_Witch_Watch_06_ABEMA_1920x1080_AVC_AAC_MP4.mp4")
        val all = listOf(baha8, abema8, saku7, baha7, abema7, abema6)
        assertEquals(listOf(5L, 10L, 20L), TgEpisodes.playOrder(abema7, all).map { it.messageId })
        assertEquals(listOf(11L, 21L), TgEpisodes.playOrder(baha7, all).map { it.messageId })
        // 只有一集的版本：每集挑一个
        assertEquals(listOf(5L, 12L, 20L), TgEpisodes.playOrder(saku7, all).map { it.messageId })
    }

    @Test
    fun noEpisodesFallsBackToTime() {
        val a = v(3, "a.mp4")
        val b = v(1, "b.mp4")
        assertEquals(listOf(1L, 3L), TgEpisodes.playOrder(a, listOf(a, b)).map { it.messageId })
    }
}
