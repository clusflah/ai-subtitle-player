package com.aisub.player.meta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NfoTest {

    @Test
    fun kodiMovie() {
        val nfo = Nfo.parse(
            """
            <?xml version="1.0" encoding="UTF-8" standalone="yes" ?>
            <movie>
              <title>健听女孩</title>
              <originaltitle>CODA</originaltitle>
              <ratings>
                <rating name="themoviedb" max="10" default="true"><value>8.0</value><votes>2800</votes></rating>
                <rating name="douban" max="10"><value>8.6</value><votes>165000</votes></rating>
              </ratings>
              <plot>露比是聋人家庭中唯一的健听者。</plot>
              <runtime>111</runtime>
              <thumb aspect="poster">https://image.tmdb.org/t/p/original/poster.jpg</thumb>
              <fanart><thumb>https://image.tmdb.org/t/p/original/fanart.jpg</thumb></fanart>
              <uniqueid type="tmdb" default="true">776503</uniqueid>
              <uniqueid type="imdb">tt10366460</uniqueid>
              <genre>剧情</genre>
              <genre>音乐</genre>
              <country>美国</country>
              <director>夏安·海德</director>
              <premiered>2021-08-13</premiered>
              <actor><name>艾米莉亚·琼斯</name><role>Ruby</role></actor>
              <actor><name>特洛伊·科特苏尔</name></actor>
            </movie>
            https://www.themoviedb.org/movie/776503
            """.trimIndent(),
        )!!
        assertEquals("movie", nfo.kind)
        assertEquals("健听女孩", nfo.title)
        assertEquals("CODA", nfo.originalTitle)
        assertEquals(2021, nfo.year)
        assertEquals(8.0, nfo.rating!!, 0.001)
        assertEquals("TMDB", nfo.ratingSource)
        assertEquals(8.6, nfo.doubanRating!!, 0.001)
        assertEquals(165000, nfo.doubanVotes)
        assertEquals("776503", nfo.tmdbId)
        assertEquals("tt10366460", nfo.imdbId)
        assertEquals(listOf("剧情", "音乐"), nfo.genres)
        assertEquals(listOf("艾米莉亚·琼斯", "特洛伊·科特苏尔"), nfo.cast)
        assertEquals(111, nfo.runtimeMinutes)
        assertEquals("https://image.tmdb.org/t/p/original/poster.jpg", nfo.posterUrl)
        assertEquals("https://image.tmdb.org/t/p/original/fanart.jpg", nfo.fanartUrl)
    }

    @Test
    fun oldStyleEpisode() {
        val nfo = Nfo.parse(
            "<episodedetails><title>重逢</title><showtitle>某剧</showtitle><season>2</season><episode>3</episode>" +
                "<rating>7.5</rating><id>tt1234567</id></episodedetails>",
        )!!
        assertEquals("episode", nfo.kind)
        assertEquals("某剧", nfo.showTitle)
        assertEquals(2, nfo.season)
        assertEquals(3, nfo.episode)
        assertEquals(7.5, nfo.rating!!, 0.001)
        assertEquals("tt1234567", nfo.imdbId)
    }

    @Test
    fun linkOnly() {
        val nfo = Nfo.parse("https://www.themoviedb.org/tv/1399-game-of-thrones")!!
        assertEquals("tv", nfo.kind)
        assertEquals("1399", nfo.tmdbId)
        assertNull(Nfo.parse("随便写的说明"))
    }

    @Test
    fun idsInFolderName() {
        assertEquals("776503" to null, Nfo.idsFromName("健听女孩 (2021) {tmdb-776503}"))
        assertEquals("123" to "tt0133093", Nfo.idsFromName("The Matrix [tmdbid=123] [imdbid-tt0133093]"))
        assertEquals(null to null, Nfo.idsFromName("健听女孩 (2021)"))
    }

    @Test
    fun parserIgnoresIdTags() {
        val p = NameParser.parse("健听女孩 (2021) {tmdb-776503}")
        assertEquals("健听女孩", p.title)
        assertEquals(2021, p.year)
    }
}
