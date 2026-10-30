package com.harshvardhan.ultrawatch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class VitalsAndTrainingTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun bloodPressureCategories() {
        assertEquals(Vitals.BpCategory.NORMAL, Vitals.bpCategory(119, 79))
        assertEquals(Vitals.BpCategory.ELEVATED, Vitals.bpCategory(125, 79))
        assertEquals(Vitals.BpCategory.STAGE1, Vitals.bpCategory(125, 82))
        assertEquals(Vitals.BpCategory.STAGE1, Vitals.bpCategory(135, 70))
        assertEquals(Vitals.BpCategory.STAGE2, Vitals.bpCategory(140, 85))
        assertEquals(Vitals.BpCategory.CRISIS, Vitals.bpCategory(185, 100))
        assertEquals(Vitals.BpCategory.CRISIS, Vitals.bpCategory(150, 121))
        assertTrue(Vitals.isPlausibleBp(120, 80, 70))
        assertFalse(Vitals.isPlausibleBp(80, 120))
        assertFalse(Vitals.isPlausibleBp(300, 80))
    }

    @Test fun spo2Labels() {
        assertEquals("Normal", Vitals.spo2Label(97))
        assertEquals("Slightly low", Vitals.spo2Label(93))
        assertTrue(Vitals.spo2Label(85).startsWith("Very low"))
    }

    @Test fun odiCountsDipsPerHour() {
        val s = ArrayList<Pair<Long, Int>>()
        for (t in 0L until 7_200_000L step 10_000L) {
            val inDip = (t / 60_000) % 10 == 5L && (t % 60_000) < 30_000
            s += t to (if (inDip) 92 else 97)
        }
        s += 50_000L to 0 // dropout sample: ignored
        val odi = Vitals.odi(s)!!
        assertEquals(6.0, odi, 0.11)
        assertEquals("Mild pattern - consider discussing with a doctor", Vitals.apneaScreen(odi))
        assertNull(Vitals.odi(s.take(50))) // under an hour
        val flat = (0L until 7_200_000L step 10_000L).map { it to 97 }
        assertEquals(0.0, Vitals.odi(flat)!!, 0.0)
    }

    @Test fun rhythmIndicator() {
        val regular = List(100) { if (it % 2 == 0) 800.0 else 810.0 }
        assertEquals(false, Vitals.rhythm(regular).irregular)
        val r = Random(1)
        val chaotic = List(100) { 500.0 + r.nextDouble() * 600.0 }
        assertEquals(true, Vitals.rhythm(chaotic).irregular)
        assertNull(Vitals.rhythm(regular.take(30)).irregular)
        assertEquals(98, Vitals.rhythm(regular.take(98) + listOf(100.0, 5_000.0)).beats) // artefacts dropped
    }

    @Test fun bodyCompositionFormulas() {
        assertEquals(24.49, Vitals.bmi(75.0, 175.0), 0.01)
        assertEquals("Healthy", Vitals.bmiLabel(24.49))
        assertEquals(21.2, Vitals.bodyFatPct(75.0, 175.0, 35, Vitals.Sex.MALE), 0.05)
        assertEquals(58.05, Vitals.leanMassKg(75.0, 175.0, Vitals.Sex.MALE), 0.06)
        assertEquals(43.1, Vitals.bodyWaterL(75.0, 175.0, 35, Vitals.Sex.MALE), 0.05)
        assertEquals(1674, Vitals.bmr(75.0, 175.0, 35, Vitals.Sex.MALE))
    }

    @Test fun gattSfloat() {
        assertEquals(120.0, GattParsers.sfloat(bytes(0x78, 0x00), 0)!!, 0.0)
        assertEquals(120.5, GattParsers.sfloat(bytes(0xB5, 0xF4), 0)!!, 1e-9)
        assertNull(GattParsers.sfloat(bytes(0xFF, 0x07), 0))
    }

    @Test fun gattHeartRate() {
        val a = GattParsers.heartRate(bytes(0x10, 72, 0x00, 0x04, 0x00, 0x03))
        assertEquals(72, a.bpm)
        assertEquals(listOf(1000.0, 750.0), a.rrMs)
        assertEquals(300, GattParsers.heartRate(bytes(0x01, 0x2C, 0x01)).bpm)
    }

    @Test fun gattBloodPressureAndPlx() {
        val bp = GattParsers.bloodPressure(bytes(0x04, 0x78, 0x00, 0x50, 0x00, 0x5D, 0x00, 0x48, 0x00))!!
        assertEquals(120.0, bp.systolic, 0.0); assertEquals(80.0, bp.diastolic, 0.0)
        assertEquals(72.0, bp.pulse!!, 0.0); assertFalse(bp.unitKpa)
        assertNull(GattParsers.bloodPressure(bytes(0x00, 0xFF, 0x07, 0x50, 0x00, 0x5D, 0x00)))
        val plx = GattParsers.plx(bytes(0x00, 0x61, 0x00, 0x48, 0x00))!!
        assertEquals(97.0, plx.spo2, 0.0); assertEquals(72.0, plx.pulse, 0.0)
    }

    @Test fun gattScale() {
        assertEquals(75.0, GattParsers.weight(bytes(0x00, 0x98, 0x3A))!!.kg, 1e-9)
        assertNull(GattParsers.weight(bytes(0x00, 0xFF, 0xFF)))
        val bc = GattParsers.bodyComposition(bytes(0x20, 0x01, 0xD7, 0x00, 0x58, 0x1B, 0x98, 0x21))
        assertEquals(21.5, bc.fatPct, 1e-9)
        assertEquals(35.0, bc.muscleKg!!, 1e-9)
        assertEquals(43.0, bc.waterKg!!, 1e-9)
        assertNull(bc.weightKg)
    }

    @Test fun trainingLoad() {
        assertEquals(100.4, Training.trimp(60.0, 150, 60, 190), 0.5)
        assertEquals(0.0, Training.trimp(0.0, 150, 60, 190), 0.0)
        val loads = List(21) { 50.0 } + List(7) { 100.0 }
        assertEquals(1.6, Training.loadRatio(loads)!!, 1e-9)
        assertEquals("Very high - injury risk", Training.loadStatus(1.6))
        assertNull(Training.loadRatio(loads.take(10)))
    }

    @Test fun energyScore() {
        assertEquals(93, Training.energyScore(85, 58, 58.0, 100.0, 100.0))
        assertEquals(39, Training.energyScore(40, 66, 58.0, 200.0, 100.0))
        assertEquals("Ready for a hard session", Training.energyAdvice(93))
        assertEquals("Rest and recover", Training.energyAdvice(30))
    }

    @Test fun geoBasics() {
        assertEquals(111_195.0, Geo.distanceM(Geo.Pt(0.0, 0.0), Geo.Pt(1.0, 0.0)), 1.0)
        assertEquals(0.0, Geo.bearing(Geo.Pt(0.0, 0.0), Geo.Pt(1.0, 0.0)), 1e-6)
        assertEquals(90.0, Geo.bearing(Geo.Pt(0.0, 0.0), Geo.Pt(0.0, 1.0)), 1e-6)
        assertEquals("N", Geo.compassPoint(350.0))
        assertEquals("NE", Geo.compassPoint(44.0))
        assertEquals("S", Geo.compassPoint(200.0))
        assertEquals("5:00", Geo.pace(1000.0, 300_000))
        assertEquals("--:--", Geo.pace(0.0, 1000))
        assertEquals("850 m", Geo.formatDistance(850.0))
        assertEquals("1.23 km", Geo.formatDistance(1234.0))
    }

    @Test fun trackBackGuidesHome() {
        val tb = TrackBack()
        assertFalse(tb.record(Geo.Pt(60.0, 25.0), 50f))          // poor accuracy rejected
        for (i in 0 until 10) assertTrue(tb.record(Geo.Pt(60.0 + i * 0.0002, 25.0), 5f))
        assertFalse(tb.record(Geo.Pt(60.0 + 9 * 0.0002 + 0.00002, 25.0), 5f)) // jitter rejected
        val g = tb.guide(Geo.Pt(60.0018, 25.0))!!
        assertEquals(180.0, g.bearingDeg, 0.1)
        assertEquals(200.0, g.distanceToStartM, 3.0)
        assertFalse(g.arrived)
        // walk back south along the trail in ~11 m steps; guidance should end in "arrived"
        var last: TrackBack.Guidance? = null
        for (k in 18 downTo 0) {
            last = tb.guide(Geo.Pt(60.0 + k * 0.0001, 25.0))
            if (k > 1) assertFalse(last!!.arrived)
        }
        assertTrue(last!!.arrived)
        assertEquals(20.0, tb.arrowRotation(10.0, 350.0), 1e-9)
    }

    @Test fun multisport() {
        val m = Multisport()
        m.start(0)
        assertEquals("Swim", m.currentName)
        assertTrue(m.next(600_000)); assertEquals("Cycle", m.currentName)
        assertTrue(m.next(4_200_000)); assertEquals("Run", m.currentName)
        assertTrue(m.next(6_000_000))
        assertFalse(m.next(7_000_000))
        assertEquals(listOf(600_000L, 3_600_000L, 1_800_000L), m.legDurations(9_999_999))
        assertEquals(6_000_000L, m.total(9_999_999))
    }

    @Test fun environment() {
        assertEquals(2.0, Environment.depthM(1013.25f + 195.8f, 1013.25f), 0.05)
        assertEquals(0.0, Environment.depthM(1000f, 1013f), 0.0)
        assertEquals(0.0, Environment.altitudeM(1013.25f), 0.0)
        assertEquals(1000.0, Environment.altitudeM(898.75f), 5.0)
        assertTrue(Environment.isNight(22)); assertTrue(Environment.isNight(5)); assertFalse(Environment.isNight(12))
        assertTrue(Environment.isNight(10, 9, 17))
    }
}
