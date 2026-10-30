package com.harshvardhan.ultrawatch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GnssTest {
    @Test fun bands() {
        assertEquals("L1", Gnss.band(1575.42e6f))
        assertEquals("L5", Gnss.band(1176.45e6f))
        assertEquals("G1", Gnss.band(1602.0e6f))
        assertEquals("B1", Gnss.band(1561.098e6f))
        assertNull(Gnss.band(0f)); assertNull(Gnss.band(null)); assertNull(Gnss.band(900e6f))
    }

    @Test fun summaryDetectsDualFrequency() {
        val sats = listOf(
            Gnss.Sat(1, 5, 40f, true, 1575.42e6f),
            Gnss.Sat(1, 5, 35f, true, 1176.45e6f),
            Gnss.Sat(6, 11, 30f, false, 1575.42e6f),
            Gnss.Sat(3, 2, 20f, true, null),
            Gnss.Sat(7, 3, 25f, false, 1176.45e6f),
        )
        val s = Gnss.summarize(sats)
        assertEquals(5, s.inView); assertEquals(3, s.used)
        assertEquals(2 to 2, s.byConstellation["GPS"])
        assertEquals(0 to 1, s.byConstellation["Galileo"])
        assertEquals(0 to 1, s.byConstellation["NavIC"])
        assertTrue(s.dualFrequency)
        assertEquals(31.7, s.avgCn0Used, 0.05)
        assertFalse(Gnss.summarize(sats.filter { it.carrierHz != 1176.45e6f }).dualFrequency)
        assertEquals(0.0, Gnss.summarize(emptyList()).avgCn0Used, 0.0)
    }

    @Test fun fixQuality() {
        assertEquals("No fix", Gnss.fixQuality(null, 10))
        assertEquals("No fix", Gnss.fixQuality(3f, 2))
        assertEquals("Excellent", Gnss.fixQuality(4f, 9))
        assertEquals("Good", Gnss.fixQuality(4f, 5))
        assertEquals("Fair", Gnss.fixQuality(20f, 6))
        assertEquals("Poor", Gnss.fixQuality(60f, 6))
    }

    @Test fun coordinateFormats() {
        assertEquals("60.29245, 25.04056", Gnss.decimal(60.29245, 25.04056))
        assertEquals("60°17'32.8\"N", Gnss.dms(60.29245, true))
        assertEquals("25°02'26.0\"E", Gnss.dms(25.04056, false))
        assertEquals("23°20'40.0\"S", Gnss.dms(-23.344444, true))
        assertEquals("10°00'00.0\"W", Gnss.dms(-9.9999999, false)) // rounding carries over cleanly
        assertEquals(36.0, Gnss.speedKmh(10f), 0.0)
    }

    @Test fun waypointsRoundTrip() {
        val w = listOf(Gnss.Waypoint("Car", 60.1, 24.9, 5), Gnss.Waypoint("Hotel|A", 28.6, 77.2, 6))
        val back = Gnss.decodeWaypoints(Gnss.encodeWaypoints(w) + "\nbad")
        assertEquals(2, back.size)
        assertEquals("Hotel A", back[1].name)
        assertEquals(w[0], back[0])
    }
}
