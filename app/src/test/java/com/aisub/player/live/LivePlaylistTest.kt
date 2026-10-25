package com.aisub.player.live

import org.junit.Assert.assertEquals
import org.junit.Test

class LivePlaylistTest {

    @Test
    fun parsesM3uWithAttributes() {
        val text = """
            #EXTM3U x-tvg-url="http://epg.example/e.xml"
            #EXTINF:-1 tvg-id="cctv1" tvg-name="CCTV1" tvg-logo="http://logo/cctv1.png" group-title="央视",CCTV-1 综合
            http://live.example/cctv1.m3u8
            #EXTINF:-1 tvg-name="Name, with comma" group-title="卫视",湖南卫视
            #EXTVLCOPT:http-user-agent=Mozilla/5.0
            #EXTVLCOPT:http-referrer=http://ref.example/
            http://live.example/hunan.m3u8
            #EXTINF:-1,无分组
            #EXTGRP:新闻
            rtmp://live.example/news
        """.trimIndent()
        val ch = LivePlaylist.parse(text)
        assertEquals(3, ch.size)
        assertEquals(LiveChannel("CCTV-1 综合", "http://live.example/cctv1.m3u8", "央视", "http://logo/cctv1.png", "cctv1"), ch[0])
        assertEquals("湖南卫视", ch[1].name)
        assertEquals("卫视", ch[1].group)
        assertEquals("Mozilla/5.0", ch[1].userAgent)
        assertEquals("http://ref.example/", ch[1].referrer)
        assertEquals("新闻", ch[2].group)
        assertEquals("无分组", ch[2].name)
    }

    @Test
    fun parsesTxtGenreFormat() {
        val text = """
            央视频道,#genre#
            CCTV1,http://a/cctv1.m3u8
            CCTV2,http://a/cctv2.m3u8#http://b/cctv2.m3u8
            卫视频道,#genre#
            湖南卫视，http://a/hunan.m3u8
            这行没有地址
        """.trimIndent()
        val ch = LivePlaylist.parse(text)
        assertEquals(listOf("CCTV1", "CCTV2", "CCTV2", "湖南卫视"), ch.map { it.name })
        assertEquals(listOf("央视频道", "央视频道", "央视频道", "卫视频道"), ch.map { it.group })
        assertEquals("http://b/cctv2.m3u8", ch[2].url)
    }

    @Test
    fun bomAndBareUrls() {
        val ch = LivePlaylist.parse("﻿#EXTM3U\nhttp://x/a.m3u8\n")
        assertEquals(1, ch.size)
        assertEquals("a.m3u8", ch[0].name)
    }
}
