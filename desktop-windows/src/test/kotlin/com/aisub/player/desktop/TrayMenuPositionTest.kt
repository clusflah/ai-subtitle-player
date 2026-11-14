package com.aisub.player.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Rectangle

class TrayMenuPositionTest {

    @Test
    fun bottomRightTrayPlacesMenuAboveAndClampsRightEdge() {
        val workArea = Rectangle(0, 0, 1920, 1032)
        val pos = computeTrayMenuPosition(
            anchorX = 1905,
            anchorY = 1010,
            menuWidth = 216,
            menuHeight = 154,
            workArea = workArea,
        )
        assertTrue("Menu right edge must stay within monitor work area", pos.x <= 1920 - 216 - 8)
        assertEquals(1010 - 154 - 6, pos.y)
    }

    @Test
    fun topTaskbarFlipsMenuBelowCursor() {
        val workArea = Rectangle(0, 48, 1920, 1032)
        val pos = computeTrayMenuPosition(
            anchorX = 1700,
            anchorY = 52,
            menuWidth = 216,
            menuHeight = 154,
            workArea = workArea,
        )
        assertEquals(52 + 6, pos.y)
        assertEquals(1700 - 108, pos.x)
    }

    @Test
    fun secondaryMonitorNegativeCoordinatesClampWithinMonitor() {
        val workArea = Rectangle(-1920, 0, 1920, 1040)
        val pos = computeTrayMenuPosition(
            anchorX = -1910,
            anchorY = 1020,
            menuWidth = 216,
            menuHeight = 154,
            workArea = workArea,
        )
        assertTrue("Left edge must stay inside secondary monitor: x=${pos.x}", pos.x >= -1920 + 8)
        assertTrue("Right edge must stay inside secondary monitor: x=${pos.x}", pos.x + 216 <= 0 - 8)
        assertEquals(1020 - 154 - 6, pos.y)
    }
}
