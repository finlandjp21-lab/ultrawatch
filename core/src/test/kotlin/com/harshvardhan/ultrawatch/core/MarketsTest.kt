package com.harshvardhan.ultrawatch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketsTest {
    @Test fun indianGrouping() {
        assertEquals("1,23,45,678.90", MarketFormat.inr(12345678.9))
        assertEquals("24,350.00", MarketFormat.inr(24350.0))
        assertEquals("999.50", MarketFormat.inr(999.5))
        assertEquals("-1,00,000", MarketFormat.inr(-100000.0, 0))
        assertEquals("+1.25%", MarketFormat.pct(1.2499))
        assertEquals("-0.40%", MarketFormat.pct(-0.4))
    }

    @Test fun quoteMaths() {
        val q = Quote("NIFTY 50", 24600.0, 24000.0)
        assertEquals(600.0, q.change, 1e-9)
        assertEquals(2.5, q.changePct, 1e-9)
        assertTrue(q.isUp)
        assertEquals(0.0, Quote("X", 10.0, 0.0).changePct, 0.0)
    }

    @Test fun parseArrayAndWrappedShapesWithAliases() {
        val a = QuoteParser.parse("""[{"symbol":"NIFTY 50","ltp":24500.5,"prevClose":24400},{"s":"TCS","p":"4100.2","pc":4000}]""")
        assertEquals(2, a.size)
        assertEquals(Quote("NIFTY 50", 24500.5, 24400.0), a[0])
        assertEquals(Quote("TCS", 4100.2, 4000.0), a[1])
        val b = QuoteParser.parse("""{"status":"ok","data":[{"tradingsymbol":"INFY","last_price":1880,"close":1850}]}""")
        assertEquals(listOf(Quote("INFY", 1880.0, 1850.0)), b)
    }

    @Test fun parseSkipsBadItems() {
        val q = QuoteParser.parse("""[{"symbol":"A"},{"ltp":5},{"symbol":"B","ltp":-1},{"symbol":"C","ltp":7}, 3, null]""")
        assertEquals(listOf(Quote("C", 7.0, 7.0)), q)
    }

    @Test(expected = IllegalArgumentException::class) fun malformedJsonThrows() {
        QuoteParser.parse("""[{"symbol":"A","ltp":}]""")
    }

    @Test fun jsonEscapesAndNesting() {
        val v = MiniJson.parse("""{"a":"x\"yA\n","b":[true,false,null,-1.5e2],"c":{}}""") as Map<*, *>
        assertEquals("x\"yA\n", v["a"])
        assertEquals(listOf(true, false, null, -150.0), v["b"])
        assertEquals(emptyMap<String, Any?>(), v["c"])
    }

    @Test fun aboveAlertFiresOnceOnCrossing() {
        val e = AlertEngine(listOf(PriceAlert(1, "NIFTY 50", AlertCondition.ABOVE, 24500.0, oneShot = false)))
        assertEquals(0, e.evaluate(listOf(Quote("NIFTY 50", 24400.0, 24300.0))).size)
        assertEquals(1, e.evaluate(listOf(Quote("NIFTY 50", 24510.0, 24300.0))).size)
        assertEquals(0, e.evaluate(listOf(Quote("NIFTY 50", 24600.0, 24300.0))).size) // still above, no spam
        assertEquals(0, e.evaluate(listOf(Quote("NIFTY 50", 24400.0, 24300.0))).size) // back below
        assertEquals(1, e.evaluate(listOf(Quote("NIFTY 50", 24550.0, 24300.0))).size) // crosses again
    }

    @Test fun belowAlertOneShotIsRemoved() {
        val e = AlertEngine(listOf(PriceAlert(2, "tcs", AlertCondition.BELOW, 4000.0)))
        e.evaluate(listOf(Quote("TCS", 4050.0, 4100.0)))
        val hits = e.evaluate(listOf(Quote("TCS", 3990.0, 4100.0)))
        assertEquals(1, hits.size)
        assertTrue(hits[0].message.contains("fell below 4,000.00"))
        assertTrue(e.alerts.isEmpty())
    }

    @Test fun firstObservationBeyondLevelFires() {
        val e = AlertEngine(listOf(PriceAlert(3, "INFY", AlertCondition.ABOVE, 1800.0)))
        assertEquals(1, e.evaluate(listOf(Quote("INFY", 1880.0, 1850.0))).size)
    }

    @Test fun movePctFiresOnlyOnce() {
        val e = AlertEngine(listOf(PriceAlert(4, "SENSEX", AlertCondition.MOVE_PCT, 1.0, oneShot = false)))
        assertEquals(0, e.evaluate(listOf(Quote("SENSEX", 80500.0, 80000.0))).size) // 0.625 %
        assertEquals(1, e.evaluate(listOf(Quote("SENSEX", 79100.0, 80000.0))).size) // -1.125 %
        assertEquals(0, e.evaluate(listOf(Quote("SENSEX", 78000.0, 80000.0))).size)
    }

    @Test fun alertsRoundTrip() {
        val e = AlertEngine(listOf(
            PriceAlert(1, "NIFTY 50", AlertCondition.ABOVE, 24500.0),
            PriceAlert(2, "TCS", AlertCondition.MOVE_PCT, 2.0, false),
        ))
        val back = AlertEngine.decode(e.encode() + "\ngarbage line\n9|X|NOPE|1|true")
        assertEquals(e.alerts, back.alerts)
    }

    @Test fun demoFeedIsDeterministicAndBounded() {
        val a = DemoMarketFeed(7).tick()
        val b = DemoMarketFeed(7).tick()
        assertEquals(a, b)
        assertEquals(7, a.size)
        val feed = DemoMarketFeed(1)
        repeat(100) { feed.tick() }
        val last = feed.tick()
        assertTrue(last.all { kotlin.math.abs(it.changePct) < 15.0 })
    }
}
