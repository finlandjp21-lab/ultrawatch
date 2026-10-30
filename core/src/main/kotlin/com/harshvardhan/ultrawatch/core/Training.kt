package com.harshvardhan.ultrawatch.core

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object Training {

    /**
     * Banister TRIMP for one session: minutes x HRr x 0.64 x e^(k x HRr),
     * HRr = (avgHr - rest) / (max - rest), k = 1.92 male / 1.67 female.
     */
    fun trimp(minutes: Double, avgHr: Int, restHr: Int, maxHr: Int, male: Boolean = true): Double {
        if (minutes <= 0 || maxHr <= restHr) return 0.0
        val hrr = ((avgHr - restHr).toDouble() / (maxHr - restHr)).coerceIn(0.0, 1.0)
        val k = if (male) 1.92 else 1.67
        return (minutes * hrr * 0.64 * exp(k * hrr) * 10).roundToInt() / 10.0
    }

    /** Acute (7-day) vs chronic (28-day) load ratio. dailyLoads: oldest first, today last. */
    fun loadRatio(dailyLoads: List<Double>): Double? {
        if (dailyLoads.size < 28) return null
        val acute = dailyLoads.takeLast(7).average()
        val chronic = dailyLoads.takeLast(28).average()
        if (chronic <= 0) return null
        return (acute / chronic * 100).roundToInt() / 100.0
    }

    fun loadStatus(ratio: Double?): String = when {
        ratio == null -> "Building baseline (28 days)"
        ratio < 0.8 -> "Detraining"
        ratio <= 1.3 -> "Optimal"
        ratio <= 1.5 -> "High - watch recovery"
        else -> "Very high - injury risk"
    }

    /**
     * Energy Score 0..100 for the morning:
     *  45 % sleep score, 35 % resting HR vs your 14-day baseline, 20 % yesterday's load vs usual.
     */
    fun energyScore(sleepScore: Int, restHrToday: Int, restHrBaseline: Double, yesterdayLoad: Double, usualLoad: Double): Int {
        val sleep = sleepScore.coerceIn(0, 100).toDouble()
        val dev = restHrToday - restHrBaseline // each bpm above baseline costs 8 points
        val hr = (100 - (dev * 8).coerceAtLeast(0.0) + (-dev * 2).coerceAtLeast(0.0)).coerceIn(0.0, 100.0)
        val loadRatio = if (usualLoad <= 0) 1.0 else yesterdayLoad / usualLoad
        val load = (100 - ((loadRatio - 1.0) * 60).coerceAtLeast(0.0)).coerceIn(0.0, 100.0)
        return (0.45 * sleep + 0.35 * hr + 0.20 * load).roundToInt()
    }

    fun energyAdvice(score: Int) = when {
        score >= 80 -> "Ready for a hard session"
        score >= 60 -> "Good for moderate training"
        score >= 40 -> "Go easy today"
        else -> "Rest and recover"
    }
}

/** Geographic helpers for Track Back and route recording. */
object Geo {
    data class Pt(val lat: Double, val lon: Double, val t: Long = 0)

    private const val R = 6_371_000.0
    private fun rad(d: Double) = d * PI / 180

    fun distanceM(a: Pt, b: Pt): Double {
        val dLat = rad(b.lat - a.lat)
        val dLon = rad(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } + cos(rad(a.lat)) * cos(rad(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * R * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Initial bearing a -> b, degrees 0..360 (0 = north). */
    fun bearing(a: Pt, b: Pt): Double {
        val y = sin(rad(b.lon - a.lon)) * cos(rad(b.lat))
        val x = cos(rad(a.lat)) * sin(rad(b.lat)) - sin(rad(a.lat)) * cos(rad(b.lat)) * cos(rad(b.lon - a.lon))
        return (atan2(y, x) * 180 / PI + 360) % 360
    }

    fun compassPoint(deg: Double): String =
        listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg % 360) + 22.5) / 45).toInt() % 8]

    fun pathLength(pts: List<Pt>): Double = pts.zipWithNext { a, b -> distanceM(a, b) }.sum()

    fun formatDistance(m: Double): String = if (m < 1000) "${m.roundToInt()} m" else String.format(java.util.Locale.US, "%.2f km", m / 1000)

    /** Pace in min/km as "m:ss". */
    fun pace(meters: Double, ms: Long): String {
        if (meters < 10 || ms <= 0) return "--:--"
        val secPerKm = ms / 1000.0 / (meters / 1000.0)
        val m = (secPerKm / 60).toInt()
        val s = (secPerKm % 60).roundToInt().let { if (it == 60) 0 else it }
        return "$m:${s.toString().padStart(2, '0')}"
    }
}

