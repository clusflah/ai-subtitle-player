package com.aisub.player.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieJarTest {

    @Test
    fun parseNetscapeFile() {
        val text = """
            # Netscape HTTP Cookie File
            .bilibili.com	TRUE	/	FALSE	1800000000	SESSDATA	abc%2C123
            #HttpOnly_.youtube.com	TRUE	/	TRUE	1800000000	LOGIN_INFO	x	y
            bad line
        """.trimIndent()
        val entries = CookieJar.parse(text)
        assertEquals(2, entries.size)
        assertEquals("SESSDATA", entries[0].name)
        assertEquals("abc%2C123", entries[0].value)
        assertEquals(".youtube.com", entries[1].domain)
        assertTrue(entries[1].secure)
        assertEquals("x\ty", entries[1].value)
    }

    @Test
    fun mergeOverridesSameKey() {
        val old = CookieJar.parse(".a.com\tTRUE\t/\tFALSE\t0\tk\t1\n.a.com\tTRUE\t/\tFALSE\t0\tj\t2")
        val new = CookieJar.parse(".a.com\tTRUE\t/\tFALSE\t0\tk\t3")
        val merged = CookieJar.merge(old, new)
        assertEquals(listOf("k" to "3", "j" to "2"), merged.map { it.name to it.value })
    }

    @Test
    fun registrableDomain() {
        assertEquals("bilibili.com", CookieJar.registrable("www.bilibili.com"))
        assertEquals("douyin.com", CookieJar.registrable(".douyin.com"))
    }
}
