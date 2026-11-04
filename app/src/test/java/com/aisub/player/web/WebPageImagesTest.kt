package com.aisub.player.web

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebPageImagesTest {
    @After
    fun clear() {
        WebPageImages.exporter = null
    }

    @Test
    fun urlKeepsIndexAndFrame() {
        val frame = "https://m.example.com/18427/401.html#p=2"
        val url = WebPageImages.url(frame, 12)
        assertTrue(WebPageImages.isPage(url))
        val asked = ArrayList<Pair<String, Int>>()
        WebPageImages.exporter = { f, i, id ->
            asked.add(f to i)
            // 网页在别的线程上送回来
            Thread { WebPageImages.complete(id, "/9j/AA==") }.start()
            true
        }
        val bytes = runBlocking { WebPageImages.bytes(url) }
        assertEquals(listOf(frame to 12), asked)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0), bytes)
    }

    @Test
    fun emptyResultIsAnError() {
        WebPageImages.exporter = { _, _, id -> WebPageImages.complete(id, ""); true }
        try {
            runBlocking { WebPageImages.bytes(WebPageImages.url("https://a.com/", 0)) }
            fail()
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("还没加载"))
        }
    }

    @Test
    fun noPageIsAnError() {
        try {
            runBlocking { WebPageImages.bytes(WebPageImages.url("https://a.com/", 0)) }
            fail()
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("网页已经关了"))
        }
        WebPageImages.exporter = { _, _, _ -> false }
        try {
            runBlocking { WebPageImages.bytes(WebPageImages.url("https://a.com/", 0)) }
            fail()
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("网页已经关了"))
        }
    }
}
