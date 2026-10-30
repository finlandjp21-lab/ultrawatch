package com.harshvardhan.ultrawatch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FaceAndMediaTest {
    @Test fun faceConfigRoundTripsEveryPreset() {
        for ((_, cfg) in FaceConfig.PRESETS) assertEquals(cfg, FaceConfig.decode(cfg.encode()))
    }

    @Test fun faceConfigToleratesGarbage() {
        assertEquals(FaceConfig(), FaceConfig.decode(null))
        assertEquals(FaceConfig(), FaceConfig.decode("total nonsense"))
        val c = FaceConfig.decode("style=TRADER;accent=zz;ticks=7;sec=maybe;slots=LEFT:STEPS,BAD:STEPS,TOP:NOPE")
        assertEquals(FaceStyle.TRADER, c.style)
        assertEquals(FaceConfig().accent, c.accent)
        assertEquals(12, c.tickDensity)
        assertTrue(c.showSeconds)
        assertEquals(mapOf(SlotPosition.LEFT to SlotData.STEPS), c.slots)
        assertEquals(emptyMap<SlotPosition, SlotData>(), FaceConfig.decode("slots=").slots)
    }

    @Test fun handAngles() {
        assertEquals(0.0, WatchGeometry.hourAngle(12, 0), 1e-9)
        assertEquals(90.0, WatchGeometry.hourAngle(3, 0), 1e-9)
        assertEquals(105.0, WatchGeometry.hourAngle(15, 30), 1e-9)
        assertEquals(183.0, WatchGeometry.minuteAngle(30, 30), 1e-9)
        assertEquals(90.3, WatchGeometry.secondAngle(15, 50), 1e-9)
        assertEquals(90.0, WatchGeometry.secondAngle(15, 50, smooth = false), 1e-9)
    }

    @Test fun pointsOnDial() {
        val (x12, y12) = WatchGeometry.pointAt(100.0, 100.0, 50.0, 0.0)
        assertEquals(100.0, x12, 1e-9); assertEquals(50.0, y12, 1e-9)
        val (x3, y3) = WatchGeometry.pointAt(100.0, 100.0, 50.0, 90.0)
        assertEquals(150.0, x3, 1e-9); assertEquals(100.0, y3, 1e-9)
    }

    @Test fun slotsFitInsideRoundScreen() {
        for (p in SlotPosition.values()) {
            val b = WatchGeometry.slotBounds(p)
            assertTrue(b[0] < b[2] && b[1] < b[3])
            // every corner within the inscribed circle (radius 0.5 around 0.5,0.5)
            for (x in listOf(b[0], b[2])) for (y in listOf(b[1], b[3])) {
                val dx = x - 0.5; val dy = y - 0.5
                assertTrue("$p corner out of circle", dx * dx + dy * dy <= 0.25)
            }
        }
    }

    @Test fun progressAndTicks() {
        assertEquals(180f, WatchGeometry.progressSweep(5000.0, 10000.0), 0f)
        assertEquals(360f, WatchGeometry.progressSweep(15000.0, 10000.0), 0f)
        assertEquals(0f, WatchGeometry.progressSweep(5.0, 0.0), 0f)
        assertEquals(60, WatchGeometry.ticks(60).size)
        assertEquals(12, WatchGeometry.ticks(60).count { it.second })
        assertEquals(12, WatchGeometry.ticks(13).size)
    }

    private fun items(n: Int) = (1..n).map { MediaItemInfo("$it", "Song $it", "Artist ${it % 2}", "uri://$it") }

    @Test fun queueNextPrevAndRepeat() {
        val q = MediaQueue(items(3))
        assertEquals("1", q.current!!.id)
        assertEquals("2", q.next()!!.id)
        assertEquals("3", q.next()!!.id)
        assertEquals("3", q.next()!!.id)            // manual next on last, repeat off: stay
        assertNull(q.next(auto = true))              // track ended, repeat off: stop
        q.cycleRepeat()                              // ALL
        assertEquals("1", q.next(auto = true)!!.id)  // wraps
        q.cycleRepeat()                              // ONE
        assertEquals("1", q.next(auto = true)!!.id)  // repeats same
        assertEquals("2", q.next()!!.id)             // manual skip still moves
        val (p, restart) = q.previous(5_000)
        assertEquals("2", p!!.id); assertTrue(restart)
        val (p2, restart2) = q.previous(1_000)
        assertEquals("1", p2!!.id); assertFalse(restart2)
        assertEquals(RepeatMode.OFF, q.cycleRepeat())
    }

    @Test fun shuffleKeepsCurrentAndCoversAll() {
        val q = MediaQueue(items(10), Random(3))
        q.next(); q.next() // on "3"
        q.setShuffle(true)
        assertEquals("3", q.current!!.id)
        val seen = mutableSetOf(q.current!!.id)
        seen += q.upNext.map { it.id }
        assertEquals((1..10).map { "$it" }.toSet(), seen)
        q.setShuffle(false)
        assertEquals("3", q.current!!.id)
        assertEquals(listOf("4", "5", "6", "7", "8", "9", "10"), q.upNext.map { it.id })
    }

    @Test fun emptyQueueIsSafe() {
        val q = MediaQueue(emptyList())
        assertNull(q.current); assertNull(q.next()); assertNull(q.previous(0).first)
        q.setShuffle(true)
        assertFalse(q.jumpTo("x"))
    }

    @Test fun timeFormatAndSearch() {
        assertEquals("0:00", MediaQueue.formatTime(-5))
        assertEquals("3:07", MediaQueue.formatTime(187_000))
        assertEquals("1:01:01", MediaQueue.formatTime(3_661_000))
        val all = items(5)
        assertEquals(listOf("1", "3", "5"), MediaQueue.search(all, "artist 1").map { it.id })
        assertEquals(listOf("2"), MediaQueue.search(all, "SONG 2").map { it.id })
        assertEquals(5, MediaQueue.search(all, " ").size)
    }
}
