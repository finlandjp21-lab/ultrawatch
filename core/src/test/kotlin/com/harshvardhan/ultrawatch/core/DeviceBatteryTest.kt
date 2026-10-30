package com.harshvardhan.ultrawatch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceBatteryTest {
    @Test fun payloadRoundTrip() {
        assertEquals(Triple(64, true, 1_700_000_000_000L), BatteryProtocol.decode(BatteryProtocol.encode(64, true, 1_700_000_000_000L)))
        assertNull(BatteryProtocol.decode("150|true|1".toByteArray()))
        assertNull(BatteryProtocol.decode("x".toByteArray()))
        assertNull(BatteryProtocol.decode(null))
        // tolerant of extra fields from a newer phone app
        assertEquals(Triple(5, false, 9L), BatteryProtocol.decode("5|false|9|extra".toByteArray()))
    }

    @Test fun bleLevel() {
        assertEquals(87, BatteryProtocol.parseBleLevel(byteArrayOf(87)))
        assertEquals(100, BatteryProtocol.parseBleLevel(byteArrayOf(100)))
        assertNull(BatteryProtocol.parseBleLevel(byteArrayOf(0xFF.toByte())))
        assertNull(BatteryProtocol.parseBleLevel(ByteArray(0)))
    }

    @Test fun kinds() {
        assertEquals(DeviceBattery.Kind.EARBUDS, BatteryProtocol.kindFor("Galaxy Buds2 Pro", null))
        assertEquals(DeviceBattery.Kind.HEADPHONES, BatteryProtocol.kindFor("WH-1000XM5", null))
        assertEquals(DeviceBattery.Kind.SENSOR, BatteryProtocol.kindFor("Polar H10 1234", null))
        assertEquals(DeviceBattery.Kind.HEADPHONES, BatteryProtocol.kindFor("Unknown", 0x0400))
        assertEquals(DeviceBattery.Kind.OTHER, BatteryProtocol.kindFor(null, null))
    }

    @Test fun sortAndStale() {
        val list = listOf(
            DeviceBattery("a", "Buds", DeviceBattery.Kind.EARBUDS, 80),
            DeviceBattery("phone", "Phone", DeviceBattery.Kind.PHONE, 50, updatedAt = 0),
            DeviceBattery("b", "Strap", DeviceBattery.Kind.SENSOR, 15),
            DeviceBattery("watch", "Watch", DeviceBattery.Kind.WATCH, 70),
            DeviceBattery("c", "Speaker", DeviceBattery.Kind.OTHER, null),
        )
        assertEquals(listOf("watch", "phone", "b", "a", "c"), BatteryProtocol.sortForDisplay(list).map { it.id })
        assertTrue(list[1].isStale(16 * 60_000))
        assertFalse(list[1].isStale(10 * 60_000))
        assertFalse(list[0].isStale(Long.MAX_VALUE))
    }

    @Test fun lowAlertOncePerDischarge() {
        val a = BatteryProtocol.LowAlert(20)
        fun d(p: Int, c: Boolean = false) = DeviceBattery("x", "X", DeviceBattery.Kind.EARBUDS, p, c)
        assertFalse(a.check(d(25)))
        assertTrue(a.check(d(20)))
        assertFalse(a.check(d(15)))
        assertFalse(a.check(d(25)))       // small bounce doesn't re-arm
        assertFalse(a.check(d(18)))
        assertFalse(a.check(d(40, true))) // charging re-arms
        assertTrue(a.check(d(19)))
    }
}