/**
 * Records a route with GPS-jitter filtering and guides the user back along it.
 * Guidance targets the nearest recorded point that is behind you, then walks the breadcrumbs back to start.
 */
class TrackBack(private val minStepM: Double = 8.0, private val maxAccuracyM: Float = 35f) {
    private val route = ArrayList<Geo.Pt>()
    val points: List<Geo.Pt> get() = route
    private var targetIdx = -1

    fun record(p: Geo.Pt, accuracyM: Float): Boolean {
        if (accuracyM > maxAccuracyM) return false
        val last = route.lastOrNull()
        if (last != null && Geo.distanceM(last, p) < minStepM) return false
        route += p
        return true
    }

    data class Guidance(val bearingDeg: Double, val distanceToNextM: Double, val distanceToStartM: Double, val arrived: Boolean)

    fun guide(here: Geo.Pt, arriveRadiusM: Double = 15.0): Guidance? {
        if (route.isEmpty()) return null
        if (targetIdx < 0) targetIdx = route.indices.minBy { Geo.distanceM(here, route[it]) }
        // advance along the breadcrumbs toward the start while close to the current one
        while (targetIdx > 0 && Geo.distanceM(here, route[targetIdx]) < arriveRadiusM) targetIdx--
        val target = route[targetIdx]
        val toTarget = Geo.distanceM(here, target)
        val remaining = toTarget + Geo.pathLength(route.subList(0, targetIdx + 1))
        val arrived = targetIdx == 0 && toTarget < arriveRadiusM
        return Guidance(Geo.bearing(here, target), toTarget, remaining, arrived)
    }

    /** Arrow rotation on screen = bearing to target minus the direction the user is facing. */
    fun arrowRotation(bearingDeg: Double, headingDeg: Double): Double = ((bearingDeg - headingDeg) % 360 + 360) % 360
}

/** Multisport (triathlon) session: ordered legs with transitions between them. */
class Multisport(val legs: List<String> = listOf("Swim", "Cycle", "Run")) {
    private val marks = ArrayList<Long>() // start of leg 0, then each switch
    var finishedAt: Long = -1; private set

    fun start(t: Long) { marks.clear(); marks += t; finishedAt = -1 }
    val currentLeg: Int get() = (marks.size - 1).coerceAtLeast(0)
    val currentName: String get() = legs.getOrElse(currentLeg) { legs.last() }

    /** Next leg, or finish after the last one. Returns false if already finished. */
    fun next(t: Long): Boolean {
        if (marks.isEmpty() || finishedAt >= 0) return false
        if (currentLeg >= legs.lastIndex) { finishedAt = t; return true }
        marks += t
        return true
    }

    fun legDurations(now: Long): List<Long> {
        val end = if (finishedAt >= 0) finishedAt else now
        return marks.indices.map { i -> (marks.getOrNull(i + 1) ?: end) - marks[i] }
    }

    fun total(now: Long): Long = if (marks.isEmpty()) 0 else (if (finishedAt >= 0) finishedAt else now) - marks[0]
}

object Environment {
    /**
     * Water depth (m) from absolute pressure (hPa). surfaceHpa is captured just before entering the water.
     * Fresh water: 1 m ~= 97.9 hPa; sea water ~= 100.5 hPa. Estimate only; not a dive computer.
     */
    fun depthM(pressureHpa: Float, surfaceHpa: Float, saltWater: Boolean = false): Double {
        val perM = if (saltWater) 100.5 else 97.9
        return ((pressureHpa - surfaceHpa) / perM).coerceAtLeast(0.0).let { (it * 10).roundToInt() / 10.0 }
    }

    /** Barometric altitude (m) using the international standard atmosphere. */
    fun altitudeM(pressureHpa: Float, seaLevelHpa: Float = 1013.25f): Double =
        (44_330.0 * (1 - Math.pow(pressureHpa / seaLevelHpa.toDouble(), 1 / 5.255))).roundToInt().toDouble()

    /** Night vision red mode: on between the given hours (handles ranges crossing midnight). */
    fun isNight(hour: Int, startHour: Int = 20, endHour: Int = 6): Boolean =
        if (startHour <= endHour) hour in startHour until endHour else hour >= startHour || hour < endHour
}
