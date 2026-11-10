package com.aisub.player.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeSetTest {

    @Test
    fun mergesOverlappingAndAdjacentRanges() {
        val r = RangeSet()
        r.add(0, 1000)
        r.add(2000, 3000)
        r.add(1000, 2000)
        assertEquals(listOf(0L to 3000L), r.snapshot())
        r.add(5000, 6000)
        r.add(2500, 5500)
        assertEquals(listOf(0L to 6000L), r.snapshot())
    }

    @Test
    fun nextGapStartsAtCursorAndStopsAtNextRange() {
        val r = RangeSet()
        r.add(1000, 2000)
        r.add(5000, 6000)
        assertEquals(0L to 1000L, r.nextGap(0, 10_000))
        assertEquals(2000L to 5000L, r.nextGap(1500, 10_000))
        assertEquals(6000L to 10_000L, r.nextGap(5500, 10_000))
        assertEquals(2300L to 4000L, r.nextGap(2300, 4000))
        assertNull(r.nextGap(6000, 6000))
    }

    @Test
    fun fullWhenOneRangeCoversEverything() {
        val r = RangeSet()
        r.add(0, 500)
        assertFalse(r.isFull(1000))
        r.add(500, 1000)
        assertTrue(r.isFull(1000))
        assertEquals(1000L, r.coveredMs(1000))
        assertEquals(400L, r.coveredMs(400))
    }

    @Test
    fun containsIsLeftClosed() {
        val r = RangeSet()
        r.add(100, 200)
        assertTrue(r.contains(100))
        assertTrue(r.contains(199))
        assertFalse(r.contains(200))
        assertFalse(r.contains(99))
    }

    @Test
    fun loadRoundTrips() {
        val r = RangeSet()
        r.load(listOf(10L to 20L, 15L to 30L, 50L to 60L))
        assertEquals(listOf(10L to 30L, 50L to 60L), r.snapshot())
    }
}
