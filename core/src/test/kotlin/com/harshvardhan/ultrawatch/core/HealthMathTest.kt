package com.harshvardhan.ultrawatch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthMathTest {
    @Test fun maxHeartRateTanaka() {
        assertEquals(187, HealthMath.maxHeartRate(30))
        assertEquals(180, HealthMath.maxHeartRate(40))
    }

    @Test fun zonesAcrossRange() {
        // age 40 -> max 180
        assertEquals(0, HealthMath.heartRateZone(80, 40))   // 44 %
        assertEquals(1, HealthMath.heartRateZone(95, 40))   // 53 %
        assertEquals(2, HealthMath.heartRateZone(115, 40))  // 64 %
        assertEquals(3, HealthMath.heartRateZone(135, 40))  // 75 %
        assertEquals(4, HealthMath.heartRateZone(155, 40))  // 86 %
        assertEquals(5, HealthMath.heartRateZone(170, 40))  // 94 %
        assertEquals(0, HealthMath.heartRateZone(0, 40))
    }

    @Test fun caloriesNeverNegativeAndRiseWithHr() {
        assertEquals(0.0, HealthMath.caloriesPerMinute(40, 30, 70.0, HealthMath.Sex.MALE), 0.0)
        val easy = HealthMath.caloriesPerMinute(110, 35, 75.0, HealthMath.Sex.MALE)
        val hard = HealthMath.caloriesPerMinute(160, 35, 75.0, HealthMath.Sex.MALE)
        assertTrue(hard > easy)
        // Keytel male 150 bpm, 35 y, 75 kg -> about 14.7 kcal/min
        assertEquals(14.7, HealthMath.caloriesPerMinute(150, 35, 75.0, HealthMath.Sex.MALE), 0.1)
    }

    @Test fun rmssdKnownValue() {
        // diffs: 10, -20, 10 -> squares 100, 400, 100 -> mean 200 -> sqrt 14.142
        assertEquals(14.142, HealthMath.rmssd(listOf(800.0, 810.0, 790.0, 800.0)), 0.001)
        assertEquals(0.0, HealthMath.rmssd(listOf(800.0)), 0.0)
    }

    @Test fun stressScoreBoundsAndNullWhenSparse() {
        assertNull(HealthMath.stressScore(listOf(800.0, 810.0)))
        val calm = listOf(800.0, 950.0, 790.0, 960.0, 800.0, 940.0) // big variability
        val tense = listOf(600.0, 602.0, 601.0, 603.0, 600.0, 602.0) // tiny variability
        assertEquals(0, HealthMath.stressScore(calm))
        assertEquals(100, HealthMath.stressScore(tense))
        assertEquals("Relaxed", HealthMath.stressLabel(10))
        assertEquals("High", HealthMath.stressLabel(90))
    }

    @Test fun bpmToRrFiltersJunk() {
        assertEquals(listOf(1000.0, 500.0), HealthMath.bpmToRr(listOf(60, 0, 120, 300)))
    }

    @Test fun stepsToDistance() {
        // 175 cm -> stride 0.72625 m -> 10,000 steps = 7.2625 km
        assertEquals(7.2625, HealthMath.stepsToKm(10_000, 175.0), 0.0001)
    }

    @Test fun vo2max() {
        // age 30 -> max 187; resting 60 -> 15.3*187/60 = 47.685 -> 47.7
        assertEquals(47.7, HealthMath.vo2MaxEstimate(60, 30)!!, 0.0)
        assertNull(HealthMath.vo2MaxEstimate(10, 30))
    }

    @Test fun sleepScoreIdealAndPoor() {
        assertEquals(100, HealthMath.sleepScore(480, 80, 4))   // 8 h, 16.7 % deep, 0.5/h moves
        val short = HealthMath.sleepScore(240, 20, 40)          // 4 h, 8 % deep, 10/h moves
        assertTrue("short=$short", short in 30..50)
        assertEquals(0, HealthMath.sleepScore(0, 0, 0))
    }

    @Test fun deepMinutesNeedStillNeighbours() {
        assertEquals(3, HealthMath.deepMinutesFromMovement(listOf(0, 0, 0, 3, 0, 0)))
    }

    @Test fun movementDetection() {
        assertTrue(!HealthMath.isMovement(0f, 0f, 9.81f))
        assertTrue(HealthMath.isMovement(3f, 2f, 12f))
    }

    @Test fun breathPhaseCycle() {
        // 6 breaths/min -> 10 s cycle: 0 at start, 1 at 5 s, 0.5 at 2.5 s and 7.5 s
        assertEquals(0.0, HealthMath.breathPhase(0), 1e-9)
        assertEquals(0.5, HealthMath.breathPhase(2_500), 1e-9)
        assertEquals(1.0, HealthMath.breathPhase(5_000), 1e-9)
        assertEquals(0.5, HealthMath.breathPhase(7_500), 1e-9)
    }
}
