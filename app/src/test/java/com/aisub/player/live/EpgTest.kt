package com.aisub.player.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class EpgTest {

    @Test
    fun normalizesChannelNamesAndExtractsDiypQuery() {
        assertEquals("cctv1", EpgMatcher.normalize("CCTV-1 综合 高清"))
        assertEquals("cctv5+", EpgMatcher.normalize("CCTV-5+ 体育赛事 HD"))
        assertEquals("cctv5", EpgMatcher.normalize("CCTV5-4K"))
        assertEquals("湖南卫视", EpgMatcher.normalize("湖南卫视 (1080P) [备]"))

        val ch = LiveChannel(
            name = "CCTV5-4K",
            url = "http://live.example/cctv5.m3u8",
            group = "央视频道",
            tvgId = "cctv5",
            tvgName = "CCTV-5 体育",
        )
        assertEquals("CCTV5", EpgMatcher.queryName(ch))
        assertTrue(EpgMatcher.keysOf(ch).contains("cctv5"))
    }

    @Test
    fun parsesXmlTvSchedule() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE tv SYSTEM "xmltv.dtd">
            <tv generator-info-name="test">
                <channel id="ch_cctv5">
                    <display-name lang="zh">CCTV-5 体育</display-name>
                </channel>
                <programme start="20261109180000 +0800" stop="20261109183500 +0800" channel="ch_cctv5">
                    <title lang="zh">体育新闻</title>
                    <desc lang="zh">今日体坛快讯</desc>
                </programme>
                <programme start="20261109200000 +0800" stop="20261109213000 +0800" channel="ch_cctv5">
                    <title lang="zh">2024欧洲杯足球赛报道-足球盛宴-27</title>
                </programme>
            </tv>
        """.trimIndent()

        val ch = LiveChannel(name = "CCTV5-4K", url = "http://live.example/cctv5.m3u8")
        val map = XmlTvParser.parse(
            input = ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)),
            channels = listOf(ch),
            windowStartMs = 0L,
            windowEndMs = Long.MAX_VALUE,
        )
        val list = map["cctv5"]
        assertNotNull(list)
        assertEquals(2, list!!.size)
        assertEquals("体育新闻", list[0].title)
        assertEquals("时长: 35分钟", list[0].durationLabel())
        assertEquals("时长: 1小时30分钟", list[1].durationLabel())
    }

    @Test
    fun parsesDiypJsonFormat() {
        val json = """
            {
              "channel_name": "CCTV5",
              "date": "2026-11-09",
              "epg_data": [
                {"title": "实况录像-星耀五环", "start": "18:35", "end": "20:00", "desc": ""},
                {"title": "欧洲杯足球赛报道", "start": "20:00", "end": "21:30", "desc": "直播"}
              ]
            }
        """.trimIndent()
        val programs = DiypEpg.parseJson(json, fallbackDate = "2026-11-09")
        assertEquals(2, programs.size)
        assertEquals("实况录像-星耀五环", programs[0].title)
        assertEquals("时长: 1小时25分钟", programs[0].durationLabel())
        assertEquals("欧洲杯足球赛报道", programs[1].title)
        assertEquals("20:00 - 21:30", programs[1].timeRangeLabel( java.util.TimeZone.getTimeZone("Asia/Shanghai") ))
    }

    @Test
    fun buildsCatchupUrls() {
        val appendChannel = LiveChannel(
            name = "CCTV1",
            url = "http://live.example/cctv1/index.m3u8",
            catchup = "append",
            catchupSource = "?playseek={utc}-{end}",
        )
        assertTrue(CatchupUrl.canCatchup(appendChannel))
        val prog = EpgProgram(startMs = 1700000000_000L, endMs = 1700003600_000L, title = "新闻联播")
        val built = CatchupUrl.build(appendChannel, prog)
        assertEquals("http://live.example/cctv1/index.m3u8?playseek=1700000000-1700003600", built)
    }
}
