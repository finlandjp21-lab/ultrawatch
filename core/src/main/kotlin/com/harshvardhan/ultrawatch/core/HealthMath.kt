package com.harshvardhan.ultrawatch.core

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Pure health calculations. No Android imports so it can be unit tested anywhere. */
object HealthMath {

    enum class Sex { MALE, FEMALE }

    /** Tanaka formula: 208 - 0.7 x age. */
    fun maxHeartRate(age: Int): Int {
        require(age in 5..120) { "age out of range: $age" }
        return (208 - 0.7 * age).roundToInt()
    }

    /**
     * Heart-rate zone 0..5 from percentage of max HR.
     * 0 = below 50 %, 1 = 50-60 %, 2 = 60-70 %, 3 = 70-80 %, 4 = 80-90 %, 5 = 90 %+.
     */
    fun heartRateZone(bpm: Int, age: Int): Int {
        if (bpm <= 0) return 0
        val pct = bpm.toDouble() / maxHeartRate(age)
        return when {
            pct < 0.50 -> 0
            pct < 0.60 -> 1
            pct < 0.70 -> 2
            pct < 0.80 -> 3
            pct < 0.90 -> 4
            else -> 5
        }
    }

    fun zoneName(zone: Int): String = when (zone) {
        0 -> "Rest"
        1 -> "Warm up"
        2 -> "Fat burn"
        3 -> "Cardio"
        4 -> "Hard"
        5 -> "Peak"
        else -> "-"
    }

    /** Keytel et al. (2005) energy expenditure, kcal per minute. Never negative. */
    fun caloriesPerMinute(bpm: Int, age: Int, weightKg: Double, sex: Sex): Double {
        if (bpm <= 0) return 0.0
        val kj = when (sex) {
            Sex.MALE -> -55.0969 + 0.6309 * bpm + 0.1988 * weightKg + 0.2017 * age
            Sex.FEMALE -> -20.4022 + 0.4472 * bpm - 0.1263 * weightKg + 0.074 * age
        }
        return (kj / 4.184).coerceAtLeast(0.0)
    }

    /** Root mean square of successive differences of RR intervals (ms). */
    fun rmssd(rrIntervalsMs: List<Double>): Double {
        if (rrIntervalsMs.size < 2) return 0.0
        var sum = 0.0
        for (i in 1 until rrIntervalsMs.size) {
            val d = rrIntervalsMs[i] - rrIntervalsMs[i - 1]
            sum += d * d
        }
        return sqrt(sum / (rrIntervalsMs.size - 1))
    }

    /** Converts a stream of bpm samples into approximate RR intervals (ms). */
    fun bpmToRr(bpmSamples: List<Int>): List<Double> =
        bpmSamples.filter { it in 25..240 }.map { 60_000.0 / it }

    /**
     * Stress estimate 0..100 from RMSSD. 100+ ms RMSSD = relaxed (0), 15 ms or less = very stressed (100).
     * Returns null when there is not enough data.
     */
    fun stressScore(rrIntervalsMs: List<Double>): Int? {
        if (rrIntervalsMs.size < 5) return null
        val r = rmssd(rrIntervalsMs)
        val lo = 15.0
        val hi = 100.0
        val score = (1.0 - (r - lo) / (hi - lo)) * 100.0
        return score.coerceIn(0.0, 100.0).roundToInt()
    }

    fun stressLabel(score: Int?): String = when {
        score == null -> "Measuring"
        score < 25 -> "Relaxed"
        score < 50 -> "Normal"
        score < 75 -> "Elevated"
        else -> "High"
    }

    /** Average stride length estimate from height. */
    fun strideMeters(heightCm: Double): Double = heightCm * 0.415 / 100.0

    fun stepsToKm(steps: Long, heightCm: Double): Double = steps * strideMeters(heightCm) / 1000.0

    /** Uth-Sorensen VO2max estimate: 15.3 x HRmax / HRrest. */
    fun vo2MaxEstimate(restingHr: Int, age: Int): Double? {
        if (restingHr !in 30..120) return null
        val v = 15.3 * maxHeartRate(age) / restingHr
        return (v * 10).roundToInt() / 10.0
    }

    /**
     * Sleep score 0..100.
     * Duration (50 pts, best at 7-9 h), deep share (20 pts, best 13-23 %),
     * restlessness (30 pts, movement events per hour; 0-2 is ideal).
     */
    fun sleepScore(totalMinutes: Int, deepMinutes: Int, movementEvents: Int): Int {
        if (totalMinutes <= 0) return 0
        val hours = totalMinutes / 60.0
        val duration = when {
            hours in 7.0..9.0 -> 50.0
            hours < 7.0 -> 50.0 * (hours / 7.0)
            else -> (50.0 - (hours - 9.0) * 10.0).coerceAtLeast(30.0)
        }
        val deepShare = deepMinutes.toDouble() / totalMinutes
        val deep = when {
            deepShare in 0.13..0.23 -> 20.0
            deepShare < 0.13 -> 20.0 * (deepShare / 0.13)
            else -> (20.0 - (deepShare - 0.23) * 50.0).coerceAtLeast(10.0)
        }
        val perHour = movementEvents / hours
        val calm = when {
            perHour <= 2.0 -> 30.0
            else -> (30.0 - (perHour - 2.0) * 3.0).coerceAtLeast(0.0)
        }
        return (duration + deep + calm).coerceIn(0.0, 100.0).roundToInt()
    }

    /**
     * Rough deep-sleep minutes from per-minute movement counts:
     * a minute is "deep" if it and its two neighbours are still.
     */
    fun deepMinutesFromMovement(perMinuteMovement: List<Int>, stillThreshold: Int = 1): Int {
        var deep = 0
        for (i in perMinuteMovement.indices) {
            val a = perMinuteMovement.getOrElse(i - 1) { 0 }
            val b = perMinuteMovement[i]
            val c = perMinuteMovement.getOrElse(i + 1) { 0 }
            if (a < stillThreshold && b < stillThreshold && c < stillThreshold) deep++
        }
        return deep
    }

    /** Counts a movement event when acceleration magnitude deviates from gravity by more than threshold (m/s2). */
    fun isMovement(ax: Float, ay: Float, az: Float, threshold: Double = 1.2): Boolean {
        val mag = sqrt((ax * ax + ay * ay + az * az).toDouble())
        return abs(mag - 9.80665) > threshold
    }

    /** Breathing pacer: phase 0..1 inhale then exhale for a given breaths-per-minute. */
    fun breathPhase(elapsedMs: Long, breathsPerMinute: Int = 6): Double {
        val cycle = 60_000.0 / breathsPerMinute
        val t = (elapsedMs % cycle.toLong()) / cycle
        return if (t < 0.5) t * 2 else (1 - t) * 2
    }
}
