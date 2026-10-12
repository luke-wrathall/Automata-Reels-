package com.automata.reels

import com.automata.reels.ui.RowClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RowClockTest {
    private fun rowsAfterOneSecond(fps: Int, rowsPerSecond: Float): Int {
        val clock = RowClock()
        return (0 until fps).sumOf { clock.advance(rowsPerSecond / fps) }
    }

    @Test
    fun speedDoesNotDependOnFrameRate() {
        for (fps in listOf(30, 60, 90, 120)) {
            val rows = rowsAfterOneSecond(fps, 45f)
            assertTrue("$fps fps wrote $rows rows", rows in 44..45)
        }
    }

    @Test
    fun longStallsAreCapped() {
        val clock = RowClock(maxRowsPerFrame = 12)
        assertEquals(12, clock.advance(100f))
        assertTrue(clock.phase in 0f..1f)
    }
}
