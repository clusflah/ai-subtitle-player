package com.aisub.player.meta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NameParserTest {

    @Test
    fun dottedMovieName() {
        val p = NameParser.parse("The.Matrix.1999.1080p.BluRay.x264-GROUP.mkv")
        assertEquals("The Matrix", p.title)
        assertEquals(1999, p.year)
        assertNull(p.episode)
    }

    @Test
    fun yearInParentheses() {
        val p = NameParser.parse("流浪地球2 (2023).mp4")
        assertEquals("流浪地球2", p.title)
        assertEquals(2023, p.year)
    }

    @Test
    fun titleThatIsAYear() {
        val p = NameParser.parse("1917.2019.2160p.WEB-DL.mkv")
        assertEquals("1917", p.title)
        assertEquals(2019, p.year)
    }

    @Test
    fun seasonEpisode() {
        val p = NameParser.parse("Breaking.Bad.S02E05.720p.HDTV.mkv")
        assertEquals("Breaking Bad", p.title)
        assertEquals(2, p.season)
        assertEquals(5, p.episode)
    }

    @Test
    fun fansubChineseEpisode() {
        val p = NameParser.parse("[某字幕组] 进击的巨人 第三季 第12集 [1080p].mp4")
        assertEquals("进击的巨人", p.title)
        assertEquals(3, p.season)
        assertEquals(12, p.episode)
    }

    @Test
    fun episodeTitleFirst() {
        val p = NameParser.parse("第 3 集 · 重逢.mkv")
        assertEquals("重逢", p.title)
        assertEquals(3, p.episode)
    }

    @Test
    fun keepsDotsInsideTitleWords() {
        val p = NameParser.parse("Mr.Robot.S01E01.mkv")
        assertEquals("Mr Robot", p.title)
    }

    @Test
    fun mixedTitleCandidates() {
        val p = NameParser.parse("流浪地球.The.Wandering.Earth.2019.1080p.mkv")
        assertEquals(listOf("流浪地球 The Wandering Earth", "流浪地球", "The Wandering Earth"), p.candidates())
    }

    @Test
    fun chineseNumbers() {
        assertEquals(12, NameParser.cnNumber("十二"))
        assertEquals(23, NameParser.cnNumber("二十三"))
        assertEquals(105, NameParser.cnNumber("一百零五"))
        assertEquals("二十", NameParser.toCn(20))
        assertEquals("十一", NameParser.toCn(11))
    }
}
