package org.agentos.sample.calendar

import org.agentos.sample.calendar.ui.WeekLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeekLayoutTest {
    @Test fun emptyAndSingle() {
        assertTrue(WeekLayout.lanes(emptyList()).isEmpty())
        assertEquals(listOf(WeekLayout.Lane(0, 1)), WeekLayout.lanes(listOf(60 to 120)))
    }

    @Test fun nonOverlappingEventsEachGetTheFullWidth() {
        val lanes = WeekLayout.lanes(listOf(60 to 120, 120 to 180, 300 to 360))
        assertTrue(lanes.all { it == WeekLayout.Lane(0, 1) })
    }

    @Test fun overlappingEventsShareColumnsInInputOrder() {
        // A 9:00-10:30, B 9:30-10:00, C 10:00-11:00（与 A 重叠，与 B 首尾相接）
        val lanes = WeekLayout.lanes(listOf(540 to 630, 570 to 600, 600 to 660))
        assertEquals(WeekLayout.Lane(0, 2), lanes[0])
        assertEquals(WeekLayout.Lane(1, 2), lanes[1])
        assertEquals("C reuses B's lane once B is over", WeekLayout.Lane(1, 2), lanes[2])
    }

    @Test fun separateClustersHaveIndependentWidths() {
        val lanes = WeekLayout.lanes(listOf(60 to 120, 90 to 150, 100 to 130, 600 to 660))
        assertEquals(3, lanes[0].count)
        assertEquals(3, lanes[1].count)
        assertEquals(3, lanes[2].count)
        assertEquals(setOf(0, 1, 2), lanes.take(3).map { it.index }.toSet())
        assertEquals(WeekLayout.Lane(0, 1), lanes[3])
    }

    @Test fun unsortedInputKeepsPositions() {
        val lanes = WeekLayout.lanes(listOf(300 to 400, 60 to 200, 100 to 150))
        assertEquals(WeekLayout.Lane(0, 1), lanes[0])
        assertEquals(WeekLayout.Lane(0, 2), lanes[1])
        assertEquals(WeekLayout.Lane(1, 2), lanes[2])
    }
}
